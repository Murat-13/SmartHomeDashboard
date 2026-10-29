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
import org.json.JSONObject
import org.junit.Assert.*
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

// Настоящие MainActivity, контроллер панели, ViewModel и хранилище состояний.
// XML/onCreate и сеть не запускаются; команды устройствам не отправляются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BatteryModernPanelIntegrationTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val dim by lazy { View(activity).apply { visibility = View.GONE } }

    private val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel")
        .apply { isAccessible = true }.invoke(activity) as MainViewModel
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private val expansion get() = controller.modernExpansion
    @Suppress("UNCHECKED_CAST")
    private val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")

    private fun modernConfig(): String {
        var config = BatteryModernConfig()
        listOf("soc" to "sensor.bms_soc", "power" to "sensor.bms_power", "voltage" to "sensor.bms_voltage",
            "cycles" to "sensor.bms_cycles").forEach { (role, id) ->
            config = config.withRow(config.row(role)!!.copy(entityId = id))
        }
        return config.writeTo("""{"button_size":520,"widget_height":380}""")
    }

    private fun open() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            TileEntity("modern", BatteryModernConfig.TYPE, "grid", "АКБ", 40, 140, 1, 1, config = modernConfig()),
            TileEntity("classic", "battery", "grid", "Classic", 700, 140, 1, 1,
                config = """{"entity_id":"sensor.bms_soc","button_size":260,"widget_height":210}""")
        ))
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", dim)
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(dim)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        connect(mapOf("sensor.bms_soc" to "89", "sensor.bms_power" to "-407.3", "sensor.bms_voltage" to "51.2",
            "sensor.bms_cycles" to "74"))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
    }

    /** Новая сессия: соединение, затем первоначальный снимок, как в MainViewModel. */
    private fun connect(values: Map<String, String>) {
        state.value = state.value.copy(haSession = HaSession(connected = true))
        viewModel.onStatesSnapshot(values.map { (id, value) ->
            HaEntityState(id, value, JSONObject().put("unit_of_measurement", if (id.endsWith("power")) "W" else ""))
        })
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
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
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
    }

    private fun tile(id: String): FrameLayout = panel.findViewWithTag(id)

    private fun tapTile(id: String) {
        val view = tile(id)
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(time, time + index, action, 20f, 20f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun tapWindow(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(time, time + index, action, x, y, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun expandedText(tag: String) = expansion.card!!.findViewWithTag<TextView>(tag).text.toString()

    @Test fun tapExpandsOverPanelAndTapClosesBackToSavedBounds() {
        open()
        val source = tile("modern")
        val before = Triple(source.x, source.y, source.layoutParams.width to source.layoutParams.height)
        val prefs = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
        tapTile("modern")
        advance(400)
        assertTrue(expansion.isOpen)
        assertEquals("modern", expansion.tileId)
        assertNotSame("Раскрытая карточка — отдельная View поверх панели", panel, expansion.card!!.parent)
        assertEquals("89", expandedText("bm_soc"))
        assertEquals("74", expandedText("bm_value_cycles"))
        tapWindow(5f, 5f)
        advance(400)
        assertFalse(expansion.isOpen)
        assertNull(expansion.card)
        assertEquals(before, Triple(source.x, source.y, source.layoutParams.width to source.layoutParams.height))
        assertEquals(1f, source.alpha, 0f)
        assertEquals("Раскрытие не пишет конфигурацию", prefs, activity.getSharedPreferences("tiles_prefs", 0).all.toMap())
    }

    @Test fun haEventsUpdateOpenCardWithoutResettingTimer() {
        open()
        tapTile("modern")
        advance(400)
        val deadline = expansion.collapseAt
        advance(20_000)
        viewModel.onEntityStateChanged("sensor.bms_power", "1381", JSONObject().put("unit_of_measurement", "W"))
        controller.renderEntityState("sensor.bms_power", "1381")
        assertEquals("↑ Заряд", expandedText("bm_flow"))
        assertEquals("1381", expandedText("bm_power").filter { it.isDigit() })
        assertEquals(deadline, expansion.collapseAt)
        advance(10_500)
        assertFalse(expansion.isOpen)
    }

    @Test fun connectionLossWhileOpenThenReconnectShowsOnlyConfirmedData() {
        open()
        tapTile("modern")
        advance(400)
        // Потеря связи: значения в хранилище остаются, но не выдаются за текущие.
        viewModel.devices.markDisconnected()
        state.value = state.value.copy(haSession = HaSession())
        controller.renderSessionState()
        assertEquals("—", expandedText("bm_soc"))
        assertEquals("—", expandedText("bm_power"))
        assertEquals("— Нет данных", expandedText("bm_flow"))
        assertEquals("—", expandedText("bm_value_cycles"))
        assertEquals("Нет связи с Home Assistant", expandedText("bm_status"))
        assertNull(expansion.card!!.findViewWithTag<BatterySegmentBar>("bm_bar").fraction)
        assertEquals("89", viewModel.devices.singleStates["sensor.bms_soc"])
        val compact = tile("modern").findViewWithTag<TextView>("bm_soc").text.toString()
        assertEquals("—", compact)
        assertTrue("Карточка остаётся открытой", expansion.isOpen)

        // Переподключение до снимка: старые значения всё ещё недостоверны.
        state.value = state.value.copy(haSession = HaSession(connected = true))
        controller.renderSessionState()
        assertEquals("—", expandedText("bm_soc"))
        assertEquals("Синхронизация с Home Assistant…", expandedText("bm_status"))

        // Снимок новой сессии без мощности: SOC актуален, мощность — нет.
        viewModel.onStatesSnapshot(listOf(HaEntityState("sensor.bms_soc", "64", JSONObject())))
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
        controller.renderInitialStates(listOf("sensor.bms_soc"))
        controller.renderSessionState()
        assertEquals("64", expandedText("bm_soc"))
        assertEquals("—", expandedText("bm_power"))
        assertEquals(View.GONE, expansion.card!!.findViewWithTag<View>("bm_status").visibility)
    }

    @Test fun deletingTileOrRefreshingPanelReleasesExpansion() {
        open()
        tapTile("modern")
        advance(400)
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val overlayChildren = content.childCount
        manager.deleteTile("modern")
        tapWindow(5f, 5f)
        assertNull(expansion.card)
        assertNull(expansion.collapseAt)
        assertEquals(overlayChildren - 2, content.childCount)

        manager.saveTiles(manager.loadTiles() + TileEntity("modern", BatteryModernConfig.TYPE, "grid", "АКБ", 40, 140, 1, 1,
            config = modernConfig()))
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
        tapTile("modern")
        advance(100)
        controller.clearPanelInteractions()
        assertNull(expansion.card)
        assertEquals(overlayChildren - 2, content.childCount)
        advance(60_000)
        assertNull(expansion.card)
    }

    @Test fun classicBatteryTreeIsUnchanged() {
        open()
        val classic = tile("classic")
        assertNotNull(classic.findViewWithTag<View>("battery_progress_fill"))
        assertNotNull(classic.findViewWithTag<View>("sensor_text_container"))
        assertNull(classic.findViewWithTag<View>("bm_compact"))
        val modern = tile("modern")
        assertNotNull(modern.findViewWithTag<View>("bm_compact"))
        assertNull(modern.findViewWithTag<View>("sensor_text_container"))
    }

    @Test fun windowFocusLossPausesAutoCollapse() {
        open()
        tapTile("modern")
        advance(400)
        controller.setInteractive(false)
        advance(45_000)
        assertTrue(expansion.isOpen)
        controller.setInteractive(true)
        advance(30_500)
        assertFalse(expansion.isOpen)
    }
}
