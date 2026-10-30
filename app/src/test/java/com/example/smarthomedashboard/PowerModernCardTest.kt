package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.Color
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

// Настоящие Views и аниматоры в окне Activity; время виртуальное. Сеть и HA не используются.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernCardTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val config = PowerModernConfig.profile(PowerModernConfig.PROFILE_GRID).let { c ->
        c.withRow(c.row("voltage")!!.copy(entityId = "sensor.v"))
            .withRow(c.row("power")!!.copy(entityId = "sensor.p"))
            .withRow(c.row("frequency")!!.copy(entityId = "sensor.f"))
            .withRow(c.row("current")!!.copy(entityId = "sensor.i"))
    }
    private val states = mutableMapOf("sensor.v" to "227.43", "sensor.p" to "1930.45", "sensor.f" to "50.02", "sensor.i" to "8.39")
    private val attrs = mapOf("sensor.v" to unit("V"), "sensor.p" to unit("W"), "sensor.f" to unit("Hz"), "sensor.i" to unit("A"))
    private var session = HaSession(true, true)
    private val sep get() = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

    private fun unit(u: String) = JSONObject().put("unit_of_measurement", u)
    private fun readings() = BatteryReadings(session, states::get, { false }, attrs::get)

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

    private fun card(expanded: Boolean = false, w: Int = 300, h: Int = 360): PowerModernCard {
        val card = PowerModernCard(activity, expanded)
        root.addView(card, FrameLayout.LayoutParams(w, h))
        // Первый кадр окна прикрепляет карточку: анимации идут только у видимой прикреплённой View.
        advance(50)
        showWindow(card)
        assertTrue(card.isAttachedToWindow)
        return card
    }

    private fun visual(v: Double) = PowerVisual.preview(config, v)
    private fun text(view: View, tag: String) = view.findViewWithTag<TextView>(tag).text.toString()

    @Test fun compactRoundsOnlyForDisplayAndDigitsStayWhiteInEveryState() {
        val card = card()
        listOf(0.0, 150.0, 180.0, 195.0, 228.0, 245.0, 255.0, 285.0).forEach { v ->
            card.bind("Сеть · Вход", config, readings(), visual(v), animate = false)
            assertEquals(Color.WHITE, card.findViewWithTag<TextView>("pm_voltage").currentTextColor)
            assertEquals(Color.WHITE, card.findViewWithTag<TextView>("pm_power").currentTextColor)
        }
        assertEquals("227", text(card, "pm_voltage"))
        assertEquals("В", text(card, "pm_voltage_unit"))
        assertEquals("1930", text(card, "pm_power"))
        assertEquals("Вт", text(card, "pm_power_unit"))
        assertEquals("50${sep}0 Гц", text(card, "pm_frequency"))
        assertEquals("Исходное значение HA не меняется", "227.43", states["sensor.v"])
        states["sensor.p"] = "-12.6"
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        assertEquals("Знак мощности сохраняется", "-13", text(card, "pm_power"))
        states["sensor.p"] = "-0.2"
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        assertEquals("0", text(card, "pm_power"))
    }

    @Test fun expandedShowsSourcePrecisionWithoutArtificialDigitsAndDiagnostics() {
        val card = card(expanded = true, w = 1200, h = 560)
        card.bind("Сеть · Вход", config, readings(), visual(227.43), animate = false)
        assertEquals("227${sep}4", text(card, "pm_voltage"))
        assertEquals("1930${sep}45", text(card, "pm_power"))
        assertEquals("50${sep}02 Гц", text(card, "pm_frequency"))
        assertEquals("8${sep}39 А", text(card, "pm_value_current"))
        assertEquals("—", text(card, "pm_value_apparent_power"))
        assertEquals("—", text(card, "pm_value_reactive_power"))
        assertEquals("Норма", text(card, "pm_value_status"))
        states["sensor.v"] = "228"
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        assertEquals("Без ложной десятичной", "228", text(card, "pm_voltage"))
        assertNotNull(card.findViewWithTag<View>("pm_scroll"))
    }

    @Test fun extraRowShowsChosenIconAndChangesItInPlace() {
        val card = card(expanded = true, w = 1200, h = 560)
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        val row = card.findViewWithTag<View>("pm_row_current")
        val value = card.findViewWithTag<TextView>("pm_value_current")
        val icon = card.findViewWithTag<TextView>("pm_icon_current")
        assertEquals("Иконка шаблона «Ток»", BatteryIcons.glyph("current"), icon.text.toString())
        assertEquals(PowerModernConfig.EMERALD, icon.currentTextColor)
        // Смена иконки видна сразу и не пересоздаёт строку.
        val changed = config.withRow(config.row("current")!!.copy(icon = "cycles"))
        card.bind("Сеть · Вход", changed, readings(), visual(228.0), animate = false)
        assertSame(row, card.findViewWithTag<View>("pm_row_current"))
        assertSame(icon, card.findViewWithTag<TextView>("pm_icon_current"))
        assertEquals(BatteryIcons.glyph("cycles"), icon.text.toString())
        // Новые показания обновляют значение в тех же View.
        states["sensor.i"] = "9.5"
        card.bind("Сеть · Вход", changed, readings(), visual(285.0), animate = false)
        assertSame(row, card.findViewWithTag<View>("pm_row_current"))
        assertSame(value, card.findViewWithTag<TextView>("pm_value_current"))
        assertEquals("9${sep}5 А", value.text.toString())
        assertEquals("Цвет иконки следует за состоянием", PowerModernConfig.RED, icon.currentTextColor)
        // У каждой строки шаблона своя иконка.
        changed.extras.forEach { r -> assertEquals(r.id, BatteryIcons.glyph(r.icon), text(card, "pm_icon_${r.id}")) }
    }

    @Test fun missingPowerOrFrequencyShowDashWhileVoltageKeepsColor() {
        val card = card()
        val noPower = config.withRow(config.row("power")!!.copy(entityId = ""))
            .withRow(config.row("frequency")!!.copy(entityId = ""))
        card.bind("Сеть · Вход", noPower, readings(), visual(228.0), animate = false)
        assertEquals("227", text(card, "pm_voltage"))
        assertEquals("—", text(card, "pm_power"))
        assertEquals("Вт", text(card, "pm_power_unit"))
        assertEquals("— Гц", text(card, "pm_frequency"))
        assertEquals(PowerModernConfig.EMERALD, card.accent)
        states["sensor.p"] = "unavailable"
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        assertEquals("—", text(card, "pm_power"))
    }

    @Test fun missingVoltageOrConnectionIsNeutralWithDashesAndNoPulse() {
        val card = card()
        val r = readings()
        val noVoltage = config.withRow(config.row("voltage")!!.copy(entityId = ""))
        val v = PowerVisual.of(noVoltage, r.of(""), r, null)
        card.bind("Сеть · Вход", noVoltage, r, v, animate = false)
        assertEquals("—", text(card, "pm_voltage"))
        assertEquals("1930", text(card, "pm_power"))
        assertEquals(PowerModernConfig.NEUTRAL, card.accent)
        assertFalse(card.pulseRequested)
        session = HaSession()
        val offline = readings()
        card.bind("Сеть · Вход", config, offline, PowerVisual.of(config, offline.of("sensor.v"), offline, 0), animate = true)
        assertEquals("Нет связи с Home Assistant", text(card, "pm_status"))
        assertEquals("—", text(card, "pm_voltage"))
        assertEquals("—", text(card, "pm_power"))
        assertFalse(card.pulseRequested)
        advance(600)
        assertEquals(PowerModernConfig.NEUTRAL, card.accent)
    }

    @Test fun colorTransitionIsSmoothAndPulseIsSoftAndReleased() {
        val card = card()
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        assertEquals(PowerModernConfig.EMERALD, card.accent)
        card.bind("Сеть · Вход", config, readings(), visual(285.0), animate = true)
        assertTrue("Переход цвета анимирован", card.isColorAnimating)
        // Пульсация видимой карточки стартует сразу; выборки — в первых двух полупериодах (1 с):
        // Robolectric анимирует INFINITE как один повтор, сам бесконечный повтор проверяется ниже.
        assertTrue(card.pulsing)
        assertEquals("Бесконечная мягкая пульсация ~1 Гц", android.animation.ValueAnimator.INFINITE,
            shadowOf(card.pulseAnimatorForTest!!).actualRepeatCount)
        assertEquals(android.animation.ValueAnimator.REVERSE, card.pulseAnimatorForTest!!.repeatMode)
        assertEquals(500L, card.pulseAnimatorForTest!!.duration)
        val size = card.findViewWithTag<TextView>("pm_voltage").textSize
        val levels = (1..8).map { step ->
            advance(125)
            if (step == 2) {
                assertNotEquals(PowerModernConfig.EMERALD, card.accent)
                assertNotEquals(PowerModernConfig.RED, card.accent)
            }
            assertEquals("Цифры не меняются при пульсации", size, card.findViewWithTag<TextView>("pm_voltage").textSize, 0f)
            card.glowLevel
        }
        assertEquals(PowerModernConfig.RED, card.accent)
        assertTrue("Яркость меняется мягко: $levels", levels.max() - levels.min() > 0.3f && levels.min() >= 0.45f - 0.01f)
        // Возврат в норму: пульсация останавливается, свечение полное.
        card.bind("Сеть · Вход", config, readings(), visual(228.0), animate = true)
        assertFalse(card.pulsing)
        assertEquals(1f, card.glowLevel, 0f)
        // Отсоединение и dispose освобождают аниматоры.
        card.bind("Сеть · Вход", config, readings(), visual(0.0), animate = true)
        root.removeView(card)
        assertFalse(card.pulsing)
        assertFalse(card.isColorAnimating)
        root.addView(card)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Повторное присоединение возобновляет нужную пульсацию", card.pulsing)
        card.dispose()
        assertFalse(card.pulsing)
        assertFalse(card.pulseRequested)
    }

    @Test fun smallTileHidesFrequencyButKeepsMainNumbers() {
        val small = card(w = 180, h = 200)
        small.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        advance(50)
        assertEquals(View.GONE, small.findViewWithTag<View>("pm_frequency_box").visibility)
        assertEquals("227", text(small, "pm_voltage"))
        val normal = card()
        normal.bind("Сеть · Вход", config, readings(), visual(228.0), animate = false)
        advance(50)
        assertEquals(View.VISIBLE, normal.findViewWithTag<View>("pm_frequency_box").visibility)
    }

    @Test fun inverterProfileUsesOwnIconTitleAndCyanNorm() {
        val inverter = PowerModernConfig.profile(PowerModernConfig.PROFILE_INVERTER)
        val card = card()
        card.bind("", inverter, readings(), PowerVisual.preview(inverter, 230.0), animate = false)
        assertEquals("Инвертор · Выход", text(card, "pm_title"))
        assertEquals(PowerModernConfig.CYAN, card.accent)
        assertNotNull(card.findViewWithTag<android.widget.ImageView>("pm_icon").drawable)
        assertEquals("Нет напряжения на выходе", PowerVisual.preview(inverter, 0.0).status)
    }
}
