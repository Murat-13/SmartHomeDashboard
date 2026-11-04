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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 0.9.5 — возврат на локальный адрес без разрыва (решения владельца 04.11.2026): локальный адрес всегда
 * в приоритете; проба авторизуется и подменяет соединение без «нет связи», устаревших показаний и разрыва
 * графика; команды не обрываются; нестабильный локальный адрес проверяется реже.
 * Только loopback-серверы с искусственными данными; команд реальному дому нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaSoftSwitchTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<HomeAssistantWebSocket>()
    private var model: MainViewModel? = null

    /** Искусственный HA: [accept] — принимает ли токен; ответы на запросы — через [reply]. */
    private class Peer(private val accept: Boolean = true, private val reply: (WebSocket, JSONObject) -> Unit = { _, _ -> }) :
        WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        @Volatile var closedByClient = false
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            if (message.optString("type") == "auth") {
                webSocket.send(if (accept) """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
            } else reply(webSocket, message)
        }
        // Как HA: на кадр закрытия клиента сервер отвечает своим.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closedByClient = true
            webSocket.close(1000, null)
        }
        fun count(type: String) = received.count { it.optString("type") == type }
    }

    private fun states(socket: WebSocket, message: JSONObject, value: String) {
        if (message.optString("type") != "get_states") return
        socket.send(JSONObject().put("id", message.getInt("id")).put("type", "result").put("success", true)
            .put("result", JSONArray().put(JSONObject().put("entity_id", "sensor.power").put("state", value)
                .put("attributes", JSONObject().put("unit_of_measurement", "W")))).toString())
    }

    /** Локальный адрес: сначала [down] отказов, затем указанные пиры, затем снова отказы (для поздних проб). */
    private fun local(down: Int, vararg peers: Peer) = MockWebServer().also { server ->
        servers += server
        repeat(down) { server.enqueue(MockResponse().setResponseCode(503)) }
        peers.forEach { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
        repeat(8) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()
    }

    private fun server(peer: Peer) = local(0, peer)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    private fun advanceUntil(step: Long = 250, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    @After fun cleanup() {
        model?.disconnectWebSocket()
        clients.forEach { it.disconnect() }
        shadowOf(Looper.getMainLooper()).idle()
        servers.forEach { it.shutdown() }
    }

    private fun client(local: MockWebServer, remote: MockWebServer, connected: () -> Unit = {}, disconnected: () -> Unit = {},
                       switched: () -> Unit = {}, status: (String) -> Unit = {}) =
        HomeAssistantWebSocket(local.url("/").toString(), "test-token", { _, _, _ -> }, connected, disconnected,
            alternateHosts = listOf(remote.url("/").toString()), primaryProbeIntervalMs = 1_000L,
            onStatus = status, onEndpointSwitched = switched).also { clients += it }

    @Test fun panelStaysConnectedAndFreshThroughTheSwitch() {
        val remotePeer = Peer { socket, message -> states(socket, message, "100") }
        val probe = Peer { socket, message -> states(socket, message, "250") }
        val local = local(1, probe)
        val remote = server(remotePeer)
        val model = MainViewModel().also { model = it }
        model.connectWebSocket(local.url("/").toString(), "artificial-token", alternateHosts = listOf(remote.url("/").toString()))
        // Панель проверяет локальный адрес раз в 5 минут; в тесте — раз в секунду (до авторизации на запасном).
        org.robolectric.util.ReflectionHelpers.setField(model.getWebSocket()!!, "primaryProbeIntervalMs", 1_000L)
        model.subscribeEntities(emptyList())
        await { model.devices.singleStates["sensor.power"] == "100" && model.uiState.value.haSession.synced }
        val statuses = CopyOnWriteArrayList<ConnectionStatus>()
        val sessions = CopyOnWriteArrayList<HaSession>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            model.uiState.collect { statuses += it.connectionStatus; sessions += it.haSession }
        }
        try {
            advanceUntil { model.getWebSocket()?.getHost() == local.url("/").toString() && probe.count("get_states") == 1 }
            await { model.devices.singleStates["sensor.power"] == "250" }
            assertTrue("Ни одного «нет связи» при переходе: $statuses", statuses.all { it == ConnectionStatus.Connected })
            assertTrue("Показания всё время достоверны", sessions.all { it.connected && it.synced })
            assertTrue("Показания не помечены устаревшими", model.devices.staleEntities.isEmpty())
            assertTrue(remotePeer.closedByClient)
        } finally { job.cancel() }
    }

    @Test fun switchHappensWithoutDisconnectAndClosesTheRemoteSocketPolitely() {
        val remotePeer = Peer()
        val probe = Peer()
        val local = local(1, probe)
        val remote = server(remotePeer)
        var connected = 0; var disconnected = 0; var switched = 0
        val client = client(local, remote, { connected++ }, { disconnected++ }, { switched++ })
        client.subscribeEntities(emptyList())
        client.connect()
        await { connected == 1 }
        // Первая попытка локального адреса до подключения — это обычный отказ; дальше разрывов быть не должно.
        val before = disconnected
        advanceUntil { switched == 1 }
        assertEquals(before, disconnected)
        assertEquals(1, connected)
        assertEquals(local.url("/").toString(), client.getHost())
        await { remotePeer.closedByClient }
        await { probe.count("subscribe_events") == 1 && probe.count("get_states") == 1 }
        // ID на новом сокете продолжают расти: ни один не повторяет ID прошлого соединения.
        val oldIds = remotePeer.received.filter { it.has("id") }.map { it.getInt("id") }
        val newIds = probe.received.filter { it.has("id") }.map { it.getInt("id") }
        assertTrue(newIds.min() > oldIds.max())
    }

    @Test fun commandInFlightIsNotCutAndSwitchWaitsForItsAnswer() {
        var held: Pair<WebSocket, Int>? = null
        val remotePeer = Peer { socket, message -> if (message.optString("type") == "call_service") held = socket to message.getInt("id") }
        val probe = Peer()
        val local = local(1, probe)
        val remote = server(remotePeer)
        var connected = 0; var switched = 0
        val client = client(local, remote, { connected++ }, switched = { switched++ })
        client.connect()
        await { connected == 1 }
        val results = CopyOnWriteArrayList<ServiceCommandResult>()
        assertTrue(client.callService("light", "turn_on", "light.artificial") { results += it })
        await { held != null }
        // Проба готова, но команда ещё ждёт ответа — переход откладывается.
        advanceUntil { probe.count("auth") == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertEquals(0, switched)
        assertTrue(results.isEmpty())
        held!!.first.send(JSONObject().put("id", held!!.second).put("type", "result").put("success", true).toString())
        await { results.isNotEmpty() }
        assertEquals("Команда завершилась ответом HA, а не обрывом", ServiceCommandResult.ACKNOWLEDGED, results.single())
        advanceUntil { switched == 1 }
    }

    @Test fun localAddressThatRejectsTheTokenIsNotUsedAndRemoteKeepsWorking() {
        val probe = Peer(accept = false)
        val local = local(1, probe)
        val remote = server(Peer())
        var connected = 0; var disconnected = 0; var switched = 0
        val client = client(local, remote, { connected++ }, { disconnected++ }, { switched++ })
        client.connect()
        await { connected == 1 }
        val before = disconnected
        advanceUntil { probe.count("auth") == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertEquals(0, switched)
        assertEquals(before, disconnected)
        assertEquals(remote.url("/").toString(), client.getHost())
        // Неверный токен не повторяется каждые 5 минут: HA считает это неудачными входами и может забанить IP.
        val requests = local.requestCount
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); Thread.sleep(5) }
        assertEquals(requests, local.requestCount)
        assertEquals(1, probe.count("auth"))
    }

    @Test fun flappingLocalAddressIsProbedLessOften() {
        val first = Peer()
        val local = local(1, first)
        val remote = MockWebServer().also { servers += it }
        repeat(2) { remote.enqueue(MockResponse().withWebSocketUpgrade(Peer())) }
        remote.start()
        var switched = 0
        val client = client(local, remote, switched = { switched++ })
        client.connect()
        advanceUntil { switched == 1 }
        // Локальный адрес тут же отвалился — панель уходит на запасной.
        first.socket!!.close(1000, "artificial flap")
        advanceUntil(step = 100) { client.getHost() == remote.url("/").toString() && remote.requestCount == 2 }
        val before = local.requestCount
        // Следующая проба — не через 1 с, а через 2 с (проверки реже после нестабильного возврата).
        shadowOf(Looper.getMainLooper()).idleFor(1_200, TimeUnit.MILLISECONDS)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, local.requestCount)
        advanceUntil(step = 100) { local.requestCount == before + 1 }
    }
}
