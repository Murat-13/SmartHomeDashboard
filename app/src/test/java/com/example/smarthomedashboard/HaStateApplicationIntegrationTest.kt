package com.example.smarthomedashboard

import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

// Запускаем настоящий setupWebSocket Activity и доставляем реальные сообщения через loopback.
// XML/onCreate не исполняются; ресурсные подмены и программное окно — из прежних фикстур.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class HaStateApplicationIntegrationTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val panel by lazy { FrameLayout(activity) }
    private val manager by lazy { TileManager(activity) }
    private val server = MockWebServer()
    private val peer = Peer()
    private lateinit var client: HomeAssistantWebSocket
    private var marker = 0

    private class Peer : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        var acknowledgeForecasts = true
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            when (message.optString("type")) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "weather/subscribe_forecast" -> if (acknowledgeForecasts) result(message, JSONObject.NULL)
            }
        }
        fun result(request: JSONObject, payload: Any) {
            socket!!.send(JSONObject().put("type", "result").put("id", request.getInt("id"))
                .put("success", true).put("result", payload).toString())
        }
    }

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }
    private fun <T> get(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    private fun start() {
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        manager.saveTiles(listOf("one" to "weather.a", "two" to "weather.a", "other" to "weather.b").map { (id, eid) ->
            TileEntity(id, "weather", "grid", id, 20, 30, 1, 1,
                config = JSONObject().put("entity_id", eid).toString())
        } + TileEntity("button", "button", "bottom_panel", "Кнопка", 20, 400, 1, 1,
            config = """{"entity_id":"light.safe"}"""))
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(TextView(activity).apply { id = R.id.tvHaLiveStatus })
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        activity.getSharedPreferences("dashboard_prefs", 0).edit()
            .putString("ha_local_host", server.url("/").toString()).putString("ha_token", "test-token")
            .putBoolean("use_yandex_weather", false).commit()
        invoke("refreshBottomPanel")
        set("haTokens", haTokenTestAccess(activity))
        invoke("setupWebSocket")
        await { get<HomeAssistantWebSocket?>("webSocket") != null }
        client = get("webSocket")
        await { peer.received.any { it.optString("type") == "config/entity_registry/list" } }
        drain()
    }

    private fun state(eid: String = "weather.a", value: String = "sunny", attrs: JSONObject = JSONObject()) {
        val subscription = peer.received.single { it.optString("type") == "subscribe_events" }.getInt("id")
        peer.socket!!.send(JSONObject().put("type", "event").put("id", subscription)
            .put("event", JSONObject().put("event_type", "state_changed").put("data",
                JSONObject().put("entity_id", eid).put("new_state",
                    JSONObject().put("state", value).put("attributes", attrs)))).toString())
    }
    private fun drain() {
        val value = (++marker).toString()
        state("light.marker", value)
        await { get<Map<String, String>>("singleStates")["light.marker"] == value }
    }
    private fun forecast(value: Any, eid: String = "weather.a") {
        val request = peer.received.last {
            it.optString("type") == "weather/subscribe_forecast" && it.getString("entity_id") == eid
        }
        peer.socket!!.send(JSONObject().put("type", "event").put("id", request.getInt("id"))
            .put("event", JSONObject().put("forecast", value)).toString())
    }
    private fun snapshot(value: String, attrs: JSONObject) {
        val request = peer.received.last { it.optString("type") == "get_states" }
        peer.result(request, JSONArray().put(JSONObject().put("entity_id", "weather.a")
            .put("state", value).put("attributes", attrs)))
    }
    private fun attrs(eid: String = "weather.a") = get<Map<String, JSONObject>>("entityAttributes").getValue(eid)
    private fun forecast7() = JSONArray("""[{"time":"09:00","condition":"clear","temperature":7}]""")
    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun card(id: String = "one"): FrameLayout = panel.findViewWithTag(id)
    private fun content(id: String = "one"): LinearLayout = card(id).findViewWithTag("sensor_text_container")
    private fun expand() {
        invoke("expandSensor", manager.loadTiles().single { it.id == "one" }, card())
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
    }

    @After fun cleanup() {
        if (::client.isInitialized) client.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
    }

    @Test fun stateChangedReplacesAttributesAndRemovesFieldsAbsentFromFullSnapshot() {
        start()
        state(attrs = JSONObject("""{"temperature":20,"humidity":60,"old":"remove"}"""))
        drain()
        state(attrs = JSONObject("""{"temperature":21}"""))
        drain()
        assertEquals("""{"temperature":21}""", attrs().toString())
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
    }

    @Test fun getStatesAlsoReplacesAttributesIncludingAnEmptySnapshot() {
        start()
        snapshot("sunny", JSONObject("""{"temperature":20,"humidity":60}"""))
        drain()
        client.subscribeEntities(emptyList())
        await { peer.received.count { it.optString("type") == "get_states" } == 2 }
        snapshot("unavailable", JSONObject())
        drain()
        assertEquals(0, attrs().length())
        assertEquals("unavailable", get<Map<String, String>>("singleStates")["weather.a"])
    }

    @Test fun forecastPreservesRealStateTemperatureAndOtherAttributes() {
        start()
        state(attrs = JSONObject("""{"temperature":20,"humidity":60}"""))
        drain()
        val count = peer.received.size
        forecast(forecast7())
        drain()
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
        assertEquals(20, attrs().getInt("temperature"))
        assertEquals(60, attrs().getInt("humidity"))
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        assertEquals(count, peer.received.size)
    }

    @Test fun genuineUnknownIncludingForecastAttributeIsStillAFullState() {
        start()
        state(value = "sunny", attrs = JSONObject("""{"temperature":20,"humidity":60}"""))
        drain()
        snapshot("unknown", JSONObject().put("forecast", forecast7()))
        drain()
        assertEquals("unknown", get<Map<String, String>>("singleStates")["weather.a"])
        assertFalse(attrs().has("temperature"))
        assertFalse(attrs().has("humidity"))
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
    }

    @Test fun nullForecastRemovesOldListAndSuppressesStaleAlternativeForecast() {
        start()
        state(attrs = JSONObject("""{"temperature":20,"forecast_daily":[{"temperature":99}]}"""))
        drain()
        forecast(forecast7())
        drain()
        expand()
        forecast(JSONObject.NULL)
        drain()
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
        assertEquals(20, attrs().getInt("temperature"))
        assertEquals(0, attrs().getJSONArray("forecast").length())
        assertTrue(texts(content()).contains("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"))
        assertFalse(texts(content()).contains("7°"))
        assertFalse(texts(content()).contains("99°"))
    }

    @Test fun fullForecastFullForecastSequencePreservesOnlySyntheticForecast() {
        start()
        state(attrs = JSONObject("""{"temperature":20,"humidity":60}"""))
        drain()
        forecast(forecast7())
        drain()
        state(attrs = JSONObject("""{"temperature":21}"""))
        drain()
        assertFalse(attrs().has("humidity"))
        assertEquals(21, attrs().getInt("temperature"))
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        forecast(JSONArray("""[{"time":"10:00","temperature":8}]"""))
        drain()
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
        assertEquals(21, attrs().getInt("temperature"))
        assertEquals(8, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
    }

    @Test fun explicitHaForecastWinsAndCanLaterBeRemovedAsHaAttribute() {
        start()
        state(attrs = JSONObject("""{"temperature":20}"""))
        drain()
        forecast(forecast7())
        drain()
        state(attrs = JSONObject("""{"temperature":21,"forecast":[{"temperature":99}]}"""))
        drain()
        assertEquals(99, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        state(attrs = JSONObject("""{"temperature":22}"""))
        drain()
        assertFalse(attrs().has("forecast"))
    }

    @Test fun nullForecastMarkerSurvivesFullSnapshotAndNextForecastRecovers() {
        start()
        state(attrs = JSONObject("""{"temperature":20}"""))
        drain()
        forecast(forecast7())
        drain()
        forecast(JSONObject.NULL)
        drain()
        state(attrs = JSONObject("""{"temperature":21}"""))
        drain()
        assertEquals(0, attrs().getJSONArray("forecast").length())
        forecast(JSONArray("""[{"time":"11:00","temperature":9}]"""))
        drain()
        assertEquals(9, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        assertEquals(21, attrs().getInt("temperature"))
    }

    @Test fun entitiesAndTilesStayIndependentAcrossFullAndForecastUpdates() {
        start()
        state(attrs = JSONObject("""{"temperature":10,"humidity":60}"""))
        state("weather.b", "rain", JSONObject("""{"temperature":30,"pressure":1000}"""))
        drain()
        forecast(forecast7())
        forecast(JSONArray("""[{"temperature":3}]"""), "weather.b")
        drain()
        val otherBefore = attrs("weather.b").toString()
        state(value = "unavailable", attrs = JSONObject())
        drain()
        assertEquals(otherBefore, attrs("weather.b").toString())
        assertEquals("rain", get<Map<String, String>>("singleStates")["weather.b"])
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        assertFalse(attrs().has("temperature"))
        assertEquals("—", card("one").findViewWithTag<TextView>("weather_temp").text.toString())
        assertEquals("—", card("two").findViewWithTag<TextView>("weather_temp").text.toString())
        assertEquals("30°C", card("other").findViewWithTag<TextView>("weather_temp").text.toString())
    }

    @Test fun openWeatherUpdatesWithoutNetworkAndStillCollapsesToCurrentValues() {
        start()
        state(attrs = JSONObject("""{"temperature":20,"humidity":60}"""))
        drain()
        expand()
        val requestsBefore = peer.received.size
        forecast(forecast7())
        drain()
        assertTrue(texts(content()).contains("7°"))
        state(attrs = JSONObject("""{"temperature":21}"""))
        drain()
        assertEquals(listOf("☀️", "one", "Ясно", "21°C"), texts(content().getChildAt(0)))
        assertTrue(texts(content()).contains("—%"))
        assertTrue(texts(content()).contains("7°"))
        forecast(JSONObject.NULL)
        drain()
        assertTrue(texts(content()).contains("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"))
        invoke("collapseSensor")
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals("21°C", card().findViewWithTag<TextView>("weather_temp").text.toString())
        forecast(JSONArray("""[{"time":"12:00","temperature":8}]"""))
        drain()
        expand()
        assertTrue(texts(content()).contains("8°"))
        assertEquals(requestsBefore, peer.received.size)
    }

    @Test fun inputAttributesAreNotMutatedOrKeptAsWritableCache() {
        start()
        val input = JSONObject("""{"temperature":20,"humidity":60}""")
        val original = input.toString()
        invoke("updateTilesForEntity", "weather.a", "sunny", input)
        forecast(forecast7())
        drain()
        assertEquals(original, input.toString())
        assertNotSame(input, attrs())
        invoke("updateTilesForEntity", "weather.a", "sunny", JSONObject())
        assertEquals(2, input.length())
        assertFalse(attrs().has("humidity"))
        assertTrue(attrs().has("forecast"))
    }

    @Test fun emptySubscriptionAckDoesNotClearKnownForecast() {
        start()
        state(attrs = JSONObject("""{"temperature":20}"""))
        drain()
        forecast(forecast7())
        drain()
        client.syncForecastSubscriptions(emptyList())
        client.syncForecastSubscriptions(listOf("weather.a"))
        await { peer.received.count { it.optString("type") == "weather/subscribe_forecast" && it.getString("entity_id") == "weather.a" } == 2 }
        drain()
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
    }

    @Test fun explicitHaNullForecastAlsoReplacesSyntheticOrigin() {
        start()
        state(attrs = JSONObject("""{"temperature":20}"""))
        drain()
        forecast(forecast7())
        drain()
        state(attrs = JSONObject("""{"temperature":21,"forecast":null}"""))
        drain()
        assertTrue(attrs().isNull("forecast"))
        state(attrs = JSONObject("""{"temperature":22}"""))
        drain()
        assertFalse(attrs().has("forecast"))
    }

    @Test fun missingAndWrongTypeForecastAreNotMistakenForNull() {
        start()
        state(attrs = JSONObject("""{"temperature":20}"""))
        drain()
        forecast(forecast7())
        drain()
        val request = peer.received.single {
            it.optString("type") == "weather/subscribe_forecast" && it.getString("entity_id") == "weather.a"
        }
        for (value in listOf<Any>("broken", 42, true)) {
            forecast(value)
            drain()
            assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        }
        peer.socket!!.send(JSONObject().put("type", "event").put("id", request.getInt("id"))
            .put("event", JSONObject()).toString())
        drain()
        assertEquals(7, attrs().getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
    }

    @Test fun forecastDoesNotReplaceGenuineUnknownOrUnavailable() {
        start()
        for (value in listOf("unknown", "unavailable")) {
            state(value = value, attrs = JSONObject("""{"temperature":20}"""))
            drain()
            forecast(forecast7())
            drain()
            assertEquals(value, get<Map<String, String>>("singleStates")["weather.a"])
            assertEquals(20, attrs().getInt("temperature"))
        }
    }

    @Test fun legacyCallbackStillReceivesForecastArrayAndExplicitNull() {
        val legacy = mutableListOf<Triple<String, String, JSONObject>>()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        client = HomeAssistantWebSocket(server.url("/").toString(), "test-token",
            { eid, value, attributes -> legacy.add(Triple(eid, value, attributes)) }, {}, {})
        client.subscribeForecast("weather.a")
        client.connect()
        await { peer.received.any { it.optString("type") == "weather/subscribe_forecast" } }
        forecast(forecast7())
        await { legacy.size == 1 }
        assertEquals("weather.a", legacy[0].first)
        assertEquals("unknown", legacy[0].second)
        assertEquals(7, legacy[0].third.getJSONArray("forecast").getJSONObject(0).getInt("temperature"))
        forecast(JSONObject.NULL)
        await { legacy.size == 2 }
        assertTrue(legacy[1].third.has("forecast"))
        assertTrue(legacy[1].third.isNull("forecast"))
    }

    @Test fun oneShotObjectWithNullForecastClearsDataWithoutCreatingSubscription() {
        peer.acknowledgeForecasts = false
        start()
        state(attrs = JSONObject("""{"temperature":20,"forecast":[{"temperature":7}]}"""))
        drain()
        val request = peer.received.single {
            it.optString("type") == "weather/subscribe_forecast" && it.getString("entity_id") == "weather.a"
        }
        peer.result(request, JSONObject("""{"forecast":null}"""))
        drain()
        assertEquals(0, attrs().getJSONArray("forecast").length())
        assertEquals(20, attrs().getInt("temperature"))
        assertEquals("sunny", get<Map<String, String>>("singleStates")["weather.a"])
        client.syncForecastSubscriptions(emptyList())
        client.getEntities()
        await { peer.received.count { it.optString("type") == "config/entity_registry/list" } == 2 }
        assertTrue(peer.received.none { it.optString("type") == "unsubscribe_events" })
    }

}
