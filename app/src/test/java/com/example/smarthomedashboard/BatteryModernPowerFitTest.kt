package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.view.View
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

// Нативная графика Robolectric: ширина текста измеряется настоящими шрифтами.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryModernPowerFitTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val config = BatteryModernConfig().let { it.withRow(it.row("power")!!.copy(entityId = "sensor.p", unit = "Вт")) }

    private fun card(power: String, width: Int, height: Int): BatteryModernCard {
        val states = mapOf("sensor.p" to power)
        val attrs = mapOf("sensor.p" to JSONObject().put("unit_of_measurement", "W"))
        val card = BatteryModernCard(activity, expanded = false)
        card.bind("АКБ", config, BatteryReadings(HaSession(true, true), states::get, { false }, attrs::get), "C")
        // Карточка в окне, как на панели: масштаб и подгонка применяются после layout через post.
        val root = android.widget.FrameLayout(activity)
        root.addView(card, android.widget.FrameLayout.LayoutParams(width, height))
        activity.setContentView(root)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1200)
        }
        return card
    }

    private fun bounds(view: View): IntArray {
        val result = IntArray(4)
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !is BatteryModernCard) {
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        result[0] = x; result[1] = y; result[2] = x + view.width; result[3] = y + view.height
        return result
    }

    @Test fun powerDigitsAreLargerThanBeforeWhenSpaceAllows() {
        // Прежний размер — 50 базовых единиц; теперь 62 (+24%), если хватает места.
        listOf(Triple("-9", BatteryModernConfig.DEFAULT_WIDTH, BatteryModernConfig.DEFAULT_HEIGHT),
            Triple("-407.3", 760, 380), Triple("1381.65", 760, 380)).forEach { (value, w, h) ->
            val power = card(value, w, h).findViewWithTag<TextView>("bm_power")
            val scale = minOf(w / 540f, h / 390f)
            assertEquals("$value at ${w}x$h", 62f * scale, power.textSize, 0.6f)
            assertTrue(power.textSize >= 50f * scale * 1.2f)
            assertEquals(android.graphics.Color.WHITE, power.currentTextColor)
        }
    }

    @Test fun longValuesFitWithoutClippingOrOverlapAtSeveralTileSizes() {
        val sizes = listOf(520 to 380, 440 to 320, 360 to 260, 700 to 500, 760 to 380)
        listOf("0", "-9", "-407", "1381", "-9999", "10000").forEach { value ->
            sizes.forEach { (w, h) ->
                val card = card(value, w, h)
                val power = card.findViewWithTag<TextView>("bm_power")
                val line = power.parent as View
                val column = line.parent as View
                val block = column.parent as View
                val unit = (line as android.view.ViewGroup).getChildAt(1) as TextView
                val needed = power.paint.measureText(power.text.toString()) + unit.paint.measureText(unit.text.toString()) +
                    unit.paddingLeft + unit.paddingRight
                val label = "$value at ${w}x$h"
                val room = column.width - column.paddingLeft - column.paddingRight
                assertTrue("$label: цифры и единица помещаются ($needed > $room)", needed <= room + 1)
                assertEquals("$label: единица", "Вт", unit.text.toString())
                assertEquals("$label: цифры не переносятся", 1, power.lineCount)
                val p = bounds(power)
                val b = bounds(block)
                assertTrue("$label: внутри блока по вертикали", p[1] >= b[1] && p[3] <= b[3])
                assertTrue("$label: внутри блока по горизонтали", p[0] >= b[0] && p[2] <= b[2])
                val labelView = bounds((column as android.view.ViewGroup).getChildAt(0))
                val flow = bounds(column.getChildAt(2))
                assertTrue("$label: не наезжает на подпись", p[1] >= labelView[3])
                assertTrue("$label: не наезжает на направление", p[3] <= flow[1])
            }
        }
    }

    @Test fun shrinksOnlyWhenNeededAndGrowsBackForShortValues() {
        val small = card("10000", 360, 260).findViewWithTag<TextView>("bm_power").textSize
        val short = card("0", 360, 260).findViewWithTag<TextView>("bm_power").textSize
        assertTrue("Длинное значение уменьшено ($small < $short)", small <= short)
        val scale = minOf(360 / 540f, 260 / 390f)
        assertEquals(62f * scale, short, 0.6f)
    }
}
