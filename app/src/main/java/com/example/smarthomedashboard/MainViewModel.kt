package com.example.smarthomedashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    internal val devices = DashboardDeviceState()
    internal val commands = DashboardDeviceCommands(devices, ::callService, ::publishDevices) { id ->
        commandStateCallback?.invoke(id)
    }

    private var webSocket: HomeAssistantWebSocket? = null

    internal var tokenRevision = -1L
    private var generation = 0
    private var configuration: List<String>? = null
    private var stateCallback: ((String, String, JSONObject) -> Unit)? = null
    private var forecastCallback: ((String, JSONArray?) -> Unit)? = null
    private var connectedCallback: (() -> Unit)? = null
    private var commandStateCallback: ((String) -> Unit)? = null
    private var entitiesCallback: ((List<HaEntity>) -> Unit)? = null

    // Заменяем обработчики экрана, чтобы ViewModel не удерживал уничтоженную Activity.
    fun detachUi() {
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
        onCommandStateChangedCallback: ((String) -> Unit)? = null
    ) {
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
        val currentGeneration = ++generation
        webSocket?.disconnect()
        configuration = nextConfiguration
        _uiState.update {
            it.copy(connectionStatus = ConnectionStatus.Connecting(host), statusMessage = null)
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
            onConnected = {
                if (generation == currentGeneration) {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Connected) }
                    connectedCallback?.invoke()
                }
            },
            onDisconnected = {
                if (generation == currentGeneration) {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
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
            }
        )
        webSocket?.connect()
    }

    fun disconnectWebSocket() {
        generation++
        configuration = null
        webSocket?.disconnect()
        webSocket = null
        _uiState.update {
            it.copy(connectionStatus = ConnectionStatus.Disconnected, statusMessage = null)
        }
    }

    fun subscribeEntities(entityIds: List<String>) {
        webSocket?.subscribeEntities(entityIds)
    }

    fun syncForecastSubscriptions(entityIds: Collection<String>) {
        webSocket?.syncForecastSubscriptions(entityIds)
    }

    fun callService(domain: String, service: String, entityId: String, onFinished: () -> Unit = {}): Boolean {
        val client = webSocket
        if (client == null) {
            publishStatus("Нет соединения: дождитесь переподключения и повторите команду")
            return false
        }
        return client.callService(domain, service, entityId, onFinished)
    }

    fun getWebSocket(): HomeAssistantWebSocket? = webSocket

    fun onEntityStateChanged(eid: String, st: String, attrs: JSONObject? = null) {
        commands.entityChanged(eid)
        devices.entityChanged(eid, st, attrs)
        publishDevices()
    }

    fun updateGroupState(groupId: String, entityId: String, state: String) {
        devices.updateGroup(groupId, entityId, state)
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
        _uiState.update { devices.snapshot(it) }
    }

    fun setEditMode(enabled: Boolean) {
        _uiState.update { it.copy(isEditMode = enabled) }
    }

    fun setOptimisticState(entityId: String, state: String) {
        devices.singleStates[entityId] = state
        publishDevices()
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
