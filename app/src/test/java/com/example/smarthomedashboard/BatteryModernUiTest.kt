package com.example.smarthomedashboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
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

// Настоящие Views, ValueAnimator и Handler; время виртуальное. Сеть и команды не используются.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BatteryModernUiTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: FrameLayout
    private lateinit var source: View
    private lateinit var expansion: BatteryModernExpansion
    private lateinit var input: SensorWindowTouchHandler
    private val handler = Handler(Looper.getMainLooper())
    private val target = BatteryModernExpansion.Bounds(100f, 50f, 1000, 800)

    private val live = HaSession(connected = true, synced = true)
    private val states = mutableMapOf("sensor.soc" to "89", "sensor.power" to "-407.3", "sensor.v" to "51.2")
    private val attrs = mutableMapOf("sensor.power" to JSONObject().put("unit_of_measurement", "W"),
        "sensor.v" to JSONObject().put("unit_of_measurement", "V"))
    private var session = live
    private fun readings() = BatteryReadings(session, states::get, { false }, attrs::get)

    private val config = BatteryModernConfig().let { c ->
        c.withRow(c.row("soc")!!.copy(entityId = "sensor.soc"))
            .let { it.withRow(it.row("power")!!.copy(entityId = "sensor.power")) }
            .let { it.withRow(it.row("voltage")!!.copy(entityId = "sensor.v")) }
    }

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        panel = FrameLayout(activity)
        root.addView(panel, FrameLayout.LayoutParams(1200, 900))
        source = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(300, 220)
            x = 40f
            y = 60f
        }
        panel.addView(source)
        activity.setContentView(root, android.view.ViewGroup.LayoutParams(1200, 900))
        expansion = BatteryModernExpansion(handler, { root }, { panel }, blurSupported = false)
        input = SensorWindowTouchHandler({ expansion.card?.takeIf { expansion.isOpen } }, { it.parent != null },
            expansion::touch, expansion::clear, expansion::collapse, 16)
    }

    @After fun tearDown() {
        expansion.clear()
    }

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
    }

    private fun card(expanded: Boolean = true, editor: BatteryModernCard.Editor? = null) =
        BatteryModernCard(activity, expanded, editor).apply { bind("АКБ", config, readings(), "C") }

    private fun open(): BatteryModernCard {
        val card = card()
        expansion.expand("m", source, card, target)
        advance(400)
        return card
    }

    private fun gesture(vararg points: Pair<Float, Float>, holdMs: Long = 50) {
        val start = SystemClock.uptimeMillis()
        points.forEachIndexed { index, (x, y) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val time = if (index == points.lastIndex) start + holdMs else start + index
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            try { assertEquals(true, input.dispatch(event)) } finally { event.recycle() }
        }
    }

    private fun text(view: View, tag: String) = view.findViewWithTag<TextView>(tag).text.toString()

    @Test fun cardShowsValuesDirectionAndNeutralStateWhenDisconnected() {
        val card = card(expanded = false)
        assertEquals("89", text(card, "bm_soc"))
        assertEquals("-407,3".replace(',', java.text.DecimalFormatSymbols.getInstance().decimalSeparator), text(card, "bm_power"))
        assertEquals("↓ Разряд", text(card, "bm_flow"))
        assertEquals(0.89f, card.findViewWithTag<BatterySegmentBar>("bm_bar").fraction!!, 0.001f)
        assertEquals(View.GONE, card.findViewWithTag<View>("bm_status").visibility)

        session = HaSession()
        card.bind("АКБ", config, readings(), "C")
        assertEquals("—", text(card, "bm_soc"))
        assertEquals("—", text(card, "bm_power"))
        assertEquals("— Нет данных", text(card, "bm_flow"))
        assertEquals("—", text(card, "bm_metric_voltage"))
        assertNull("Шкала нейтральна", card.findViewWithTag<BatterySegmentBar>("bm_bar").fraction)
        assertEquals("Нет связи с Home Assistant", text(card, "bm_status"))
        assertEquals("Хранилище состояний не очищается", "89", states["sensor.soc"])

        session = live
        states["sensor.power"] = "1381"
        card.bind("АКБ", config.copy(invertPowerSign = true), readings(), "C")
        assertEquals("↓ Разряд", text(card, "bm_flow"))
        card.bind("АКБ", config, readings(), "C")
        assertEquals("↑ Заряд", text(card, "bm_flow"))
        states["sensor.power"] = "3"
        card.bind("АКБ", config, readings(), "C")
        assertEquals("■ Ожидание", text(card, "bm_flow"))
    }

    @Test fun expandedListShowsAllTemplateRowsAndHidesUnconfiguredOnRequest() {
        val card = card()
        BatteryModernConfig().rows.forEach { assertNotNull(it.id, card.findViewWithTag<View>("bm_row_${it.id}")) }
        assertEquals("—", text(card, "bm_value_cycles"))
        card.bind("АКБ", config.copy(hideUnconfigured = true), readings(), "C")
        assertNull(card.findViewWithTag<View>("bm_row_cycles"))
        assertNotNull(card.findViewWithTag<View>("bm_row_power"))
        card.bind("АКБ", config.let { it.withRow(it.row("power")!!.copy(hidden = true)) }, readings(), "C")
        assertNull(card.findViewWithTag<View>("bm_row_power"))
    }

    @Test fun editorTouchOpensMatchingIndicatorButNormalCardHasNoEditorHandlers() {
        val calls = mutableListOf<String>()
        val editor = object : BatteryModernCard.Editor {
            override fun editRole(role: String) { calls += "role:$role" }
            override fun editRow(id: String) { calls += "row:$id" }
            override fun editHeader() { calls += "header" }
        }
        val card = card(editor = editor)
        fun zone(tag: String): View {
            var view: View = card.findViewWithTag(tag)
            while (!view.hasOnClickListeners()) view = view.parent as View
            return view
        }
        zone("bm_soc").performClick()
        zone("bm_power").performClick()
        listOf("runtime", "voltage", "temperature").forEach { zone("bm_metric_$it").performClick() }
        BatteryModernConfig().rows.forEach { card.findViewWithTag<View>("bm_row_${it.id}").performClick() }
        assertEquals(listOf("role:soc", "role:power", "role:runtime", "role:voltage", "role:temperature") +
            BatteryModernConfig().rows.map { "row:${it.id}" }, calls)

        val normal = card()
        fun anyClick(view: View): Boolean = view.hasOnClickListeners() ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { anyClick(view.getChildAt(it)) })
        assertFalse("В обычном режиме касания не открывают редактор", anyClick(normal))
    }

    @Test fun expandsOverPanelAndCollapsesToSavedBounds() {
        val card = open()
        assertSame(root, card.parent)
        assertEquals(0f, source.alpha, 0f)
        assertNotNull(root.findViewWithTag<View>("bm_scrim"))
        assertEquals(target.x, card.x, 0.5f)
        assertEquals(1f, card.scaleX, 0.001f)
        expansion.collapse()
        assertFalse(expansion.isOpen)
        advance(400)
        assertNull(card.parent)
        assertNull(root.findViewWithTag<View>("bm_scrim"))
        assertEquals(1f, source.alpha, 0f)
        assertEquals(40f, source.x, 0f)
        assertEquals(300, source.layoutParams.width)
        assertNull(expansion.card)
    }

    @Test fun autoCollapsesAfterThirtySecondsOfInactivityAndHaEventsDoNotResetIt() {
        val card = open()
        val deadline = expansion.collapseAt
        advance(20_000)
        states["sensor.power"] = "-500"
        card.bind("АКБ", config, readings(), "C") // событие HA только перерисовывает
        assertEquals(deadline, expansion.collapseAt)
        advance(9_000)
        assertTrue(expansion.isOpen)
        advance(1_500)
        assertFalse(expansion.isOpen)
        advance(400)
        assertNull(card.parent)
    }

    @Test fun userTouchAndScrollRestartTheTimer() {
        open()
        advance(20_000)
        val card = expansion.card!!
        val location = IntArray(2).also { card.getLocationInWindow(it) }
        val x = location[0] + 400f
        val y = location[1] + 600f
        // Прокрутка: движение больше порога, даже с возвратом к началу, не закрывает карточку.
        gesture(x to y, x to y - 60f, x to y - 120f, x to y)
        assertTrue(expansion.isOpen)
        advance(25_000)
        assertTrue("Таймер начат заново после прокрутки", expansion.isOpen)
        advance(6_000)
        assertFalse(expansion.isOpen)
    }

    @Test fun smallFingerMovementIsTapAndLongHoldIsNot() {
        open()
        val card = expansion.card!!
        val location = IntArray(2).also { card.getLocationInWindow(it) }
        val x = location[0] + 300f
        val y = location[1] + 300f
        gesture(x to y, x + 3f to y + 2f, x + 1f to y, holdMs = 1_000)
        assertTrue("Долгое удержание не считается тапом", expansion.isOpen)
        gesture(x to y, x + 3f to y + 2f, x + 1f to y)
        assertFalse("Тап по содержимому закрывает", expansion.isOpen)
        advance(400)
        open()
        gesture(5f to 5f, 6f to 5f) // тап по свободному месту вне карточки
        assertFalse(expansion.isOpen)
    }

    @Test fun pauseStopsTimerDuringEditingAndResumeRestartsIt() {
        open()
        advance(10_000)
        expansion.pause()
        assertNull(expansion.collapseAt)
        expansion.touch()
        assertNull("Касания во время паузы не перезапускают отсчёт", expansion.collapseAt)
        advance(60_000)
        assertTrue(expansion.isOpen)
        expansion.resume()
        advance(29_000)
        assertTrue(expansion.isOpen)
        advance(1_500)
        assertFalse(expansion.isOpen)
    }

    @Test fun clearReleasesOverlayTimersAnimationsAndHandlers() {
        val calls = mutableListOf<String>()
        val editorCard = BatteryModernCard(activity, true, object : BatteryModernCard.Editor {
            override fun editRole(role: String) { calls += role }
            override fun editRow(id: String) { calls += id }
            override fun editHeader() { calls += "header" }
        })
        expansion.expand("m", source, editorCard, target)
        advance(32) // посреди анимации открытия
        expansion.clear()
        assertNull(expansion.card)
        assertNull(expansion.collapseAt)
        assertEquals(1, root.childCount)
        assertEquals(1f, source.alpha, 0f)
        fun anyClick(view: View): Boolean = view.hasOnClickListeners() ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { anyClick(view.getChildAt(it)) })
        assertFalse("Обработчики освобождены", anyClick(editorCard))
        advance(60_000)
        assertTrue(calls.isEmpty())
        // Повторные циклы не накапливают Views overlay.
        repeat(50) {
            expansion.expand("m", source, card(), target)
            advance(48)
            if (it % 2 == 0) expansion.collapse() else expansion.clear()
            advance(300)
        }
        assertEquals(1, root.childCount)
        assertNull(expansion.card)
    }
}
