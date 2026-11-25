package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** HA-04 (аудит 25.11.2026): перенаправление не уводит токен туда, где он ушёл бы без шифрования. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaRedirectTest {
    private val servers = mutableListOf<MockWebServer>()
    private var client: HomeAssistantWebSocket? = null

    private class Peer : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(JSONObject(text))
            if (received.last().optString("type") == "auth") webSocket.send("""{"type":"auth_ok"}""")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        val token get() = received.any { it.optString("access_token") == "TEST-TOKEN" }
    }

    @After fun tearDown() {
        client?.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
        servers.forEach { it.shutdown() }
    }

    private fun server(response: MockResponse) = MockWebServer().also { servers += it; it.enqueue(response); it.start() }
    private fun await(ms: Long, condition: () -> Boolean): Boolean {
        val until = System.nanoTime() + ms * 1_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return condition()
    }

    private fun run(targetHost: String, allowInsecure: Boolean): Triple<Peer, List<String>, Boolean> {
        val peer = Peer()
        val target = server(MockResponse().withWebSocketUpgrade(peer))
        val first = server(MockResponse().setResponseCode(301).setHeader("Location", "http://$targetHost:${target.port}/api/websocket"))
        val errors = CopyOnWriteArrayList<String>()
        var connected = false
        val c = HomeAssistantWebSocket("http://127.0.0.1:${first.port}", "TEST-TOKEN", { _, _, _ -> }, { connected = true }, {},
            autoReconnect = false, onError = { errors += it }, allowInsecureRemote = allowInsecure)
        client = c
        // Внешнее имя ведёт на этот же компьютер: настоящей сети в тесте нет.
        ReflectionHelpers.setField(c, "client", OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }).build())
        c.connect()
        await(3_000) { connected || errors.isNotEmpty() }
        await(300) { false }
        return Triple(peer, errors, connected)
    }

    @Test fun redirectToUnencryptedExternalAddressIsRefusedBeforeToken() {
        val (peer, errors, connected) = run("ha.example.com", allowInsecure = false)
        assertFalse("токен не отправлен", peer.token)
        assertFalse(connected)
        assertEquals(listOf(HomeAssistantAddress.UNSAFE_REDIRECT), errors)
    }

    @Test fun redirectInsideHomeNetworkStillWorks() {
        val (peer, errors, connected) = run("127.0.0.1", allowInsecure = false)
        assertTrue("домашнее перенаправление работает: $errors", connected && peer.token)
    }

    @Test fun explicitConsentStillAllowsUnencryptedExternalAddress() {
        val (peer, errors, connected) = run("ha.example.com", allowInsecure = true)
        assertTrue("владелец разрешил: $errors", connected && peer.token)
    }

    @Test fun rules() {
        fun unsafe(from: String, to: String, allow: Boolean = false) = HomeAssistantAddress.unsafeRedirect(from, to.toHttpUrl(), allow)
        assertTrue("https → http", unsafe("https://ha.example.com", "http://ha.example.com/api/websocket"))
        assertTrue("https → http и с разрешением", unsafe("https://ha.example.com", "http://ha.example.com/api/websocket", allow = true))
        assertTrue("https → http в домашней сети", unsafe("https://192.168.1.5", "http://192.168.1.5/api/websocket"))
        assertFalse("http → https", unsafe("http://192.168.1.5:8123", "https://192.168.1.5/api/websocket"))
        assertFalse("https → https", unsafe("https://ha.example.com", "https://www.ha.example.com/api/websocket"))
        assertFalse("без перенаправления, дома", unsafe("http://192.168.1.5:8123", "http://192.168.1.5:8123/api/websocket"))
        assertFalse("без перенаправления, https", unsafe("ha.example.com", "https://ha.example.com/api/websocket"))
        assertTrue("дома → наружу без шифрования", unsafe("192.168.1.5:8123", "http://ha.example.com/api/websocket"))
        assertFalse("разрешено явно", unsafe("http://ha.example.com", "http://ha.example.com/api/websocket", allow = true))
    }
}
