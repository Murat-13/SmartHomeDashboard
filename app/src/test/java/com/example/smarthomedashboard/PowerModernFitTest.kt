package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

// Нативная графика Robolectric: ширина цифр измеряется настоящими шрифтами.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PowerModernFitTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val config = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID).let { c ->
        c.withRow(c.row("voltage")!!.copy(entityId = "sensor.v")).withRow(c.row("power")!!.copy(entityId = "sensor.p"))
    }

    private fun card(voltage: String, power: String, w: Int, h: Int, expanded: Boolean = false): PowerModernCard {
        val states = mapOf("sensor.v" to voltage, "sensor.p" to power)
        val attrs = mapOf("sensor.v" to JSONObject().put("unit_of_measurement", "V"), "sensor.p" to JSONObject().put("unit_of_measurement", "W"))
        val card = PowerModernCard(activity, expanded)
        val root = FrameLayout(activity)
        root.addView(card, FrameLayout.LayoutParams(w, h))
        activity.setContentView(root)
        card.bind("Сеть · Вход", config, BatteryReadings(HaSession(true, true), states::get, { false }, attrs::get),
            PowerVisual.preview(config, 228.0), animate = false)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1200)
        }
        return card
    }

    private fun right(view: View, stop: View): Int {
        var x = view.width
        var current: View? = view
        while (current != null && current !== stop) { x += current.left; current = current.parent as? View }
        return x
    }

    @Test fun mainNumbersFitWithoutClippingAndStayEqualAtSeveralSizes() {
        val sizes = listOf(300 to 360, 240 to 300, 200 to 240, 420 to 500, 360 to 260)
        listOf("0" to "0", "228" to "980", "230" to "1930.45", "265" to "9999", "285" to "12500").forEach { (v, p) ->
            sizes.forEach { (w, h) ->
                val card = card(v, p, w, h)
                val voltage = card.findViewWithTag<TextView>("pm_voltage")
                val power = card.findViewWithTag<TextView>("pm_power")
                val numbers = card.findViewWithTag<ViewGroup>("pm_numbers")
                val label = "$v В / $p Вт at ${w}x$h"
                assertEquals("$label: одинаковый размер цифр", voltage.textSize, power.textSize, 0.01f)
                listOf(voltage to card.findViewWithTag<TextView>("pm_voltage_unit"), power to card.findViewWithTag<TextView>("pm_power_unit"))
                    .forEach { (number, unit) ->
                        val needed = number.paint.measureText(number.text.toString()) + unit.paint.measureText(unit.text.toString()) +
                            number.textSize * 0.18f
                        assertTrue("$label: ${number.text} помещается ($needed > ${numbers.width})", needed <= numbers.width + 2)
                        assertEquals("$label: одна строка", 1, number.lineCount)
                        assertTrue("$label: в пределах карточки", right(unit, card) <= card.width + 1)
                    }
                assertTrue("$label: две строки помещаются по высоте", voltage.height + power.height <= numbers.height + 2)
            }
        }
    }

    @Test fun outerHaloIsDrawnOutsideDarkBodyWithinOwnBounds() {
        val card = card("228", "980", 300, 360)
        val halo = card.haloPx
        assertTrue("Кольцо ореола небольшое: $halo", halo in 4f..12f)
        val bitmap = android.graphics.Bitmap.createBitmap(card.width, card.height, android.graphics.Bitmap.Config.ARGB_8888)
        card.draw(android.graphics.Canvas(bitmap))
        val y = card.height / 2
        fun alpha(x: Int) = android.graphics.Color.alpha(bitmap.getPixel(x, y))
        val inner = bitmap.getPixel((halo * 0.8f).toInt(), y)
        // Снаружи тела — полупрозрачный ореол цвета состояния (изумрудный: зелёный канал ведущий).
        assertTrue("ореол виден: ${Integer.toHexString(inner)}", android.graphics.Color.alpha(inner) in 1..254)
        assertTrue(android.graphics.Color.green(inner) > android.graphics.Color.red(inner))
        assertTrue("яркость убывает наружу", alpha(0) < alpha((halo * 0.8f).toInt()))
        // Тёмное тело начинается сразу за кольцом и непрозрачно.
        assertEquals(255, alpha(halo.toInt() + 6))
        // Контур (тень) — по телу; содержимое не заходит на кольцо; сохранённый размер прежний.
        val outline = android.graphics.Outline()
        card.outlineProvider.getOutline(card, outline)
        val bounds = android.graphics.Rect()
        assertTrue(outline.getRect(bounds))
        assertEquals(halo.toInt(), bounds.left)
        assertEquals(card.width - halo.toInt(), bounds.right)
        assertFalse(card.clipToOutline)
        assertTrue((card.getChildAt(0) as ViewGroup).paddingLeft >= halo.toInt())
        assertEquals(300, card.width)
        val (tile, _) = PowerModernCard.createTile(activity, "t", 300, 360, false)
        tile.layout(0, 0, 300, 360)
        val tileOutline = android.graphics.Outline()
        tile.outlineProvider.getOutline(tile, tileOutline)
        assertTrue(tileOutline.getRect(bounds))
        assertEquals(PowerModernCard.haloFor(1f).toInt(), bounds.left)
        assertFalse(tile.clipToOutline)
        assertEquals(300, tile.layoutParams.width)
    }

    @Test fun numbersAreLargeOnStandardTile() {
        val card = card("228", "980", 300, 360)
        val voltage = card.findViewWithTag<TextView>("pm_voltage")
        // Главные цифры — основная часть площади: не меньше 18% высоты плитки.
        assertTrue("размер ${voltage.textSize}", voltage.textSize >= 360 * 0.18f)
    }

    @Test fun expandedNumbersAlsoFit() {
        val card = card("229.8", "1930.45", 1300, 600, expanded = true)
        val numbers = card.findViewWithTag<ViewGroup>("pm_numbers")
        val power = card.findViewWithTag<TextView>("pm_power")
        val unit = card.findViewWithTag<TextView>("pm_power_unit")
        val needed = power.paint.measureText(power.text.toString()) + unit.paint.measureText(unit.text.toString()) + power.textSize * 0.18f
        assertTrue(needed <= numbers.width + 2)
    }
}
