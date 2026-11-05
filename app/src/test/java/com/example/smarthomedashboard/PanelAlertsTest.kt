package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Голосовые уведомления: когда и что произносить. Чистая логика, время задаёт тест.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PanelAlertsTest {
    private val grid = TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 0, 0, 1, 1,
        config = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID).let { c ->
            c.withRow(c.row(PowerModernConfig.ROLE_VOLTAGE)!!.copy(entityId = "sensor.v")) }.writeTo("{}"))
    private val battery = TileEntity("bat", BatteryModernConfig.TYPE, "grid", "АКБ", 0, 0, 1, 1,
        config = BatteryModernConfig().let { it.withRow(it.row(BatteryModernConfig.ROLE_SOC)!!.copy(entityId = "sensor.soc")) }.writeTo("{}"))
    private val inverter = TileEntity("inv", InverterModernConfig.TYPE, "grid", "Инвертор", 0, 0, 1, 1,
        config = InverterModernConfig().let { c ->
            listOf(InverterModernConfig.ROLE_LOAD_POWER, InverterModernConfig.ROLE_FAULT, InverterModernConfig.ROLE_PV_POWER)
                .fold(c) { acc, role -> acc.withRow(acc.row(role)!!.copy(entityId = "sensor.$role")) }
        }.writeTo("{}"))
    private val all = AlertEvent.entries.toSet()
    private val settings = VoiceAlertSettings(enabled = all)

    private fun readings(values: Map<String, String>, connected: Boolean = true) =
        BatteryReadings(HaSession(connected, connected), values::get, { false }, { JSONObject().put("unit_of_measurement", "W") })

    private fun run(m: PanelAlertMonitor, values: Map<String, String>, at: Long, tiles: List<TileEntity> = listOf(grid),
                    s: VoiceAlertSettings = settings, connected: Boolean = true) =
        m.evaluate(tiles, readings(values, connected), s, at).map { it.phrase }

    @Test fun gridLostAndRestoredAfterConfirmationOnly() {
        val m = PanelAlertMonitor()
        assertTrue(run(m, mapOf("sensor.v" to "230"), 0).isEmpty())
        assertTrue("Первое состояние только запоминается", run(m, mapOf("sensor.v" to "230"), 2_000).isEmpty())
        assertTrue(run(m, mapOf("sensor.v" to "0"), 3_000).isEmpty())
        assertNotNull("Ждём подтверждения", m.nextCheckAt)
        assertTrue("Короткий провал молчит", run(m, mapOf("sensor.v" to "230"), 3_500).isEmpty())
        run(m, mapOf("sensor.v" to "0"), 4_000)
        assertEquals(listOf("Сеть пропала"), run(m, mapOf("sensor.v" to "0"), 6_000))
        run(m, mapOf("sensor.v" to "229"), 7_000)
        // Повтор через 3 минуты: кулдаун на «появилась» не сработал — это первое такое событие.
        assertEquals(listOf("Сеть появилась"), run(m, mapOf("sensor.v" to "229"), 9_000))
    }

    @Test fun lostConnectionToHaIsNeitherLossNorRestore() {
        val m = PanelAlertMonitor()
        run(m, mapOf("sensor.v" to "230"), 0); run(m, mapOf("sensor.v" to "230"), 2_000)
        assertTrue(run(m, mapOf("sensor.v" to "0"), 3_000, connected = false).isEmpty())
        assertTrue(run(m, emptyMap(), 6_000).isEmpty())
        assertTrue("Связь вернулась, сеть по-прежнему есть", run(m, mapOf("sensor.v" to "231"), 7_000).isEmpty())
        assertTrue(run(m, mapOf("sensor.v" to "231"), 9_000).isEmpty())
    }

    @Test fun lowVoltageIsNotALossAndCooldownPreventsChatter() {
        val m = PanelAlertMonitor()
        run(m, mapOf("sensor.v" to "230"), 0); run(m, mapOf("sensor.v" to "230"), 2_000)
        run(m, mapOf("sensor.v" to "120"), 3_000)
        assertTrue("120 В — низкое напряжение, но не пропажа сети", run(m, mapOf("sensor.v" to "120"), 6_000).isEmpty())
        run(m, mapOf("sensor.v" to "0"), 7_000)
        assertEquals(listOf("Сеть пропала"), run(m, mapOf("sensor.v" to "0"), 9_000))
        run(m, mapOf("sensor.v" to "230"), 10_000); assertEquals(listOf("Сеть появилась"), run(m, mapOf("sensor.v" to "230"), 12_000))
        run(m, mapOf("sensor.v" to "0"), 13_000)
        assertTrue("Через 6 с после прошлой фразы — молчим", run(m, mapOf("sensor.v" to "0"), 15_000).isEmpty())
    }

    @Test fun disabledEventIsSilent() {
        val m = PanelAlertMonitor()
        val quiet = VoiceAlertSettings(enabled = all - AlertEvent.GRID_LOST)
        run(m, mapOf("sensor.v" to "230"), 0, s = quiet); run(m, mapOf("sensor.v" to "230"), 2_000, s = quiet)
        run(m, mapOf("sensor.v" to "0"), 3_000, s = quiet)
        assertTrue(run(m, mapOf("sensor.v" to "0"), 6_000, s = quiet).isEmpty())
    }

    @Test fun batteryLowOnceUntilRecovered() {
        val m = PanelAlertMonitor()
        val tiles = listOf(battery)
        assertTrue(run(m, mapOf("sensor.soc" to "45"), 0, tiles).isEmpty())
        assertEquals(listOf("Низкий заряд аккумулятора: 19 процентов"), run(m, mapOf("sensor.soc" to "19"), 1_000, tiles))
        assertTrue(run(m, mapOf("sensor.soc" to "18"), 40 * 60_000, tiles).isEmpty())
        assertTrue("Подъём до 22% — ещё не перевооружено", run(m, mapOf("sensor.soc" to "22"), 41 * 60_000, tiles).isEmpty())
        run(m, mapOf("sensor.soc" to "26"), 42 * 60_000, tiles)
        assertEquals(1, run(m, mapOf("sensor.soc" to "19"), 80 * 60_000, tiles).size)
        val startLow = PanelAlertMonitor()
        assertTrue("Запуск уже с низким зарядом — не сразу", run(startLow, mapOf("sensor.soc" to "10"), 0, tiles).isEmpty())
    }

    @Test fun inverterFaultAndOverloadAfterConfirmation() {
        val m = PanelAlertMonitor()
        val tiles = listOf(inverter)
        val normal = mapOf("sensor.load_power" to "2000", "sensor.pv_power" to "2000", "sensor.fault" to "0")
        run(m, normal, 0, tiles)
        assertTrue(run(m, normal + ("sensor.load_power" to "9500"), 1_000, tiles).isEmpty())
        assertEquals(listOf("Перегрузка инвертора"), run(m, normal + ("sensor.load_power" to "9500"), 3_000, tiles))
        run(m, normal + ("sensor.fault" to "7"), 4_000, tiles)
        assertEquals(listOf("Авария инвертора"), run(m, normal + ("sensor.fault" to "7"), 6_000, tiles))
    }

    @Test fun externalDoorbellRingRespectsShortCooldown() {
        val m = PanelAlertMonitor()
        val ring = Alert(AlertEvent.DOORBELL_RING, "Звонят в калитку")
        assertNotNull(m.external(ring, settings, 0))
        assertNull(m.external(ring, settings, 5_000))
        assertNotNull(m.external(ring, settings, 11_000))
    }

    @Test fun quietHoursAndSettingsRoundTrip() {
        val s = VoiceAlertSettings(volumePercent = 50, quietPercent = 20)
        assertEquals(0.5f, s.volumeAt(12 * 60), 0.001f)
        assertEquals(0.1f, s.volumeAt(23 * 60 + 30), 0.001f)
        assertEquals(0.1f, s.volumeAt(6 * 60 + 59), 0.001f)
        assertEquals(0.5f, s.volumeAt(7 * 60), 0.001f)
        assertFalse(VoiceAlertSettings(quietStartMin = 600, quietEndMin = 600).isQuiet(600))
        assertTrue(VoiceAlertSettings(quietStartMin = 13 * 60, quietEndMin = 15 * 60).isQuiet(14 * 60))
        assertEquals(23 * 60, VoiceAlertSettings.parseMinute("23:00"))
        assertEquals(7 * 60 + 5, VoiceAlertSettings.parseMinute(" 7.05 "))
        assertEquals(22 * 60, VoiceAlertSettings.parseMinute("22"))
        assertNull(VoiceAlertSettings.parseMinute("25:00"))
        assertNull(VoiceAlertSettings.parseMinute("abc"))
        val prefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("t", android.content.Context.MODE_PRIVATE)
        assertEquals("По умолчанию включены сеть и домофон", setOf(AlertEvent.GRID_LOST, AlertEvent.GRID_RESTORED,
            AlertEvent.DOORBELL_RING, AlertEvent.DOORBELL_OFFLINE), VoiceAlertSettings.load(prefs).enabled)
        val custom = VoiceAlertSettings(enabled = setOf(AlertEvent.BATTERY_LOW), volumePercent = 33, quietStartMin = 22 * 60,
            quietEndMin = 6 * 60, quietPercent = 10, batteryLowPercent = 15)
        custom.save(prefs)
        assertEquals(custom, VoiceAlertSettings.load(prefs))
    }
}
