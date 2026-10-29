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

// Настоящий MainViewModel и транспорт через loopback MockWebServer: признак синхронизации сессии.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaSessionSyncTest {
    private val server = MockWebServer()
    private val model = MainViewModel()

    private class Peer : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            if (message.optString("type") == "auth") webSocket.send("""{"type":"auth_ok"}""")
        }
    }

    @After fun tearDown() {
        model.disconnectWebSocket()
        server.shutdown()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition timed out", condition())
    }

    @Test fun sessionIsSyncedOnlyAfterSnapshotOfCurrentConnection() {
        val peer = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        server.start()
        assertEquals(HaSession(), model.uiState.value.haSession)
        model.connectWebSocket(server.url("/").toString(), "artificial-token", autoReconnect = false)
        model.subscribeEntities(emptyList())
        await { peer.received.any { it.optString("type") == "get_states" } && model.uiState.value.haSession.connected }
        assertEquals("Соединение есть, снимка ещё нет", HaSession(connected = true), model.uiState.value.haSession)

        val request = peer.received.last { it.optString("type") == "get_states" }
        peer.socket!!.send(JSONObject().put("type", "result").put("id", request.getInt("id")).put("success", true)
            .put("result", JSONArray().put(JSONObject().put("entity_id", "sensor.bms_soc").put("state", "89")
                .put("attributes", JSONObject()))).toString())
        await { model.uiState.value.haSession.synced }
        assertEquals("89", model.devices.singleStates["sensor.bms_soc"])
        assertEquals(BatteryReading.Value("89", 89.0, JSONObject()).number,
            (BatteryReadings.from(model.uiState.value).of("sensor.bms_soc") as BatteryReading.Value).number)

        peer.socket!!.close(1000, "artificial")
        await { !model.uiState.value.haSession.connected }
        assertFalse(model.uiState.value.haSession.synced)
        // Хранилище не очищено, но показание больше не считается текущим.
        assertEquals("89", model.devices.singleStates["sensor.bms_soc"])
        assertEquals(BatteryReading.Disconnected, BatteryReadings.from(model.uiState.value).of("sensor.bms_soc"))
    }
}
