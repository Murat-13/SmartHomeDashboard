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

/** Пульсация и переход цвета останавливаются у скрытой карточки (родитель, окно, раскрытие) и возобновляются у видимой. Настоящие трекер, карточка и раскрытие. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernHiddenCardTest {
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



    private fun pulsingCard(parent: FrameLayout = root): PowerModernCard {
        val card = PowerModernCard(activity, expanded = false)
        parent.addView(card, FrameLayout.LayoutParams(300, 360))
        advance(50) // первый кадр окна прикрепляет View
        showWindow(root)
        card.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 0.0), animate = false)
        assertTrue(card.pulsing)
        return card
    }

    @Test fun hiddenParentOrWindowStopsPulseAndShowingResumesIt() {
        val holder = FrameLayout(activity)
        root.addView(holder)
        val card = pulsingCard(holder)
        holder.visibility = View.INVISIBLE
        assertFalse("Скрытый родитель — анимация остановлена", card.pulsing)
        assertTrue("Подтверждённое состояние не сброшено", card.pulseRequested)
        holder.visibility = View.VISIBLE
        assertTrue(card.pulsing)
        root.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse("Скрытое окно — анимация остановлена", card.pulsing)
        root.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertTrue(card.pulsing)
        // Пока карточка скрыта, новые данные обновляют цифры, а цвет применяется без скрытой анимации.
        holder.visibility = View.GONE
        states["sensor.v"] = "228"
        card.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 228.0), animate = true)
        assertEquals("228", card.findViewWithTag<TextView>("pm_voltage").text.toString())
        assertFalse(card.isColorAnimating)
        assertEquals(PowerModernConfig.EMERALD, card.accent)
        assertFalse(card.pulsing)
    }

    @Test fun compactCardUnderExpandedOverlayDoesNotAnimateAndResumesAfterCollapse() {
        val panel = FrameLayout(activity)
        root.addView(panel, FrameLayout.LayoutParams(1920, 1200))
        val (tile, _) = PowerModernCard.createTile(activity, "grid", 300, 360, false)
        tile.x = 40f
        tile.y = 60f
        panel.addView(tile)
        advance(50) // первый кадр окна прикрепляет View
        showWindow(root)
        val compact = tile.findViewWithTag<PowerModernCard>("pm_compact")
        compact.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 285.0), animate = false)
        assertTrue(compact.pulsing)
        val expansion = BatteryModernExpansion(Handler(Looper.getMainLooper()), { root }, { panel }, blurSupported = false)
        val expanded = PowerModernCard(activity, expanded = true)
        expansion.expand("grid", tile, expanded, BatteryModernExpansion.Bounds(100f, 50f, 1200, 560))
        expanded.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 285.0), animate = false)
        advance(400)
        assertEquals(0f, tile.alpha, 0f)
        assertFalse("Скрытая исходная карточка не анимируется", compact.pulsing)
        assertTrue("Подтверждённое состояние сохранено", compact.pulseRequested)
        assertTrue("Видимая раскрытая карточка пульсирует", expanded.pulsing)
        // События HA продолжают обновлять скрытую карточку.
        states["sensor.v"] = "290"
        compact.bind("Сеть · Вход", grid, readings(), PowerVisual.preview(grid, 290.0), animate = true)
        assertEquals("290", compact.findViewWithTag<TextView>("pm_voltage").text.toString())
        assertFalse(compact.pulsing)
        expansion.collapse()
        advance(400)
        assertEquals(1f, tile.alpha, 0f)
        assertTrue("После закрытия видимая карточка снова пульсирует", compact.pulsing)
        assertFalse("Раскрытая карточка освобождена", expanded.pulsing)
        assertNull(expanded.parent)
        // Немедленная очистка тоже возвращает исходную карточку в рабочее состояние.
        expansion.expand("grid", tile, PowerModernCard(activity, expanded = true), BatteryModernExpansion.Bounds(100f, 50f, 1200, 560))
        advance(100)
        assertFalse(compact.pulsing)
        expansion.clear()
        assertTrue(compact.pulsing)
        compact.dispose()
        assertFalse(compact.pulsing)
    }

}
