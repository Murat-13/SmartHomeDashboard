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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * HA-03 (аудит 25.11.2026): сущность удалили или переименовали — её значение больше не «свежее». Раньше событие с пустым новым
 * состоянием пропускалось, а в снимке отсутствие не замечалось: лампа оставалась «включённой», удалённый датчик «никого» — основанием
 * для «закрыть само». Только loopback-сервер с искусственными данными.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaGoneEntityTest {
    private val server = MockWebServer()
    private val peer = Peer()
    private lateinit var client: HomeAssistantWebSocket
    private val events = mutableListOf<Pair<String, String>>()
    private val snapshots = mutableListOf<Map<String, String>>()
    private var known = emptySet<String>()

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
    }

    @After fun cleanup() { client.disconnect(); idle(); server.shutdown() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    private fun start() {
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        client = HomeAssistantWebSocket(server.url("/").toString(), "test-token", { id, state, _ -> events += id to state }, {}, {},
            onError = {}, onConnectionError = {}, knownEntities = { known },
            onStatesSnapshot = { states -> snapshots += states.associate { it.entityId to it.state } })
        client.subscribeEntities(emptyList())
        client.connect()
        await("запрос снимка") { peer.all("get_states").isNotEmpty() }
    }

    private fun item(id: String, state: Any) = JSONObject().put("entity_id", id).put("state", state).put("attributes", JSONObject())
    private fun snapshot(vararg items: JSONObject) {
        val before = snapshots.size
        peer.socket!!.send(JSONObject().put("type", "result").put("id", peer.all("get_states").last().getInt("id")).put("success", true)
            .put("result", JSONArray(items.toList())).toString())
        await("снимок") { snapshots.size > before }
    }
    private fun changed(data: JSONObject) = peer.socket!!.send(JSONObject().put("type", "event").put("id", peer.all("subscribe_events").first().getInt("id"))
        .put("event", JSONObject().put("event_type", "state_changed").put("data", data)).toString())

    @Test fun removedEntityBecomesUnavailable() {
        start()
        snapshot(item("light.a", "on"))
        changed(JSONObject().put("entity_id", "light.a").put("old_state", item("light.a", "on")).put("new_state", JSONObject.NULL))
        await("событие об удалении") { events.isNotEmpty() }
        assertEquals(listOf("light.a" to "unavailable"), events)
        // Испорченные события — не удаление.
        changed(JSONObject().put("entity_id", "light.b"))
        changed(JSONObject().put("new_state", JSONObject.NULL))
        changed(JSONObject().put("entity_id", "light.a").put("new_state", item("light.a", "off")))
        await("вернулась") { events.size > 1 }
        assertEquals("вернулась обычным событием", listOf("light.a" to "unavailable", "light.a" to "off"), events)
    }

    @Test fun entityAbsentFromSnapshotBecomesUnavailable() {
        known = setOf("light.a", "light.gone", "light.late", "sensor.odd")
        start()
        // Событие пришло после запроса снимка — сущность есть, хотя в снимок не попала.
        changed(JSONObject().put("entity_id", "light.late").put("new_state", item("light.late", "on")))
        await("событие") { events.isNotEmpty() }
        snapshot(item("light.a", "on"), item("sensor.odd", 5), item("light.new", "off"))
        assertEquals(mapOf("light.a" to "on", "light.new" to "off", "light.gone" to "unavailable"), snapshots.last())
    }

    /** Проверка адреса, выбор сущностей и мастер состояния не хранят — для них ничего не меняется. */
    @Test fun clientsWithoutStatesAreNotAffected() {
        start()
        snapshot(item("light.a", "on"))
        assertEquals(mapOf("light.a" to "on"), snapshots.last())
        assertFalse(events.any { it.second == "unavailable" })
    }
}
