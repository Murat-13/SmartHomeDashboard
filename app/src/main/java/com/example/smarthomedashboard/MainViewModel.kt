package com.example.smarthomedashboard

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
    internal val commands = DashboardDeviceCommands(
        devices, ::callService, ::publishDevices,
        render = { id -> commandStateCallback?.invoke(id) },
        cancelRequest = { id -> webSocket?.cancelServiceRequests(id) },
        connectionGeneration = { webSocket?.connectionGeneration ?: generation },
        error = ::publishStatus
    )

    private var webSocket: HomeAssistantWebSocket? = null

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
        onStatesSnapshotCallback: ((List<String>) -> Unit)? = null
    ) {
        snapshotCallback = onStatesSnapshotCallback
        commandStateCallback = onCommandStateChangedCallback
        stateCallback = onStateChangedCallback
        forecastCallback = onForecastChangedCallback
        connectedCallback = onConnectedCallback
        entitiesCallback = onEntitiesListCallback
        val nextConfiguration = listOf(host, token, autoReconnect.toString()) + alternateHosts
        if (configuration == nextConfiguration && webSocket != null) {
            resumeWebSocket()
            if (_uiState.value.connectionStatus == ConnectionStatus.Connected) {
                connectedCallback?.invoke()
            }
            return
        }
        commands.disconnected()
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
            onStateChanged = { entityId, state, attributes ->
                if (generation == currentGeneration) {
                    onEntityStateChanged(entityId, state, attributes)
                    stateCallback?.invoke(entityId, state, attributes)
                }
            },
            onStatesSnapshot = { states ->
                if (generation == currentGeneration) {
                    snapshotDepth++
                    try {
                        onStatesSnapshot(states)
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
                    connectedCallback?.invoke()
                }
            },
            onDisconnected = {
                if (generation == currentGeneration) {
                    commands.disconnected()
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, haSession = HaSession()) }
                }
            },
            onEntitiesList = { entities ->
                if (generation == currentGeneration) entitiesCallback?.invoke(entities)
            },
            onError = { message ->
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
        commands.disconnected()
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
            publishStatus("Нет соединения: дождитесь переподключения и повторите команду")
            return false
        }
        return client.callService(domain, service, entityId, onResult)
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
}
