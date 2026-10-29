package com.example.smarthomedashboard

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class DashboardDeviceCommandsTest {
    private val state = DashboardDeviceState()
    private val sent = mutableListOf<Triple<String, String, String>>()
    private val callbacks = mutableListOf<(ServiceCommandResult) -> Unit>()
    private var changes = 0
    private val commands = DashboardDeviceCommands(state, { domain, service, id, callback ->
        sent.add(Triple(domain, service, id))
        callbacks.add(callback)
        true
    }, { changes++ })

    @After fun cleanup() { commands.disconnected() }

    private fun fact(id: String, value: String) {
        state.entityChanged(id, value, null)
        commands.entityChanged(id)
    }

    @Test fun singleTogglePreservesArbitraryDomainAndKeepsConfirmedState() {
        fact("custom.test", "on")
        assertEquals("off", commands.toggleSingle("custom.test"))
        assertEquals(listOf(Triple("custom", "turn_off", "custom.test")), sent)
        assertEquals("on", state.singleStates["custom.test"])
        val pending = commands.pendingFor("custom.test")!!
        assertEquals("off", pending.expectedState)
        assertTrue(pending.sent)
        assertFalse(pending.acknowledged)
        assertTrue(pending.deadlineAtMs > pending.startedAtMs)
        assertEquals(2, changes) // Создание ожидания и подтверждённая локальная отправка.
    }

    @Test fun groupKeepsOrderDuplicatesAndWaitsForActualEntityEvents() {
        fact("light.last", "on")
        val ids = listOf("custom.first", "light.last", "custom.first")
        commands.toggleGroup("group", ids)
        assertEquals(ids, sent.map { it.third })
        assertEquals(listOf("custom", "light", "custom"), sent.map { it.first })
        assertTrue(sent.all { it.second == "turn_off" })
        assertEquals("on", state.singleStates["light.last"])
        assertEquals(2, commands.pendingCommands.size)
        callbacks.forEach { it(ServiceCommandResult.ACKNOWLEDGED) }
        assertEquals(2, commands.pendingCommands.size)
        fact("light.last", "off")
        assertNotNull(commands.pendingFor("custom.first"))
        fact("custom.first", "off")
        assertTrue(commands.pendingCommands.isEmpty())
        assertFalse(state.groupIsOn("group", ids))
    }

    @Test fun childAndSingleShareOnePendingWithoutOverwritingFacts() {
        fact("light.test", "off")
        assertEquals("on", commands.toggleMember("group", "light.test"))
        assertEquals("off", state.groupStates["group"]?.get("light.test"))
        assertEquals("off", state.singleStates["light.test"])
        assertNull(commands.toggleSingle("light.test"))
        fact("light.test", "off")
        assertNotNull(commands.pendingFor("light.test"))
        fact("light.test", "on")
        assertNull(commands.pendingFor("light.test"))
        assertEquals("on", state.groupStates["group"]?.get("light.test"))
    }

    @Test fun sendingHasNoReplayQueue() {
        fact("switch.test", "off")
        commands.toggleSingle("switch.test")
        val size = sent.size
        state.groupIsOn("group", listOf("switch.test"))
        DashboardDeviceCommands(state, { _, _, _, _ -> error("stale command replayed") }, {})
        assertEquals(size, sent.size)
    }

    @Test fun ackDoesNotEndPendingAndOppositeFactDoesNotUndoIt() {
        fact("switch.test", "off")
        commands.toggleSingle("switch.test")
        callbacks.single()(ServiceCommandResult.ACKNOWLEDGED)
        fact("switch.test", "off")
        assertTrue(commands.pendingFor("switch.test")!!.acknowledged)
        fact("switch.test", "on")
        assertNull(commands.pendingFor("switch.test"))
        callbacks.single()(ServiceCommandResult.REJECTED)
        assertEquals("on", state.singleStates["switch.test"])
    }

    @Test fun unavailableCancelsPendingAndLaterFactRestoresAvailability() {
        fact("switch.test", "on")
        commands.toggleSingle("switch.test")
        fact("switch.test", "unavailable")
        assertTrue(commands.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState("switch.test"))
        assertEquals("on", commands.toggleSingle("switch.test")) // Явное turn_on без ложного OFF в данных.
        callbacks.last()(ServiceCommandResult.REJECTED)
        fact("switch.test", "off")
        assertEquals("on", commands.toggleSingle("switch.test"))
    }

    @Test fun lateCallbackOfFailedOperationCannotFinishNewOperation() {
        fact("switch.test", "off")
        commands.toggleSingle("switch.test")
        callbacks[0](ServiceCommandResult.REJECTED)
        commands.toggleSingle("switch.test")
        val current = commands.pendingFor("switch.test")
        callbacks[0](ServiceCommandResult.ACKNOWLEDGED)
        callbacks[0](ServiceCommandResult.DISCONNECTED)
        assertEquals(current, commands.pendingFor("switch.test"))
        assertEquals("off", state.singleStates["switch.test"])
    }

    @Test fun partialGroupFailureKeepsOtherMembersPendingAndAnyOnSemantics() {
        fact("switch.first", "off")
        fact("switch.second", "off")
        commands.toggleGroup("group", listOf("switch.first", "switch.second"))
        callbacks[1](ServiceCommandResult.REJECTED)
        assertNull(commands.pendingFor("switch.second"))
        assertNotNull(commands.pendingFor("switch.first"))
        commands.toggleGroup("group", listOf("switch.first", "switch.second"))
        assertEquals(2, sent.size)
        fact("switch.first", "on")
        assertTrue(commands.pendingCommands.isEmpty())
        assertTrue(state.groupIsOn("group", listOf("switch.first", "switch.second")))
        assertEquals("off", state.singleStates["switch.second"])
    }

    @Test fun deadlineAndDisconnectDoNotAccumulateCallbacksOrReplayCommands() {
        fact("switch.test", "off")
        commands.toggleSingle("switch.test")
        callbacks[0](ServiceCommandResult.ACKNOWLEDGED)
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertTrue(commands.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState("switch.test"))
        fact("switch.test", "on")
        commands.toggleSingle("switch.test")
        commands.disconnected()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.DAYS)
        assertTrue(commands.pendingCommands.isEmpty())
        assertEquals(2, sent.size)
    }

    @Test fun repeatedTapDoesNotBlockOtherEntitiesAndPublishedSnapshotIsImmutable() {
        fact("switch.first", "off")
        fact("switch.second", "on")
        commands.toggleSingle("switch.first")
        val snapshot = commands.pendingCommands
        repeat(100) { assertNull(commands.toggleSingle("switch.first")) }
        assertEquals("off", commands.toggleSingle("switch.second"))
        assertEquals(2, sent.size)
        assertEquals(1, snapshot.size)
        assertEquals(2, commands.pendingCommands.size)
    }
    @Test fun alreadyOffMemberOfTurningOffGroupDoesNotWaitForAnImpossibleChangeEvent() {
        fact("switch.first", "on")
        fact("switch.second", "off")
        commands.toggleGroup("group", listOf("switch.first", "switch.second"))
        callbacks[1](ServiceCommandResult.ACKNOWLEDGED)
        assertNull(commands.pendingFor("switch.second"))
        assertNotNull(commands.pendingFor("switch.first"))
        callbacks[0](ServiceCommandResult.ACKNOWLEDGED)
        assertNotNull(commands.pendingFor("switch.first"))
        fact("switch.first", "off")
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(ConfirmedState.OFF, state.confirmedState("switch.first"))
        assertEquals(ConfirmedState.OFF, state.confirmedState("switch.second"))
        assertFalse(state.groupIsOn("group", listOf("switch.first", "switch.second")))
    }

    @Test fun expiredElapsedDeadlineIsAppliedOnResumeBeforeDelayedHandlerRuns() {
        fact("switch.test", "off")
        commands.toggleSingle("switch.test")
        val pending = org.robolectric.util.ReflectionHelpers.getField<MutableMap<String, PendingCommand>>(commands, "pending")
        pending["switch.test"] = pending.getValue("switch.test").copy(deadlineAtMs = android.os.SystemClock.elapsedRealtime() - 1)
        commands.expireOverdue()
        assertTrue(commands.pendingCommands.isEmpty())
        assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState("switch.test"))
        assertEquals(1, sent.size)
    }

    @Test fun retryAfterUnconfirmedTurnOffDoesNotUnexpectedlyTurnDeviceOn() {
        fact("switch.test", "on")
        assertEquals("off", commands.toggleSingle("switch.test"))
        callbacks[0](ServiceCommandResult.ACKNOWLEDGED)
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState("switch.test"))
        assertEquals("off", commands.toggleSingle("switch.test"))
        assertEquals(listOf("turn_off", "turn_off"), sent.map { it.second })
        fact("switch.test", "off")
        assertTrue(commands.pendingCommands.isEmpty())
    }

}
