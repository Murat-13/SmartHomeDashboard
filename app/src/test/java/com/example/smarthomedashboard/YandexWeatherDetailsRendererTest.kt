package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class YandexWeatherDetailsRendererTest {
    // These expectations passed against the original Activity method before extraction.
    private val context = RuntimeEnvironment.getApplication()
    private fun manager() = YandexWeatherManager(context)

    private fun render(container: LinearLayout, manager: YandexWeatherManager) {
        YandexWeatherDetailsRenderer.render(context, container, manager) {}
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun populatedManager() = manager().apply {
        currentTemp = "7"
        currentState = "clear"
        humidity = "60"
        pressure = "750"
        windSpeed = "3.5"
        windDir = "СЗ"
        hourlyForecasts = listOf(
            YandexWeatherManager.ForecastHour("9", "6", "rain"),
            YandexWeatherManager.ForecastHour("10", "7", "clear"))
        forecasts = listOf(
            YandexWeatherManager.ForecastDay("2026-10-26", "8", "2", "clear"),
            YandexWeatherManager.ForecastDay("bad-date", "6", "-1", "rain"))
    }

    @Test fun emptyForecastKeepsCurrentWeatherAndDetailsWithoutForecastSections() {
        val container = LinearLayout(context).apply {
            addView(TextView(context).apply { text = "old content" })
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(1, 2, 3, 4)
        }
        render(container, manager())
        assertEquals(2, container.childCount)
        assertTrue(container.getChildAt(0) is LinearLayout)
        assertTrue(container.getChildAt(1) is GridLayout)
        assertEquals(listOf("⛅", "Погода", "unknown", "—°C",
            "💧", "Влажность", "—%", "⏲️", "Давление", "— мм",
            "🌬️", "Ветер", "— м/с", "🧭", "Напр.", "—"), texts(container))
        assertEquals(LinearLayout.VERTICAL, container.orientation)
        assertEquals(Gravity.TOP or Gravity.START, container.gravity)
        assertEquals(listOf(30, 30, 30, 30), listOf(container.paddingLeft, container.paddingTop,
            container.paddingRight, container.paddingBottom))
    }

    @Test fun fullForecastPreservesSectionsOrderingValuesAndKeyViewProperties() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val container = LinearLayout(context)
            render(container, populatedManager())
            assertEquals(6, container.childCount)
            assertEquals(listOf("☀️", "Погода", "Ясно", "7°C",
                "💧", "Влажность", "60%", "⏲️", "Давление", "750 мм",
                "🌬️", "Ветер", "3.5 м/с", "🧭", "Напр.", "СЗ",
                "Сегодня:", "9:00", "🌧️", "6°", "10:00", "☀️", "7°",
                "На неделю:", "MON 26", "☀️", "8°/2°", "bad-date", "🌧️", "6°/-1°"), texts(container))

            val top = container.getChildAt(0) as LinearLayout
            assertEquals(4, top.childCount)
            assertEquals(LinearLayout.HORIZONTAL, top.orientation)
            assertEquals(Gravity.CENTER_VERTICAL or Gravity.START, top.gravity)
            val title = (top.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
            assertEquals(Color.WHITE, title.currentTextColor)
            assertEquals(Typeface.BOLD, title.typeface.style)
            assertEquals(24f * context.resources.displayMetrics.scaledDensity, title.textSize, 0.01f)
            val spacer = top.getChildAt(2).layoutParams as LinearLayout.LayoutParams
            assertEquals(0, spacer.width)
            assertEquals(1, spacer.height)
            assertEquals(1f, spacer.weight, 0f)
            assertEquals(2, (container.getChildAt(1) as GridLayout).columnCount)

            assertTrue(container.getChildAt(2) is TextView)
            assertTrue(container.getChildAt(4) is TextView)
            for (index in listOf(3, 5)) {
                val scroll = container.getChildAt(index) as HorizontalScrollView
                assertTrue(scroll.isFillViewport)
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, scroll.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, scroll.layoutParams.height)
                assertEquals(1, scroll.childCount)
                val row = scroll.getChildAt(0) as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL, row.orientation)
                assertEquals(2, row.childCount)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun rebuildingReadsUpdatedValuesAndReplacesOldViewsAndForecasts() {
        val manager = populatedManager()
        val container = LinearLayout(context)
        render(container, manager)
        val oldTop = container.getChildAt(0)
        manager.currentTemp = "-3"
        manager.currentState = "snow"
        manager.humidity = "90"
        manager.hourlyForecasts = emptyList()
        manager.forecasts = emptyList()
        render(container, manager)
        assertEquals(2, container.childCount)
        assertNotSame(oldTop, container.getChildAt(0))
        assertNull(oldTop.parent)
        assertEquals(listOf("❄️", "Погода", "Снег", "-3°C",
            "💧", "Влажность", "90%", "⏲️", "Давление", "750 мм",
            "🌬️", "Ветер", "3.5 м/с", "🧭", "Напр.", "СЗ"), texts(container))
        render(container, manager)
        assertEquals(2, container.childCount)
        assertEquals(1, texts(container).count { it == "Погода" })
    }

    @Test fun touchingEitherForecastScrollInvokesCollapseTimerCallback() {
        val container = LinearLayout(context)
        var touches = 0
        YandexWeatherDetailsRenderer.render(context, container, populatedManager()) { touches++ }
        for (index in listOf(3, 5)) {
            val scroll = container.getChildAt(index) as HorizontalScrollView
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            try {
                val before = touches
                scroll.dispatchTouchEvent(event)
                assertEquals(before + 1, touches)
            } finally {
                event.recycle()
            }
        }
    }

    @Test fun hourlyAndDailySectionsAreIndependentlyOptional() {
        val manager = populatedManager()
        val container = LinearLayout(context)
        manager.forecasts = emptyList()
        render(container, manager)
        assertEquals(4, container.childCount)
        assertEquals("Сегодня:", (container.getChildAt(2) as TextView).text.toString())
        assertTrue(container.getChildAt(3) is HorizontalScrollView)
        manager.hourlyForecasts = emptyList()
        manager.forecasts = listOf(YandexWeatherManager.ForecastDay("bad-date", "6", "-1", "rain"))
        render(container, manager)
        assertEquals(4, container.childCount)
        assertEquals("На неделю:", (container.getChildAt(2) as TextView).text.toString())
        assertEquals(listOf("bad-date", "🌧️", "6°/-1°"), texts(container.getChildAt(3)))
    }
}
