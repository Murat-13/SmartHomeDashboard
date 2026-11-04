package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel : ViewModel() {

    private var snapshotDepth = 0
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    internal val devices = DashboardDeviceState()

    // Подтверждённые цветовые состояния «Электропитание Modern» по id плитки: переживают
    // пересоздание экрана, чтобы новая View сразу показала уже подтверждённый диапазон.
    private val powerTrackers = mutableMapOf<String, PowerStateTracker>()
    internal fun powerTracker(tileId: String) = powerTrackers.getOrPut(tileId) { PowerStateTracker() }
    internal fun retainPowerTrackers(tileIds: Set<String>) { powerTrackers.keys.retainAll(tileIds) }

    // Открытая Modern-группа остаётся открытой после поворота и изменения размера окна.
    internal var openModernGroup: String? = null
    /** Текущая страница панели (0 — главная); переживает пересоздание экрана, но не перезапуск. */
    internal var currentPage = 0
    internal val commands = DashboardDeviceCommands(
        devices, ::callService, ::publishDevices,
        render = { id -> commandStateCallback?.invoke(id) },
        cancelRequest = { id -> webSocket?.cancelServiceRequests(id) },
        connectionGeneration = { webSocket?.connectionGeneration ?: generation },
        error = ::publishOperationError
    )

    private var webSocket: HomeAssistantWebSocket? = null

    /** История мощности для графика (docs/power-graph.md); задаёт экран, в тестах может отсутствовать. */
    internal var powerHistory: PowerHistoryRecorder? = null
        set(value) {
            field = value
            value?.reader = { type, params, result -> webSocket?.read(type, params, result = result) ?: result(null) }
        }

    /** Текущие источники графиков: пишется только их история. */
    internal fun setPowerSources(entityIds: Set<String>) {
        powerHistory?.setSources(entityIds)
    }

    private val identifyHandler = Handler(Looper.getMainLooper())
    private var identifyRetry: Runnable? = null
    private var identifyAttempt = 0

    /**
     * Сервер HA определяется по get_config (только чтение), чтобы истории разных домов не смешивались.
     * Без ответа запись истории не начинается, поэтому отказ повторяется с растущей паузой, пока
     * соединение живо. Ответ без данных о доме ([PowerHistoryRecorder.instanceKey] = null) не повторяется.
     */
    private fun identifyServer(currentGeneration: Int) {
        cancelIdentify()
        identifyAttempt = 0
        requestIdentity(currentGeneration)
    }

    private fun requestIdentity(currentGeneration: Int) {
        val history = powerHistory ?: return
        val client = webSocket ?: return
        client.read("get_config") { config ->
            if (generation != currentGeneration || _uiState.value.connectionStatus != ConnectionStatus.Connected) return@read
            if (config == null) {
                val delay = IDENTIFY_RETRY_MS[identifyAttempt.coerceAtMost(IDENTIFY_RETRY_MS.lastIndex)]
                identifyAttempt++
                identifyRetry = Runnable {
                    identifyRetry = null
                    requestIdentity(currentGeneration)
                }.also { identifyHandler.postDelayed(it, delay) }
                return@read
            }
            PowerHistoryRecorder.instanceKey(config)?.let(history::setInstance)
        }
    }

    private fun cancelIdentify() {
        identifyRetry?.let { identifyHandler.removeCallbacks(it) }
        identifyRetry = null
    }

    internal var tokenRevision = -1L
    private var generation = 0
    private var configuration: List<String>? = null
    private var snapshotCallback: ((List<String>) -> Unit)? = null
    private var stateCallback: ((String, String, JSONObject) -> Unit)? = null
    private var forecastCallback: ((String, JSONArray?) -> Unit)? = null
    private var connectedCallback: (() -> Unit)? = null
    private var commandStateCallback: ((String) -> Unit)? = null
    private var entitiesCallback: ((List<HaEntity>) -> Unit)? = null

    // Заменяем обработчики экрана, чтобы ViewModel не удерживал уничтоженную Activity.
    fun detachUi() {
        snapshotCallback = null
        stateCallback = null
        forecastCallback = null
        connectedCallback = null
        entitiesCallback = null
        commandStateCallback = null
    }

    // Статус — сохраняемое состояние экрана, а не одноразовое событие подключённого UI.
    internal fun publishStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    /** Ошибка операции: текст статуса и кратковременная плашка, статус соединения не меняется. */
    internal fun publishOperationError(message: String) {
        _uiState.update {
            it.copy(statusMessage = message, operationError = OperationError(message, SystemClock.elapsedRealtime()))
        }
    }

    fun resumeWebSocket() {
        commands.expireOverdue()
        webSocket?.connect()
    }

    fun getEntities() {
        webSocket?.getEntities()
    }

    fun connectWebSocket(
        host: String,
        token: String,
        alternateHosts: List<String> = emptyList(),
        autoReconnect: Boolean = true,
        onStateChangedCallback: ((entityId: String, state: String, attributes: JSONObject) -> Unit)? = null,
        onForecastChangedCallback: ((entityId: String, forecast: JSONArray?) -> Unit)? = null,
        onConnectedCallback: (() -> Unit)? = null,
        onEntitiesListCallback: ((List<HaEntity>) -> Unit)? = null,
        onCommandStateChangedCallback: ((String) -> Unit)? = null,
        onStatesSnapshotCallback: ((List<String>) -> Unit)? = null,
        allowInsecureRemote: Boolean = false
    ) {
        snapshotCallback = onStatesSnapshotCallback
        commandStateCallback = onCommandStateChangedCallback
        stateCallback = onStateChangedCallback
        forecastCallback = onForecastChangedCallback
        connectedCallback = onConnectedCallback
        entitiesCallback = onEntitiesListCallback
        val nextConfiguration = listOf(host, token, autoReconnect.toString(), allowInsecureRemote.toString()) + alternateHosts
        if (configuration == nextConfiguration && webSocket != null) {
            resumeWebSocket()
            if (_uiState.value.connectionStatus == ConnectionStatus.Connected) {
                connectedCallback?.invoke()
            }
            return
        }
        commands.disconnected()
        powerHistory?.onDisconnected()
        val currentGeneration = ++generation
        webSocket?.disconnect()
        configuration = nextConfiguration
        _uiState.update {
            it.copy(connectionStatus = ConnectionStatus.Connecting(host), statusMessage = null, haSession = HaSession())
        }
        webSocket = HomeAssistantWebSocket(
            host = host,
            token = token,
            alternateHosts = alternateHosts,
            autoReconnect = autoReconnect,
            allowInsecureRemote = allowInsecureRemote,
            onStateChanged = { entityId, state, attributes ->
                if (generation == currentGeneration) {
                    onEntityStateChanged(entityId, state, attributes)
                    powerHistory?.onState(entityId, state, attributes)
                    stateCallback?.invoke(entityId, state, attributes)
                }
            },
            onStatesSnapshot = { states ->
                if (generation == currentGeneration) {
                    snapshotDepth++
                    try {
                        onStatesSnapshot(states)
                        powerHistory?.let { history ->
                            states.forEach { history.onState(it.entityId, it.state, it.attributes) }
                            history.onSnapshotDone()
                        }
                        // Снимок текущей сессии принят до отрисовки: карточки видят данные достоверными.
                        _uiState.update { it.copy(haSession = it.haSession.copy(synced = it.haSession.connected)) }
                        val callback = snapshotCallback
                        if (callback != null) callback(states.map { it.entityId })
                        else states.forEach { stateCallback?.invoke(it.entityId, it.state, it.attributes) }
                    } finally {
                        snapshotDepth--
                        publishDevices()
                    }
                }
            },
            onConnected = {
                if (generation == currentGeneration) {
                    _uiState.update {
                        it.copy(connectionStatus = ConnectionStatus.Connected, haSession = HaSession(connected = true))
                    }
                    identifyServer(currentGeneration)
                    connectedCallback?.invoke()
                }
            },
            onDisconnected = {
                if (generation == currentGeneration) {
                    cancelIdentify()
                    commands.disconnected()
                    powerHistory?.onDisconnected()
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, haSession = HaSession()) }
                }
            },
            onEntitiesList = { entities ->
                if (generation == currentGeneration) entitiesCallback?.invoke(entities)
            },
            // Ошибка операции при живом соединении: сокет работает, «нет связи» не показываем.
            onError = { message ->
                if (generation == currentGeneration) publishOperationError(message)
            },
            // Только разрыв меняет статус соединения.
            onConnectionError = { message ->
                if (generation == currentGeneration) {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Error(message)) }
                    publishStatus(message)
                }
            },
            onStatus = { status ->
                if (generation == currentGeneration) publishStatus(status)
            },
            onForecastChanged = { entityId, forecast ->
                if (generation == currentGeneration) {
                    onForecastChanged(entityId, forecast)
                    forecastCallback?.invoke(entityId, forecast)
                }
            },
            onHourlyForecastChanged = { entityId, forecast, supported ->
                if (generation == currentGeneration) {
                    devices.hourlyForecastChanged(entityId, forecast, supported)
                    publishDevices()
                    // Экран читает прогноз из хранилища; callback только сообщает, что перерисовать.
                    forecastCallback?.invoke(entityId, null)
                }
            },
            onDailyForecastUnavailable = { entityId ->
                if (generation == currentGeneration) {
                    devices.dailyForecasts.remove(entityId)
                    devices.dailyUnsupported.add(entityId)
                    publishDevices()
                    forecastCallback?.invoke(entityId, null)
                }
            }
        )
        webSocket?.connect()
    }

    fun disconnectWebSocket() {
        cancelIdentify()
        commands.disconnected()
        powerHistory?.onDisconnected()
        generation++
        configuration = null
        webSocket?.disconnect()
        webSocket = null
        _uiState.update {
            it.copy(connectionStatus = ConnectionStatus.Disconnected, statusMessage = null, haSession = HaSession())
        }
    }

    fun subscribeEntities(entityIds: List<String>) {
        webSocket?.subscribeEntities(entityIds)
    }

    fun syncForecastSubscriptions(entityIds: Collection<String>) {
        val desired = entityIds.toSet()
        val removed = devices.dailyForecasts.keys.retainAll(desired) or devices.dailyUnsupported.retainAll(desired)
        if (removed) publishDevices()
        webSocket?.syncForecastSubscriptions(entityIds)
    }

    fun syncHourlyForecastSubscriptions(entityIds: Collection<String>) {
        val desired = entityIds.toSet()
        val removed = devices.hourlyForecasts.keys.retainAll(desired) or devices.hourlyUnsupported.retainAll(desired)
        if (removed) publishDevices()
        webSocket?.syncHourlyForecastSubscriptions(entityIds)
    }

    fun callService(domain: String, service: String, entityId: String, onResult: (ServiceCommandResult) -> Unit = {}): Boolean {
        val client = webSocket
        if (client == null) {
            publishOperationError("Нет соединения: дождитесь переподключения и повторите команду")
            return false
        }
        return client.callService(domain, service, entityId, onResult)
    }

    internal fun onYandexWeatherChanged(weather: YandexClimateWeather) {
        _uiState.update { it.copy(yandexWeather = weather) }
    }

    fun getWebSocket(): HomeAssistantWebSocket? = webSocket

    internal fun onStatesSnapshot(states: List<HaEntityState>) {
        devices.applySnapshot(states)
        states.forEach { commands.entityChanged(it.entityId) }
        publishDevices()
    }

    fun onEntityStateChanged(eid: String, st: String, attrs: JSONObject? = null) {
        devices.entityChanged(eid, st, attrs)
        commands.entityChanged(eid)
        publishDevices()
    }

    fun updateGroupState(groupId: String, entityId: String, state: String) {
        devices.updateGroup(groupId, entityId, state)
        commands.entityChanged(entityId)
        publishDevices()
    }

    internal fun groupIsOn(groupId: String, ids: List<String>): Boolean {
        val before = devices.groupStates[groupId]?.toMap()
        val active = devices.groupIsOn(groupId, ids)
        if (before != devices.groupStates[groupId]) publishDevices()
        return active
    }

    fun onForecastChanged(eid: String, forecast: JSONArray?) {
        devices.forecastChanged(eid, forecast)
        publishDevices()
    }

    private fun publishDevices() {
        if (snapshotDepth != 0) return
        _uiState.update { devices.snapshot(it).copy(pendingCommands = commands.pendingCommands) }
    }

    fun setEditMode(enabled: Boolean) {
        _uiState.update { it.copy(isEditMode = enabled) }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    override fun onCleared() {
        detachUi()
        disconnectWebSocket()
        super.onCleared()
    }

    private companion object {
        /** Паузы повтора get_config: 15 с, 30 с, 1 мин, 2 мин, далее каждые 5 мин. */
        val IDENTIFY_RETRY_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}
