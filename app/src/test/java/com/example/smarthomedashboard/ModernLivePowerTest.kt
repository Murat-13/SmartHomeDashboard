package com.example.smarthomedashboard

import android.app.Activity
import android.widget.AutoCompleteTextView
import android.widget.Switch
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModernLivePowerTest {
    private val power = LivePowerConfig(true, "sensor.boiler_power")
    private fun attributes(unit: String = "W", deviceClass: String = "power") =
        JSONObject().put("unit_of_measurement", unit).put("device_class", deviceClass)
    private fun text(value: String, unit: String = "W", stale: Set<String> = emptySet()) = power.text(
        mapOf(power.entityId to value), mapOf(power.entityId to attributes(unit)), stale)

    @Test fun disabledEverywhereUnlessExplicitlyEnabled() {
        for (json in listOf("{}", "{broken", """{"power_entity_id":"sensor.a"}""")) {
            val config = LivePowerConfig.read(json)
            assertFalse(config.enabled)
            assertNull(config.text(mapOf("sensor.a" to "100"), mapOf("sensor.a" to attributes()), emptySet()))
        }
    }

    @Test fun unitsZerosAndAutomaticKilowatts() {
        assertEquals("0 Вт", text("0"))
        assertEquals("648 Вт", text("648"))
        assertEquals("1,82 кВт", text("1820"))
        assertEquals("1,82 кВт", text("1.82", "kW"))
        assertEquals("648 Вт", text("0.648", "kW"))
        assertEquals("2,35 кВт", text("2350", "Вт"))
        assertEquals("2,35 кВт", text("2.35", "кВт"))
        assertEquals("-25 Вт", text("-25"))
    }

    @Test fun missingEnergyAndInvalidReadingsAreNeverFalseZeros() {
        listOf("unknown", "unavailable", "NaN", "Infinity", "", "abc", "1e20", "1e-2147483647", "1e2147483647").forEach {
            assertEquals(it, "— Вт", text(it))
        }
        assertEquals("— Вт", text("5", "kWh"))
        assertEquals("— Вт", text("5", "Wh"))
        assertEquals("— Вт", text("5", ""))
        assertEquals("— Вт", text("648", stale = setOf(power.entityId)))
        assertEquals("— Вт", power.text(emptyMap(), emptyMap(), emptySet()))
        assertEquals("— Вт", power.text(mapOf(power.entityId to "5"),
            mapOf(power.entityId to attributes("W", "energy")), emptySet()))
    }

    @Test fun enabledConfigurationRequiresSensorAndRejectsKnownEnergy() {
        listOf("", "switch.boiler", "sensor.bad id", "sensor.").forEach { assertNotNull(LivePowerConfig(true, it).error()) }
        assertNull(power.error()) // Ручная настройка разрешена без доступного HA.
        assertNull(power.error(attributes()))
        assertNotNull(power.error(attributes("kWh", "energy")))
        assertNull(LivePowerConfig(false, "").error())
    }

    @Test fun settingsRoundTripDoesNotTouchOtherFields() {
        val json = JSONObject("""{"button_style":"modern","modern_icon":"gate","future":42}""")
        power.writeTo(json)
        assertEquals(power, LivePowerConfig.read(json.toString()))
        assertEquals("gate", json.getString("modern_icon"))
        assertEquals(42, json.getInt("future"))
    }

    @Test fun childPowerFollowsIdentityAcrossReorderAndReload() {
        val group = GroupChildren(mutableListOf(ChildButtonData("switch.a", "A"),
            ChildButtonData("switch.b", "B", true, "sensor.b_power"), ChildButtonData("switch.c", "C")))
        group.move(1, 0)
        val json = JSONObject().also(group::writeTo)
        val restored = GroupChildren.fromConfig(JSONObject(json.toString()))
        assertEquals("switch.b", restored.items[0].entityId)
        assertTrue(restored.items[0].showPower)
        assertEquals("sensor.b_power", restored.items[0].powerEntityId)
        assertFalse(restored.items[1].showPower)
        restored.replace(0, "switch.b")
        assertTrue(restored.items[0].showPower)
        restored.replace(0, "switch.new")
        assertFalse(restored.items[0].showPower)
        assertEquals("", restored.items[0].powerEntityId)
    }

    @Test fun powerSensorParticipatesInAddressedUpdatesIncludingChildren() {
        val one = TileEntity("one", "button", "bottom_panel", "", 0, 0, 1, 1,
            config = """{"entity_id":"switch.a","show_power":true,"power_entity_id":"sensor.a_power"}""")
        val group = one.copy(id = "group", type = "group", config = """{"entity_ids":["switch.a"],
            "child_power":{"switch.a":{"show_power":true,"power_entity_id":"sensor.child_power"}}}""")
        val index = PanelDependencyIndex(listOf(one, group))
        assertEquals(listOf("one"), index.affected(listOf("sensor.a_power")).map { it.id })
        assertEquals(listOf("group"), index.affected(listOf("sensor.child_power")).map { it.id })
        assertTrue(index.affected(listOf("sensor.unrelated")).isEmpty())
    }

    @Test fun editorHidesSourceUntilEnabledAndValidatesWithoutSaving() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        var updates = 0
        val editor = ModernPowerEditor(host, LivePowerConfig()) { updates++ }
        val toggle = editor.getChildAt(1) as Switch
        val sensor = editor.getChildAt(2) as AutoCompleteTextView
        assertEquals(android.view.View.GONE, sensor.visibility)
        toggle.isChecked = true
        assertFalse(editor.validate())
        sensor.setText("sensor.boiler_power")
        assertTrue(editor.validate())
        editor.acceptState(MainUiState(singleStates = mapOf(power.entityId to "648"),
            entityAttributes = mapOf(power.entityId to attributes())))
        assertEquals("648 Вт", editor.powerText())
        assertTrue(updates >= 2)
        toggle.isChecked = false
        assertNull(editor.powerText())
    }

    @Test fun disconnectAndFreshSnapshotRestorePowerThroughExistingState() {
        val devices = DashboardDeviceState()
        devices.entityChanged(power.entityId, "1820", attributes())
        assertEquals("1,82 кВт", power.text(devices.singleStates, devices.entityAttributes, devices.staleEntities))
        devices.markDisconnected()
        assertEquals("— Вт", power.text(devices.singleStates, devices.entityAttributes, devices.staleEntities))
        devices.entityChanged(power.entityId, "0", attributes())
        assertEquals("0 Вт", power.text(devices.singleStates, devices.entityAttributes, devices.staleEntities))
    }
}
