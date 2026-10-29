package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardDeviceStateTest {
    @Test fun readsUseOneOwnerAcrossScreensAndPublishedSnapshotsStayStable() {
        val owner = MainViewModel()
        owner.onEntityStateChanged("light.test", "off", JSONObject().put("friendly_name", "Test"))
        val previous = owner.uiState.value
        val cache = owner.devices.singleStates
        owner.onEntityStateChanged("light.test", "on", JSONObject())
        assertSame(cache, owner.devices.singleStates)
        assertEquals("on", cache["light.test"])
        assertEquals("off", previous.singleStates["light.test"])
        assertEquals("Test", previous.entityAttributes["light.test"]?.getString("friendly_name"))
    }

    @Test fun membershipReconciliationDropsRemovedMembersAndSharesFacts() {
        val owner = MainViewModel()
        owner.onEntityStateChanged("light.added", "on")
        owner.updateGroupState("group", "light.removed", "on")
        owner.updateGroupState("group", "light.kept", "off")
        assertTrue(owner.groupIsOn("group", listOf("light.kept", "light.added")))
        assertFalse(owner.devices.groupStates.getValue("group").containsKey("light.removed"))
        assertEquals(owner.devices.groupStates["group"], owner.uiState.value.groupStates["group"])
        owner.updateGroupState("group", "light.added", "off")
        assertFalse(owner.groupIsOn("group", listOf("light.kept", "light.added")))
        owner.onEntityStateChanged("light.added", "on")
        assertTrue(owner.groupIsOn("group", listOf("light.added")))
    }
}
