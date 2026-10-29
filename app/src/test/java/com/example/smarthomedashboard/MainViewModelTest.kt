package com.example.smarthomedashboard

import org.robolectric.util.ReflectionHelpers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        viewModel = MainViewModel()
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertEquals(ConnectionStatus.Disconnected, state.connectionStatus)
        assertTrue(state.singleStates.isEmpty())
        assertTrue(state.groupStates.isEmpty())
        assertTrue(state.entityAttributes.isEmpty())
        assertTrue(state.syntheticForecasts.isEmpty())
        assertFalse(state.isEditMode)
        assertNull(state.statusMessage)
    }

    @Test
    fun testEntityStateChangedUpdatesSingleStatesAndAttributes() {
        val attrs = JSONObject().apply {
            put("friendly_name", "Temperature Sensor")
            put("unit_of_measurement", "°C")
        }

        viewModel.onEntityStateChanged("sensor.temp", "22.5", attrs)

        val state = viewModel.uiState.value
        assertEquals("22.5", state.singleStates["sensor.temp"])
        assertEquals("Temperature Sensor", state.entityAttributes["sensor.temp"]?.optString("friendly_name"))
        assertEquals("°C", state.entityAttributes["sensor.temp"]?.optString("unit_of_measurement"))
    }

    @Test
    fun testUpdateGroupState() {
        viewModel.updateGroupState("group1", "light.kitchen", "on")
        viewModel.updateGroupState("group1", "light.living_room", "off")

        val state = viewModel.uiState.value
        val groupMap = state.groupStates["group1"]
        assertEquals("on", groupMap?.get("light.kitchen"))
        assertEquals("off", groupMap?.get("light.living_room"))
    }

    @Test
    fun testForecastChangedUpdatesSyntheticForecasts() {
        val forecastArray = JSONArray().apply {
            put(JSONObject().apply {
                put("datetime", "2025-01-01")
                put("temperature", 15)
            })
        }

        viewModel.onForecastChanged("weather.home", forecastArray)

        val state = viewModel.uiState.value
        assertEquals(forecastArray, state.syntheticForecasts["weather.home"])
        assertEquals(forecastArray, state.entityAttributes["weather.home"]?.optJSONArray("forecast"))
    }

    @Test
    fun testEditModeToggling() {
        assertFalse(viewModel.uiState.value.isEditMode)

        viewModel.setEditMode(true)
        assertTrue(viewModel.uiState.value.isEditMode)

        viewModel.toggleEditMode()
        assertFalse(viewModel.uiState.value.isEditMode)
    }

    @Test
    fun testDisconnectWebSocketUpdatesConnectionStatus() {
        viewModel.disconnectWebSocket()
        assertEquals(ConnectionStatus.Disconnected, viewModel.uiState.value.connectionStatus)
    }

    private fun connect(): HomeAssistantWebSocket {
        // Invalid syntax exercises callbacks without contacting a server.
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false)
        return requireNotNull(viewModel.getWebSocket())
    }

    private fun callback(client: HomeAssistantWebSocket, name: String): (String) -> Unit =
        ReflectionHelpers.getField(client, name)

    @Test
    fun sameConfigurationKeepsClientAndLatestStatus() {
        val client = connect()
        // Работающий клиент не начинает новую проверку искусственного адреса при attach.
        org.robolectric.util.ReflectionHelpers.setField(client, "running", true)
        callback(client, "onStatus")("Повторное подключение")
        viewModel.detachUi()
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false)
        org.junit.Assert.assertSame(client, viewModel.getWebSocket())
        assertEquals("Повторное подключение", viewModel.uiState.value.statusMessage)
        viewModel.disconnectWebSocket()
    }

    @Test
    fun errorAndRetryStatusReachRetainedState() {
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false)
        val client = requireNotNull(viewModel.getWebSocket())
        callback(client, "onError")("Неверный токен HA")
        assertEquals(ConnectionStatus.Error("Неверный токен HA"), viewModel.uiState.value.connectionStatus)
        assertEquals("Неверный токен HA", viewModel.uiState.value.statusMessage)
        callback(client, "onStatus")("Повтор через 10 минут")
        assertEquals("Повтор через 10 минут", viewModel.uiState.value.statusMessage)
        viewModel.disconnectWebSocket()
    }

    @Test
    fun replacedAndDisconnectedClientsCannotPublishLateEvents() {
        val old = connect()
        viewModel.connectWebSocket("http://]", "another-artificial-token", autoReconnect = false)
        val before = viewModel.uiState.value
        callback(old, "onError")("late error")
        org.junit.Assert.assertSame(before, viewModel.uiState.value)
        val current = requireNotNull(viewModel.getWebSocket())
        viewModel.disconnectWebSocket()
        callback(current, "onStatus")("late retry")
        assertNull(viewModel.getWebSocket())
        assertNull(viewModel.uiState.value.statusMessage)
        assertEquals(ConnectionStatus.Disconnected, viewModel.uiState.value.connectionStatus)
    }

    @Test
    fun detachedUiReceivesNoCallbacksButStateContinuesToUpdate() {
        var calls = 0
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onStateChangedCallback = { _, _, _ -> calls++ })
        val client = requireNotNull(viewModel.getWebSocket())
        viewModel.detachUi()
        val before = calls
        val event: (String, String, JSONObject) -> Unit =
            ReflectionHelpers.getField(client, "onStateChanged")
        event("light.test", "on", JSONObject())
        callback(client, "onStatus")("Ожидание")
        assertEquals(before, calls)
        assertEquals("on", viewModel.uiState.value.singleStates["light.test"])
        assertEquals("Ожидание", viewModel.uiState.value.statusMessage)
        viewModel.disconnectWebSocket()
    }

    @Test
    fun clearingViewModelStoreDisconnectsClientAndInvalidatesCallbacks() {
        val store = androidx.lifecycle.ViewModelStore()
        val owner = object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = store
        }
        viewModel = androidx.lifecycle.ViewModelProvider(owner)[MainViewModel::class.java]
        val client = connect()
        store.clear()
        callback(client, "onError")("late")
        assertNull(viewModel.getWebSocket())
        assertEquals(ConnectionStatus.Disconnected, viewModel.uiState.value.connectionStatus)
    }

    @Test
    fun actualEventPreservesAttributesAndSyntheticForecast() {
        viewModel.onEntityStateChanged("light.test", "off", JSONObject().put("friendly_name", "Test"))
        viewModel.onForecastChanged("light.test", JSONArray().put(JSONObject().put("temperature", 10)))
        viewModel.onEntityStateChanged("light.test", "on", JSONObject().put("friendly_name", "Test"))
        assertEquals("on", viewModel.uiState.value.singleStates["light.test"])
        assertEquals("Test", viewModel.uiState.value.entityAttributes["light.test"]?.optString("friendly_name"))
        assertTrue(viewModel.uiState.value.syntheticForecasts.containsKey("light.test"))
    }

    @Test
    fun partialForecastSurvivesFullStateUntilExplicitForecastArrives() {
        viewModel.onEntityStateChanged("weather.test", "sunny", JSONObject().put("temperature", 12))
        viewModel.onForecastChanged("weather.test", null)
        assertEquals("sunny", viewModel.uiState.value.singleStates["weather.test"])
        viewModel.onEntityStateChanged("weather.test", "cloudy", JSONObject().put("temperature", 13))
        assertEquals(0, viewModel.uiState.value.entityAttributes["weather.test"]?.getJSONArray("forecast")?.length())
        viewModel.onEntityStateChanged("weather.test", "rainy", JSONObject().put("forecast", JSONArray()))
        assertFalse(viewModel.uiState.value.syntheticForecasts.containsKey("weather.test"))
    }

    @Test
    fun serviceAndSubscriptionsAreDelegatedToOwnedClient() {
        val messages = mutableListOf<JSONObject>()
        val transport = object : okhttp3.WebSocket {
            override fun request() = okhttp3.Request.Builder().url("http://localhost/test-only").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean {
                messages.add(JSONObject(text))
                return true
            }
            override fun send(bytes: okio.ByteString): Boolean = error("Unexpected binary message")
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        val client = HomeAssistantWebSocket("http://localhost/test-only", "artificial-token", { _, _, _ -> }, {}, {})
        org.robolectric.util.ReflectionHelpers.setField(client, "authenticated", true)
        org.robolectric.util.ReflectionHelpers.setField(client, "webSocket", transport)
        org.robolectric.util.ReflectionHelpers.setField(viewModel, "webSocket", client)
        viewModel.callService("light", "turn_on", "light.test")
        viewModel.subscribeEntities(listOf("light.test"))
        viewModel.syncForecastSubscriptions(listOf("weather.test"))
        viewModel.getEntities()
        val service = messages.single { it.getString("type") == "call_service" }
        assertEquals("light", service.getString("domain"))
        assertEquals("turn_on", service.getString("service"))
        assertEquals("light.test", service.getJSONObject("service_data").getString("entity_id"))
        assertTrue(messages.any { it.getString("type") == "subscribe_events" })
        assertEquals("weather.test", messages.single { it.getString("type") == "weather/subscribe_forecast" }.getString("entity_id"))
        assertTrue(messages.any { it.getString("type") == "config/entity_registry/list" })
        viewModel.disconnectWebSocket()
        val count = messages.size
        viewModel.callService("light", "turn_off", "light.test")
        assertEquals(count, messages.size)
    }

    @Test
    fun groupStateKeepsUpWithEventsWhileUiIsDetached() {
        viewModel.updateGroupState("group.test", "light.test", "off")
        viewModel.detachUi()
        viewModel.onEntityStateChanged("light.test", "on", JSONObject())
        assertEquals("on", viewModel.uiState.value.groupStates["group.test"]?.get("light.test"))
    }

    @Test
    fun reattachingUiToAuthenticatedClientRestoresConnectionWithoutReplacingIt() {
        val client = connect()
        // An authenticated connection is running; the earlier invalid-URL probe was stopped.
        org.robolectric.util.ReflectionHelpers.setField(client, "running", true)
        org.robolectric.util.ReflectionHelpers.setField(client, "authenticated", true)
        val connected: () -> Unit = ReflectionHelpers.getField(client, "onConnected")
        connected()
        viewModel.detachUi()
        var restored = 0
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onConnectedCallback = { restored++ })
        org.junit.Assert.assertSame(client, viewModel.getWebSocket())
        assertEquals(1, restored)
        viewModel.disconnectWebSocket()
    }

    @Test
    fun offlineToggleDoesNotInventStateOnRepeatedPresses() {
        viewModel.onEntityStateChanged("switch.test", "off", JSONObject())
        repeat(2) {
            viewModel.commands.toggleSingle("switch.test")
            assertEquals("off", viewModel.uiState.value.singleStates["switch.test"])
        }
        assertTrue(viewModel.uiState.value.statusMessage.orEmpty().contains("соединени"))
    }

    @Test fun staleBatchAndDetachedScreenCannotUpdateNewGeneration() {
        var uiCalls = 0
        viewModel.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onStatesSnapshotCallback = { uiCalls++ })
        val old = requireNotNull(viewModel.getWebSocket())
        val batch: (List<HaEntityState>) -> Unit = ReflectionHelpers.getField(old, "onStatesSnapshot")
        viewModel.detachUi()
        batch(listOf(HaEntityState("sensor.test", "1", JSONObject())))
        assertEquals("1", viewModel.devices.singleStates["sensor.test"])
        assertEquals(0, uiCalls)
        viewModel.connectWebSocket("http://]", "artificial-next-token", autoReconnect = false)
        batch(listOf(HaEntityState("sensor.test", "stale", JSONObject())))
        assertEquals("1", viewModel.devices.singleStates["sensor.test"])
        viewModel.disconnectWebSocket()
        batch(listOf(HaEntityState("sensor.test", "stale", JSONObject())))
        assertEquals("1", viewModel.devices.singleStates["sensor.test"])
    }
}
