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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 0.9.5 — исправления транспорта HA: закрытие без кода, общая очистка при возврате на локальный адрес,
 * ошибки операций отдельно от статуса соединения, без дублей get_states, повтор get_config.
 * Только loopback-серверы с искусственными данными; команд реальному дому нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaTransportFixesTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<HomeAssistantWebSocket>()
    private val sockets = mutableListOf<ServerSocket>()
    private var model: MainViewModel? = null
    private var store: PowerHistoryStore? = null
    private var recorder: PowerHistoryRecorder? = null

    /** Искусственный HA: авторизует, записывает запросы, отвечает только через [reply]. */
    private class Peer(private val reply: (WebSocket, JSONObject) -> Unit = { _, _ -> }) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            if (message.optString("type") == "auth") webSocket.send("""{"type":"auth_ok"}""") else reply(webSocket, message)
        }
        fun count(type: String) = received.count { it.optString("type") == type }
        fun ids() = received.filter { it.has("id") }.map { it.getInt("id") }
        // Как HA: на кадр закрытия клиента сервер отвечает своим.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    private fun server(vararg peers: Peer): MockWebServer = MockWebServer().also { server ->
        servers.add(server)
        peers.forEach { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
        server.start()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    private fun settle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }

    private fun result(socket: WebSocket, id: Int, success: Boolean, result: Any? = null, code: String? = null) {
        val json = JSONObject().put("id", id).put("type", "result").put("success", success)
        if (result != null) json.put("result", result)
        if (code != null) json.put("error", JSONObject().put("code", code).put("message", "artificial"))
        socket.send(json.toString())
    }

    @After fun cleanup() {
        model?.disconnectWebSocket()
        recorder?.stop()
        clients.forEach { it.disconnect() }
        shadowOf(Looper.getMainLooper()).idle()
        servers.forEach { it.shutdown() }
        sockets.forEach { runCatching { it.close() } }
        store?.close()
    }

    /**
     * Минимальный WebSocket-сервер: рукопожатие и сразу кадр закрытия без кода состояния.
     * OkHttp передаёт его в onClosing как 1005; ответный close(1005) бросил бы исключение.
     */
    private fun closeWithoutCodeServer(): String {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).also { sockets.add(it) }
        thread(isDaemon = true) {
            runCatching {
                val socket = server.accept()
                val input = socket.getInputStream().bufferedReader()
                var key = ""
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
                }
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                output.write(byteArrayOf(0x88.toByte(), 0x00))
                output.flush()
                Thread.sleep(3_000)
                socket.close()
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    @Test fun closeFrameWithoutStatusCodeDoesNotCrashAndReportsDisconnect() {
        val connectionErrors = CopyOnWriteArrayList<String>()
        var disconnected = 0
        HomeAssistantWebSocket(closeWithoutCodeServer(), "test-token", { _, _, _ -> }, {}, { disconnected++ },
            autoReconnect = false, onConnectionError = { connectionErrors.add(it) }).also { clients.add(it) }.connect()
        // До исправления idle() пробрасывал IllegalArgumentException из close(1005) в главном потоке.
        await { connectionErrors.isNotEmpty() }
        settle()
        assertEquals("Сервер закрыл соединение", connectionErrors.first())
        assertEquals(1, disconnected)
    }

    @Test fun rejectedCommandIsOperationErrorNotConnectionError() {
        val peer = Peer { socket, message ->
            if (message.optString("type") == "call_service") result(socket, message.getInt("id"), false, code = "home_assistant_error")
        }
        val server = server(peer)
        val errors = CopyOnWriteArrayList<String>()
        val connectionErrors = CopyOnWriteArrayList<String>()
        var connected = false
        val client = HomeAssistantWebSocket(server.url("/").toString(), "test-token", { _, _, _ -> }, { connected = true }, {},
            autoReconnect = false, onError = { errors.add(it) }, onConnectionError = { connectionErrors.add(it) })
            .also { clients.add(it) }
        client.connect()
        await { connected }
        val results = CopyOnWriteArrayList<ServiceCommandResult>()
        assertTrue(client.callService("light", "turn_on", "light.artificial") { results.add(it) })
        await { results.isNotEmpty() && errors.isNotEmpty() }
        assertEquals(ServiceCommandResult.REJECTED, results.single())
        assertTrue("Отказ команды не разрыв соединения", connectionErrors.isEmpty())
    }

    @Test fun viewModelKeepsConnectedStatusOnOperationErrorAndPublishesItSeparately() {
        val peer = Peer { socket, message ->
            when (message.optString("type")) {
                "call_service" -> result(socket, message.getInt("id"), false, code = "home_assistant_error")
                "get_states" -> result(socket, message.getInt("id"), true, JSONArray())
                "get_config" -> result(socket, message.getInt("id"), true, JSONObject())
            }
        }
        val server = server(peer)
        val model = MainViewModel().also { model = it }
        model.connectWebSocket(server.url("/").toString(), "artificial-token", autoReconnect = false)
        await { model.uiState.value.connectionStatus == ConnectionStatus.Connected }
        assertNull(model.uiState.value.operationError)

        model.callService("light", "turn_on", "light.artificial")
        await { model.uiState.value.operationError != null }
        settle()
        assertEquals(ConnectionStatus.Connected, model.uiState.value.connectionStatus)
        assertTrue(model.uiState.value.haSession.connected)
        assertEquals(model.uiState.value.statusMessage, model.uiState.value.operationError?.message)

        // Настоящий разрыв по-прежнему меняет статус соединения.
        peer.socket!!.close(1000, "artificial")
        await { model.uiState.value.connectionStatus is ConnectionStatus.Error }
    }

    @Test fun duplicateStatesRequestIsSkippedOnlyWhileNothingChanged() {
        val peer = Peer()
        val server = server(peer)
        var connected = false
        val client = HomeAssistantWebSocket(server.url("/").toString(), "test-token", { _, _, _ -> }, { connected = true }, {},
            autoReconnect = false).also { clients.add(it) }
        client.subscribeEntities(listOf("light.artificial"))
        client.connect()
        await { connected && peer.count("get_states") == 1 }
        // Как onConnected сразу после auth_ok и onResume панели: точные дубли не отправляются.
        repeat(3) { client.subscribeEntities(listOf("light.artificial")) }
        settle()
        assertEquals(1, peer.count("get_states"))

        // Пришло событие — следующий вызов снова пересинхронизирует полным снимком.
        peer.socket!!.send(JSONObject().put("type", "event").put("id", 1).put("event", JSONObject()
            .put("event_type", "state_changed").put("data", JSONObject().put("entity_id", "light.artificial")
                .put("new_state", JSONObject().put("state", "on").put("attributes", JSONObject())))).toString())
        settle()
        client.subscribeEntities(listOf("light.artificial"))
        await { peer.count("get_states") == 2 }

        // После ответа явная пересинхронизация по-прежнему возможна.
        val request = peer.received.last { it.optString("type") == "get_states" }
        result(peer.socket!!, request.getInt("id"), true, JSONArray())
        settle()
        client.subscribeEntities(listOf("light.artificial"))
        await { peer.count("get_states") == 3 }
        assertEquals(1, peer.count("subscribe_events"))
    }

    @Test fun failedStatesSnapshotIsRequestedAgain() {
        val peer = Peer()
        val server = server(peer)
        val errors = CopyOnWriteArrayList<String>()
        var connected = false
        val client = HomeAssistantWebSocket(server.url("/").toString(), "test-token", { _, _, _ -> }, { connected = true }, {},
            autoReconnect = false, onError = { errors.add(it) }).also { clients.add(it) }
        client.subscribeEntities(emptyList())
        client.connect()
        await { connected && peer.count("get_states") == 1 }
        val request = peer.received.last { it.optString("type") == "get_states" }
        result(peer.socket!!, request.getInt("id"), false, code = "unknown_error")
        await { errors.isNotEmpty() }
        client.subscribeEntities(emptyList())
        await { peer.count("get_states") == 2 }
    }

    @Test fun switchToLocalFinishesPendingReadsAndKeepsMessageIdsGrowing() {
        val probe = Peer()
        val remotePeer = Peer() // на get_config не отвечает: запрос висит до переключения
        val local = MockWebServer().also { servers.add(it) }
        local.enqueue(MockResponse().setResponseCode(503))
        local.enqueue(MockResponse().withWebSocketUpgrade(probe))
        local.start()
        val remote = server(remotePeer)
        var connections = 0
        var switched = 0
        val client = HomeAssistantWebSocket(local.url("/").toString(), "test-token", { _, _, _ -> }, { connections++ }, {},
            alternateHosts = listOf(remote.url("/").toString()), primaryProbeIntervalMs = 1_000L,
            onEndpointSwitched = { switched++ }).also { clients.add(it) }
        client.subscribeEntities(emptyList())
        client.connect()
        await { connections == 1 && remotePeer.count("get_states") == 1 }

        val answers = CopyOnWriteArrayList<Any?>()
        client.read("get_config") { answers.add(it ?: "null") }
        await { remotePeer.count("get_config") == 1 }
        assertTrue(answers.isEmpty())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (switched == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        await { switched == 1 && probe.count("get_states") == 1 }
        // Запрос прошлого соединения завершён отказом сразу, а не через 60 с тайм-аута.
        assertEquals(listOf<Any?>("null"), answers.toList())
        // Нумерация сквозная: ни один ID нового соединения не совпадает с ID прошлого.
        assertTrue(probe.ids().min() > remotePeer.ids().max())
    }

    @Test fun publicCleartextAddressIsRefusedWithoutConsentAndPanelFallsBackToLocal() {
        val peer = Peer()
        val local = server(peer)
        val connectionErrors = CopyOnWriteArrayList<String>()
        var connected = false
        // Порядок как у подозрительной настройки: внешний http первым, домашний адрес запасным.
        val client = HomeAssistantWebSocket("http://203.0.113.7:8123", "test-token", { _, _, _ -> }, { connected = true }, {},
            alternateHosts = listOf(local.url("/").toString()), autoReconnect = false,
            onConnectionError = { connectionErrors.add(it) }).also { clients.add(it) }
        client.connect()
        await { connected }
        assertEquals(HomeAssistantAddress.INSECURE_REMOTE_REFUSED, connectionErrors.first())
        assertEquals(local.url("/").toString(), client.getHost())
        assertEquals("Токен ушёл только на домашний адрес", 1, peer.count("auth"))
    }

    @Test fun publicCleartextAddressAloneStopsWithClearReason() {
        val statuses = CopyOnWriteArrayList<String>()
        HomeAssistantWebSocket("http://203.0.113.7:8123", "test-token", { _, _, _ -> }, {}, {},
            onStatus = { statuses.add(it) }).also { clients.add(it) }.connect()
        await { statuses.any { it.startsWith("Ошибка:") } }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals("Ошибка: ${HomeAssistantAddress.INSECURE_REMOTE_REFUSED}", statuses.last())
    }

    @Test fun serverIdentityIsRetriedWhenGetConfigFails() {
        var configAnswers = 0
        val peer = Peer { socket, message ->
            when (message.optString("type")) {
                "get_states" -> result(socket, message.getInt("id"), true, JSONArray())
                "get_config" -> if (++configAnswers == 1) result(socket, message.getInt("id"), false, code = "unknown_error")
                    else result(socket, message.getInt("id"), true, JSONObject().put("location_name", "Artificial home")
                        .put("time_zone", "UTC").put("latitude", 1.0).put("longitude", 2.0))
            }
        }
        val server = server(peer)
        val history = PowerHistoryStore(RuntimeEnvironment.getApplication(), name = null).also { store = it }
        val recorder = PowerHistoryRecorder(history, worker = { it.run() }).also { recorder = it }
        val model = MainViewModel().also { model = it }
        model.powerHistory = recorder
        model.connectWebSocket(server.url("/").toString(), "artificial-token", autoReconnect = false)
        await { peer.count("get_config") == 1 }
        settle()
        assertNull("Отказ get_config — сервер ещё не определён", recorder.instance)

        // Первый повтор через 15 с, без переподключения.
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        await { peer.count("get_config") == 2 && recorder.instance != null }
        assertEquals(1, server.requestCount)
    }
}
