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

/** Границы пульсации при гистерезисе: 150/151 и 279/280 В, колебания, пользовательские флаги. Настоящие трекер, карточка и раскрытие. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernPulseBoundaryTest {
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



    @Test fun steady280AfterConfirmed279StartsPulseAfterConfirmation() {
        val tracker = PowerStateTracker()
        var t = confirmed(tracker, 279.0)
        assertFalse(pulse(tracker))
        t += 100
        tracker.update(280.0, grid, t)
        assertFalse("До подтверждения пульсации нет", pulse(tracker))
        tracker.update(280.0, grid, t + 1_799)
        assertFalse(pulse(tracker))
        tracker.update(280.0, grid, t + 1_800)
        assertTrue("Устойчивые 280 В через 1,8 с пульсируют", pulse(tracker))
        assertEquals("Опасно высокое напряжение", grid.rules[tracker.confirmed!!].label)
        // Возврат на 279 В: выход из критического интервала тоже только с подтверждением.
        tracker.update(279.0, grid, t + 2_000)
        assertTrue(pulse(tracker))
        tracker.update(279.0, grid, t + 3_800)
        assertFalse("Вне [280; ∞) пульсация не остаётся", pulse(tracker))
    }

    @Test fun steady151AfterConfirmed150StopsPulseAndReturnTo150Restarts() {
        val tracker = PowerStateTracker()
        var t = confirmed(tracker, 150.0)
        assertTrue(pulse(tracker))
        t += 100
        tracker.update(151.0, grid, t)
        tracker.update(151.0, grid, t + 1_800)
        assertFalse("151 В вне критической зоны (0; 150]", pulse(tracker))
        assertEquals("Низкое напряжение", grid.rules[tracker.confirmed!!].label)
        tracker.update(150.0, grid, t + 2_000)
        tracker.update(150.0, grid, t + 3_800)
        assertTrue(pulse(tracker))
    }

    @Test fun boundaryOscillationDoesNotFlickerAndSettlesOnSteadySide() {
        // 279/280 по очереди: устойчивых 1,8 с в критической зоне нет — пульсации нет, мерцания нет.
        val high = PowerStateTracker()
        var t = confirmed(high, 279.0)
        repeat(30) { i ->
            t += 500
            high.update(if (i % 2 == 0) 280.0 else 279.0, grid, t)
            assertFalse("шаг $i", pulse(high))
        }
        // 150/151 по очереди: напряжение постоянно возвращается в критическую зону — пульсация сохраняется
        // без переключений; устойчивые 151 В выключают её после подтверждения.
        val low = PowerStateTracker()
        t = confirmed(low, 150.0)
        repeat(30) { i ->
            t += 500
            low.update(if (i % 2 == 0) 151.0 else 150.0, grid, t)
            assertTrue("шаг $i", pulse(low))
        }
        low.update(151.0, grid, t + 500)
        low.update(151.0, grid, t + 2_300)
        assertFalse(pulse(low))
    }

    @Test fun ordinaryColorTransitionsKeepHysteresisAndCustomPulseFlagsWork() {
        val tracker = PowerStateTracker()
        val t = confirmed(tracker, 228.0)
        // 240,5 В — за границей нормы меньше чем на 2 В: жёлтый не включается (гистерезис сохранён).
        tracker.update(240.5, grid, t + 100)
        tracker.update(240.5, grid, t + 10_000)
        assertEquals("Норма", grid.rules[tracker.confirmed!!].label)
        // Пользовательское правило с пульсацией: граница пульсации тоже без гистерезиса.
        val custom = grid.copy(rules = grid.rules.map { if (it.label == "Повышенное напряжение") it.copy(pulse = true) else it })
        val user = PowerStateTracker()
        user.update(239.0, custom, 0)
        user.update(239.0, custom, 1_800)
        user.update(240.0, custom, 2_000)
        user.update(240.0, custom, 3_800)
        assertTrue(custom.rules[user.confirmed!!].pulse)
    }

    @Test fun cardFollowsTrackerAtBothPulseBoundaries() {
        val card = PowerModernCard(activity, expanded = false)
        root.addView(card, FrameLayout.LayoutParams(300, 360))
        advance(50) // первый кадр окна прикрепляет View
        showWindow(root)
        val tracker = PowerStateTracker()
        fun show(v: Double, now: Long) {
            states["sensor.v"] = v.toString()
            val r = readings()
            val reading = r.of("sensor.v")
            card.bind("Сеть · Вход", grid, r, PowerVisual.of(grid, reading, r, tracker.update(v, grid, now)), animate = true)
            assertEquals("Цифры сразу", v.toInt().toString(), card.findViewWithTag<TextView>("pm_voltage").text.toString())
        }
        show(279.0, 0); show(279.0, 1_800)
        show(280.0, 2_000)
        assertFalse(card.pulseRequested)
        show(280.0, 3_800)
        assertTrue(card.pulseRequested)
        assertTrue(card.pulsing)
        show(151.0, 4_000); show(151.0, 5_800)
        assertFalse("151 В: не пульсирует", card.pulseRequested)
        assertFalse(card.pulsing)
    }
}
