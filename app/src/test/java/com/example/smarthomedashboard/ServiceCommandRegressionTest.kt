package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/** Только записывающий WebSocket: ни одного подключения или команды реальному дому. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ServiceCommandRegressionTest {
    private val owner = MainViewModel()
    private val messages = mutableListOf<JSONObject>()
    private val restored = mutableListOf<String>()
    private var accepts = true
    private var throwsOnSend = false
    private val socket = object : WebSocket {
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (throwsOnSend) throw IllegalStateException("artificial transport error")
            if (accepts) messages.add(JSONObject(text))
            return accepts
        }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun ready(): HomeAssistantWebSocket {
        // Неверный синтаксис адреса останавливает клиент до любых сетевых обращений.
        owner.connectWebSocket("http://[", "artificial-token", autoReconnect = false,
            onCommandStateChangedCallback = { restored.add(it) })
        return requireNotNull(owner.getWebSocket()).also {
            ReflectionHelpers.setField(it, "running", true)
            ReflectionHelpers.setField(it, "authenticated", true)
            ReflectionHelpers.setField(it, "webSocket", socket)
        }
    }

    private fun result(client: HomeAssistantWebSocket, message: JSONObject, success: Boolean) {
        deliver(client, JSONObject().put("type", "result").put("id", message.getInt("id"))
            .put("success", success).put("error", JSONObject().put("code", "artificial-private-error")))
    }

    private fun deliver(client: HomeAssistantWebSocket, message: JSONObject) {
        client.javaClass.getDeclaredMethod("handleMessage", String::class.java)
            .apply { isAccessible = true }.invoke(client, message.toString())
    }

    private fun event(client: HomeAssistantWebSocket, value: String) {
        deliver(client, JSONObject().put("type", "event").put("event", JSONObject()
            .put("event_type", "state_changed").put("data", JSONObject()
                .put("entity_id", "switch.test").put("new_state", JSONObject()
                    .put("state", value).put("attributes", JSONObject())))))
    }

    private fun services() = messages.filter { it.optString("type") == "call_service" }

    @After fun cleanup() {
        owner.disconnectWebSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun acceptedSendIsOptimisticButActualEventWinsOverLateFailure() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        assertEquals("on", owner.commands.toggleSingle("switch.test"))
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        event(client, "off")
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertTrue(restored.isEmpty())
    }

    @Test fun falseSendAndTransportExceptionNeverChangeState() {
        for (throwing in listOf(false, true)) {
            ready()
            owner.onEntityStateChanged("switch.test", "off")
            accepts = false
            throwsOnSend = throwing
            repeat(2) {
                assertNull(owner.commands.toggleSingle("switch.test"))
                assertEquals("off", owner.uiState.value.singleStates["switch.test"])
            }
            assertTrue(services().isEmpty())
            assertTrue(owner.uiState.value.statusMessage.orEmpty().contains("соединени"))
            owner.disconnectWebSocket()
        }
    }

    @Test fun unauthenticatedConnectionRejectsWithoutQueuing() {
        val client = ready()
        ReflectionHelpers.setField(client, "authenticated", false)
        owner.onEntityStateChanged("switch.test", "off")
        repeat(2) { assertNull(owner.commands.toggleSingle("switch.test")) }
        ReflectionHelpers.setField(client, "authenticated", true)
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertTrue(services().isEmpty())
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun rejectedAckRestoresConfirmedStateAndSanitizesError() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertEquals(listOf("switch.test"), restored)
        assertFalse(owner.uiState.value.statusMessage.orEmpty().contains("artificial-private-error"))
    }

    @Test fun successfulAckAloneDoesNotConfirmDeviceStateAndLaterEventStillApplies() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        result(client, services().single(), true)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        event(client, "on")
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun missingAckExpiresOnceWithoutReplayAndDoesNotRetainCallbacks() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertEquals(1, services().size)
        assertEquals(listOf("switch.test"), restored)
        assertTrue(owner.uiState.value.statusMessage.orEmpty().contains("Нет ответа"))
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertEquals(1, services().size)
        assertEquals(1, restored.size)
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(client, "serviceRequests").isEmpty())
    }

    @Test fun disconnectRestoresThenReconnectNeverReplaysOldCommand() {
        ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        owner.disconnectWebSocket()
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        ready()
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, services().size)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun olderAckDoesNotUndoNewerToggleAndNewestFailureRestoresOriginalState() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        owner.commands.toggleSingle("switch.test")
        owner.commands.toggleSingle("switch.test")
        result(client, services()[0], false)
        result(client, services()[1], true)
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        result(client, services()[2], false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertEquals(1, restored.size)
    }

    @Test fun memberFailureRestoresGroupWithoutChangingActualSingleState() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.updateGroupState("group", "switch.test", "off")
        owner.commands.toggleMember("group", "switch.test")
        assertEquals("on", owner.uiState.value.groupStates["group"]?.get("switch.test"))
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.groupStates["group"]?.get("switch.test"))
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun groupsKeepOrderDuplicatesAndDoNotOptimisticallySwitchMembers() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "on")
        owner.onEntityStateChanged("light.other", "off")
        val ids = listOf("switch.test", "light.other", "switch.test")
        owner.commands.toggleGroup("group", ids)
        assertEquals(ids, services().map { it.getJSONObject("service_data").getString("entity_id") })
        assertEquals(listOf("switch", "light", "switch"), services().map { it.getString("domain") })
        assertTrue(services().all { it.getString("service") == "turn_off" })
        services().forEach { result(client, it, false) }
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        assertEquals("off", owner.uiState.value.singleStates["light.other"])
        assertEquals("on", owner.uiState.value.groupStates["group"]?.get("switch.test"))
    }

    @Test fun workerThreadCallIsRejectedRatherThanPostedAsFutureDeviceAction() {
        val client = ready()
        var accepted = true
        val worker = Thread { accepted = client.callService("switch", "turn_on", "switch.test") }
        worker.start()
        worker.join()
        assertFalse(accepted)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(services().isEmpty())
    }

    @Test fun detachedUiIsNotRetainedByPendingCommand() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        owner.detachUi()
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertTrue(restored.isEmpty())
        assertNull(ReflectionHelpers.getField<Any?>(owner, "commandStateCallback"))
    }
}
