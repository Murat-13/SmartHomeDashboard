package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Фальшивый Home Assistant, который шлёт мусор (0.9.13, docs/deep-testing.md): испорченные, обрезанные, повторные и
 * чужие ответы, события странной формы вперемешку с командами панели. Клиент не должен падать, терять учёт команд
 * (каждая команда получает ровно один итог) и копить запросы без предела.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaProtocolFuzzTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/artificial-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += JSONObject(text); return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun deliver(client: HomeAssistantWebSocket, text: String) {
        val message = HaInboundMessage.parse(text) ?: return
        client.javaClass.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
            .apply { isAccessible = true }.invoke(client, message)
    }

    @Test fun garbageFromServerNeverCrashesAndEveryCommandEndsOnce() {
        val iterations = System.getProperty("chaos.iterations")?.toIntOrNull()?.times(50) ?: 3_000
        val rnd = Random(System.getProperty("chaos.seed")?.toLongOrNull() ?: 20261107L)
        val owner = MainViewModel()
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onStatesSnapshotCallback = {}, onStateChangedCallback = { _, _, _ -> }, onForecastChangedCallback = { _, _ -> })
        val client = requireNotNull(owner.getWebSocket())
        val socket = Socket()
        ReflectionHelpers.setField(client, "running", true)
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", socket)
        val outcomes = mutableMapOf<Int, MutableList<ServiceCommandResult>>()
        var commands = 0
        var reads = 0
        var readAnswers = 0
        try {
            owner.subscribeEntities(emptyList())
            owner.getEntities()
            owner.syncForecastSubscriptions(listOf("weather.home"))
            owner.syncHourlyForecastSubscriptions(listOf("weather.home"))
            repeat(iterations) { i ->
                when (rnd.nextInt(10)) {
                    0 -> {
                        val n = commands++
                        val list = mutableListOf<ServiceCommandResult>().also { outcomes[n] = it }
                        client.callService("light", "toggle", "light.fz_${rnd.nextInt(5)}") { list += it }
                    }
                    1 -> { reads++; client.read("get_config") { readAnswers++ } }
                    2 -> shadowOf(Looper.getMainLooper()).idleFor(rnd.nextLong(0, 20_000), TimeUnit.MILLISECONDS)
                    3 -> if (rnd.nextInt(20) == 0) owner.subscribeEntities(emptyList())
                    else -> deliver(client, message(rnd, socket))
                }
                if (i % 500 == 0) shadowOf(Looper.getMainLooper()).idle()
            }
            // Все сроки ожидания истекают: незавершённых команд и чтений не остаётся.
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
            outcomes.forEach { (n, list) -> assertEquals("Команда $n: ровно один итог, получено $list", 1, list.size) }
            assertEquals("Каждое чтение завершилось ответом или отказом", reads, readAnswers)
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(client, "serviceRequests").isEmpty())
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(client, "readRequests").isEmpty())
            assertTrue(ReflectionHelpers.getField<Set<*>>(client, "retiredServiceIds").size <= 128)
            assertTrue(ReflectionHelpers.getField<Set<*>>(client, "registryRequests").size <= 32)
            assertTrue("Подписок прогноза не больше нужного", ReflectionHelpers.getField<Map<*, *>>(client, "forecastSubscriptions").size <= 64)
        } finally { owner.detachUi(); owner.disconnectWebSocket() }
    }

    /** Настоящий сокет: сервер шлёт бинарные кадры, мусор и обрывает соединение посреди потока — клиент переподключается. */
    @Test fun realSocketWithGarbageFramesReconnectsAndKeepsWorking() {
        MockWebServer().use { server ->
            val sessions = mutableListOf<okhttp3.WebSocket>()
            repeat(3) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        synchronized(sessions) { sessions += webSocket }
                        webSocket.send("""{"type":"auth_required"}""")
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "auth" -> {
                                webSocket.send("""{"type":"auth_ok"}""")
                                webSocket.send("ничего похожего на JSON")
                                webSocket.send("[1,2,3]")
                                webSocket.send("null")
                                webSocket.send("{\"type\":\"event\",\"event\":[]}")
                                webSocket.send(byteArrayOf(0, 1, 2, -1).let { ByteString.of(*it) })
                                webSocket.send("\uD800 broken surrogate".encodeUtf8())
                            }
                            "get_states" -> webSocket.send(JSONObject().put("type", "result").put("id", json.getInt("id"))
                                .put("success", true).put("result", JSONArray().put(JSONObject().put("entity_id", "sensor.a").put("state", "1"))
                                    .put("garbage").put(JSONObject().put("entity_id", 5)).put(JSONObject.NULL)).toString())
                            else -> if (json.has("id")) webSocket.send(JSONObject().put("type", "result").put("id", json.getInt("id")).put("success", true).toString())
                        }
                    }
                }))
            }
            server.start()
            val owner = MainViewModel()
            var connects = 0
            val states = mutableListOf<String>()
            owner.connectWebSocket(server.url("/").toString().removeSuffix("/"), "artificial-token", autoReconnect = true,
                onConnectedCallback = { connects++; owner.subscribeEntities(emptyList()) },
                onStateChangedCallback = { id, state, _ -> states += "$id=$state" },
                onStatesSnapshotCallback = { it.forEach { e -> states += "${e}" } })
            try {
                await { connects >= 1 && states.isNotEmpty() }
                // Сервер рвёт соединение посреди потока — клиент возвращается сам.
                synchronized(sessions) { sessions.last().close(1011, "restart") }
                await { connects >= 2 }
                assertTrue("После обрыва — снова подключено", connects >= 2)
            } finally { owner.detachUi(); owner.disconnectWebSocket() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        assertTrue("Не дождались условия", condition())
    }

    private fun message(rnd: Random, socket: Socket): String {
        // Ответ «по адресу»: id одного из уже отправленных панелью запросов, иначе случайный/чужой.
        val id: Any = when (rnd.nextInt(6)) {
            0 -> rnd.nextInt(-5, 100_000)
            1 -> "7"
            2 -> JSONObject.NULL
            else -> socket.sent.takeIf { it.isNotEmpty() }?.let { it[rnd.nextInt(it.size)].optInt("id") } ?: 1
        }
        val base = when (rnd.nextInt(12)) {
            0 -> JSONObject().put("type", "auth_ok")
            1 -> JSONObject().put("type", "auth_required")
            2 -> JSONObject().put("type", "result").put("id", id).put("success", rnd.nextBoolean())
                .put("error", JSONObject().put("code", ChaosStates.text(rnd)).put("message", "x"))
            3 -> JSONObject().put("type", "result").put("id", id).put("success", true).put("result", JSONArray().apply {
                repeat(rnd.nextInt(0, 30)) { put(JSONObject().put("entity_id", "sensor.s$it").put("state", ChaosStates.state(rnd))
                    .put("attributes", ChaosStates.attributes(rnd))) }
            })
            4 -> JSONObject().put("type", "result").put("id", id).put("success", true).put("result", JSONObject.NULL)
            5 -> JSONObject().put("type", "event").put("id", id).put("event", JSONObject().put("forecast", ChaosStates.forecast(rnd) ?: JSONObject.NULL))
            6 -> JSONObject().put("type", "event").put("id", id).put("event", JSONObject().put("event_type", "state_changed")
                .put("data", JSONObject().put("entity_id", "light.fz_${rnd.nextInt(5)}").put("new_state",
                    JSONObject().put("state", ChaosStates.state(rnd)).put("attributes", ChaosStates.attributes(rnd)))))
            7 -> JSONObject().put("type", "event").put("id", id).put("event", JSONObject()
                .put("a", JSONObject().put("sensor.x", JSONObject().put("s", ChaosStates.state(rnd)).put("a", ChaosStates.attributes(rnd))))
                .put("c", JSONObject().put("sensor.x", JSONObject().put("+", JSONObject().put("s", ChaosStates.state(rnd)))))
                .put("r", JSONArray().put("sensor.y")))
            8 -> JSONObject().put("type", "pong").put("id", id)
            9 -> JSONObject().put("type", ChaosStates.text(rnd)).put("id", id)
            10 -> JSONObject().put("type", "result").put("id", id).put("success", true).put("result", JSONObject().put("forecast", JSONArray()))
            else -> JSONObject().put("type", "auth_invalid").put("message", "x")
        }
        // auth_invalid разрывает соединение — в этом тесте оно живо всегда; разрыв проверяет второй тест.
        if (base.optString("type") == "auth_invalid") base.put("type", "event")
        val mutated = if (rnd.nextInt(3) == 0) ChaosJson.mutate(base, rnd, 0) else base
        val text = mutated.toString()
        return when (rnd.nextInt(25)) {
            0 -> text.take(rnd.nextInt(0, text.length + 1))
            1 -> "{\"type\":\"result\",\"id\":"
            2 -> "\u0000"
            else -> text
        }
    }
}
