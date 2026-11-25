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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * HA-01, HA-02 (аудит 25.11.2026): отказ на снимок состояний и на подписку прогноза повторяется, пока живо соединение, —
 * панель всегда на экране, сама она иначе не восстановится. Только loopback-сервер с искусственными данными.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaRetryTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<HomeAssistantWebSocket>()
    private val errors = mutableListOf<String>()
    private val hourly = mutableListOf<Boolean>()
    private var snapshots = 0
    private var connections = 0

    private class Peer : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; webSocket.send("""{"type":"auth_required"}""") }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            if (message.optString("type") == "auth") webSocket.send("""{"type":"auth_ok"}""")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        fun all(type: String) = received.filter { it.optString("type") == type }
        fun refuse(request: JSONObject, code: String) { socket!!.send(JSONObject().put("id", request.getInt("id")).put("type", "result").put("success", false)
            .put("error", JSONObject().put("code", code).put("message", "artificial")).toString()) }
        fun accept(request: JSONObject, result: Any = JSONObject.NULL) { socket!!.send(JSONObject().put("id", request.getInt("id")).put("type", "result")
            .put("success", true).put("result", result).toString()) }
    }

    @After fun cleanup() {
        clients.forEach { it.disconnect() }
        idle()
        servers.forEach { it.shutdown() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun later(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun settle() = repeat(15) { idle(); Thread.sleep(10) }
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    private fun client(vararg peers: Peer): HomeAssistantWebSocket {
        val server = MockWebServer().also { s -> servers += s; peers.forEach { s.enqueue(MockResponse().withWebSocketUpgrade(it)) }; s.start() }
        return HomeAssistantWebSocket(server.url("/").toString(), "test-token", { _, _, _ -> }, { connections++ }, {},
            onError = { errors += it }, onConnectionError = {}, onStatesSnapshot = { snapshots++ },
            onHourlyForecastChanged = { _, _, supported -> hourly += supported }).also { clients += it }
    }

    @Test fun refusedSnapshotIsRetriedWithGrowingPauseUntilItArrives() {
        val peer = Peer()
        val c = client(peer)
        c.subscribeEntities(emptyList())
        c.connect()
        fun requests() = peer.all("get_states")
        await("первый запрос") { requests().size == 1 }
        peer.refuse(requests().last(), "unknown_error")
        await("ошибка показана") { errors.size == 1 }
        later(1); settle()
        assertEquals("ещё рано", 1, requests().size)
        later(2)
        await("повтор через 2 с") { requests().size == 2 }
        peer.refuse(requests().last(), "unknown_error")
        settle()
        later(3); settle()
        assertEquals(2, requests().size)
        later(3)
        await("повтор через 5 с") { requests().size == 3 }
        // Не ответил вовсе: 30 с ожидания и 15 с паузы.
        later(31); settle()
        later(16)
        await("повтор после тишины") { requests().size == 4 }
        peer.accept(requests().last(), JSONArray())
        await("снимок получен") { snapshots == 1 }
        later(600); settle()
        assertEquals("дальше не повторяем", 4, requests().size)
        assertEquals("об ошибке сказано один раз", 1, errors.size)
        assertEquals("соединение то же", 1, connections)
        // Следующий сбой — снова с начала: и ошибка, и пауза 2 с.
        c.subscribeEntities(emptyList())
        await("новый запрос") { requests().size == 5 }
        peer.refuse(requests().last(), "unknown_error")
        await("ошибка снова") { errors.size == 2 }
        later(3)
        await("повтор") { requests().size == 6 }
    }

    @Test fun snapshotRetryDiesWithItsConnection() {
        val first = Peer()
        val second = Peer()
        val c = client(first, second)
        c.subscribeEntities(emptyList())
        c.connect()
        await("запрос") { first.all("get_states").size == 1 }
        first.refuse(first.all("get_states").last(), "unknown_error")
        await("ошибка") { errors.size == 1 }
        first.socket!!.close(1000, null)
        await("переподключение") { later(3); connections == 2 && second.all("get_states").size == 1 }
        second.accept(second.all("get_states").last(), JSONArray())
        await("снимок") { snapshots == 1 }
        later(120); settle()
        assertEquals("повтор прежнего соединения не сработал", 1, second.all("get_states").size)
        c.disconnect()
        later(600); settle()
        assertEquals(1, second.all("get_states").size)
    }

    @Test fun refusedForecastIsRetriedButUnsupportedIsNot() {
        val peer = Peer()
        val c = client(peer)
        c.syncForecastSubscriptions(listOf("weather.home", "weather.old"))
        c.syncHourlyForecastSubscriptions(listOf("weather.home"))
        c.connect()
        fun of(type: String, entity: String) = peer.all("weather/subscribe_forecast").filter { it.getString("forecast_type") == type && it.getString("entity_id") == entity }
        await("три подписки") { peer.all("weather/subscribe_forecast").size == 3 }
        // Интеграция погоды ещё не загружена: Home Assistant не знает сущность.
        peer.refuse(of("daily", "weather.home").last(), "invalid_entity_id")
        peer.refuse(of("hourly", "weather.home").last(), "unknown_command")
        peer.refuse(of("daily", "weather.old").last(), "forecast_not_supported")
        await("отказы получены") { errors.size == 1 && hourly.size == 1 }
        later(29); settle()
        assertEquals("ещё рано", 3, peer.all("weather/subscribe_forecast").size)
        later(2)
        await("повтор через 30 с") { of("daily", "weather.home").size == 2 && of("hourly", "weather.home").size == 2 }
        peer.accept(of("hourly", "weather.home").last())
        peer.refuse(of("daily", "weather.home").last(), "invalid_entity_id")
        settle()
        later(121)
        await("следующий — через 2 минуты") { of("daily", "weather.home").size == 3 }
        peer.accept(of("daily", "weather.home").last())
        settle()
        later(3_600); settle()
        assertEquals("получилось — больше не повторяем", 3, of("daily", "weather.home").size)
        assertEquals(2, of("hourly", "weather.home").size)
        assertEquals("«не поддерживается» не повторяется", 1, of("daily", "weather.old").size)
        assertEquals("об ошибке сказано один раз", 1, errors.size)
    }

    @Test fun removedSourceIsNotRetriedAndReturnsAtOnce() {
        val peer = Peer()
        val c = client(peer)
        c.syncForecastSubscriptions(listOf("weather.home"))
        c.connect()
        fun requests() = peer.all("weather/subscribe_forecast")
        await("подписка") { requests().size == 1 }
        peer.refuse(requests().last(), "invalid_entity_id")
        await("отказ") { errors.size == 1 }
        c.syncForecastSubscriptions(emptyList())
        later(1_200); settle()
        assertEquals("источник убрали — повтора нет", 1, requests().size)
        c.syncForecastSubscriptions(listOf("weather.home"))
        await("вернули — подписка сразу") { requests().size == 2 }
        assertTrue(peer.all("unsubscribe_events").isEmpty())
    }
}
