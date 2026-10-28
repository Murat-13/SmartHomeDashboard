package com.example.smarthomedashboard

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
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

// Реальные touch-listeners и MotionEvent. Сокет записывает команды, но не подключается к сети.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PressCancelIntegrationTest {
    private val controller by lazy { Robolectric.buildActivity(MainActivity::class.java) }
    private val activity get() = controller.get().apply { pinGestureFixture(this) }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val socket = RecordingSocket()

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary data")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Сетевых ресурсов у записывающего сокета нет.
        }
    }

    private fun set(target: Any, name: String, value: Any?) = ArchitectureTestAccess.setField(target, name, value)
    private fun <T> get(name: String): T = ArchitectureTestAccess.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun open(edit: Boolean = false) {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            TileEntity("group", "group", "bottom_panel", "Группа", 20, 350, 1, 1,
                config = """{"entity_ids":["light.safe","switch.safe"]}"""),
            TileEntity("button", "button", "bottom_panel", "Кнопка", 300, 350, 1, 1,
                config = """{"entity_id":"light.safe"}"""),
            TileEntity("sensor", "sensor", "grid", "Датчик", 20, 100, 1, 1,
                config = """{"entity_id":"sensor.temp"}""")
        ))
        set(activity, "tileManager", manager)
        set(activity, "bottomPanel", panel)
        val dim = View(activity).apply { visibility = View.GONE }
        set(activity, "dimOverlay", dim)
        set(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        set(activity, "isEditMode", edit)
        val root = FrameLayout(activity)
        (panel.parent as? ViewGroup)?.removeView(panel)
        root.addView(panel)
        root.addView(dim)
        activity.window.setContentView(root)
        ArchitectureTestAccess.setField(activity.delegate, "mSubDecorInstalled", true)
        ArchitectureTestAccess.setField(activity.delegate, "mWindow", activity.window)
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        set(client, "authenticated", true)
        set(client, "webSocket", socket)
        val owner = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        ArchitectureTestAccess.setField(owner, "webSocket", client)
        invoke("refreshBottomPanel")
        controller.visible()
        // Завершаем начальный StateListAnimator темы до снимка свойств жеста.
        advance(400)
        socket.messages.clear()
    }

    private fun view(id: String): View = panel.findViewWithTag(id)
    private fun layoutCards() {
        for (i in 0 until panel.childCount) {
            val v = panel.getChildAt(i)
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
    private fun send(v: View, action: Int, x: Float = 5f, y: Float = 5f) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { v.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun tap(v: View) {
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_UP)
        advance(16)
    }
    private fun calls() = socket.messages.filter { it.optString("type") == "call_service" }
    private fun prefs() = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
    private data class Visual(val x: Float, val y: Float, val alpha: Float, val elevation: Float)
    private fun visual(v: View) = Visual(v.x, v.y, v.alpha, v.elevation)
    private fun assertNoAction() {
        assertTrue(calls().isEmpty())
        assertNull(get<String?>("expandedGroupId"))
        assertTrue(get<List<Button>>("expandedChildButtons").isEmpty())
        assertNull(sensorSessionFixture(activity).current)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun groupCancelBeforeThresholdNeverExpandsOrSendsCommandEvenAfterStrayUp() {
        open()
        val before = prefs()
        val v = view("group")
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(1200)
        assertNoAction()
        send(v, MotionEvent.ACTION_UP)
        advance(16)
        assertNoAction()
        assertEquals(before, prefs())
    }

    @Test fun newGroupShortAndLongPressAfterCancelKeepExistingActions() {
        open()
        val v = view("group")
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(1200)
        tap(v)
        assertEquals(listOf("turn_on", "turn_on"), calls().map { it.getString("service") })
        assertEquals(listOf("light.safe", "switch.safe"),
            calls().map { it.getJSONObject("service_data").getString("entity_id") })
        send(v, MotionEvent.ACTION_DOWN)
        advance(1100)
        send(v, MotionEvent.ACTION_UP)
        assertEquals("group", get<String>("expandedGroupId"))
        assertEquals(2, get<List<Button>>("expandedChildButtons").size)
        assertEquals(2, calls().size)
    }

    private fun cancelBeforeDrag(id: String) {
        open(edit = true)
        val v = view(id)
        v.alpha = 0.73f
        v.elevation = 11f
        val original = visual(v)
        val before = prefs()
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(700)
        assertEquals(original, visual(v))
        assertNoAction()
        send(v, MotionEvent.ACTION_UP)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(16)
        assertEquals(original, visual(v))
        assertEquals(before, prefs())
        assertNoAction()
        tap(v)
        awaitPinGate(activity)
        assertEquals(TileSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }
    @Test fun groupEditCancelBeforeDragRestoresRealStyleAndAllowsNewGesture() = cancelBeforeDrag("group")
    @Test fun sensorEditCancelBeforeDragRestoresRealStyleAndAllowsNewGesture() = cancelBeforeDrag("sensor")
    @Test fun buttonEditCancelBeforeDragRestoresRealStyleAndAllowsNewGesture() = cancelBeforeDrag("button")

    private fun cancelMovedDrag(id: String) {
        open(edit = true)
        val v = view(id)
        v.alpha = 0.73f
        v.elevation = 11f
        val original = visual(v)
        val before = prefs()
        send(v, MotionEvent.ACTION_DOWN)
        advance(600)
        assertEquals(0.6f, v.alpha, 0.001f)
        send(v, MotionEvent.ACTION_MOVE, 65f, 75f)
        assertEquals(original.x + 60, v.x, 0.001f)
        assertEquals(original.y + 70, v.y, 0.001f)
        send(v, MotionEvent.ACTION_CANCEL, 65f, 75f)
        assertEquals(original, visual(v))
        send(v, MotionEvent.ACTION_MOVE, 100f, 100f)
        send(v, MotionEvent.ACTION_UP, 100f, 100f)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(700)
        assertEquals(original, visual(v))
        assertEquals(before, prefs())
        assertNoAction()
    }
    @Test fun groupMovedDragCancelRollsBackWithoutSaveOrStrayUp() = cancelMovedDrag("group")
    @Test fun sensorMovedDragCancelRollsBackWithoutSaveOrStrayUp() = cancelMovedDrag("sensor")
    @Test fun buttonMovedDragCancelRollsBackWithoutSaveOrStrayUp() = cancelMovedDrag("button")

    @Test fun sensorNormalCancelIgnoresStrayUpButNextTapStillExpands() {
        open()
        val v = view("sensor")
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(1200)
        send(v, MotionEvent.ACTION_UP)
        assertNoAction()
        tap(v)
        assertSame(v, checkNotNull(sensorSessionFixture(activity).current))
    }

    @Test fun normalButtonNativeCancelDoesNotSendCommandsAndNextTapWorks() {
        open()
        val v = view("button")
        send(v, MotionEvent.ACTION_DOWN)
        send(v, MotionEvent.ACTION_CANCEL)
        advance(1200)
        send(v, MotionEvent.ACTION_UP)
        advance(16)
        assertTrue(calls().isEmpty())
        tap(v)
        assertEquals("turn_on", calls().single().getString("service"))
        assertEquals("light.safe", calls().single().getJSONObject("service_data").getString("entity_id"))
    }

    @Test fun successfulDragUpStillSavesPositionForAllThreeKinds() {
        open(edit = true)
        for (id in listOf("group", "sensor", "button")) {
            val v = view(id)
            val original = visual(v)
            send(v, MotionEvent.ACTION_DOWN)
            advance(600)
            send(v, MotionEvent.ACTION_MOVE, 65f, 75f)
            send(v, MotionEvent.ACTION_UP, 65f, 75f)
            val saved = manager.loadTiles().single { it.id == id }
            assertEquals((original.x + 60).toInt(), saved.x)
            assertEquals((original.y + 70).toInt(), saved.y)
            assertEquals(1f, v.alpha, 0.001f)
            assertEquals(8f, v.elevation, 0.001f)
        }
        assertTrue(calls().isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun rebuildAfterCancelRemovesOldCallbacksAndNewPanelRemainsUsable() {
        open(edit = true)
        val old = listOf("group", "sensor", "button").map(::view)
        val before = prefs()
        old.forEach { send(it, MotionEvent.ACTION_DOWN); send(it, MotionEvent.ACTION_CANCEL) }
        invoke("refreshBottomPanel")
        advance(1500)
        assertTrue(old.all { it.parent == null && shadowOf(it).onTouchListener == null })
        assertEquals(before, prefs())
        assertNoAction()
        tap(view("group"))
        awaitPinGate(activity)
        assertEquals(TileSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun destroyAfterCancelLeavesNoDelayedDragOrExpansion() {
        open(edit = true)
        val old = view("group")
        val original = visual(old)
        send(old, MotionEvent.ACTION_DOWN)
        send(old, MotionEvent.ACTION_CANCEL)
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
        advance(1500)
        assertEquals(original, visual(old))
        assertNull(shadowOf(old).onTouchListener)
        assertNoAction()
    }

    @Test fun rebuildDuringActiveEditDragDisposesAllKindsWithoutSaving() {
        open(edit = true)
        val old = listOf("group", "sensor", "button").map(::view)
        val originals = old.map(::visual)
        val before = prefs()
        old.forEach { send(it, MotionEvent.ACTION_DOWN) }
        advance(600)
        old.forEach { send(it, MotionEvent.ACTION_MOVE, 65f, 75f) }
        invoke("refreshBottomPanel")
        advance(1500)
        old.forEachIndexed { index, v ->
            assertEquals(originals[index], visual(v))
            assertNull(shadowOf(v).onTouchListener)
            send(v, MotionEvent.ACTION_UP, 65f, 75f)
        }
        assertEquals(before, prefs())
        assertNoAction()
        tap(view("sensor"))
        awaitPinGate(activity)
        assertEquals(TileSettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun backgroundCancelAlreadyRemovesEditModeLongPressAndNewDownStillWorks() {
        open()
        fun background(action: Int) {
            val e = MotionEvent.obtain(0, 0, action, 1800f, 40f, 0)
            try { activity.onTouchEvent(e) } finally { e.recycle() }
        }
        background(MotionEvent.ACTION_DOWN)
        background(MotionEvent.ACTION_CANCEL)
        advance(3200)
        assertFalse(get<Boolean>("isEditMode"))
        background(MotionEvent.ACTION_UP)
        assertFalse(get<Boolean>("isEditMode"))
        background(MotionEvent.ACTION_DOWN)
        advance(3100)
        awaitPinGate(activity)
        assertTrue(get<Boolean>("isEditMode"))
        background(MotionEvent.ACTION_UP)
        assertTrue(calls().isEmpty())
    }
}
