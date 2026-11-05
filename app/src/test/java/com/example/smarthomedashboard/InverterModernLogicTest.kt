package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Модель потоков, режимы, цвет, конфигурация и подтверждение цвета плитки «Инвертор». Без Views и сети.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InverterModernLogicTest {
    private fun config(vararg roles: String, base: InverterModernConfig = InverterModernConfig()): InverterModernConfig =
        roles.fold(base) { c, role -> c.withRow(c.row(role)!!.copy(entityId = "sensor.$role")) }

    private fun readings(values: Map<String, String>, units: Map<String, String> = emptyMap(),
                         session: HaSession = HaSession(true, true), options: List<String>? = null) =
        BatteryReadings(session, { id -> values[id.removePrefix("sensor.")] }, { false }, { id ->
            val role = id.removePrefix("sensor.")
            JSONObject().apply {
                (units[role] ?: if (role.endsWith("power")) "W" else null)?.let { put("unit_of_measurement", it) }
                if (role == InverterModernConfig.ROLE_PRIORITY && options != null) put("options", JSONArray(options))
            }
        })

    private val flows = arrayOf(InverterModernConfig.ROLE_PV_POWER, InverterModernConfig.ROLE_GRID_POWER,
        InverterModernConfig.ROLE_BATTERY_POWER, InverterModernConfig.ROLE_LOAD_POWER, InverterModernConfig.ROLE_BATTERY_SOC)

    private fun snap(values: Map<String, String>, c: InverterModernConfig = config(*flows), units: Map<String, String> = emptyMap()) =
        InverterModel.snapshot(c, readings(values, units))

    @Test fun solarAndBatteryDischargeIsAutonomousGreen() {
        val s = snap(mapOf("pv_power" to "3200", "grid_power" to "0", "battery_power" to "-650", "load_power" to "3850", "battery_soc" to "76"))
        assertEquals(InverterModel.Look.AUTONOMOUS, s.look)
        assertEquals(PowerModernConfig.EMERALD, s.look.color)
        assertEquals("От солнца и АКБ", s.status)
        assertTrue(s.solarActive && s.batteryActive && s.loadActive)
        assertFalse("Ноль — потока нет", s.gridActive)
        assertEquals(76.0, s.soc!!, 0.0)
    }

    @Test fun gridSupplyIsSkyBlueAndBypassModeToo() {
        val s = snap(mapOf("pv_power" to "0", "grid_power" to "2400", "battery_power" to "0", "load_power" to "2400"))
        assertEquals(InverterModel.Look.GRID, s.look)
        assertEquals("От сети", s.status)
        val bypass = InverterModel.snapshot(config(*flows, InverterModernConfig.ROLE_MODE),
            readings(mapOf("grid_power" to "5", "load_power" to "900", "mode" to "Bypass")))
        assertEquals(InverterModel.Look.GRID, bypass.look)
        assertEquals("Байпас: от сети", bypass.status)
    }

    @Test fun pvSumFromTwoInputsAndKilowattUnits() {
        val c = config(InverterModernConfig.ROLE_PV1_POWER, InverterModernConfig.ROLE_PV2_POWER, InverterModernConfig.ROLE_LOAD_POWER)
        val s = InverterModel.snapshot(c, readings(mapOf("pv1_power" to "1.5", "pv2_power" to "2.25", "load_power" to "800"),
            units = mapOf("pv1_power" to "kW", "pv2_power" to "kW")))
        assertEquals(3750.0, s.solar!!, 0.001)
        // Один вход недоступен — второй всё равно учитывается, а не обнуляет солнце.
        val one = InverterModel.snapshot(c, readings(mapOf("pv1_power" to "1200", "pv2_power" to "unavailable", "load_power" to "800")))
        assertEquals(1200.0, one.solar!!, 0.001)
    }

    @Test fun batteryPowerFromVoltageAndCurrentAndSignInversion() {
        val c = config(InverterModernConfig.ROLE_BATTERY_VOLTAGE, InverterModernConfig.ROLE_BATTERY_CURRENT, InverterModernConfig.ROLE_LOAD_POWER)
        val s = InverterModel.snapshot(c, readings(mapOf("battery_voltage" to "52.0", "battery_current" to "-20", "load_power" to "1040")))
        assertEquals(-1040.0, s.battery!!, 0.001)
        assertEquals("От АКБ", s.status)
        val inverted = InverterModel.snapshot(c.copy(invertBatterySign = true),
            readings(mapOf("battery_voltage" to "52.0", "battery_current" to "-20", "load_power" to "1040")))
        assertEquals("Инверсия: тот же датчик — заряд", 1040.0, inverted.battery!!, 0.001)
        val grid = InverterModel.snapshot(config(InverterModernConfig.ROLE_GRID_POWER, InverterModernConfig.ROLE_LOAD_POWER)
            .copy(invertGridSign = true), readings(mapOf("grid_power" to "-800", "load_power" to "800")))
        assertEquals(800.0, grid.grid!!, 0.001)
        assertEquals(InverterModel.Look.GRID, grid.look)
    }

    @Test fun overloadByRatedPowerOrPercentSensorIsAmber() {
        val s = snap(mapOf("pv_power" to "4000", "battery_power" to "-5300", "load_power" to "9300"))
        assertEquals(InverterModel.Look.WARNING, s.look)
        assertEquals("Перегруз 85%", s.status)
        val custom = snap(mapOf("load_power" to "9300", "pv_power" to "9300"), config(*flows).copy(overloadPercent = 90.0))
        assertEquals(InverterModel.Look.AUTONOMOUS, custom.look)
        val percent = InverterModel.snapshot(config(*flows, InverterModernConfig.ROLE_LOAD_PERCENT),
            readings(mapOf("load_power" to "100", "pv_power" to "100", "load_percent" to "82")))
        assertEquals("Датчик % нагрузки важнее расчёта", InverterModel.Look.WARNING, percent.look)
    }

    @Test fun faultIsRedPulsingAndWinsOverEverything() {
        val c = config(*flows, InverterModernConfig.ROLE_FAULT, InverterModernConfig.ROLE_WARNING)
        val s = InverterModel.snapshot(c, readings(mapOf("load_power" to "9900", "fault" to "Перегрев", "warning" to "on")))
        assertEquals(InverterModel.Look.FAULT, s.look)
        assertTrue(s.look.pulse)
        assertEquals("Авария: Перегрев", s.status)
        val code = InverterModel.snapshot(c, readings(mapOf("load_power" to "100", "fault" to "7")))
        assertEquals("Авария: код 7", code.status)
        val clear = InverterModel.snapshot(c, readings(mapOf("load_power" to "100", "pv_power" to "100", "fault" to "0", "warning" to "off")))
        assertEquals(InverterModel.Look.AUTONOMOUS, clear.look)
        val warn = InverterModel.snapshot(c, readings(mapOf("load_power" to "100", "pv_power" to "100", "warning" to "on")))
        assertEquals(InverterModel.Look.WARNING, warn.look)
        assertEquals("«on» сам по себе ничего не объясняет", "Предупреждение", warn.status)
        val mode = InverterModel.snapshot(config(*flows, InverterModernConfig.ROLE_MODE), readings(mapOf("mode" to "6")))
        assertEquals("Код SRNE 6 — авария", InverterModel.Look.FAULT, mode.look)
    }

    @Test fun noDataAndDisconnectedAreNeutralWithDashesNotZeros() {
        val empty = InverterModel.snapshot(InverterModernConfig(), readings(emptyMap()))
        assertEquals(InverterModel.Look.NO_DATA, empty.look)
        assertEquals("Нет данных", empty.status)
        assertNull(empty.solar)
        assertEquals("—", InverterModel.power(empty.solar))
        val lost = InverterModel.snapshot(config(*flows), readings(mapOf("load_power" to "500"), session = HaSession()))
        assertEquals(InverterModel.Look.NO_DATA, lost.look)
        assertEquals("Нет связи с Home Assistant", lost.status)
        assertNull("Показание старой сессии не показывается", lost.load)
        val unavailable = snap(mapOf("load_power" to "unavailable", "pv_power" to "unknown"))
        assertEquals(InverterModel.Look.NO_DATA, unavailable.look)
    }

    @Test fun nightNegativePvIsNotAFlow() {
        val s = snap(mapOf("pv_power" to "-25", "battery_power" to "-400", "load_power" to "380"))
        assertFalse(s.solarActive)
        assertEquals("От АКБ", s.status)
    }

    @Test fun smallFlowsAreIdleAndLoadZeroSaysNoLoad() {
        val s = snap(mapOf("pv_power" to "12", "grid_power" to "-10", "battery_power" to "0", "load_power" to "0"))
        assertFalse(s.solarActive || s.gridActive || s.batteryActive || s.loadActive)
        assertEquals("Нагрузки нет", s.status)
    }

    @Test fun modeParsingCodesAndWords() {
        assertEquals(InverterModel.Mode.GRID, InverterModel.parseMode("2"))
        assertEquals(InverterModel.Mode.OFF_GRID, InverterModel.parseMode("3.0"))
        assertEquals(InverterModel.Mode.BYPASS, InverterModel.parseMode("4"))
        assertEquals(InverterModel.Mode.CHARGING, InverterModel.parseMode("5"))
        assertEquals(InverterModel.Mode.STANDBY, InverterModel.parseMode("1"))
        assertEquals(InverterModel.Mode.UNKNOWN, InverterModel.parseMode("0"))
        assertEquals(InverterModel.Mode.GRID, InverterModel.parseMode("Line Mode"))
        assertEquals(InverterModel.Mode.OFF_GRID, InverterModel.parseMode("Battery Mode"))
        assertEquals(InverterModel.Mode.FAULT, InverterModel.parseMode("Fault"))
        assertEquals(InverterModel.Mode.STANDBY, InverterModel.parseMode("Standby"))
        assertEquals(InverterModel.Mode.UNKNOWN, InverterModel.parseMode("offline"))
        assertEquals(InverterModel.Mode.UNKNOWN, InverterModel.parseMode(null))
    }

    @Test fun powerAndPriorityLabels() {
        assertEquals("640 Вт", InverterModel.power(640.4))
        assertEquals("1,25 кВт", InverterModel.power(1250.0))
        assertEquals("11,0 кВт", InverterModel.power(11000.0))
        assertEquals("+4,20 кВт", InverterModel.power(4200.0, signed = true))
        assertEquals("−650 Вт", InverterModel.power(-650.0, signed = true))
        assertEquals("Около нуля — без знака", "5 Вт", InverterModel.power(5.0, signed = true))
        assertEquals("Солнце → АКБ → Сеть", InverterModel.priorityLabel("SBU"))
        assertEquals("Сеть первой", InverterModel.priorityLabel("uti"))
        assertEquals("Неизвестное — как есть", "Hybrid", InverterModel.priorityLabel("Hybrid"))
    }

    @Test fun configRoundTripKeepsTemplateOrderAndOtherKeys() {
        val c = config(InverterModernConfig.ROLE_LOAD_POWER).copy(ratedPowerW = 8000.0, overloadPercent = 75.0, invertBatterySign = true)
        val written = c.writeTo("""{"button_size":400,"widget_height":400,"foreign":1}""")
        val json = JSONObject(written)
        assertEquals(400, json.getInt("button_size"))
        assertEquals(1, json.getInt("foreign"))
        assertEquals(c, InverterModernConfig.from(written))
        // Неизвестная роль и пропавшая строка не ломают плитку: порядок и состав — по шаблону.
        val block = json.getJSONObject(InverterModernConfig.KEY)
        block.getJSONArray("rows").put(JSONObject().put("role", "future_role").put("entity_id", "sensor.x"))
        block.getJSONArray("rows").remove(0)
        val parsed = InverterModernConfig.fromJson(block)
        assertEquals(InverterModernConfig.templateRows().map { it.role }, parsed.rows.map { it.role })
        assertEquals("sensor.load_power", parsed.row(InverterModernConfig.ROLE_LOAD_POWER)!!.entityId)
        // Повреждённые числа — умолчания.
        val bad = InverterModernConfig.fromJson(JSONObject().put("rated_power_w", -5).put("overload_percent", "x"))
        assertEquals(InverterModernConfig.DEFAULT_RATED_W, bad.ratedPowerW, 0.0)
        assertEquals(InverterModernConfig.DEFAULT_OVERLOAD_PERCENT, bad.overloadPercent, 0.0)
        assertEquals(InverterModernConfig(), InverterModernConfig.from("not json"))
    }

    @Test fun newTileIsSquareAndEntityIdsForSubscriptions() {
        val json = JSONObject(InverterModernConfig.newTileConfig())
        assertEquals(InverterModernConfig.DEFAULT_SIZE, json.getInt("button_size"))
        assertEquals(InverterModernConfig.DEFAULT_SIZE, json.getInt("widget_height"))
        val kept = JSONObject(InverterModernConfig.newTileConfig("""{"button_size":500,"widget_height":500}"""))
        assertEquals(500, kept.getInt("button_size"))
        val tile = TileEntity("inv", InverterModernConfig.TYPE, "grid", "Инвертор", 0, 0, 1, 1,
            config = config(InverterModernConfig.ROLE_LOAD_POWER, InverterModernConfig.ROLE_PRIORITY).writeTo("{}"))
        assertEquals(setOf("sensor.load_power", "sensor.priority"), InverterModernConfig.entityIds(listOf(tile)))
        assertTrue("Панель подписывается на строки плитки",
            PanelEntityIds.collect(listOf(tile), emptyList()).containsAll(setOf("sensor.load_power", "sensor.priority")))
    }

    @Test fun lookChangesAreConfirmedWithoutFlicker() {
        val t = InverterLookTracker(confirmMs = 1_800)
        assertEquals("Первое — сразу", InverterModel.Look.AUTONOMOUS, t.update(InverterModel.Look.AUTONOMOUS, 0))
        assertEquals(InverterModel.Look.AUTONOMOUS, t.update(InverterModel.Look.GRID, 100))
        assertEquals(1_900L, t.nextCheckAt)
        assertEquals("Вернулись до подтверждения — мерцания нет", InverterModel.Look.AUTONOMOUS, t.update(InverterModel.Look.AUTONOMOUS, 900))
        assertNull(t.nextCheckAt)
        t.update(InverterModel.Look.GRID, 1_000)
        assertEquals(InverterModel.Look.AUTONOMOUS, t.update(InverterModel.Look.GRID, 2_700))
        assertEquals(InverterModel.Look.GRID, t.update(InverterModel.Look.GRID, 2_800))
        assertEquals("Нет данных — сразу", InverterModel.Look.NO_DATA, t.update(InverterModel.Look.NO_DATA, 2_801))
        assertEquals("После «нет данных» — сразу", InverterModel.Look.FAULT, t.update(InverterModel.Look.FAULT, 2_802))
    }
}
