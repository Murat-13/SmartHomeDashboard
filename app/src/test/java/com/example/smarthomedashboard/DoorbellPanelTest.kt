package com.example.smarthomedashboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.TimeUnit

// Настоящие MainActivity, контроллер панели и ViewModel: плитка «Домофон» на панели, раскрытие, звонок на панели.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DoorbellPanelTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get().apply { pinGestureFixture(this) } }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel")
        .apply { isAccessible = true }.invoke(activity) as MainViewModel
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")
    private val config = DoorbellConfig(camera = "camera.door", visitor = "binary_sensor.door_visitor", lock = "lock.gate")

    @Before fun noSession() = PinSecurityUi.session.revoke()
    @After fun cleanup() {
        PinSecurityUi.session.revoke()
        DoorbellHub.actions = null
        DoorbellHub.connection = null
        DoorbellHub.detach()
    }

    private fun open(tileConfig: DoorbellConfig = config) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(TileEntity("door", DoorbellConfig.TYPE, "grid", "Калитка", 40, 140, 1, 1,
            config = tileConfig.writeTo("""{"button_size":500,"widget_height":420}"""))))
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        ArchitectureTestAccess.methods(activity).single { it.name == "connectDoorbells" }.apply { isAccessible = true }.invoke(activity)
        DoorbellHub.attach(activity.application)
        state.value = state.value.copy(haSession = HaSession(connected = true))
        viewModel.onStatesSnapshot(listOf(HaEntityState("camera.door", "idle", org.json.JSONObject()),
            HaEntityState("binary_sensor.door_visitor", "off", org.json.JSONObject()),
            HaEntityState("lock.gate", "locked", org.json.JSONObject())))
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
        DoorbellHub.onState(manager.getAllTiles(), BatteryReadings.from(state.value))
    }

    private fun layout() {
        for (i in 0 until panel.childCount) {
            val v = panel.getChildAt(i)
            v.measure(View.MeasureSpec.makeMeasureSpec(v.layoutParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(v.layoutParams.height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, v.layoutParams.width, v.layoutParams.height)
        }
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun tile() = panel.findViewWithTag<FrameLayout>("door")
    private fun card(): DoorbellCard = tile().findViewWithTag("door_compact")

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val e = MotionEvent.obtain(time, time + i, action, 20f, 20f, 0)
            try { view.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }

    @Test fun tileIsFourByThreeAndShowsStatus() {
        open()
        assertEquals("Сохранённый прямоугольник вписан в 4:3", 500, tile().layoutParams.width)
        assertEquals(375, tile().layoutParams.height)
        assertEquals("Калитка", card().findViewWithTag<TextView>("door_title").text.toString())
        assertEquals("В сети", card().findViewWithTag<TextView>("door_status").text.toString())
        assertEquals("Звонков не было", card().findViewWithTag<TextView>("door_last").text.toString())
    }

    @Test fun tapExpandsWithSoundAndAutoCollapseSetting() {
        open()
        tap(tile())
        advance(400)
        assertTrue(controller.modernExpansion.isOpen)
        val expanded = controller.modernExpansion.card as DoorbellCard
        assertTrue(expanded.expanded)
        assertFalse("Раскрытая — со звуком", expanded.video.muted)
        assertNotNull("По умолчанию сворачивается сама", controller.modernExpansion.collapseAt)
    }

    @Test fun autoCollapseCanBeTurnedOff() {
        open(config.copy(autoCollapse = false))
        tap(tile())
        advance(400)
        assertTrue(controller.modernExpansion.isOpen)
        assertNull("«Сворачивать автоматически» выключено", controller.modernExpansion.collapseAt)
    }

    @Test fun ringOnPanelOpensWindowAndCountsMissedUntilSeen() {
        open()
        DoorbellHub.RingUi.resumed(activity)
        viewModel.onEntityStateChanged("binary_sensor.door_visitor", "on", org.json.JSONObject())
        DoorbellHub.onState(manager.getAllTiles(), BatteryReadings.from(state.value))
        advance(400)
        val window = DoorbellHub.RingUi.current
        assertNotNull("На панели окно звонка открывается сразу", window)
        assertTrue(window!!.isShowing)
        assertSame(activity, window.host)
        assertEquals(1, DoorbellHub.missed("door"))
        assertEquals("Звонят в калитку", card().findViewWithTag<TextView>("door_status").text.toString())
        window.findViewWithTag<View>("door_close").performClick()
        advance(400)
        assertNull(DoorbellHub.RingUi.current)
        assertEquals("Касание окна — звонок увиден", 0, DoorbellHub.missed("door"))
    }
}
