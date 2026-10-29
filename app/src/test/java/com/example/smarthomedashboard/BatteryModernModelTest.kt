package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

// Модель, достоверность, направление мощности и черновик редактора. Без сети и Views.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BatteryModernModelTest {
    private val ru = Locale.forLanguageTag("ru-RU")

    private fun tile(config: String = BatteryModernConfig.newTileConfig("""{"custom":"keep"}""")) =
        TileEntity("m", BatteryModernConfig.TYPE, "grid", "АКБ", 10, 20, 1, 1, config = config)

    private fun configured(): BatteryModernConfig {
        var config = BatteryModernConfig()
        config = config.withRow(config.row("soc")!!.copy(entityId = "sensor.bms_soc"))
        config = config.withRow(config.row("power")!!.copy(entityId = "sensor.bms_power"))
        return config
    }

    private fun readings(session: HaSession, states: Map<String, String>, stale: Set<String> = emptySet(),
                         attrs: Map<String, JSONObject> = emptyMap()) =
        BatteryReadings(session, states::get, { it in stale }, attrs::get)

    private val live = HaSession(connected = true, synced = true)

    @Test fun templateMatchesHomeAssistantCardWithoutInventedEntities() {
        val config = BatteryModernConfig()
        assertEquals(listOf("Батарея", "Зарядка", "Мощность", "Мощность Inverted", "Накопленная энергия", "Напряжение",
            "Температура", "Ток", "Battery health", "Cycles", "Runtime"),
            config.rows.filter { it.section == BatteryModernConfig.Section.SENSORS }.map { it.label })
        assertEquals(listOf("Проблема", "Уровень сигнала", "Balancer", "Charge MOSFET", "Delta cell voltage",
            "Design capacity", "Discharge MOSFET", "Highest cell voltage", "Lowest cell voltage"),
            config.rows.filter { it.section == BatteryModernConfig.Section.DIAGNOSTICS }.map { it.label })
        assertTrue("Шаблон не привязан к сущностям", config.rows.all { it.entityId.isEmpty() })
    }

    @Test fun configRoundTripKeepsForeignKeysAndCorruptBlockFallsBackToTemplate() {
        val config = configured().copy(subtitle = "Литий • 48 В", invertPowerSign = true, idleThreshold = 35.5, hideUnconfigured = true)
            .let { it.withRow(it.row("cycles")!!.copy(decimals = 0, unit = "шт", hidden = true, icon = "custom")) }
        val written = config.writeTo("""{"button_size":500,"widget_height":360,"custom":"keep"}""")
        val json = JSONObject(written)
        assertEquals(500, json.getInt("button_size"))
        assertEquals("keep", json.getString("custom"))
        assertEquals(config, BatteryModernConfig.from(written))
        assertEquals(BatteryModernConfig(), BatteryModernConfig.from("""{"modern_battery":"broken"}"""))
        assertEquals(BatteryModernConfig(), BatteryModernConfig.from("not json"))
        // Новая плитка: шаблон и размер компактного вида, чужие ключи сохраняются.
        val fresh = JSONObject(BatteryModernConfig.newTileConfig("""{"button_size":700,"x":"keep"}"""))
        assertEquals(700, fresh.getInt("button_size"))
        assertEquals(BatteryModernConfig.DEFAULT_HEIGHT, fresh.getInt("widget_height"))
        assertEquals("keep", fresh.getString("x"))
    }

    @Test fun rowsMoveOnlyInsideTheirSectionAndUnconfiguredCanBeHidden() {
        val config = BatteryModernConfig()
        val moved = config.move("charging", -1)
        assertEquals(listOf("charging", "soc"), moved.rows.take(2).map { it.id })
        assertEquals("Верхнюю строку раздела не переносим в другой раздел", config, config.move("problem", -1))
        assertEquals(config, config.move("runtime", 1))
        val one = configured().copy(hideUnconfigured = true)
        assertEquals(listOf("soc", "power"), one.visibleRows(BatteryModernConfig.Section.SENSORS).map { it.id })
        assertTrue(one.visibleRows(BatteryModernConfig.Section.DIAGNOSTICS).isEmpty())
        assertEquals(11, configured().visibleRows(BatteryModernConfig.Section.SENSORS).size)
    }

    @Test fun readingsSeparateZeroUnknownMissingDisconnectedAndSyncing() {
        val states = mapOf("sensor.zero" to "0", "sensor.unknown" to "unknown", "sensor.na" to "unavailable", "sensor.old" to "51.2")
        val r = readings(live, states, stale = setOf("sensor.old"))
        val zero = r.of("sensor.zero")
        assertTrue(zero is BatteryReading.Value)
        assertEquals(0.0, (zero as BatteryReading.Value).number!!, 0.0)
        assertEquals(BatteryReading.Unavailable("unknown"), r.of("sensor.unknown"))
        assertEquals(BatteryReading.Unavailable("unavailable"), r.of("sensor.na"))
        assertEquals(BatteryReading.Missing, r.of("sensor.absent"))
        assertEquals(BatteryReading.Unconfirmed, r.of("sensor.old"))
        assertEquals(BatteryReading.NotConfigured, r.of(""))
        assertNull(r.status)
        val offline = readings(HaSession(), states)
        assertEquals(BatteryReading.Disconnected, offline.of("sensor.zero"))
        assertEquals("Нет связи с Home Assistant", offline.status)
        val syncing = readings(HaSession(connected = true), states)
        assertEquals(BatteryReading.Syncing, syncing.of("sensor.zero"))
        assertEquals("Синхронизация с Home Assistant…", syncing.status)
    }

    @Test fun textNeverReplacesMissingDataWithZero() {
        val row = BatteryModernConfig().row("power")!!.copy(entityId = "sensor.p")
        val unit = JSONObject().put("unit_of_measurement", "W")
        assertEquals(BatteryFormat.Text("0", "W"), BatteryFormat.text(row, readings(live, mapOf("sensor.p" to "0"), attrs = mapOf("sensor.p" to unit)).of(row), locale = ru))
        listOf(HaSession(), HaSession(connected = true)).forEach { session ->
            assertEquals("—", BatteryFormat.text(row, readings(session, mapOf("sensor.p" to "0")).of(row)).value)
        }
        assertEquals("Недоступно", BatteryFormat.text(row, readings(live, mapOf("sensor.p" to "unavailable")).of(row)).value)
        assertNull(BatteryFormat.socFraction(BatteryReading.Disconnected))
        assertNull("Вне 0–100 шкала нейтральна", BatteryFormat.socFraction(BatteryReading.Value("140", 140.0, null)))
        assertEquals(0f, BatteryFormat.socFraction(BatteryReading.Value("0", 0.0, null))!!, 0f)
    }

    @Test fun powerSignChargeDischargeIdleAndInversion() {
        assertEquals(PowerFlow.DISCHARGE, PowerFlow.of(-407.3, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.CHARGE, PowerFlow.of(1381.0, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.IDLE, PowerFlow.of(-12.0, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.IDLE, PowerFlow.of(0.0, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.NO_DATA, PowerFlow.of(null, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.NO_DATA, PowerFlow.of(Double.NaN, invert = false, idleThreshold = 20.0))
        assertEquals(PowerFlow.CHARGE, PowerFlow.of(-407.3, invert = true, idleThreshold = 20.0))
        assertEquals(PowerFlow.DISCHARGE, PowerFlow.of(1381.0, invert = true, idleThreshold = 20.0))
        // Подпись и стрелка различимы без цвета.
        assertEquals(4, PowerFlow.entries.map { it.label }.toSet().size)
        assertEquals(4, PowerFlow.entries.map { it.arrow }.toSet().size)
        // Знак числа и единица сохраняются.
        val row = BatteryModernConfig().row("power")!!.copy(entityId = "sensor.p", decimals = 1)
        val reading = BatteryReading.Value("-407.31", -407.31, JSONObject().put("unit_of_measurement", "W"))
        assertEquals(BatteryFormat.Text("-407,3", "W"), BatteryFormat.text(row, reading, locale = ru))
        assertEquals("0", BatteryFormat.decimal(-0.2, 0, ru))
    }

    @Test fun formattingUsesHaUnitsDurationsAndDeviceClasses() {
        val base = BatteryModernConfig().row("runtime")!!.copy(entityId = "sensor.t")
        assertEquals("4 ч 16 мин", BatteryFormat.text(base, BatteryReading.Value("256", 256.0, JSONObject().put("unit_of_measurement", "min"))).value)
        assertEquals("1 д 2 ч", BatteryFormat.duration(26 * 3600.0))
        val charging = BatteryModernConfig().row("charging")!!.copy(entityId = "binary_sensor.c")
        assertEquals("Не заряжается", BatteryFormat.text(charging, BatteryReading.Value("off", null, JSONObject().put("device_class", "battery_charging"))).value)
        val problem = BatteryModernConfig().row("problem")!!.copy(entityId = "binary_sensor.p")
        assertEquals("OK", BatteryFormat.text(problem, BatteryReading.Value("off", null, JSONObject().put("device_class", "problem"))).value)
        assertEquals("Включено", BatteryFormat.text(problem, BatteryReading.Value("on", null, JSONObject())).value)
        val voltage = BatteryModernConfig().row("voltage")!!.copy(entityId = "sensor.v", unit = "В")
        assertEquals(BatteryFormat.Text("53,0", "В"), BatteryFormat.text(voltage, BatteryReading.Value("53.0", 53.0, JSONObject().put("unit_of_measurement", "V")), locale = ru))
        val temp = BatteryModernConfig().row("temperature")!!.copy(entityId = "sensor.t", decimals = 0)
        assertEquals(BatteryFormat.Text("71", "°F"), BatteryFormat.text(temp, BatteryReading.Value("21.6", 21.6, JSONObject().put("unit_of_measurement", "°C")), "F", ru))
        assertNull(BatteryFormat.number("12abc"))
        assertEquals(-7.69, BatteryFormat.number("-7,69")!!, 1e-9)
    }

    @Test fun draftEditsEveryRowAndCompactRoleWithoutWritingConfig() {
        val original = tile()
        val draft = BatteryModernDraft(original)
        assertFalse(draft.isDirty)
        BatteryModernConfig().rows.forEachIndexed { index, row ->
            draft.update(draft.row(row.id)!!.copy(entityId = "sensor.e$index", label = "${row.label}*", icon = "custom",
                decimals = index % 3, unit = "u$index"))
        }
        assertTrue(draft.isDirty)
        assertTrue(draft.config.rows.all { it.entityId.startsWith("sensor.e") && it.label.endsWith("*") })
        listOf("soc", "power", "runtime", "voltage", "temperature").forEach { role ->
            assertEquals(role, draft.rowForRole(role).role)
        }
        // Исходная плитка не меняется до явного применения.
        assertEquals(BatteryModernConfig(), BatteryModernConfig.from(original.config))
        val applied = draft.applyTo(original)
        assertEquals(draft.config, BatteryModernConfig.from(applied.config))
        assertEquals("keep", JSONObject(applied.config).getString("custom"))
        assertEquals(original.x, applied.x)
    }

    @Test fun draftAddDeleteRestoreAndValidation() {
        val draft = BatteryModernDraft(tile())
        draft.delete("soc")
        val restored = draft.rowForRole("soc")
        assertNull("Предложенная строка не попадает в черновик без «Готово»", draft.config.row("soc"))
        draft.update(restored.copy(entityId = "sensor.soc"))
        assertEquals("sensor.soc", draft.config.row("soc")!!.entityId)
        val custom = draft.newRow(BatteryModernConfig.Section.DIAGNOSTICS)
        assertNull(draft.row(custom.id))
        draft.update(custom.copy(label = "Сопротивление", entityId = "sensor.r"))
        assertEquals("Сопротивление", draft.row(custom.id)!!.label)
        assertNotEquals("Произвольные строки получают разные id", custom.id, draft.newRow(BatteryModernConfig.Section.SENSORS).id)
        assertFalse(draft.setIdleThreshold("-1"))
        assertFalse(draft.setIdleThreshold("abc"))
        assertTrue(draft.setIdleThreshold("15,5"))
        assertEquals(15.5, draft.config.idleThreshold, 0.0)
        draft.update(draft.row(custom.id)!!.copy(label = " "))
        assertNotNull(draft.validationError())

        val saved = BatteryModernDraft(tile()).apply { setHeader("Батарея дома", "LiFePO4") }.saveState()
        val back = BatteryModernDraft.restore(saved, tile())!!
        assertEquals("Батарея дома", back.title)
        assertEquals("LiFePO4", back.config.subtitle)
        assertNull("Чужой черновик к изменённой плитке не применяется", BatteryModernDraft.restore(saved, tile().copy(title = "Другое")))
    }
}
