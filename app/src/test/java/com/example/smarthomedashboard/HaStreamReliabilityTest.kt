package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/** Фиксированный искусственный набор: проверяем границы хранилищ, а не Android heap/FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HaStreamReliabilityTest {
    private class Socket : WebSocket {
        var statesId = 0
        var cancelled = false
        override fun request() = Request.Builder().url("http://localhost/artificial-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val message = JSONObject(text)
            if (message.optString("type") == "get_states") statesId = message.getInt("id")
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    private fun install(owner: MainViewModel): Pair<HomeAssistantWebSocket, Socket> {
        val client = requireNotNull(owner.getWebSocket())
        val socket = Socket()
        ReflectionHelpers.setField(client, "running", true)
        ReflectionHelpers.setField(client, "authenticated", true)
        ReflectionHelpers.setField(client, "webSocket", socket)
        return client to socket
    }

    private fun deliver(client: HomeAssistantWebSocket, message: JSONObject) {
        client.javaClass.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(message.toString())))
    }

    private fun row(id: String, state: String, sequence: Int = 0) = JSONObject()
        .put("entity_id", id).put("state", state)
        .put("attributes", JSONObject().put("sequence", sequence))

    @Test fun tenThousandEventsKeepOrderAndFixedStoreSizesAfterLargeSnapshot() {
        val owner = MainViewModel()
        var callbacks = 0
        var motionEvents = 0
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onStatesSnapshotCallback = {},
            onStateChangedCallback = { id, state, _ ->
                callbacks++
                if (id == "binary_sensor.motion") {
                    assertEquals(if (motionEvents % 2 == 0) "on" else "off", state)
                    motionEvents++
                }
            })
        val (client, socket) = install(owner)
        val changingIds = (0..3).map { "switch.artificial_$it" } + "binary_sensor.motion"
        try {
            owner.subscribeEntities(emptyList())
            val rows = JSONArray()
            changingIds.forEach { rows.put(row(it, "off")) }
            repeat(1995) { rows.put(row("sensor.artificial_$it", it.toString())) }
            deliver(client, JSONObject().put("type", "result").put("id", socket.statesId)
                .put("success", true).put("result", rows))
            owner.groupIsOn("artificial-group", changingIds.take(4))
            val original = owner.uiState.value
            repeat(10_000) { index ->
                val id = changingIds[index % changingIds.size]
                val state = if ((index / changingIds.size) % 2 == 0) "on" else "off"
                deliver(client, JSONObject().put("type", "event").put("event", JSONObject()
                    .put("event_type", "state_changed").put("data", JSONObject()
                        .put("entity_id", id).put("new_state", row(id, state, index)))))
            }
            assertEquals(10_000, callbacks)
            assertEquals(2000, motionEvents)
            assertEquals(2000, owner.devices.singleStates.size)
            assertEquals(2000, owner.devices.entityAttributes.size)
            assertEquals(1, owner.devices.groupStates.size)
            assertEquals(4, owner.devices.groupStates.getValue("artificial-group").size)
            assertTrue(owner.devices.syntheticForecasts.isEmpty())
            changingIds.forEachIndexed { index, id ->
                assertEquals("off", owner.devices.singleStates[id])
                assertEquals(9995 + index, owner.devices.entityAttributes.getValue(id).getInt("sequence"))
                assertEquals("off", original.singleStates[id])
                assertEquals(0, original.entityAttributes.getValue(id).getInt("sequence"))
            }
            assertFalse(owner.groupIsOn("artificial-group", changingIds.take(4)))
            assertNull(ReflectionHelpers.getField<Any?>(client, "stateRequest"))
            assertTrue(ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").isEmpty())
        } finally { owner.detachUi(); owner.disconnectWebSocket() }
    }

    @Test fun repeatedScreenAndConnectionReplacementClearsReadWorkAndRejectsLateCallbacks() {
        val owner = MainViewModel()
        var uiCalls = 0
        try {
            repeat(20) { cycle ->
                owner.connectWebSocket("http://[", "artificial-token-$cycle", autoReconnect = false,
                    onStateChangedCallback = { _, _, _ -> uiCalls++ },
                    onStatesSnapshotCallback = { uiCalls++ })
                val (client, socket) = install(owner)
                val gate = HaMessageGate(1)
                assertTrue(gate.acquire())
                ReflectionHelpers.setField(client, "inboundGate", gate)
                owner.subscribeEntities(emptyList())
                repeat(40) { owner.getEntities() }
                assertNotNull(ReflectionHelpers.getField<Any?>(client, "stateRequest"))
                assertEquals(32, ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").size)
                val late: (List<HaEntityState>) -> Unit = ReflectionHelpers.getField(client, "onStatesSnapshot")
                val state: (String, String, JSONObject) -> Unit = ReflectionHelpers.getField(client, "onStateChanged")
                owner.detachUi()
                state("sensor.retained", cycle.toString(), JSONObject())
                assertEquals(cycle.toString(), owner.devices.singleStates["sensor.retained"])
                assertEquals(0, uiCalls)
                owner.disconnectWebSocket()
                assertTrue(socket.cancelled)
                assertFalse(gate.acquire())
                listOf("inboundGate", "stateRequest", "registryTimeout", "webSocket").forEach {
                    assertNull(ReflectionHelpers.getField<Any?>(client, it))
                }
                assertTrue(ReflectionHelpers.getField<Set<Int>>(client, "registryRequests").isEmpty())
                late(listOf(HaEntityState("sensor.retained", "obsolete", JSONObject())))
                state("sensor.retained", "obsolete", JSONObject())
                assertEquals(cycle.toString(), owner.devices.singleStates["sensor.retained"])
                assertEquals(0, uiCalls)
            }
            // Отменённые одноразовые ожидания не публикуют ошибки после остановки владельца.
            shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
            assertNull(owner.uiState.value.statusMessage)
            assertEquals(1, owner.devices.singleStates.size)
        } finally { owner.detachUi(); owner.disconnectWebSocket() }
    }
}
