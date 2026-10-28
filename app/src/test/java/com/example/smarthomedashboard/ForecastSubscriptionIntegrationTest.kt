package com.example.smarthomedashboard

import android.os.Looper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// Локальный MockWebServer: настоящие auth/result/event и методы Activity.
// XML/onCreate и внешняя сеть не используются, drawable заменены существующей фикстурой.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ForecastSubscriptionIntegrationTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val server = MockWebServer()
    private lateinit var client: HomeAssistantWebSocket
    private lateinit var peer: Peer
    private var connections = 0
    private var failures = 0
    private var marker = 0
    private val updates = mutableListOf<Triple<String, String, JSONObject>>()
    private val entityLists = mutableListOf<List<HaEntity>>()

    private class Peer(val ack: Boolean = true, val once: Boolean = false, val strict: Boolean = false) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            when (message.optString("type")) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "weather/subscribe_forecast", "weather/subscribe_forecasts" -> {
                    if (strict && message.getString("type") != "weather/subscribe_forecast") {
                        result(message, false, JSONObject().put("code", "unknown_command"))
                    } else if (ack) {
                        result(message, true, if (once) JSONObject("""{"forecast":[{"time":"09:00","temperature":7}]}""") else JSONObject.NULL)
                    }
                }
            }
        }
        fun result(request: JSONObject, success: Boolean = true, payload: Any = JSONObject.NULL) {
            socket!!.send(JSONObject().put("type", "result").put("id", request.getInt("id"))
                .put("success", success).put(if (success) "result" else "error", payload).toString())
        }
        fun forecast(request: JSONObject, temperature: Int = 7) {
            socket!!.send(JSONObject().put("type", "event").put("id", request.getInt("id"))
                .put("event", JSONObject().put("forecast", JSONArray().put(
                    JSONObject().put("time", "09:00").put("temperature", temperature)))).toString())
        }
        fun requests() = received.filter { it.optString("type").startsWith("weather/subscribe_forecast") }
        fun unsubscribes() = received.filter { it.optString("type") == "unsubscribe_events" }
    }

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    private fun tile(id: String = "weather", config: String = """{"entity_id":"weather.a"}""") =
        TileEntity(id, "weather", "grid", "Погода", 20, 30, 1, 1, config = config)

    private fun save(tiles: List<TileEntity>) {
        manager.saveTiles(tiles + TileEntity("anchor", "button", "bottom_panel", "Кнопка", 20, 400, 1, 1,
            config = """{"entity_id":"light.safe"}"""))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    private fun start(tiles: List<TileEntity> = listOf(tile()), p: Peer = Peer()) {
        peer = p
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        save(tiles)
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        client = HomeAssistantWebSocket(server.url("/").toString(), "test-token",
            { eid, state, attrs ->
                updates.add(Triple(eid, state, attrs))
                invoke("updateTilesForEntity", eid, state, attrs)
            }, { connections++ }, {}, onEntitiesList = { entityLists.add(it) }, onError = { failures++ },
            onForecastChanged = { eid, forecast ->
                // Журнал сохраняет удобный для прежних проверок формат,
                // но в Activity прогноз поступает отдельным явным путём.
                updates.add(Triple(eid, "unknown", JSONObject().put("forecast", forecast ?: JSONObject.NULL)))
                invoke("updateForecastForEntity", eid, forecast)
            })
        set("webSocket", client)
        invoke("refreshBottomPanel")
        invoke("subscribeToNeededEntities")
        client.connect()
        await { connections == 1 }
        flush()
    }

    // Барьер по тому же сокету: до registry-запроса сервер получил все предыдущие команды,
    // после marker-события Activity обработала предыдущие ответы. Отрицательные проверки без sleep.
    private fun flush() {
        val count = peer.received.count { it.optString("type") == "config/entity_registry/list" }
        client.getEntities()
        await { peer.received.count { it.optString("type") == "config/entity_registry/list" } > count }
        val value = (++marker).toString()
        peer.socket!!.send(JSONObject().put("type", "event").put("id", 99999).put("event",
            JSONObject().put("event_type", "state_changed").put("data", JSONObject()
                .put("entity_id", "light.marker").put("new_state", JSONObject()
                    .put("state", value).put("attributes", JSONObject())))).toString())
        await { updates.any { it.first == "light.marker" && it.second == value } }
    }

    private fun changed(tiles: List<TileEntity>) {
        save(tiles)
        invoke("onActivityResult", 100, android.app.Activity.RESULT_OK, null)
        flush()
    }

    private fun forecastUpdates() = updates.filter { it.third.has("forecast") }

    @After fun cleanup() {
        if (::client.isInitialized) client.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
    }

    @Test fun fallbackWeatherHomeActuallyRequestsForecast() {
        start(listOf(tile(config = "{}")))
        assertEquals(listOf("weather.home"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun arrayPriorityAndSensorSourceMatchDisplayedEntitySelection() {
        start(listOf(
            tile("array", """{"entity_ids":["weather.first","weather.ignored"],"entity_id":"weather.other"}"""),
            tile("single", """{"entity_id":"weather.single"}"""),
            tile("sensor", """{"entity_ids":["sensor.temperature","weather.not_selected"],"entity_id":"weather.not_selected_either"}""")))
        assertEquals(listOf("weather.first", "weather.single"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun realForecastCommandMatchesOfficialSubscriptionProtocol() {
        start(p = Peer(strict = true))
        assertEquals("weather/subscribe_forecast", peer.requests().single().getString("type"))
        peer.forecast(peer.requests().single())
        flush()
        assertEquals("weather.a", forecastUpdates().single().first)
        assertEquals(7, forecastUpdates().single().third.getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
    }

    @Test fun replacingSourceUnsubscribesConfirmedOldSubscriptionAndIgnoresLateEvent() {
        start()
        val old = peer.requests().single()
        changed(listOf(tile(config = """{"entity_id":"weather.b"}""")))
        assertEquals(listOf(old.getInt("id")), peer.unsubscribes().map { it.getInt("subscription") })
        peer.forecast(peer.requests().last(), 7)
        flush()
        val card = panel.findViewWithTag<FrameLayout>("weather")
        invoke("expandSensor", manager.loadTiles().single { it.id == "weather" }, card)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        val container = card.findViewWithTag<LinearLayout>("sensor_text_container")
        val currentTop = container.getChildAt(0)
        val before = forecastUpdates().size
        peer.forecast(old, 99)
        flush()
        assertEquals(before, forecastUpdates().size)
        assertSame(currentTop, container.getChildAt(0))
        assertFalse(ReflectionHelpers.getField<Map<String, JSONObject>>(activity, "entityAttributes")["weather.a"]?.has("forecast") == true)
        assertEquals(listOf("weather.a", "weather.b"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun repeatedReconciliationAndSharedSourceDoNotDuplicateForecasts() {
        start(listOf(tile("one"), tile("two")))
        repeat(3) { invoke("subscribeToNeededEntities") }
        flush()
        assertEquals(1, peer.requests().size)
        changed(listOf(tile("two")))
        assertTrue(peer.unsubscribes().isEmpty())
        changed(emptyList())
        assertEquals(1, peer.unsubscribes().size)
    }

    @Test fun disabledTileRemovesForecastSource() {
        start()
        changed(listOf(tile().copy(enabled = false)))
        assertEquals(1, peer.unsubscribes().size)
    }

    @Test fun yandexStopsHaForecastAndSwitchingBackRestoresIt() {
        start()
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", true).commit()
        changed(listOf(tile()))
        assertEquals(1, peer.requests().size)
        assertEquals(1, peer.unsubscribes().size)
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", false).commit()
        changed(listOf(tile()))
        assertEquals(listOf("weather.a", "weather.a"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun pendingRequestIsUnsubscribedOnlyAfterSubscriptionAcknowledgement() {
        start(p = Peer(ack = false))
        val old = peer.requests().single()
        changed(emptyList())
        assertTrue(peer.unsubscribes().isEmpty())
        peer.result(old)
        flush()
        assertEquals(listOf(old.getInt("id")), peer.unsubscribes().map { it.getInt("subscription") })
        peer.forecast(old)
        flush()
        assertTrue(forecastUpdates().isEmpty())
    }

    @Test fun oneShotResultDeliversForecastButNeverCreatesAnUnsubscribe() {
        start(p = Peer(once = true))
        assertEquals(1, forecastUpdates().size)
        assertEquals("weather.a", forecastUpdates().single().first)
        repeat(2) { invoke("subscribeToNeededEntities") }
        flush()
        assertEquals(1, peer.requests().size)
        changed(emptyList())
        assertTrue(peer.unsubscribes().isEmpty())
    }

    @Test fun lateOneShotResultAfterRemovalIsIgnoredWithoutUnsubscribe() {
        start(p = Peer(ack = false))
        val old = peer.requests().single()
        changed(emptyList())
        peer.result(old, payload = JSONObject("""{"forecast":[{"temperature":99}]}"""))
        flush()
        assertTrue(forecastUpdates().isEmpty())
        assertTrue(peer.unsubscribes().isEmpty())
    }

    @Test fun reconnectRestoresOnlyCurrentDesiredForecastSources() {
        start()
        changed(listOf(tile(config = """{"entity_id":"weather.b"}""")))
        val second = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        peer.socket!!.close(1001, "restart")
        await { failures > 0 }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        await { connections == 2 }
        peer = second
        flush()
        assertEquals(listOf("weather.b"), peer.requests().map { it.getString("entity_id") })
        assertEquals(1, peer.received.count { it.optString("type") == "subscribe_events" })
        assertTrue(peer.received.any { it.optString("type") == "get_states" })
        assertTrue(peer.received.any { it.optString("type") == "config/entity_registry/list" })
    }

    @Test fun removingAndReaddingBeforeOldAckDoesNotReviveOldResponses() {
        start(p = Peer(ack = false))
        val old = peer.requests().single()
        changed(emptyList())
        changed(listOf(tile()))
        assertEquals(2, peer.requests().size)
        val current = peer.requests().last()
        peer.result(old)
        peer.result(current)
        peer.forecast(old, 99)
        peer.forecast(current, 7)
        flush()
        assertEquals(listOf(old.getInt("id")), peer.unsubscribes().map { it.getInt("subscription") })
        assertEquals(listOf(7), forecastUpdates().map { it.third.getJSONArray("forecast").getJSONObject(0).getInt("temperature") })
    }

    @Test fun startingWithYandexDoesNotRequestHaForecastButSwitchBackDoes() {
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", true).commit()
        start()
        assertTrue(peer.requests().isEmpty())
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", false).commit()
        changed(listOf(tile()))
        assertEquals(listOf("weather.a"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun offlineSourceChangeIsAppliedBeforeReconnectRestoration() {
        start()
        val second = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        peer.socket!!.close(1001, "restart")
        await { failures > 0 }
        save(listOf(tile(config = """{"entity_id":"weather.b"}""")))
        invoke("onActivityResult", 100, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        await { connections == 2 }
        peer = second
        flush()
        assertEquals(listOf("weather.b"), peer.requests().map { it.getString("entity_id") })
    }

    @Test fun removedFailedRequestNeverUnsubscribesOrReportsStaleError() {
        start(p = Peer(ack = false))
        val old = peer.requests().single()
        changed(emptyList())
        val errorsBefore = failures
        peer.result(old, false, JSONObject().put("code", "forecast_not_supported"))
        flush()
        assertEquals(errorsBefore, failures)
        assertTrue(peer.unsubscribes().isEmpty())
        assertTrue(forecastUpdates().isEmpty())
    }

    @Test fun oneShotArrayResultIsNotAnEntityListOrSubscription() {
        start(p = Peer(ack = false))
        val request = peer.requests().single()
        peer.result(request, payload = JSONArray("""[{"time":"10:00","temperature":8}]"""))
        flush()
        assertEquals("weather.a", forecastUpdates().single().first)
        assertEquals(8, forecastUpdates().single().third.getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        assertTrue(entityLists.isEmpty())
        changed(emptyList())
        assertTrue(peer.unsubscribes().isEmpty())
    }

    @Test fun forecastEventBeforeAckProvesSubscriptionAndCanBeUnsubscribed() {
        start(p = Peer(ack = false))
        val request = peer.requests().single()
        peer.forecast(request)
        flush()
        assertEquals(1, forecastUpdates().size)
        changed(emptyList())
        assertEquals(listOf(request.getInt("id")), peer.unsubscribes().map { it.getInt("subscription") })
    }

    @Test fun sensorTemperatureSourceStillDisplaysItsStateWithoutForecastRequest() {
        start(listOf(tile(config = """{"entity_id":"sensor.temperature"}""")))
        peer.socket!!.send("""{"type":"event","id":99999,"event":{"event_type":"state_changed",
            "data":{"entity_id":"sensor.temperature","new_state":{"state":"12.5","attributes":{}}}}}""")
        flush()
        assertEquals("12.5°C", panel.findViewWithTag<TextView>("weather_temp").text.toString())
        assertTrue(peer.requests().isEmpty())
    }

    @Test fun advancedSensorConfigReceivesUnfilteredStateChangedEvents() {
        start(listOf(tile("sensor", """{"collapsed_sensor_configs":[
            {"entity_id":"sensor.extended_temperature","display_name":"T","decimals":1}]}""").copy(type = "sensor")))
        val subscription = peer.received.single { it.optString("type") == "subscribe_events" }
        assertEquals("state_changed", subscription.getString("event_type"))
        assertFalse(subscription.has("entity_ids"))
        peer.socket!!.send("""{"type":"event","id":99999,"event":{"event_type":"state_changed",
            "data":{"entity_id":"sensor.extended_temperature","new_state":{"state":"12.5","attributes":{}}}}}""")
        flush()
        val container = panel.findViewWithTag<LinearLayout>("sensor_text_container")
        assertEquals("T  12.5", (container.getChildAt(1) as TextView).text.toString())
        assertTrue(peer.requests().isEmpty())
    }


    @Test fun forecastAndRegistryResponsesStaySeparateAndServiceControlStillWorks() {
        start(p = Peer(ack = false))
        peer.result(peer.requests().single(), payload = JSONArray("""[{"time":"10:00","temperature":8}]"""))
        val registry = peer.received.last { it.optString("type") == "config/entity_registry/list" }
        peer.result(registry, payload = JSONArray("""[{"entity_id":"light.safe","name":"Safe"}]"""))
        flush()
        assertEquals(listOf("light.safe"), entityLists.single().map { it.entityId })
        assertEquals("Safe", entityLists.single().single().name)
        client.callService("light", "turn_on", "light.safe")
        flush()
        val command = peer.received.single { it.optString("type") == "call_service" }
        assertEquals("light", command.getString("domain"))
        assertEquals("turn_on", command.getString("service"))
        assertEquals("light.safe", command.getJSONObject("service_data").getString("entity_id"))
    }

}
