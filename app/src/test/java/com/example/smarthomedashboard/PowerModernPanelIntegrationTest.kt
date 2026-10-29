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

// Настоящие MainActivity, контроллер панели, ViewModel и хранилище состояний HA.
// XML/onCreate и сеть не запускаются; команды устройствам не отправляются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PowerModernPanelIntegrationTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel")
        .apply { isAccessible = true }.invoke(activity) as MainViewModel
    private val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController")
        .apply { isAccessible = true }.invoke(activity) as DashboardPanelController
    private val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")

    private fun tileConfig(profile: String, prefix: String): String {
        var c = PowerModernConfig.profile(profile)
        c = c.withRow(c.row("voltage")!!.copy(entityId = "sensor.${prefix}_v"))
            .withRow(c.row("power")!!.copy(entityId = "sensor.${prefix}_p"))
            .withRow(c.row("frequency")!!.copy(entityId = "sensor.${prefix}_f"))
        return c.writeTo("""{"button_size":300,"widget_height":360}""")
    }

    private fun open(gridV: String, invV: String) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            TileEntity("grid", PowerModernConfig.TYPE, "grid", "Сеть · Вход", 40, 140, 1, 1,
                config = tileConfig(PowerModernConfig.PROFILE_GRID, "grid")),
            TileEntity("inverter", PowerModernConfig.TYPE, "grid", "Инвертор · Выход", 400, 140, 1, 1,
                config = tileConfig(PowerModernConfig.PROFILE_INVERTER, "inv")),
            TileEntity("classic", "battery", "grid", "Classic", 800, 140, 1, 1,
                config = """{"entity_id":"sensor.soc","button_size":260,"widget_height":210}""")
        ))
        ReflectionHelpers.setField(activity, "tileManager", manager)
        ReflectionHelpers.setField(activity, "bottomPanel", panel)
        ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
        ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        connect(mapOf("sensor.grid_v" to gridV, "sensor.grid_p" to "320", "sensor.grid_f" to "50.0",
            "sensor.inv_v" to invV, "sensor.inv_p" to "1930.45", "sensor.inv_f" to "50.0", "sensor.soc" to "64"))
        refresh()
    }

    private fun refresh() {
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        layout()
    }

    private fun connect(values: Map<String, String>) {
        state.value = state.value.copy(haSession = HaSession(connected = true))
        viewModel.onStatesSnapshot(values.map { (id, v) -> HaEntityState(id, v, JSONObject()) })
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
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun card(id: String): PowerModernCard = panel.findViewWithTag<FrameLayout>(id).findViewWithTag("pm_compact")
    private fun text(view: View, tag: String) = view.findViewWithTag<TextView>(tag).text.toString()

    private fun event(id: String, value: String) {
        viewModel.onEntityStateChanged(id, value, JSONObject())
        controller.renderEntityState(id, value)
    }

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val e = MotionEvent.obtain(time, time + i, action, 20f, 20f, 0)
            try { view.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }

    @Test fun gridLostWhileInverterRunsAreIndependent() {
        open(gridV = "0", invV = "230")
        // Цифры — сразу, цвет — после подтверждения 1,8 с.
        assertEquals("0", text(card("grid"), "pm_voltage"))
        assertEquals("1930", text(card("inverter"), "pm_power"))
        assertEquals(PowerModernConfig.NEUTRAL, card("grid").accent)
        assertFalse(card("grid").pulseRequested)
        advance(1_900)
        advance(600)
        assertEquals(PowerModernConfig.RED, card("grid").accent)
        assertTrue("Нет сети — мягкая пульсация", card("grid").pulseRequested)
        assertEquals("Нет сети", text(card("grid"), "pm_status"))
        assertEquals(PowerModernConfig.CYAN, card("inverter").accent)
        assertFalse(card("inverter").pulseRequested)
        assertEquals("Норма", text(card("inverter"), "pm_status"))
    }

    @Test fun reverseCombinationAndAddressedUpdates() {
        open(gridV = "228", invV = "0")
        advance(2_500)
        assertEquals(PowerModernConfig.EMERALD, card("grid").accent)
        assertEquals(PowerModernConfig.RED, card("inverter").accent)
        assertEquals("Нет напряжения на выходе", text(card("inverter"), "pm_status"))
        val inverterBinds = card("inverter").binds
        // Событие датчика входной сети не трогает выход инвертора.
        event("sensor.grid_v", "229")
        assertEquals("229", text(card("grid"), "pm_voltage"))
        assertEquals(inverterBinds, card("inverter").binds)
        // Скачок до 285 В: число сразу, цвет и пульсация — после подтверждения.
        event("sensor.grid_v", "285")
        assertEquals("285", text(card("grid"), "pm_voltage"))
        assertEquals(PowerModernConfig.EMERALD, card("grid").accent)
        advance(1_900)
        advance(600)
        assertEquals(PowerModernConfig.RED, card("grid").accent)
        assertTrue(card("grid").pulseRequested)
        assertEquals(inverterBinds, card("inverter").binds)
        // Возврат в норму останавливает пульсацию после подтверждения.
        event("sensor.grid_v", "228")
        advance(2_500)
        assertFalse(card("grid").pulseRequested)
        assertEquals(PowerModernConfig.EMERALD, card("grid").accent)
    }

    @Test fun connectionLossIsImmediateAndRecoveryWaitsForNewSessionData() {
        open(gridV = "228", invV = "230")
        advance(2_500)
        viewModel.devices.markDisconnected()
        state.value = state.value.copy(haSession = HaSession())
        controller.renderSessionState()
        listOf("grid", "inverter").forEach { id ->
            assertEquals("Нет связи с Home Assistant", text(card(id), "pm_status"))
            assertEquals("—", text(card(id), "pm_voltage"))
            assertEquals("—", text(card(id), "pm_power"))
            assertFalse(card(id).pulseRequested)
        }
        advance(600)
        assertEquals(PowerModernConfig.NEUTRAL, card("grid").accent)
        assertEquals("Хранилище HA не очищено", "228", viewModel.devices.singleStates["sensor.grid_v"])
        // Связь есть, снимка нет — по-прежнему нейтрально.
        state.value = state.value.copy(haSession = HaSession(connected = true))
        controller.renderSessionState()
        assertEquals("Синхронизация с Home Assistant…", text(card("grid"), "pm_status"))
        // Снимок новой сессии: числа сразу, окраска — после подтверждения.
        viewModel.onStatesSnapshot(listOf(HaEntityState("sensor.grid_v", "0", JSONObject()),
            HaEntityState("sensor.inv_v", "231", JSONObject()), HaEntityState("sensor.inv_p", "900", JSONObject())))
        state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
        controller.renderInitialStates(listOf("sensor.grid_v", "sensor.inv_v", "sensor.inv_p"))
        controller.renderSessionState()
        assertEquals("0", text(card("grid"), "pm_voltage"))
        assertEquals("Мощность не из новой сессии — прочерк", "—", text(card("grid"), "pm_power"))
        assertFalse("Первое критическое показание не пульсирует до подтверждения", card("grid").pulseRequested)
        advance(1_900)
        advance(600)
        assertTrue(card("grid").pulseRequested)
        assertEquals(PowerModernConfig.CYAN, card("inverter").accent)
    }

    @Test fun recreatedUiShowsAlreadyConfirmedStateImmediately() {
        open(gridV = "0", invV = "230")
        advance(2_500)
        refresh() // новое дерево панели, тот же ViewModel
        assertEquals(PowerModernConfig.RED, card("grid").accent)
        assertTrue(card("grid").pulseRequested)
        assertEquals(PowerModernConfig.CYAN, card("inverter").accent)
    }

    @Test fun missingVoltageIsNeutralEvenWithPower() {
        open(gridV = "unavailable", invV = "230")
        advance(2_500)
        assertEquals("—", text(card("grid"), "pm_voltage"))
        assertEquals("320", text(card("grid"), "pm_power"))
        assertEquals("Нет данных", text(card("grid"), "pm_status"))
        assertEquals(PowerModernConfig.NEUTRAL, card("grid").accent)
        assertFalse(card("grid").pulseRequested)
    }

    @Test fun expandsLikeBatteryModernAndAutoCollapses() {
        open(gridV = "228", invV = "230")
        advance(2_500)
        val source = panel.findViewWithTag<FrameLayout>("grid")
        tap(source)
        advance(400)
        val expansion = controller.modernExpansion
        assertTrue(expansion.isOpen)
        val expanded = expansion.card as PowerModernCard
        assertTrue(expanded.expanded)
        assertEquals("228", text(expanded, "pm_voltage"))
        assertEquals("Норма", text(expanded, "pm_value_status"))
        assertEquals(PowerModernConfig.EMERALD, expanded.accent)
        val deadline = expansion.collapseAt
        event("sensor.grid_v", "229")
        assertEquals("229", text(expanded, "pm_voltage"))
        assertEquals("События HA не продлевают таймер", deadline, expansion.collapseAt)
        advance(30_500)
        assertFalse(expansion.isOpen)
        advance(400)
        assertNull(expansion.card)
        assertEquals(1f, source.alpha, 0f)
        assertEquals(40f, source.x, 0f)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { view.dispatchTouchEvent(e) } finally { e.recycle() }
    }

    @Test fun resizeAndMoveUseExistingFreePlacement() {
        open(gridV = "228", invV = "230")
        ArchitectureTestAccess.setField(activity, "isEditMode", true)
        refresh()
        val tile = panel.findViewWithTag<FrameLayout>("grid")
        val handle = (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<android.widget.ImageView>().last()
        assertEquals(View.VISIBLE, handle.visibility)
        var down = SystemClock.uptimeMillis()
        touch(handle, MotionEvent.ACTION_DOWN, 0f, 0f, down)
        touch(handle, MotionEvent.ACTION_MOVE, 60f, 40f, down)
        touch(handle, MotionEvent.ACTION_UP, 60f, 40f, down)
        val resized = JSONObject(manager.loadTiles().single { it.id == "grid" }.config)
        assertEquals(360, resized.getInt("button_size"))
        assertEquals(400, resized.getInt("widget_height"))
        assertEquals("Настройки плитки не потеряны", "sensor.grid_v",
            PowerModernConfig.from(resized.toString()).row("voltage")!!.entityId)
        // Перемещение: удержание 500 мс и перетаскивание, как у остальных плиток.
        refresh()
        val moved = panel.findViewWithTag<FrameLayout>("grid")
        down = SystemClock.uptimeMillis()
        touch(moved, MotionEvent.ACTION_DOWN, 10f, 10f, down)
        advance(600)
        touch(moved, MotionEvent.ACTION_MOVE, 110f, 70f, down)
        touch(moved, MotionEvent.ACTION_UP, 110f, 70f, down)
        val saved = manager.loadTiles().single { it.id == "grid" }
        assertEquals(140, saved.x)
        assertEquals(200, saved.y)
        assertEquals("Другие плитки не сдвинуты", 400, manager.loadTiles().single { it.id == "inverter" }.x)
        assertFalse("В режиме редактирования плитка не раскрывается", controller.modernExpansion.isOpen)
    }

    @Test fun classicBatteryAndClearingAreUnaffected() {
        open(gridV = "228", invV = "230")
        val classic = panel.findViewWithTag<FrameLayout>("classic")
        assertNotNull(classic.findViewWithTag<View>("battery_progress_fill"))
        assertNull(classic.findViewWithTag<View>("pm_compact"))
        controller.clearPanelInteractions()
        advance(5_000)
        assertNull(controller.modernExpansion.card)
    }
}
