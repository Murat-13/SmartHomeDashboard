package com.example.smarthomedashboard.deep

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Фальшивый Home Assistant на самом устройстве (docs/deep-testing.md): WebSocket API с авторизацией, снимком
 * состояний, подпиской на события, командами и прогнозом. Только искусственные сущности и токен; к реальному дому
 * не подключается. Можно рвать соединения и временно не пускать клиента — проверка переподключения.
 */
internal class FakeHomeAssistant(val token: String = "FAKE-DEEP-TEST-TOKEN") : AutoCloseable {
    val server = MockWebServer()
    private val states = ConcurrentHashMap<String, JSONObject>()
    private val sockets = CopyOnWriteArrayList<Pair<WebSocket, Int>>()
    val auths = AtomicInteger()
    val snapshots = AtomicInteger()
    val commands = CopyOnWriteArrayList<JSONObject>()
    @Volatile var refuse = false

    val address: String get() = "http://127.0.0.1:${server.port}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                refuse -> MockResponse().setResponseCode(503)
                request.path?.startsWith("/api/websocket") == true -> MockResponse().withWebSocketUpgrade(Session())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    fun set(entityId: String, state: String, attributes: JSONObject = JSONObject()) {
        states[entityId] = JSONObject().put("entity_id", entityId).put("state", state).put("attributes", attributes)
            .put("last_changed", "2026-11-07T10:00:00+00:00").put("last_updated", "2026-11-07T10:00:00+00:00")
    }

    /** Событие state_changed всем подписанным клиентам. */
    fun push(entityId: String, state: String, attributes: JSONObject = JSONObject()) {
        set(entityId, state, attributes)
        sockets.forEach { (socket, subscription) ->
            socket.send(JSONObject().put("id", subscription).put("type", "event").put("event", JSONObject()
                .put("event_type", "state_changed").put("data", JSONObject().put("entity_id", entityId)
                    .put("new_state", states[entityId]))).toString())
        }
    }

    fun connected() = sockets.size

    /** Обрыв всех соединений (как перезагрузка HA или пропавший Wi-Fi). */
    fun dropAll() {
        // Серверный сокет MockWebServer нельзя cancel() (NPE внутри OkHttp): закрываем кадром «перезапуск сервера».
        sockets.forEach { runCatching { it.first.close(1012, "restart") } }
        sockets.clear()
    }

    override fun close() {
        dropAll()
        runCatching { server.shutdown() }
    }

    private inner class Session : WebSocketListener() {
        private var authed = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2026.11.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = json.optInt("id")
            fun result(value: Any? = JSONObject.NULL, success: Boolean = true) = webSocket.send(JSONObject().put("id", id)
                .put("type", "result").put("success", success).put("result", value ?: JSONObject.NULL).toString())
            if (!authed) {
                if (json.optString("type") == "auth") {
                    authed = json.optString("access_token") == token
                    if (authed) auths.incrementAndGet()
                    webSocket.send(if (authed) """{"type":"auth_ok","ha_version":"2026.11.0"}""" else """{"type":"auth_invalid","message":"x"}""")
                }
                return
            }
            when (json.optString("type")) {
                "subscribe_events" -> { sockets += webSocket to id; result() }
                "get_states" -> { snapshots.incrementAndGet(); result(JSONArray(states.values.toList())) }
                "get_config" -> result(JSONObject().put("location_name", "Deep test").put("version", "2026.11.0")
                    .put("unit_system", JSONObject().put("temperature", "°C")))
                "config/entity_registry/list" -> result(JSONArray(states.keys.map { JSONObject().put("entity_id", it).put("name", JSONObject.NULL) }))
                "weather/subscribe_forecast" -> {
                    result()
                    webSocket.send(JSONObject().put("id", id).put("type", "event").put("event", JSONObject()
                        .put("type", json.optString("forecast_type")).put("forecast", forecast())).toString())
                }
                "history/history_during_period" -> result(JSONObject())
                "camera/stream" -> result(success = false)
                "call_service" -> {
                    commands += json
                    log("Команда устройству: ${json.optString("domain")}.${json.optString("service")} " +
                        json.optJSONObject("service_data")?.optString("entity_id").orEmpty())
                    result(JSONObject().put("context", JSONObject().put("id", "c$id")))
                    val entity = json.optJSONObject("service_data")?.optString("entity_id").orEmpty()
                    val service = json.optString("service")
                    if (entity.isNotEmpty()) push(entity, when (service) {
                        "turn_on" -> "on"
                        "turn_off" -> "off"
                        "toggle" -> if (states[entity]?.optString("state") == "on") "off" else "on"
                        else -> states[entity]?.optString("state") ?: "unknown"
                    })
                }
                else -> result()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets.removeAll { it.first === webSocket } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets.removeAll { it.first === webSocket } }
    }

    private fun forecast() = JSONArray().apply {
        repeat(24) { h ->
            put(JSONObject().put("datetime", "2026-11-07T%02d:00:00+00:00".format(h)).put("condition", "cloudy")
                .put("temperature", 5 + h % 7).put("templow", 1).put("precipitation", 0.2))
        }
    }
}
