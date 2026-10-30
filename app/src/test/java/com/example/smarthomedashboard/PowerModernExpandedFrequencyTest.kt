package com.example.smarthomedashboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Частота в развёрнутом виде: точность источника, не больше 2 знаков, явный выбор сохраняется. Настоящие трекер, карточка и раскрытие. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernExpandedFrequencyTest {
    private val grid = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID).let { c ->
        c.withRow(c.row("voltage")!!.copy(entityId = "sensor.v")).withRow(c.row("frequency")!!.copy(entityId = "sensor.f"))
    }
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val states = mutableMapOf("sensor.v" to "228", "sensor.f" to "50.0")
    private fun readings() = BatteryReadings(HaSession(true, true), states::get, { false },
        { id -> JSONObject().put("unit_of_measurement", if (id == "sensor.f") "Hz" else "V") })

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    /** Robolectric держит окно Activity невидимым; делаем его видимым так же, как система (dispatchAppVisibility). */
    private fun showWindow(view: View) {
        val viewRoot = view.rootView.parent ?: return
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(viewRoot, "dispatchAppVisibility",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        advance(50)
    }

    /** Подтверждает [v] с нуля и возвращает время, когда диапазон стал подтверждённым. */
    private fun confirmed(tracker: PowerStateTracker, v: Double): Long {
        tracker.update(v, grid, 0)
        assertEquals(grid.ruleFor(v), tracker.update(v, grid, 1_800))
        return 1_800
    }

    private fun pulse(tracker: PowerStateTracker) = grid.rules.getOrNull(tracker.confirmed ?: -1)?.pulse == true



    @Test fun expandedFrequencyUsesSourcePrecisionUpToTwoDigitsAndKeepsExplicitChoice() {
        val sep = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
        val card = PowerModernCard(activity, expanded = true)
        root.addView(card, FrameLayout.LayoutParams(1200, 560))
        advance(50) // первый кадр окна прикрепляет View
        showWindow(root)
        fun freq(raw: String, config: PowerModernConfig = grid): String {
            states["sensor.f"] = raw
            card.bind("Сеть · Вход", config, readings(), PowerVisual.preview(config, 228.0), animate = false)
            return card.findViewWithTag<TextView>("pm_frequency").text.toString()
        }
        assertEquals("50 Гц", freq("50"))
        assertEquals("50${sep}1 Гц", freq("50.1"))
        assertEquals("50${sep}12 Гц", freq("50.12345"))
        assertEquals("49${sep}99 Гц", freq("49.987"))
        val explicit = grid.withRow(grid.row("frequency")!!.copy(decimals = 3))
        assertEquals("Явный выбор пользователя сохраняется", "50${sep}123 Гц", freq("50.12345", explicit))
        val zero = grid.withRow(grid.row("frequency")!!.copy(decimals = 0))
        assertEquals("50 Гц", freq("50.12345", zero))
        // Компактный вид — по-прежнему один знак.
        val compact = PowerModernCard(activity, expanded = false)
        root.addView(compact, FrameLayout.LayoutParams(300, 360))
        states["sensor.f"] = "50.12345"
        compact.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 228.0), animate = false)
        assertEquals("50${sep}1 Гц", compact.findViewWithTag<TextView>("pm_frequency").text.toString())
    }
}
