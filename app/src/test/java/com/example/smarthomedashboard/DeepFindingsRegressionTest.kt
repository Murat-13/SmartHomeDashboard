package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Находки глубоких проверок 0.9.13 (docs/deep-testing.md) — короткие детерминированные повторы, чтобы исправления
 * не потерялись, даже если хаос-прогон в будущем пойдёт другими путями.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DeepFindingsRegressionTest {
    /** «NaN»/«Infinity» от сломанной интеграции и переполнение пересчёта единиц роняли панель через BigDecimal. */
    @Test fun nonFiniteNumbersAreShownAsDash() {
        assertEquals("—", BatteryFormat.decimal(Double.NaN, 1, Locale.ROOT))
        assertEquals("—", BatteryFormat.decimal(Double.POSITIVE_INFINITY, 0, Locale.ROOT))
        assertEquals("—", BatteryFormat.decimal(1e308 * 1000, 0, Locale.ROOT))
        assertEquals("—", HomeClimateData.number(Double.NaN, 1))
        assertEquals("—", PowerGraphView.axisLabel(Double.NEGATIVE_INFINITY))
        // Обычные значения — как раньше.
        assertEquals("12.5", BatteryFormat.decimal(12.46, 1, Locale.ROOT))
        assertEquals("1,5", HomeClimateData.number(1.5, 1))
    }

    /** Размер текста плитки из импортированной копии — в пределах редактора (1–200), значение в настройках не меняется. */
    @Test fun tileFontSizeIsClampedForDrawing() {
        assertEquals(1, SensorContentRenderer.fontSize(0))
        assertEquals(1, SensorContentRenderer.fontSize(-40))
        assertEquals(200, SensorContentRenderer.fontSize(Int.MAX_VALUE))
        assertEquals(16, SensorContentRenderer.fontSize(16))
    }

    /** Граница правила напряжения не число — правило пропускается; редактор не падает на повороте и сохранении. */
    @Test fun damagedVoltageRuleIsSkippedAndConfigStaysWritable() {
        val json = org.json.JSONObject().put("rules", org.json.JSONArray()
            .put(org.json.JSONObject().put("from", "abc").put("color", "#FF0000"))
            .put(org.json.JSONObject().put("from", 0).put("to", "NaN").put("color", "#00FF00"))
            .put(org.json.JSONObject().put("from", 180).put("to", 250).put("color", "#0000FF")))
        val config = PowerModernConfig.fromJson(json)
        assertEquals(listOf(180.0), config.rules.map { it.from })
        // Раньше здесь JSONException «Forbidden numeric value: NaN».
        assertEquals(1, config.toJson().getJSONArray("rules").length())
    }
}
