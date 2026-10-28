package com.example.smarthomedashboard

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

// Все проверяемые жесты проходят через Activity.dispatchTouchEvent и присоединённое окно.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorDismissWindowTest {
    private val controller by lazy { Robolectric.buildActivity(MainActivity::class.java) }
    private val activity get() = controller.get().apply { pinGestureFixture(this) }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val socket = RecordingSocket()
    private var downTime = 0L

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary data")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Этот сокет не открывает соединений.
        }
    }
    private fun set(target: Any, name: String, value: Any?) = ArchitectureTestAccess.setField(target, name, value)
    private fun <T> get(name: String): T = ArchitectureTestAccess.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        ArchitectureTestAccess.methods(activity).single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }

    private fun open(type: String = "weather", expanded: Boolean = true) {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        shadowOf(activity.windowManager.defaultDisplay).setWidth(500)
        shadowOf(activity.windowManager.defaultDisplay).setHeight(1400)
        activity.resources.displayMetrics.widthPixels = 500
        activity.resources.displayMetrics.heightPixels = 1400
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", false).commit()
        manager.saveTiles(listOf(
            TileEntity("a", type, "grid", "A", 20, 100, 1, 1,
                config = """{"entity_id":"weather.home","button_size":240,"widget_height":200,
                    "collapsed_sensor_configs":[{"entity_id":"sensor.temp","display_name":"T","decimals":1}]}"""),
            TileEntity("b", "sensor", "grid", "B", 250, 850, 1, 1),
            TileEntity("button", "button", "bottom_panel", "Кнопка", 20, 1100, 1, 1,
                config = """{"entity_id":"light.safe"}"""),
            TileEntity("group", "group", "bottom_panel", "Группа", 260, 1100, 1, 1,
                config = """{"entity_ids":["light.safe"]}""")
        ))
        set(activity, "tileManager", manager)
        set(activity, "bottomPanel", panel)
        set(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val dim = View(activity).apply { visibility = View.GONE }
        set(activity, "dimOverlay", dim)
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        activity.window.setContentView(root)
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        get<MutableMap<String, String>>("singleStates").putAll(mapOf("weather.home" to "sunny", "sensor.temp" to "12.5"))
        get<MutableMap<String, JSONObject>>("entityAttributes")["weather.home"] = JSONObject()
            .put("temperature", 20).put("forecast", JSONArray().apply {
                repeat(14) { put(JSONObject().put("time", "12:00").put("temperature", 18)) }
            })
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        ArchitectureTestAccess.setField(owner, "webSocket", client)
        invoke("refreshBottomPanel")
        controller.visible()
        advance(400)
        if (expanded) {
            tap(point(card()))
            advance(400)
            assertSame(card(), expandedView())
        }
        socket.messages.clear()
    }

    private fun view(id: String): View = panel.findViewWithTag(id)
    private fun card(): FrameLayout = view("a") as FrameLayout
    private fun expandedView(): View? = sensorSessionFixture(activity).current
    private fun content(): LinearLayout = card().findViewWithTag("sensor_text_container")
    private fun scroll(): HorizontalScrollView = (0 until content().childCount)
        .map { content().getChildAt(it) }.filterIsInstance<HorizontalScrollView>().single()
    private fun point(v: View, x: Float = v.width / 2f, y: Float = v.height / 2f): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return loc[0] + x to loc[1] + y
    }
    private fun send(action: Int, p: Pair<Float, Float>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, p.first, p.second, 0)
        try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
    }
    private fun tap(p: Pair<Float, Float>) { send(MotionEvent.ACTION_DOWN, p); send(MotionEvent.ACTION_UP, p); advance(16) }
    private fun layoutCards() {
        for (i in 0 until panel.childCount) {
            val v = panel.getChildAt(i)
            v.forceLayout()
            v.measure(View.MeasureSpec.makeMeasureSpec(v.layoutParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(v.layoutParams.height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, v.layoutParams.width, v.layoutParams.height)
        }
    }
    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
        layoutCards()
    }
    private fun calls() = socket.messages.filter { it.optString("type") == "call_service" }
    private fun swipe(start: Pair<Float, Float>, end: Pair<Float, Float>, up: Pair<Float, Float> = end) {
        send(MotionEvent.ACTION_DOWN, start)
        repeat(5) { i ->
            advance(16)
            val t = (i + 1) / 5f
            send(MotionEvent.ACTION_MOVE, start.first + (end.first - start.first) * t to start.second + (end.second - start.second) * t)
        }
        send(MotionEvent.ACTION_UP, up)
    }

    @Test fun insideTapClosesWeatherAndRestoresCompactValuesAndGeometry() {
        open()
        tap(point(card(), 50f, 50f))
        advance(400)
        assertNull(expandedView())
        assertEquals(240, card().layoutParams.width)
        assertEquals(200, card().layoutParams.height)
        assertEquals("20°C", card().findViewWithTag<TextView>("weather_temp").text.toString())
        assertNotNull(card().findViewWithTag<TextView>("weather_desc"))
        assertTrue(calls().isEmpty())
    }
    @Test fun emptyBackgroundTapClosesWithoutEnteringEditMode() {
        open()
        tap(480f to 1350f)
        advance(3200)
        assertNull(expandedView())
        assertFalse(get<Boolean>("isEditMode"))
        assertTrue(calls().isEmpty())
    }
    @Test fun buttonTapOnlyClosesAndSeparateTapSendsCommand() {
        open()
        val p = point(view("button"))
        tap(p)
        assertNull(expandedView())
        assertTrue(calls().isEmpty())
        advance(400)
        tap(point(view("button")))
        assertEquals("turn_on", calls().single().getString("service"))
    }
    @Test fun otherSensorNeedsSecondIndependentTap() {
        open()
        tap(point(view("b")))
        assertNull(expandedView())
        advance(400)
        tap(point(view("b")))
        assertSame(view("b"), expandedView())
        assertTrue(calls().isEmpty())
    }
    @Test fun groupTapDoesNotToggleOrChangeAlreadyExpandedChildren() {
        open()
        invoke("expandChildButtons", manager.loadTiles().single { it.id == "group" }, view("group") as Button)
        advance(400)
        val children = get<List<Button>>("expandedChildButtons").toList()
        tap(point(view("group")))
        assertNull(expandedView())
        assertEquals("group", get<String>("expandedGroupId"))
        assertEquals(children, get<List<Button>>("expandedChildButtons"))
        assertTrue(calls().isEmpty())
    }
    @Test fun horizontalForecastSwipeActuallyScrollsAndStaysOpen() {
        open()
        val s = scroll()
        val p = point(s, s.width - 30f, s.height / 2f)
        swipe(p, p.first - 160 to p.second)
        assertTrue("Нативная HorizontalScrollView должна действительно прокрутиться", s.scrollX > 0)
        assertSame(card(), expandedView())
        assertTrue(calls().isEmpty())
    }
    @Test fun swipeEndingOutsideNeverActivatesButtonUnderFinger() {
        open()
        val s = scroll()
        val p = point(s, s.width - 30f, s.height / 2f)
        swipe(p, p.first - 160 to p.second, point(view("button")))
        assertTrue(s.scrollX > 0)
        assertSame(card(), expandedView())
        assertTrue(calls().isEmpty())
    }
    @Test fun movementAtEdgeAndReturningToStartIsNotTapThenNewTapCloses() {
        open()
        val s = scroll()
        s.scrollTo(100000, 0)
        val p = point(s)
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first - 100 to p.second)
        send(MotionEvent.ACTION_MOVE, p)
        send(MotionEvent.ACTION_UP, p)
        assertSame(card(), expandedView())
        tap(480f to 1350f)
        assertNull(expandedView())
    }
    @Test fun standardSlopIsUsedForInsideAndOutsideGestures() {
        open()
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        val p = 480f to 1350f
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first - slop / 2 to p.second)
        send(MotionEvent.ACTION_UP, p.first - slop / 2 to p.second)
        assertNull(expandedView())
    }
    @Test fun nestedVerticalScrollInOrdinarySensorIsNotWeatherSpecific() {
        open("sensor")
        val c = content()
        c.removeAllViews()
        val vertical = ScrollView(activity)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            repeat(40) { addView(TextView(activity).apply { text = "row"; height = 48 }) }
        }
        vertical.addView(rows)
        c.addView(vertical, LinearLayout.LayoutParams(-1, -1))
        layoutCards()
        val p = point(vertical, 40f, vertical.height - 10f)
        swipe(p, p.first to p.second - 100)
        assertTrue(vertical.scrollY > 0)
        assertSame(card(), expandedView())
        tap(point(card(), 10f, 10f))
        assertNull(expandedView())
    }
    @Test fun clicksAndLongClicksInsideAreNeverExecuted() {
        open("sensor")
        var clicks = 0
        var longs = 0
        content().removeAllViews()
        val child = Button(activity).apply {
            text = "test"
            setOnClickListener { clicks++ }
            setOnLongClickListener { longs++; true }
        }
        content().addView(child, LinearLayout.LayoutParams(-1, -1))
        layoutCards()
        val p = point(child)
        send(MotionEvent.ACTION_DOWN, p)
        advance(700)
        assertEquals(0, longs)
        send(MotionEvent.ACTION_CANCEL, p)
        tap(p)
        assertEquals(0, clicks)
        assertEquals(0, longs)
        assertNull(expandedView())
    }
    @Test fun cancelDoesNotCloseAndNextTapStillWorks() {
        open()
        val p = point(scroll())
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first - 100 to p.second)
        send(MotionEvent.ACTION_CANCEL, p)
        assertSame(card(), expandedView())
        tap(point(view("button")))
        assertNull(expandedView())
        assertTrue(calls().isEmpty())
    }
    @Test fun multitouchDoesNotCloseOrSendCommand() {
        open()
        val p = point(scroll())
        send(MotionEvent.ACTION_DOWN, p)
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { x = p.first + i * 20; y = p.second; pressure = 1f; size = 1f } }
        val e = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
        val lifted = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { activity.dispatchTouchEvent(lifted) } finally { lifted.recycle() }
        send(MotionEvent.ACTION_UP, point(view("button")))
        assertSame(card(), expandedView())
        assertTrue(calls().isEmpty())
    }
    @Test fun rebuildMidGestureConsumesOldUpAndNewGestureWorks() {
        open()
        val p = point(view("button"))
        send(MotionEvent.ACTION_DOWN, p)
        invoke("refreshBottomPanel")
        advance(400)
        send(MotionEvent.ACTION_UP, p)
        advance(16)
        assertTrue(calls().isEmpty())
        tap(point(view("button")))
        assertEquals(1, calls().size)
    }
    @Test fun deletedCardMidGestureCannotRedirectUp() {
        open()
        val p = point(view("button"))
        send(MotionEvent.ACTION_DOWN, p)
        manager.deleteTile("a")
        send(MotionEvent.ACTION_UP, p)
        advance(400)
        assertTrue(calls().isEmpty())
        assertFalse(manager.loadTiles().any { it.id == "a" })
        assertNull(expandedView())
    }
    @Test fun timeoutDuringHeldGestureConsumesRemainder() {
        open()
        send(MotionEvent.ACTION_DOWN, point(card(), 50f, 50f))
        advance(31000)
        assertNull(expandedView())
        send(MotionEvent.ACTION_UP, point(view("button")))
        advance(16)
        assertTrue(calls().isEmpty())
        tap(point(view("button")))
        assertEquals(1, calls().size)
    }
    @Test fun scrollExtendsTimeoutAndNewTapAfterScrollCloses() {
        open()
        advance(20000)
        val s = scroll()
        val p = point(s, s.width - 30f, s.height / 2f)
        swipe(p, p.first - 100 to p.second)
        advance(15000)
        assertSame(card(), expandedView())
        tap(point(card(), 50f, 50f))
        assertNull(expandedView())
    }
    @Test fun batteryUsesSameWindowDismissAndNoStateWrite() {
        open("battery")
        val before = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
        tap(point(view("button")))
        assertNull(expandedView())
        assertEquals(before, activity.getSharedPreferences("tiles_prefs", 0).all)
        assertTrue(calls().isEmpty())
    }

    @Test fun destroyDuringScrollCancelsTargetAndConsumesOldUp() {
        open()
        val p = point(scroll())
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first - 80 to p.second)
        androidx.activity.ComponentActivity::class.java.declaredFields.single {
            it.type == androidx.savedstate.SavedStateRegistryController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.savedstate.SavedStateRegistryController).performRestore(null)
        }
        androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
            it.type == androidx.fragment.app.FragmentController::class.java
        }.apply {
            isAccessible = true
            (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
        }
        (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
        ArchitectureTestAccess.getField<LifecycleRegistry>(activity, "mFragmentLifecycleRegistry").currentState = Lifecycle.State.CREATED
        controller.destroy()
        send(MotionEvent.ACTION_UP, point(view("button")))
        advance(31000)
        assertNull(expandedView())
        assertTrue(calls().isEmpty())
    }

    @Test fun noExpandedCardKeepsEditTapAndResizeThroughWindow() {
        open(expanded = false)
        set(activity, "isEditMode", true)
        invoke("refreshBottomPanel")
        advance(400)
        val handle = (0 until card().childCount).map { card().getChildAt(it) }.filterIsInstance<ImageView>().single()
        val p = point(handle)
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first + 40 to p.second + 40)
        send(MotionEvent.ACTION_UP, p.first + 40 to p.second + 40)
        assertFalse(get<Boolean>("isResizing"))
        assertEquals(280, JSONObject(manager.loadTiles().single { it.id == "a" }.config).getInt("button_size"))
        tap(point(view("button")))
        awaitPinGate(activity)
        assertEquals(TileSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(calls().isEmpty())
    }

    @Test fun historicalExcursionReturningToOriginIsNotTap() {
        open()
        val p = 480f to 1350f
        send(MotionEvent.ACTION_DOWN, p)
        val e = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_MOVE, p.first - 100, p.second, 0)
        e.addBatch(downTime + 2, p.first, p.second, 1f, 1f, 0)
        try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
        send(MotionEvent.ACTION_UP, p)
        assertSame(card(), expandedView())
        assertTrue(calls().isEmpty())
    }

    @Test fun yandexHourlyScrollUsesSameWindowRules() {
        open(expanded = false)
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", true).commit()
        val yandex = get<YandexWeatherManager>("yandexWeatherManager")
        yandex.currentTemp = "7"
        yandex.currentState = "clear"
        yandex.hourlyForecasts = List(24) { YandexWeatherManager.ForecastHour("12", "7", "clear") }
        yandex.forecasts = List(14) { YandexWeatherManager.ForecastDay("2026-10-27", "7", "2", "clear") }
        tap(point(card()))
        advance(400)
        val hourly = (0 until content().childCount).map { content().getChildAt(it) }
            .filterIsInstance<HorizontalScrollView>().first()
        val p = point(hourly, hourly.width - 30f, hourly.height / 2f)
        swipe(p, p.first - 150 to p.second, point(view("button")))
        assertTrue(hourly.scrollX > 0)
        assertSame(card(), expandedView())
        tap(point(card(), 50f, 50f))
        advance(400)
        assertEquals("7°C", card().findViewWithTag<TextView>("weather_temp").text.toString())
        assertTrue(calls().isEmpty())
    }


    @Test fun movementOverClickableChildAndReturnDoesNotBecomeClickOrLongClick() {
        open("sensor")
        var clicks = 0
        var longs = 0
        content().removeAllViews()
        val button = Button(activity).apply {
            setOnClickListener { clicks++ }
            setOnLongClickListener { longs++; true }
        }
        content().addView(button, LinearLayout.LayoutParams(-1, -1))
        layoutCards()
        val p = point(button)
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first + 40 to p.second)
        send(MotionEvent.ACTION_MOVE, p)
        advance(700)
        send(MotionEvent.ACTION_UP, p)
        advance(100)
        assertEquals(0, clicks)
        assertEquals(0, longs)
        assertSame(card(), expandedView())
    }

    @Test fun rebuildingDuringScrollSendsCancelToCapturedNativeTarget() {
        open()
        val s = scroll()
        val original = shadowOf(s).onTouchListener
        var cancels = 0
        s.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
            original?.onTouch(v, e) ?: false
        }
        val p = point(s, s.width - 30f, s.height / 2f)
        send(MotionEvent.ACTION_DOWN, p)
        send(MotionEvent.ACTION_MOVE, p.first - 50 to p.second)
        send(MotionEvent.ACTION_MOVE, p.first - 100 to p.second)
        invoke("refreshBottomPanel")
        advance(400)
        assertEquals(1, cancels)
        send(MotionEvent.ACTION_UP, point(view("button")))
        advance(16)
        assertTrue(calls().isEmpty())
    }

    @Test fun groupsWithoutExpandedSensorKeepShortAndLongPress() {
        open(expanded = false)
        tap(point(view("group")))
        assertEquals(1, calls().size)
        send(MotionEvent.ACTION_DOWN, point(view("group")))
        advance(1100)
        send(MotionEvent.ACTION_UP, point(view("group")))
        assertEquals("group", get<String>("expandedGroupId"))
        assertEquals(1, calls().size)
    }
}
