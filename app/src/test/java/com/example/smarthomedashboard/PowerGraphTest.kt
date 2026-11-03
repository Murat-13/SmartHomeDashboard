package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** График мощности: просмотр (период, сдвиг, масштаб, «Сейчас»), жесты, статистика периода, снимки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PowerGraphTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = PowerHistoryStore(context, name = null)
    private val zone = TimeZone.getTimeZone("Europe/Moscow")
    /** 14:50 по Москве. */
    private var now = 1_793_706_600_000L
    private val recorder = PowerHistoryRecorder(store, io = { it.run() }, post = { it() }, clock = { now })
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val hour = PowerHistory.HOUR_MS

    @After fun close() = store.close()

    /** Час данных: потребление с провалом в отдачу и разрывом связи 3 минуты. */
    private fun seed(entity: String = "sensor.inv"): List<PowerSample> {
        recorder.setSources(setOf(entity))
        recorder.setInstance("home")
        val source = store.sourceId("home", entity)!!
        val start = now - hour
        val samples = (0 until 360).map { i ->
            val ts = start + i * 10_000L
            val minute = i / 6
            val v = when {
                minute in 20..22 -> null
                minute in 14..18 -> -300.0 - minute * 20
                else -> 600.0 + 900 * kotlin.math.sin(i / 25.0) + 400 * kotlin.math.sin(i / 7.0)
            }
            PowerSample(ts, v)
        }
        store.insert(source, samples)
        return samples
    }

    private fun panel(state: PowerGraphState = PowerGraphState(GraphPeriod.HOUR, zone).also { it.reset(now) }): PowerGraphPanel {
        val panel = PowerGraphPanel(context, recorder, "tile1", state, prefs)
        panel.clock = { now }
        panel.bind("sensor.inv")
        return panel
    }

    private fun attach(view: View, width: Int, height: Int): FrameLayout {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(view, FrameLayout.LayoutParams(width, height))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        return root
    }

    private fun render(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(9, 24, 38))
        view.draw(canvas)
        val file = File("build/reports/power-graph/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun touch(view: View, action: Int, x: Float, time: Long) {
        val e = MotionEvent.obtain(0, time, action, x, view.height / 2f, 0)
        try { view.dispatchTouchEvent(e) } finally { e.recycle() }
    }

    @Test fun firstUseIsOneHourAndChosenPeriodIsRememberedPerCard() {
        prefs.edit().clear().commit()
        assertEquals(GraphPeriod.HOUR, PowerGraphPanel.savedPeriod(prefs, "tile1"))
        val p = panel()
        attach(p, 900, 500)
        p.findViewWithTag<TextView>("pg_period_day").performClick()
        assertEquals(GraphPeriod.DAY, PowerGraphPanel.savedPeriod(prefs, "tile1"))
        assertEquals(GraphPeriod.HOUR, PowerGraphPanel.savedPeriod(prefs, "tile2"))
    }

    @Test fun panIntoPastStopsFollowingTimeAndNowReturns() {
        val state = PowerGraphState(GraphPeriod.HOUR, zone).also { it.reset(now) }
        state.pan(-20 * 60_000L, now)
        assertFalse(state.live)
        val end = state.end(now)
        now += 60_000 // новые данные/время не сдвигают просматриваемый участок
        assertEquals(end, state.end(now))
        state.pan(+3 * hour, now)
        assertTrue(state.live)
        assertEquals(now, state.end(now))
    }

    @Test fun zoomKeepsStatsWindowAndRespectsLimits() {
        val state = PowerGraphState(GraphPeriod.DAY, zone).also { it.reset(now) }
        val window = state.statsWindow(now)
        state.zoom(8f, now - hour, now)
        assertEquals(window, state.statsWindow(now))
        assertTrue(state.span < PowerHistory.DAY_MS)
        repeat(30) { state.zoom(4f, now - hour, now) }
        assertEquals(PowerGraphState.MIN_SPAN_MS, state.span)
        repeat(30) { state.zoom(0.25f, now - hour, now) }
        assertEquals(PowerHistory.DAY_MS, state.span)
        // Переход к вчерашним суткам — статистика вчерашнего календарного периода.
        state.reset(now)
        state.pan(-PowerHistory.DAY_MS, now)
        assertEquals(GraphPeriod.DAY.window(now - PowerHistory.DAY_MS, zone), state.statsWindow(now))
    }

    @Test fun statsAreForWholePeriodAndNowIsLatestReading() {
        val data = seed()
        val p = panel()
        attach(p, 900, 520)
        recorder.onState("sensor.inv", "62.64", JSONObject().put("unit_of_measurement", "W"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("+62,6 Вт", p.findViewWithTag<TextView>("pg_now").text.toString())
        val min = p.findViewWithTag<TextView>("pg_min").text.toString()
        assertTrue(min, min.startsWith("−"))
        assertEquals(PowerHistory.formatWatts(data.mapNotNull { it.watts }.min()), min)
        val before = p.findViewWithTag<TextView>("pg_avg").text.toString()
        p.state.zoom(6f, now - 30 * 60_000L, now)
        p.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, p.findViewWithTag<TextView>("pg_avg").text.toString())
    }

    @Test fun swipeTapAndCursorDragDoNotConflict() {
        seed()
        val p = panel()
        attach(p, 900, 520)
        val graph = p.graph
        // Касание — курсор на реальном замере.
        touch(graph, MotionEvent.ACTION_DOWN, 500f, 0); touch(graph, MotionEvent.ACTION_UP, 500f, 50)
        val cursor = p.state.cursor
        assertNotNull(cursor)
        assertTrue(graph.cursorValue()!!.second.isNotBlank())
        assertTrue(p.state.live)
        // Перетаскивание от линии курсора двигает только курсор.
        val x = 500f
        touch(graph, MotionEvent.ACTION_DOWN, x, 100); touch(graph, MotionEvent.ACTION_MOVE, x - 120, 150); touch(graph, MotionEvent.ACTION_UP, x - 120, 200)
        assertTrue(p.state.live)
        assertTrue(p.state.cursor!! < cursor!!)
        // Свайп вне курсора — история; появляется «Сейчас».
        touch(graph, MotionEvent.ACTION_DOWN, 820f, 300); touch(graph, MotionEvent.ACTION_MOVE, 880f, 350); touch(graph, MotionEvent.ACTION_MOVE, 900f, 400)
        touch(graph, MotionEvent.ACTION_UP, 900f, 450)
        assertFalse(p.state.live)
        assertEquals(View.VISIBLE, p.findViewWithTag<View>("pg_live").visibility)
        p.findViewWithTag<View>("pg_live").performClick()
        assertTrue(p.state.live)
        assertEquals(View.INVISIBLE, p.findViewWithTag<View>("pg_live").visibility)
    }

    @Test fun cursorNeverShowsValueInsideGap() {
        seed()
        val p = panel()
        attach(p, 900, 520)
        // Середина разрыва (минуты 20–22 часа): ближайшее — реальный замер на краю, не выдуманный ноль.
        val gapMiddle = now - hour + 21 * 60_000L
        val hit = p.graph.nearest(gapMiddle)!!
        assertTrue(hit.first <= now - hour + 20 * 60_000L || hit.first >= now - hour + 23 * 60_000L)
        assertNotEquals(0.0, hit.second, 0.0)
    }

    @Test fun noSourceShowsHintWithoutCrash() {
        val p = panel()
        attach(p, 900, 520)
        p.bind("")
        assertEquals("Выберите источник графика в настройках карточки", p.graph.message)
        render(p, "no-source")
    }

    @Test fun windowHandlerPassesGraphGesturesAndStillResetsTimer() {
        var touches = 0
        var collapsed = 0
        val card = View(context)
        attach(card, 400, 300)
        val handler = SensorWindowTouchHandler({ card }, { true }, { touches++ }, {}, { collapsed++ }, 8,
            { _, x, _ -> x < 200 })
        fun event(action: Int, x: Float, pointers: Int = 1): Boolean? {
            val e = MotionEvent.obtain(0, 10, action, x, 100f, 0)
            try { return handler.dispatch(e) } finally { e.recycle() }
        }
        assertNull(event(MotionEvent.ACTION_DOWN, 100f))
        assertNull(event(MotionEvent.ACTION_MOVE, 120f))
        assertNull(event(MotionEvent.ACTION_UP, 120f))
        assertEquals(0, collapsed)
        assertTrue(touches >= 3)
        // Вне графика — прежнее поведение: касание сворачивает карточку.
        assertEquals(true, event(MotionEvent.ACTION_DOWN, 300f))
        event(MotionEvent.ACTION_UP, 300f)
        assertEquals(1, collapsed)
    }

    @Test fun rendersHourAndDayOnTestData() {
        seed()
        val p = panel()
        attach(p, 980, 560)
        p.graph.let { touch(it, MotionEvent.ACTION_DOWN, 640f, 0); touch(it, MotionEvent.ACTION_UP, 640f, 50) }
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        render(p, "hour")
        p.findViewWithTag<TextView>("pg_period_day").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        render(p, "day")
    }

    @Test fun rendersExpandedCardWithGraph() {
        seed()
        val card = PowerModernCard(context, expanded = true)
        val config = PowerModernConfig.profile(PowerModernConfig.PROFILE_INVERTER).let { c ->
            c.withRow(c.row(PowerModernConfig.ROLE_POWER)!!.copy(entityId = "sensor.inv"))
                .withRow(c.row(PowerModernConfig.ROLE_VOLTAGE)!!.copy(entityId = "sensor.v"))
        }
        val states = mapOf("sensor.inv" to "62.64", "sensor.v" to "231.3")
        val readings = BatteryReadings(HaSession(connected = true, synced = true), { states[it] }, { false },
            { id -> JSONObject().put("unit_of_measurement", if (id == "sensor.v") "V" else "W") })
        card.bind("Инвертор · Выход", config, readings, PowerVisual.preview(config, 231.3), animate = false)
        val p = panel()
        card.attachGraph(p)
        p.bind(config.graphSource)
        attach(card, 1530, 1040)
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        card.measure(View.MeasureSpec.makeMeasureSpec(1530, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1040, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, 1530, 1040)
        // Все прежние показания карточки на месте.
        listOf("current", "apparent_power", "reactive_power", "power_factor", "energy", "status")
            .forEach { assertNotNull(it, card.findViewWithTag<View>("pm_row_$it")) }
        assertNotNull(card.findViewWithTag<View>("pm_frequency_box"))
        assertTrue(card.interactiveAt(1200f, 900f))
        assertFalse(card.interactiveAt(200f, 300f))
        render(card, "expanded-card")
    }
}
