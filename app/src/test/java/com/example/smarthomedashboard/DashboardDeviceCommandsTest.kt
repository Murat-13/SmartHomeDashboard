package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class DashboardDeviceCommandsTest {
    private val state = DashboardDeviceState()
    private val sent = mutableListOf<Triple<String, String, String>>()
    private var changes = 0
    private val commands = DashboardDeviceCommands(state, { domain, service, id ->
        sent.add(Triple(domain, service, id))
    }, { changes++ })

    @Test fun singleTogglePreservesArbitraryDomainAndOptimisticState() {
        state.singleStates["custom.test"] = "on"
        assertEquals("off", commands.toggleSingle("custom.test"))
        assertEquals(listOf(Triple("custom", "turn_off", "custom.test")), sent)
        assertEquals("off", state.singleStates["custom.test"])
        assertEquals(1, changes)
    }

    @Test fun groupKeepsOrderDuplicatesAndWaitsForActualEntityEvents() {
        state.singleStates["light.last"] = "on"
        val ids = listOf("custom.first", "light.last", "custom.first")
        commands.toggleGroup("group", ids)
        assertEquals(ids, sent.map { it.third })
        assertEquals(listOf("custom", "light", "custom"), sent.map { it.first })
        assertTrue(sent.all { it.second == "turn_off" })
        assertEquals("on", state.singleStates["light.last"])
        assertEquals(1, changes) // Опубликован актуальный состав, реальные состояния не переключены.
    }

    @Test fun childOptimismDoesNotOverwriteRealSingleEntityState() {
        state.singleStates["light.test"] = "off"
        assertEquals("on", commands.toggleMember("group", "light.test"))
        assertEquals("on", state.groupStates["group"]?.get("light.test"))
        assertEquals("off", state.singleStates["light.test"])
        state.entityChanged("light.test", "off", null)
        assertEquals("off", state.groupStates["group"]?.get("light.test"))
    }

    @Test fun sendingHasNoReplayQueue() {
        commands.toggleSingle("switch.test")
        val size = sent.size
        // Повторное чтение состояния и создание другого контроллера не отправляют прошлое действие.
        state.groupIsOn("group", listOf("switch.test"))
        DashboardDeviceCommands(state, { _, _, _ -> fail("stale command replayed") }, {})
        assertEquals(size, sent.size)
    }
}
