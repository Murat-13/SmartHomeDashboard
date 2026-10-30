package com.example.smarthomedashboard

import android.os.Looper
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Почасовой прогноз — отдельная подписка weather/subscribe_forecast в том же соединении. Локальный сервер, без сети. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeClimateForecastTest {
    private val server = MockWebServer()
    private lateinit var client: HomeAssistantWebSocket
    private val peer = Peer()
    private var connected = 0
    private val errors = mutableListOf<String>()
    private val dailyUnavailable = mutableListOf<String>()
    private val daily = mutableListOf<Pair<String, JSONArray?>>()
    private val hourly = mutableListOf<Triple<String, JSONArray?, Boolean>>()

    private class Peer : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        /** Типы прогноза, которые «интеграция» не поддерживает. */
        var unsupported = setOf<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            when (message.optString("type")) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "weather/subscribe_forecast" -> {
                    val type = message.getString("forecast_type")
                    val reply = JSONObject().put("type", "result").put("id", message.getInt("id"))
                    if (type in unsupported) reply.put("success", false).put("error", JSONObject().put("code", "forecast_not_supported"))
                    else reply.put("success", true).put("result", JSONObject.NULL)
                    webSocket.send(reply.toString())
                    if (type !in unsupported) {
                        webSocket.send(JSONObject().put("type", "event").put("id", message.getInt("id")).put("event",
                            JSONObject().put("type", type).put("forecast", JSONArray().put(JSONObject()
                                .put("datetime", "2026-10-30T12:00:00+00:00").put("temperature", if (type == "hourly") 11 else 13)))).toString())
                    }
                }
            }
        }
        fun requests(type: String) = received.filter {
            it.optString("type") == "weather/subscribe_forecast" && it.optString("forecast_type") == type
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    private fun start(unsupported: Set<String> = emptySet()) {
        peer.unsupported = unsupported
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        client = HomeAssistantWebSocket(server.url("/").toString(), "test-token", { _, _, _ -> }, { connected++ }, {},
            onError = { errors += it },
            onForecastChanged = { id, forecast -> daily += id to forecast },
            onHourlyForecastChanged = { id, forecast, supported -> hourly += Triple(id, forecast, supported) },
            onDailyForecastUnavailable = { dailyUnavailable += it })
        client.connect()
        await { connected == 1 }
    }

    @After fun cleanup() {
        if (::client.isInitialized) client.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
    }

    @Test fun hourlyAndDailyAreSeparateSubscriptionsWithSeparateData() {
        start()
        client.syncForecastSubscriptions(listOf("weather.home"))
        client.syncHourlyForecastSubscriptions(listOf("weather.home"))
        await { daily.isNotEmpty() && hourly.isNotEmpty() }
        assertEquals(1, peer.requests("daily").size)
        assertEquals(1, peer.requests("hourly").size)
        assertEquals(13, daily.last().second!!.getJSONObject(0).getInt("temperature"))
        assertEquals(11, hourly.last().second!!.getJSONObject(0).getInt("temperature"))
        assertTrue(hourly.last().third)
        assertTrue(errors.isEmpty())
        // Повторная синхронизация не дублирует подписки; снятие почасовой не трогает дневную.
        client.syncHourlyForecastSubscriptions(listOf("weather.home"))
        client.syncHourlyForecastSubscriptions(emptyList())
        client.getEntities()
        await { peer.received.any { it.optString("type") == "config/entity_registry/list" } }
        assertEquals(1, peer.requests("hourly").size)
        assertEquals(1, peer.requests("daily").size)
        assertTrue(peer.received.any { it.optString("type") == "unsubscribe_events" })
    }

    @Test fun unsupportedHourlyIsDataStateNotConnectionError() {
        start(unsupported = setOf("hourly"))
        client.syncForecastSubscriptions(listOf("weather.home"))
        client.syncHourlyForecastSubscriptions(listOf("weather.home"))
        await { hourly.isNotEmpty() && daily.isNotEmpty() }
        assertFalse(hourly.last().third)
        assertNull(hourly.last().second)
        assertTrue("Отказ в почасовом не показывается как ошибка", errors.isEmpty())
    }
    @Test fun unsupportedDailyDoesNotBlockHourlyOrReportConnectionFailure() {
        start(unsupported = setOf("daily"))
        client.syncForecastSubscriptions(listOf("weather.home"))
        client.syncHourlyForecastSubscriptions(listOf("weather.home"))
        await { dailyUnavailable.isNotEmpty() && hourly.isNotEmpty() }
        assertEquals(listOf("weather.home"), dailyUnavailable)
        assertTrue(hourly.last().third)
        assertTrue(errors.isEmpty())
    }

    @Test fun reconnectRestoresExactlyOneSubscriptionPerForecastType() {
        start()
        client.syncForecastSubscriptions(listOf("weather.home"))
        client.syncHourlyForecastSubscriptions(listOf("weather.home"))
        await { daily.isNotEmpty() && hourly.isNotEmpty() }
        client.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        client.connect()
        await { connected == 2 && daily.size == 2 && hourly.size == 2 }
        assertEquals(2, peer.requests("daily").size)
        assertEquals(2, peer.requests("hourly").size)
    }

}
