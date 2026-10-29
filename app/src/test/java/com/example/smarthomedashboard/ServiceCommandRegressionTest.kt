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
        client.javaClass.getDeclaredMethod("handleMessage", HaInboundMessage::class.java)
            .apply { isAccessible = true }.invoke(client, requireNotNull(HaInboundMessage.parse(message.toString())))
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

    @Test fun acceptedSendKeepsFactsAndActualEventWinsOverLateFailure() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        assertEquals("on", owner.commands.toggleSingle("switch.test"))
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
        event(client, "on")
        val confirmed = owner.uiState.value
        result(client, services().single(), false)
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
        assertEquals(confirmed.statusMessage, owner.uiState.value.statusMessage)
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
            assertTrue(owner.uiState.value.statusMessage.orEmpty().let { it.contains("соединени") || it.contains("актуального состояния") })
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
        restored.clear()
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
        assertTrue(owner.uiState.value.pendingCommands.getValue("switch.test").acknowledged)
        event(client, "on")
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun missingAckExpiresOnceWithoutReplayAndDoesNotRetainCallbacks() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        restored.clear()
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

    @Test fun repeatedTapIsBlockedAndOlderAckCannotAffectNewOperation() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        repeat(3) { assertNull(owner.commands.toggleSingle("switch.test")) }
        assertEquals(1, services().size)
        event(client, "on")
        owner.commands.toggleSingle("switch.test")
        val operation = owner.uiState.value.pendingCommands.getValue("switch.test")
        result(client, services()[0], false)
        result(client, services()[0], true)
        assertEquals(operation, owner.uiState.value.pendingCommands["switch.test"])
        result(client, services()[1], false)
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
    }

    @Test fun memberFailureRestoresGroupWithoutChangingActualSingleState() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.updateGroupState("group", "switch.test", "off")
        owner.commands.toggleMember("group", "switch.test")
        assertEquals("off", owner.uiState.value.groupStates["group"]?.get("switch.test"))
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
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
        restored.clear()
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertTrue(restored.isEmpty())
        assertNull(ReflectionHelpers.getField<Any?>(owner, "commandStateCallback"))
    }
    @Test fun acknowledgedCommandWithoutPhysicalChangeExpiresAndLateAckIsHarmless() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        val request = services().single()
        result(client, request, true)
        shadowOf(Looper.getMainLooper()).idleFor(19, TimeUnit.SECONDS)
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.UNAVAILABLE, owner.devices.confirmedState("switch.test"))
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        val message = owner.uiState.value.statusMessage
        result(client, request, false)
        assertEquals(message, owner.uiState.value.statusMessage)
        event(client, "on")
        assertEquals(ConfirmedState.ON, owner.devices.confirmedState("switch.test"))
        assertEquals(1, services().size)
    }

    @Test fun matchingEventBeforeAckReleasesTransportCallbackAndBothTimers() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        event(client, "on")
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(client, "serviceRequests").isEmpty())
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(owner.commands, "deadlines").isEmpty())
        val status = owner.uiState.value.statusMessage
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertEquals(status, owner.uiState.value.statusMessage)
        assertEquals(ConfirmedState.ON, owner.devices.confirmedState("switch.test"))
    }

    @Test fun newerOppositeFactIsNotOverwrittenOnRejection() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        event(client, "off")
        assertNotNull(owner.uiState.value.pendingCommands["switch.test"])
        result(client, services().single(), false)
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
    }

    @Test fun endpointChangeAndOwnerClearCancelPendingWithoutReplayOrRetainingTimers() {
        ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        owner.connectWebSocket("http://[", "artificial-new-token", autoReconnect = false)
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.UNAVAILABLE, owner.devices.confirmedState("switch.test"))
        owner.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(owner)
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(owner.commands, "deadlines").isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertEquals(1, services().size)
    }

    @Test fun retiredServiceIdsDoNotHideInitialSnapshotOnNextConnection() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        event(client, "on")
        owner.commands.toggleSingle("switch.test")
        event(client, "off")
        assertEquals(listOf(1, 2), services().map { it.getInt("id") })
        client.disconnect()
        assertSame(client, ready()) // Новый сеанс того же транспорта, ID начинаются с 1.
        client.subscribeEntities(listOf("switch.test"))
        val request = messages.last { it.optString("type") == "get_states" }
        deliver(client, JSONObject().put("type", "result").put("id", request.getInt("id"))
            .put("success", true).put("result", org.json.JSONArray().put(JSONObject()
                .put("entity_id", "switch.test").put("state", "on").put("attributes", JSONObject()))))
        assertEquals("on", owner.uiState.value.singleStates["switch.test"])
        assertEquals(ConfirmedState.ON, owner.devices.confirmedState("switch.test"))
    }

    @Test fun timeoutPreservesARealOppositeFactReceivedDuringOperation() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        owner.commands.toggleSingle("switch.test")
        result(client, services().single(), true)
        event(client, "off") // HA действительно сообщил, что устройство осталось выключенным.
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.OFF, owner.devices.confirmedState("switch.test"))
        assertEquals("off", owner.uiState.value.singleStates["switch.test"])
    }

    @Test fun repeatedOperationsKeepOnlyBoundedIdsAndReleaseAllCallbacksAndDeadlines() {
        val client = ready()
        owner.onEntityStateChanged("switch.test", "off")
        repeat(200) {
            owner.commands.toggleSingle("switch.test")
            event(client, "on")
            owner.commands.toggleSingle("switch.test")
            event(client, "off")
        }
        assertEquals(400, services().size)
        assertTrue(owner.uiState.value.pendingCommands.isEmpty())
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(client, "serviceRequests").isEmpty())
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(owner.commands, "deadlines").isEmpty())
        assertEquals(128, ReflectionHelpers.getField<Set<*>>(client, "retiredServiceIds").size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertEquals(400, services().size)
        assertEquals(ConfirmedState.OFF, owner.devices.confirmedState("switch.test"))
    }

}
