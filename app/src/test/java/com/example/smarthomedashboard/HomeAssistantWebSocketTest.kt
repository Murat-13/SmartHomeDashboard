package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeAssistantWebSocketTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<HomeAssistantWebSocket>()

    private class Peer(private val accept: Boolean = true, private val challenge: Boolean = true) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            if (challenge) webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            if (message.optString("type") == "auth") {
                webSocket.send(if (accept) """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
            }
        }
    }

    private fun server(peer: Peer): MockWebServer = MockWebServer().also {
        servers.add(it)
        it.enqueue(MockResponse().withWebSocketUpgrade(peer))
        it.start()
    }

    private fun client(host: String, alternatives: List<String> = emptyList(), retry: Boolean = true,
                       connected: () -> Unit = {}, error: (String) -> Unit = {}, status: (String) -> Unit = {},
                       state: (String, String, JSONObject) -> Unit = { _, _, _ -> }): HomeAssistantWebSocket =
        HomeAssistantWebSocket(host, "test-token", state, connected, {}, alternateHosts = alternatives,
            autoReconnect = retry, onError = error, onStatus = status).also { clients.add(it) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    @After fun cleanup() {
        clients.forEach { it.disconnect() }
        shadowOf(Looper.getMainLooper()).idle()
        servers.forEach { it.shutdown() }
    }

    @Test fun urlsPreserveSchemesAndNormalizeEndpoint() {
        assertEquals("ws://192.0.2.1:8123/api/websocket", HomeAssistantAddress.webSocketUrl("192.0.2.1:8123"))
        assertEquals("ws://ha.example.invalid:8123/api/websocket", HomeAssistantAddress.webSocketUrl("http://ha.example.invalid:8123"))
        assertEquals("wss://ha.example.invalid/api/websocket", HomeAssistantAddress.webSocketUrl("https://ha.example.invalid"))
        assertEquals("ws://ha.example.invalid/api/websocket", HomeAssistantAddress.webSocketUrl("ws://ha.example.invalid"))
        assertEquals("wss://ha.example.invalid/api/websocket", HomeAssistantAddress.webSocketUrl("wss://ha.example.invalid/api/websocket"))
        assertEquals("wss://ha.example.invalid/api/websocket", HomeAssistantAddress.webSocketUrl("https://ha.example.invalid/"))
    }

    @Test fun retryDelayIsBoundedAndIncreasing() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L),
            (0..6).map { HomeAssistantAddress.retryDelay(it) })
    }

    @Test fun socketOpenDoesNotAuthenticateUntilChallenge() {
        val peer = Peer(challenge = false)
        val server = server(peer)
        var connected = false
        val client = client(server.url("/").toString(), connected = { connected = true })
        client.connect()
        client.subscribeEntities(listOf("light.test"))
        await { peer.socket != null }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(connected)
        assertTrue(peer.received.isEmpty())
        peer.socket!!.send("""{"type":"auth_required"}""")
        await { connected && peer.received.any { it.optString("type") == "get_states" } }
        assertEquals("auth", peer.received.first().getString("type"))
        assertEquals(1, peer.received.count { it.optString("type") == "subscribe_events" })
    }

    @Test fun authenticationFailureFallsBackAndManualDisconnectDoesNotRetry() {
        val local = Peer(accept = false)
        val remote = Peer()
        val first = server(local)
        val second = server(remote)
        var connected = false
        val client = client(first.url("/").toString(), listOf(second.url("/").toString()),
            connected = { connected = true })
        client.connect()
        await { connected }
        assertEquals(second.url("/").toString(), client.getHost())
        client.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(1, first.requestCount)
        assertEquals(1, second.requestCount)
    }

    private fun probingClient(local: String, remote: String, onConnected: () -> Unit) =
        HomeAssistantWebSocket(local, "test-token", { _, _, _ -> }, onConnected, {}, alternateHosts = listOf(remote),
            primaryProbeIntervalMs = 1_000L).also { clients.add(it) }

    @Test fun returnsToLocalAddressWhenItIsBackWithoutAuthenticatingProbe() {
        val probe = Peer()
        val localPeer = Peer()
        val remotePeer = Peer()
        // Локальный сначала недоступен, затем отвечает на пробу и на настоящее подключение.
        val local = MockWebServer().also { servers.add(it) }
        local.enqueue(MockResponse().setResponseCode(503))
        local.enqueue(MockResponse().withWebSocketUpgrade(probe))
        local.enqueue(MockResponse().withWebSocketUpgrade(localPeer))
        local.start()
        val remote = server(remotePeer)
        var connections = 0
        val client = probingClient(local.url("/").toString(), remote.url("/").toString()) { connections++ }
        client.subscribeEntities(listOf("light.test"))
        client.connect()
        await { connections == 1 }
        assertEquals(remote.url("/").toString(), client.getHost())

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        await { connections == 2 }
        assertEquals(local.url("/").toString(), client.getHost())
        // Проба не отправляет токен: достаточно приглашения auth_required.
        assertTrue(probe.received.isEmpty())
        // После переключения подписки восстанавливаются на локальном адресе.
        await { localPeer.received.any { it.optString("type") == "subscribe_events" } }
        assertEquals(1, remote.requestCount)
    }

    @Test fun staysOnRemoteWhileLocalIsDownAndChecksAgainLater() {
        val localPeer = Peer()
        val local = MockWebServer().also { servers.add(it) }
        repeat(2) { local.enqueue(MockResponse().setResponseCode(503)) }
        local.enqueue(MockResponse().withWebSocketUpgrade(Peer()))
        local.enqueue(MockResponse().withWebSocketUpgrade(localPeer))
        local.start()
        val remote = server(Peer())
        var connections = 0
        val client = probingClient(local.url("/").toString(), remote.url("/").toString()) { connections++ }
        client.connect()
        await { connections == 1 }

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        await { local.requestCount == 2 }
        // Неудачная проба не разрывает работающее соединение.
        assertEquals(remote.url("/").toString(), client.getHost())
        assertEquals(1, connections)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        await { connections == 2 }
        assertEquals(local.url("/").toString(), client.getHost())
    }

    @Test fun unavailableLocalFallsBackToRemote() {
        val remote = Peer()
        val server = server(remote)
        var connected = false
        client("http://127.0.0.1:1", listOf(server.url("/").toString()),
            retry = false, connected = { connected = true }).connect()
        await { connected }
    }

    @Test fun invalidTokenStopsRetries() {
        val peer = Peer(accept = false)
        val server = server(peer)
        var error = ""
        client(server.url("/").toString(), error = { error = it }).connect()
        await { error.contains("токен") }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidTokenRetriesAfterLongDelayAndKeepsExplicitTokenStatus() {
        val denied = Peer(accept = false)
        val server = server(denied)
        val recovered = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(recovered))
        var status = ""
        var connected = false
        val socket = client(server.url("/").toString(), connected = { connected = true }, status = { status = it })
        socket.connect()
        await { status.contains("10 минут") }
        assertTrue(status.contains("токен"))
        socket.connect() // Resuming must not bypass the token cooldown.
        shadowOf(Looper.getMainLooper()).idleFor(599, TimeUnit.SECONDS)
        assertEquals(1, server.requestCount)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        await { connected }
        assertEquals(2, server.requestCount)
    }

    @Test fun handshakeUnauthorizedAndForbiddenRetryAsTransientFailures() {
        for (code in listOf(401, 403)) {
            val server = MockWebServer().also { servers.add(it) }
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().withWebSocketUpgrade(Peer()))
            server.start()
            var status = ""
            var connected = false
            val socket = client(server.url("/").toString(), connected = { connected = true }, status = { status = it })
            socket.connect()
            await { status.contains("Переподключение") }
            assertTrue(status.contains("HTTP $code"))
            shadowOf(Looper.getMainLooper()).idleFor(1999, TimeUnit.MILLISECONDS)
            assertEquals(1, server.requestCount)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            await { connected }
            socket.disconnect()
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun disconnectCancelsLongTokenRetry() {
        val server = server(Peer(accept = false))
        var status = ""
        val socket = client(server.url("/").toString(), status = { status = it })
        socket.connect()
        await { status.contains("10 минут") }
        socket.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.MINUTES)
        assertEquals(1, server.requestCount)
    }

    @Test fun authTimeoutFallsBackAndDuplicateConnectDoesNotOpenAnotherSocket() {
        val silent = Peer(challenge = false)
        val remote = Peer()
        val localServer = server(silent)
        val remoteServer = server(remote)
        var connected = false
        val client = client(localServer.url("/").toString(), listOf(remoteServer.url("/").toString()),
            retry = false, connected = { connected = true })
        client.connect()
        client.connect()
        await { silent.socket != null }
        assertFalse(connected)
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS)
        await { connected }
        assertEquals(1, localServer.requestCount)
        assertEquals(1, remoteServer.requestCount)
    }

    @Test fun manualDisconnectCancelsScheduledReconnectAndOfflineCommandsAreNotReplayed() {
        val peer = Peer()
        val server = server(peer)
        var connects = 0
        var errors = 0
        val client = client(server.url("/").toString(), connected = { connects++ }, error = { errors++ })
        client.connect()
        await { connects == 1 }
        peer.socket!!.close(1001, "offline")
        await { errors == 1 }
        client.callService("light", "turn_on", "light.test")
        client.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(1, server.requestCount)
        assertFalse(peer.received.any { it.optString("type") == "call_service" })
    }

    @Test fun bothServersRejectTokenWithoutRetryLoop() {
        val local = server(Peer(accept = false))
        val remote = server(Peer(accept = false))
        var errors = 0
        client(local.url("/").toString(), listOf(remote.url("/").toString()), error = { errors++ }).connect()
        await { errors == 2 }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(1, local.requestCount)
        assertEquals(1, remote.requestCount)
    }

    @Test fun networkErrorsHaveSpecificMessages() {
        assertTrue(HomeAssistantAddress.errorMessage(java.net.UnknownHostException()).contains("DNS"))
        assertTrue(HomeAssistantAddress.errorMessage(javax.net.ssl.SSLHandshakeException("test")).contains("TLS"))
        assertTrue(HomeAssistantAddress.errorMessage(java.net.SocketTimeoutException()).contains("Тайм-аут"))
        assertTrue(HomeAssistantAddress.errorMessage(java.net.ConnectException()).contains("сети"))
    }

    @Test fun reconnectRestoresStatesForecastAndServiceControl() {
        val first = Peer()
        val second = Peer()
        val server = server(first)
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        var connects = 0
        var errors = 0
        var state = ""
        val client = client(server.url("/").toString(), connected = { connects++ },
            error = { errors++ }, state = { _, value, _ -> state = value })
        client.subscribeEntities(listOf("light.test"))
        client.subscribeForecast("weather.home")
        client.connect()
        await { connects == 1 && first.received.size >= 4 }
        first.socket!!.close(1001, "restart")
        await { errors == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        await { connects == 2 && second.received.size >= 4 }
        assertTrue(second.received.any { it.optString("type") == "subscribe_events" })
        assertTrue(second.received.any { it.optString("type") == "get_states" })
        assertTrue(second.received.any { it.optString("type") == "weather/subscribe_forecast" })
        second.socket!!.send("""{"type":"result","success":true,"result":[{"entity_id":"light.test","state":"on","attributes":{}}]}""")
        await { state == "on" }
        client.callService("light", "turn_off", "light.test")
        await { second.received.any { it.optString("type") == "call_service" } }
        assertEquals(1, second.received.count { it.optString("type") == "subscribe_events" })
    }
    @Test fun serverControlledErrorCodeCannotLeakIntoDiagnosticCallback() {
        val peer=Peer();val errors=CopyOnWriteArrayList<String>();var connected=false
        client(server(peer).url("/").toString(),connected={connected=true},error={errors.add(it)}).connect()
        await{connected}
        peer.socket!!.send(JSONObject().put("type","result").put("id",991).put("success",false)
            .put("error",JSONObject().put("code","FAKE-PRIVATE-KEY-IN-CODE")).toString())
        await{errors.isNotEmpty()}
        assertFalse(errors.joinToString().contains("FAKE-PRIVATE-KEY-IN-CODE"))
    }
    @Test fun forecastErrorCodeCannotLeakIntoDiagnosticCallback() {
        val peer=Peer();val errors=CopyOnWriteArrayList<String>();var connected=false
        val client=client(server(peer).url("/").toString(),connected={connected=true},error={errors.add(it)})
        client.subscribeForecast("weather.fake");client.connect();await{connected}
        await{peer.received.any{it.optString("type")=="weather/subscribe_forecast"}}
        val command=peer.received.last{it.optString("type")=="weather/subscribe_forecast"}
        peer.socket!!.send(JSONObject().put("type","result").put("id",command.getInt("id")).put("success",false)
            .put("error",JSONObject().put("code","FAKE-PRIVATE-FORECAST-CODE")).toString())
        await{errors.isNotEmpty()}
        assertFalse(errors.joinToString().contains("FAKE-PRIVATE-FORECAST-CODE"))
    }

    @Test fun serviceAckCompletesOnlyItsRequestWithoutClaimingAnEntityState() {
        val peer = Peer()
        val errors = mutableListOf<String>()
        var connected = false
        var states = 0
        var finished = 0
        val client = client(server(peer).url("/").toString(), connected = { connected = true },
            error = { errors.add(it) }, state = { _, _, _ -> states++ })
        client.connect()
        await { connected }
        assertTrue(client.callService("switch", "turn_on", "switch.test") { finished++ })
        await { peer.received.any { it.optString("type") == "call_service" } }
        val request = peer.received.single { it.optString("type") == "call_service" }
        peer.socket!!.send(JSONObject().put("type", "result").put("id", request.getInt("id"))
            .put("success", false).put("error", JSONObject().put("code", "artificial-private-code")).toString())
        await { finished == 1 }
        assertEquals(0, states)
        assertEquals(1, errors.size)
        assertFalse(errors.single().contains("artificial-private-code"))
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, finished)
        assertEquals(1, peer.received.count { it.optString("type") == "call_service" })
    }
}
