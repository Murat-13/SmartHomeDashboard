package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Правила напряжения, трекер подтверждения и оценка «Нет данных». Без Views, сети и HA.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PowerModernLogicTest {
    private val grid = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID)
    private val inverter = PowerModernConfig.profile(PowerModernConfig.PROFILE_INVERTER)

    private fun label(config: PowerModernConfig, v: Double) = config.rules[config.ruleFor(v)].label
    private fun rule(config: PowerModernConfig, v: Double) = config.rules[config.ruleFor(v)]

    @Test fun defaultTableClassifiesAllReferenceVoltages() {
        val expected = listOf(
            0.0 to Triple(PowerModernConfig.RED, "Нет сети", true),
            100.0 to Triple(PowerModernConfig.RED, "Очень низкое напряжение", true),
            150.0 to Triple(PowerModernConfig.RED, "Очень низкое напряжение", true),
            151.0 to Triple(PowerModernConfig.RED, "Низкое напряжение", false),
            170.0 to Triple(PowerModernConfig.ORANGE, "Пониженное напряжение", false),
            180.0 to Triple(PowerModernConfig.ORANGE, "Пониженное напряжение", false),
            190.0 to Triple(PowerModernConfig.YELLOW, "Нижняя граница", false),
            195.0 to Triple(PowerModernConfig.YELLOW, "Нижняя граница", false),
            200.0 to Triple(PowerModernConfig.EMERALD, "Норма", false),
            228.0 to Triple(PowerModernConfig.EMERALD, "Норма", false),
            240.0 to Triple(PowerModernConfig.YELLOW, "Повышенное напряжение", false),
            245.0 to Triple(PowerModernConfig.YELLOW, "Повышенное напряжение", false),
            250.0 to Triple(PowerModernConfig.ORANGE, "Высокое напряжение", false),
            255.0 to Triple(PowerModernConfig.ORANGE, "Высокое напряжение", false),
            260.0 to Triple(PowerModernConfig.RED, "Опасно высокое напряжение", false),
            265.0 to Triple(PowerModernConfig.RED, "Опасно высокое напряжение", false),
            279.0 to Triple(PowerModernConfig.RED, "Опасно высокое напряжение", false),
            280.0 to Triple(PowerModernConfig.RED, "Опасно высокое напряжение", true),
            285.0 to Triple(PowerModernConfig.RED, "Опасно высокое напряжение", true)
        )
        expected.forEach { (v, triple) ->
            val r = rule(grid, v)
            assertEquals("$v В", triple, Triple(r.color, r.label, r.pulse))
        }
        // Точные границы.
        assertTrue(rule(grid, 0.001).pulse)
        assertEquals("Низкое напряжение", label(grid, 150.0001))
        assertEquals("Норма", label(grid, 239.999))
        assertFalse(rule(grid, 279.999).pulse)
        // Выход инвертора: та же таблица, своя подпись нуля и бирюзовая норма.
        assertEquals("Нет напряжения на выходе", label(inverter, 0.0))
        assertEquals(PowerModernConfig.CYAN, rule(inverter, 230.0).color)
        assertEquals("Норма", label(inverter, 230.0))
        assertEquals(-1, grid.ruleFor(-5.0))
    }

    @Test fun defaultTableHasNoOverlapsOrGapsAndValidationFindsProblems() {
        listOf(grid, inverter).forEach { assertEquals(PowerModernConfig.Validation(emptyList(), emptyList()), it.validate()) }
        val overlapping = grid.copy(rules = grid.rules + PowerModernConfig.Rule(220.0, true, 230.0, false, 0, "Лишнее", false))
        assertTrue(overlapping.validate().errors.single().contains("пересекаются"))
        val gap = grid.copy(rules = grid.rules.filterNot { it.label == "Нижняя граница" })
        assertEquals(listOf("Не покрыто: от 190 до 200 В"), gap.validate().gaps)
        val touchingExclusive = grid.copy(rules = grid.rules.map {
            if (it.label == "Норма") it.copy(fromInclusive = false) else it
        })
        assertEquals(listOf("Не покрыто: ровно 200 В"), touchingExclusive.validate().gaps)
        val empty = grid.copy(rules = listOf(PowerModernConfig.Rule(10.0, false, 10.0, false, 0, "x", false)))
        assertTrue(empty.validate().errors.isNotEmpty())
        val upper = grid.copy(rules = grid.rules.dropLast(1))
        assertEquals(listOf("Не покрыто: выше 280 В"), upper.validate().gaps)
    }

    @Test fun configRoundTripIsPerTileAndKeepsForeignKeys() {
        val custom = inverter.withRow(inverter.row("voltage")!!.copy(entityId = "sensor.inv_v", decimals = 1))
            .copy(rules = inverter.rules.map { if (it.label == "Норма") it.copy(color = 0xFF3B82F6.toInt(), label = "Выход активен") else it },
                hysteresis = 3.0, confirmMs = 2500)
        val written = custom.writeTo("""{"button_size":310,"x":"keep"}""")
        assertEquals("keep", JSONObject(written).getString("x"))
        assertEquals(custom, PowerModernConfig.from(written))
        // Плитка «Сеть · Вход» не видит настроек выхода инвертора.
        val gridTile = PowerModernConfig.from(PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID))
        assertEquals(grid, gridTile)
        assertNotEquals(custom.rules, gridTile.rules)
        assertTrue(gridTile.rows.all { it.entityId.isEmpty() })
        val fresh = JSONObject(PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_INVERTER, """{"widget_height":500}"""))
        assertEquals(500, fresh.getInt("widget_height"))
        assertEquals(PowerModernConfig.DEFAULT_WIDTH, fresh.getInt("button_size"))
        assertEquals(PowerModernConfig.ICON_INVERTER, PowerModernConfig.from(fresh.toString()).icon)
        assertEquals(PowerModernConfig(), PowerModernConfig.from("broken"))
        assertEquals(listOf("current", "apparent_power", "reactive_power", "power_factor", "energy", "status"),
            grid.extras.map { it.id })
    }

    // ---- Трекер ----

    @Test fun firstCriticalReadingWaitsForConfirmationAndNumbersAreNotDelayed() {
        listOf(0.0, 285.0).forEach { v ->
            val tracker = PowerStateTracker()
            assertNull("Первое показание $v В ещё не подтверждено", tracker.update(v, grid, 0))
            assertEquals(1_800L, tracker.nextCheckAt)
            assertNull(tracker.update(v, grid, 1_799))
            assertEquals(grid.ruleFor(v), tracker.update(v, grid, 1_800))
            assertNull(tracker.nextCheckAt)
            assertTrue(grid.rules[tracker.confirmed!!].pulse)
        }
        // Пока диапазон подтверждается — нейтрально, без пульсации и без «Нормы».
        val readings = BatteryReadings(HaSession(true, true), { "0" }, { false }, { null })
        val visual = PowerVisual.of(grid, BatteryReading.Value("0", 0.0, null), readings, null)
        assertTrue(visual.neutral)
        assertFalse(visual.pulse)
        assertEquals("Определение состояния…", visual.status)
    }

    @Test fun rangeChangeNeedsHysteresisAndConfirmationDelay() {
        val tracker = PowerStateTracker()
        tracker.update(228.0, grid, 0)
        assertEquals(grid.ruleFor(228.0), tracker.update(228.0, grid, 1_800))
        val norm = tracker.confirmed
        // 199 В: ниже границы 200 всего на 1 В — меньше гистерезиса, диапазон не меняется.
        assertEquals(norm, tracker.update(199.0, grid, 2_000))
        assertNull(tracker.candidate)
        assertEquals(norm, tracker.update(199.0, grid, 10_000))
        // 197,5 В: за гистерезисом — ожидание подтверждения 1,8 с.
        assertEquals(norm, tracker.update(197.5, grid, 10_000))
        assertEquals(norm, tracker.update(197.5, grid, 11_799))
        assertEquals(grid.ruleFor(195.0), tracker.update(197.5, grid, 11_800))
        // Возврат в норму: тоже гистерезис относительно жёлтого диапазона [190; 200).
        assertEquals(grid.ruleFor(195.0), tracker.update(201.0, grid, 12_000))
        tracker.update(202.0, grid, 12_000)
        assertEquals(norm, tracker.update(202.0, grid, 13_800))
    }

    @Test fun oscillationAtBoundaryDoesNotFlicker() {
        val tracker = PowerStateTracker()
        tracker.update(201.0, grid, 0)
        tracker.update(201.0, grid, 1_800)
        val norm = tracker.confirmed
        var t = 2_000L
        repeat(40) { i ->
            val v = if (i % 2 == 0) 197.0 else 201.0 // каждое второе значение возвращается в норму
            assertEquals("шаг $i", norm, tracker.update(v, grid, t))
            t += 500
        }
    }

    @Test fun lossOfDataIsImmediateAndRecoveryIsConfirmedAgain() {
        val tracker = PowerStateTracker()
        tracker.update(228.0, grid, 0)
        tracker.update(228.0, grid, 1_800)
        assertNull("Потеря данных — сразу нейтрально", tracker.update(null, grid, 1_900))
        assertNull(tracker.confirmed)
        assertNull("Восстановление связи: снова подтверждение", tracker.update(228.0, grid, 5_000))
        assertEquals(grid.ruleFor(228.0), tracker.update(228.0, grid, 6_800))
    }

    @Test fun alreadyConfirmedStateSurvivesUiRecreationImmediately() {
        // Один трекер во ViewModel обслуживает старую и новую View.
        val model = MainViewModel()
        model.powerTracker("grid").update(0.0, grid, 0)
        model.powerTracker("grid").update(0.0, grid, 1_800)
        val confirmed = model.powerTracker("grid").update(0.0, grid, 50_000)
        assertEquals(grid.ruleFor(0.0), confirmed)
        assertNull(model.powerTracker("grid").nextCheckAt)
        model.retainPowerTrackers(setOf("other"))
        assertNull("Удалённая плитка не держит состояние", model.powerTracker("grid").confirmed)
    }

    @Test fun visualDistinguishesConnectionSyncAndMissingSources() {
        val states = mapOf("sensor.v" to "230", "sensor.na" to "unavailable")
        fun readings(session: HaSession) = BatteryReadings(session, states::get, { false }, { null })
        val live = HaSession(true, true)
        val connected = PowerVisual.of(grid, readings(HaSession()).of("sensor.v"), readings(HaSession()), 5)
        assertEquals(PowerVisual(PowerModernConfig.NEUTRAL, "Нет связи с Home Assistant", false, true), connected)
        assertEquals("Синхронизация с Home Assistant…",
            PowerVisual.of(grid, readings(HaSession(true)).of("sensor.v"), readings(HaSession(true)), 5).status)
        assertEquals("Нет данных: напряжение не выбрано", PowerVisual.of(grid, BatteryReading.NotConfigured, readings(live), null).status)
        assertEquals("Нет данных: сущность не найдена", PowerVisual.of(grid, readings(live).of("sensor.absent"), readings(live), null).status)
        assertEquals("Нет данных", PowerVisual.of(grid, readings(live).of("sensor.na"), readings(live), null).status)
        assertTrue(PowerVisual.of(grid, readings(live).of("sensor.na"), readings(live), null).neutral)
        assertEquals("Вне заданных диапазонов", PowerVisual.of(grid, BatteryReading.Value("-5", -5.0, null), readings(live), -1).status)
        assertEquals("Норма", PowerVisual.of(grid, readings(live).of("sensor.v"), readings(live), grid.ruleFor(230.0)).status)
        // Предпросмотр редактора — мгновенно, без трекера.
        listOf(0.0, 145.0, 180.0, 195.0, 228.0, 245.0, 255.0, 285.0).forEach { v ->
            assertEquals(grid.rules[grid.ruleFor(v)].label, PowerVisual.preview(grid, v).status)
        }
    }

    @Test fun rulesChangeReclassifiesWithConfirmation() {
        val tracker = PowerStateTracker()
        tracker.update(228.0, grid, 0)
        tracker.update(228.0, grid, 1_800)
        val edited = grid.copy(rules = grid.rules.map { if (it.label == "Норма") it.copy(color = 0xFF3B82F6.toInt()) else it })
        assertNull(tracker.update(228.0, edited, 2_000))
        assertEquals(edited.ruleFor(228.0), tracker.update(228.0, edited, 3_800))
    }
}
