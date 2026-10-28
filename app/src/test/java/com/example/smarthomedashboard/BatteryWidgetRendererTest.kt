package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BatteryWidgetRendererTest {
    private val context = RuntimeEnvironment.getApplication()
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val states = linkedMapOf<String, String>()

    private fun tile(config: String = """{"entity_id":"sensor.soc"}""") =
        TileEntity("battery", "battery", "grid", "Батарея", 0, 0, 200, 100, config = config)

    private fun card(width: Int = 200, withFill: Boolean = true): FrameLayout {
        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(width, 100)
            alpha = 0.85f
            tag = "battery"
        }
        if (withFill) card.addView(View(context).apply {
            tag = "battery_progress_fill"
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        if (width > 0) layout(card, width)
        return card
    }

    private fun fill(card: FrameLayout): View = card.findViewWithTag("battery_progress_fill")

    private fun layout(card: FrameLayout, width: Int) {
        card.layoutParams.width = width
        // Без ViewRoot в этом тесте принудительно выполняем следующий запрошенный проход layout.
        card.forceLayout()
        for (i in 0 until card.childCount) card.getChildAt(i).forceLayout()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, 100)
    }

    private fun settle(card: FrameLayout) {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        // Запрос layout от слоя заполнения выполняется здесь так же, как при следующем кадре.
        layout(card, card.width)
        layout(card, card.width)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeActivity(tile: TileEntity, card: FrameLayout) {
        MainActivity::class.java.getDeclaredField("singleStates").apply {
            isAccessible = true
            val target = get(activity) as MutableMap<String, String>
            target.clear()
            target.putAll(states)
        }
        try {
            MainActivity::class.java.getDeclaredMethod("updateBatteryWidget",
                TileEntity::class.java, FrameLayout::class.java).apply {
                isAccessible = true
                invoke(activity, tile, card)
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    // Обычные сценарии прошли до переноса; регрессии воспроизвели ошибки старого метода.
    private fun render(tile: TileEntity, card: FrameLayout) = invokeActivity(tile, card)

    @Test fun normalBoundariesAndColorsKeepExistingAppearance() {
        val card = card()
        for ((value, width, color) in listOf(
            Triple("0", 0, "#80FF3333"), Triple("10", 20, "#80FF3333"),
            Triple("15", 30, "#80FFCC00"), Triple("20", 40, "#80FFCC00"),
            Triple("50", 100, "#8033CC33"), Triple("100", 200, "#8033CC33"))) {
            states["sensor.soc"] = value
            render(tile(), card)
            settle(card)
            assertEquals(value, width, fill(card).width)
            assertEquals(Color.parseColor(color), (fill(card).background as ColorDrawable).color)
        }
        assertEquals(Color.parseColor("#80333333"), (card.background as ColorDrawable).color)
        assertEquals(0.85f, card.alpha, 0f)
        assertEquals("battery_progress_fill", fill(card).tag)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, fill(card).layoutParams.height)
    }

    @Test fun percentSuffixWhitespaceAndFractionsKeepExistingParsing() {
        states["sensor.soc"] = " 12.5% "
        val card = card()
        render(tile(), card)
        settle(card)
        assertEquals(25, fill(card).width)
        assertEquals(Color.parseColor("#80FFCC00"), (fill(card).background as ColorDrawable).color)
    }

    @Test fun absentInvalidAndOutOfRangeValuesKeepExistingFallbackAndClamping() {
        for ((value, width, color) in listOf(
            Triple<String?, Int, String>(null, 0, "#80FF3333"),
            Triple("unknown", 0, "#80FF3333"), Triple("unavailable", 0, "#80FF3333"),
            Triple("", 0, "#80FF3333"), Triple("50,5", 0, "#80FF3333"),
            Triple("-20", 0, "#80FF3333"), Triple("150", 200, "#8033CC33"),
            Triple("NaN", 0, "#80FF3333"), Triple("Infinity", 200, "#8033CC33"))) {
            states.clear()
            if (value != null) states["sensor.soc"] = value
            val card = card()
            render(tile(), card)
            settle(card)
            assertEquals(value, width, fill(card).width)
            assertEquals(Color.parseColor(color), (fill(card).background as ColorDrawable).color)
        }
    }

    @Test fun firstEntityInArrayHasPriorityOverSingleEntityAndDisplayConfigs() {
        states["sensor.first"] = "50"
        states["sensor.second"] = "100"
        states["sensor.single"] = "10"
        val card = card()
        render(tile("""{"entity_ids":["sensor.first","sensor.second"],"entity_id":"sensor.single",
            "collapsed_sensor_configs":[{"entity_id":"sensor.second"}],
            "expanded_sensor_configs":[{"entity_id":"sensor.second"}]}"""), card)
        settle(card)
        assertEquals(100, fill(card).width)
    }

    @Test fun emptyOrWrongTypeArrayFallsBackToSingleEntity() {
        states["sensor.single"] = "50"
        for (config in listOf("""{"entity_id":"sensor.single"}""",
            """{"entity_ids":[],"entity_id":"sensor.single"}""",
            """{"entity_ids":{},"entity_id":"sensor.single"}""")) {
            val card = card()
            render(tile(config), card)
            settle(card)
            assertEquals(100, fill(card).width)
        }
    }

    @Test fun malformedConfigUsesEmptyIdAndDoesNotGuessAnotherSource() {
        states["sensor.soc"] = "100"
        for (config in listOf("{", "{}", "")) {
            val card = card()
            render(tile(config), card)
            settle(card)
            assertEquals(0, fill(card).width)
        }
    }

    @Test fun missingFillLeavesCardUntouched() {
        val card = card(withFill = false)
        card.setBackgroundColor(Color.BLUE)
        render(tile(), card)
        assertEquals(0, card.childCount)
        assertEquals(Color.BLUE, (card.background as ColorDrawable).color)
    }

    @Test fun repeatedUpdateReusesFillAndDoesNotAddChildren() {
        states["sensor.soc"] = "50"
        val card = card()
        val fill = fill(card)
        repeat(3) { render(tile(), card); settle(card) }
        assertEquals(1, card.childCount)
        assertSame(fill, fill(card))
        assertEquals(100, fill.width)
    }

    @Test fun windowLayoutAppliesLatestValueAndResizeWithoutForcedTestLayout() {
        val host = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val root = FrameLayout(host.get())
            val card = card(width = 0)
            root.addView(card, FrameLayout.LayoutParams(200, 100))
            host.get().setContentView(root)
            states["sensor.soc"] = "20"
            BatteryWidgetRenderer.render(tile(), card, states::get)
            states["sensor.soc"] = "75"
            BatteryWidgetRenderer.render(tile(), card, states::get)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(200, card.width)
            assertEquals(150, fill(card).width)

            card.layoutParams = card.layoutParams.apply { width = 300 }
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(300, card.width)
            assertEquals(225, fill(card).width)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun independentCardsKeepTheirOwnValueAndAnimation() {
        val first = card()
        val second = card()
        states["sensor.first"] = "50"
        states["sensor.second"] = "75"
        render(tile("""{"entity_id":"sensor.first"}"""), first)
        render(tile("""{"entity_id":"sensor.second"}"""), second)
        settle(first)
        settle(second)
        assertEquals(100, fill(first).width)
        assertEquals(150, fill(second).width)
        states["sensor.first"] = "0"
        render(tile("""{"entity_id":"sensor.first"}"""), first)
        settle(first)
        layout(second, 400)
        settle(second)
        assertEquals(0, fill(first).width)
        assertEquals(300, fill(second).width)
    }

    @Test fun removingFillStopsOldAnimationWithoutReadingStateAgain() {
        val card = card()
        states["sensor.soc"] = "100"
        render(tile(), card)
        val removed = fill(card)
        card.removeView(removed)
        BatteryWidgetRenderer.render(tile(), card) { error("Нет слоя — значение не требуется") }
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(0, card.childCount)
        assertEquals(0, removed.layoutParams.width)
    }

    // Исправляемое поведение: результат не должен зависеть от наличия нового сообщения HA после layout.
    @Test fun regressionUpdateBeforeMeasurementAppliesAfterFirstLayout() {
        states["sensor.soc"] = "50"
        val card = card(width = 0)
        render(tile(), card)
        layout(card, 200)
        settle(card)
        assertEquals("Запрошено ${fill(card).layoutParams.width}, измерено ${fill(card).width}", 100, fill(card).width)
    }

    @Test fun regressionResizeRecomputesFillWithoutAnotherStateUpdate() {
        states["sensor.soc"] = "50"
        val card = card()
        render(tile(), card)
        settle(card)
        layout(card, 400)
        settle(card)
        assertEquals(200, fill(card).width)
        layout(card, 100)
        settle(card)
        assertEquals(50, fill(card).width)
    }

    @Test fun regressionNewZeroCancelsOldInFlightAnimation() {
        val card = card()
        states["sensor.soc"] = "100"
        render(tile(), card)
        states["sensor.soc"] = "0"
        render(tile(), card)
        settle(card)
        assertEquals(0, fill(card).width)
        assertEquals(Color.parseColor("#80FF3333"), (fill(card).background as ColorDrawable).color)
    }

    @Test fun repeatedSameTargetFinishesAtExpectedWidth() {
        states["sensor.soc"] = "50"
        val card = card()
        render(tile(), card)
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            layout(card, 200)
            render(tile(), card)
        }
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        layout(card, 200)
        assertEquals(100, fill(card).width)
    }

    @Test fun regressionMultipleUpdatesBeforeLayoutUseLatestValue() {
        val card = card(width = 0)
        states["sensor.soc"] = "20"
        render(tile(), card)
        states["sensor.soc"] = "75"
        render(tile(), card)
        layout(card, 200)
        settle(card)
        assertEquals(150, fill(card).width)
    }
}
