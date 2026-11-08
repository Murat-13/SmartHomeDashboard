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

/**
 * Быстрый старт раскрытия (0.9.16): пока палец на Modern-плитке, раскрытая карточка собирается заранее —
 * невидимой в overlay; по отпусканию показывается она же. Сдвиг, отмена, долгое удержание и перестроение панели
 * не оставляют лишних View. Настоящие MainActivity, контроллер панели, ViewModel; команды устройствам не шлются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ExpansionWarmStartTest {
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

    private fun open(tiles: List<TileEntity>? = null) {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(tiles ?: listOf(
            TileEntity("modern", BatteryModernConfig.TYPE, "grid", "АКБ", 40, 140, 1, 1, config = modernConfig()),
            TileEntity("classic", "battery", "grid", "Classic", 700, 140, 1, 1,
                config = """{"entity_id":"sensor.bms_soc","button_size":260,"widget_height":210}"""),
            TileEntity("power", PowerModernConfig.TYPE, "grid", "Сеть", 1100, 140, 1, 1,
                config = PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID, """{"button_size":420,"widget_height":300}"""))
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

    private val content: FrameLayout get() = activity.findViewById(android.R.id.content)

    private fun event(id: String, action: Int, x: Float = 20f, y: Float = 20f) {
        val time = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(time, time, action, x, y, 0)
        try { tile(id).dispatchTouchEvent(e) } finally { e.recycle() }
    }

    /** Карточки, собранные заранее: невидимые дети overlay, кроме самой панели. */
    private fun hiddenCards(): List<View> = (0 until content.childCount).map { content.getChildAt(it) }
        .filter { it.visibility == View.INVISIBLE }

    @Test fun cardIsBuiltWhileFingerIsDownAndShownOnRelease() {
        open()
        val children = content.childCount
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        val ready = hiddenCards().singleOrNull()
        assertNotNull("Пока палец на плитке, карточка собрана и невидима", ready)
        assertTrue(ready is BatteryModernCard)
        // Разметку делает обычный проход окна (в этом стенде окно не рисуется) — проверяется размер раскрытия.
        assertTrue("Размер раскрытия задан заранее", ready!!.layoutParams.width > 1000 && ready.layoutParams.height > 500)
        assertEquals("Данные заполнены заранее", "89", ready.findViewWithTag<TextView>("bm_soc").text.toString())
        assertFalse(expansion.isOpen)
        event("modern", MotionEvent.ACTION_UP)
        assertSame("Раскрывается та же, заранее собранная карточка", ready, expansion.card)
        assertEquals(View.VISIBLE, ready.visibility)
        advance(400)
        assertTrue(expansion.isOpen)
        assertEquals("89", expandedText("bm_soc"))
        assertTrue(hiddenCards().isEmpty())
        // Затемнение под карточкой, карточка сверху.
        val scrim = content.findViewWithTag<View>("bm_scrim")
        assertTrue(content.indexOfChild(scrim) < content.indexOfChild(ready))
        tapWindow(5f, 5f)
        advance(400)
        assertFalse(expansion.isOpen)
        assertEquals("После сворачивания лишних View нет", children, content.childCount)
    }

    @Test fun powerCardWithGraphIsPreparedAndReused() {
        open()
        event("power", MotionEvent.ACTION_DOWN)
        advance(96)
        val ready = hiddenCards().singleOrNull() as? PowerModernCard
        assertNotNull(ready)
        assertNotNull("График присоединён заранее", ready!!.graph)
        event("power", MotionEvent.ACTION_UP)
        assertSame(ready, expansion.card)
        advance(400)
        assertTrue(expansion.isOpen)
        assertEquals("power", expansion.tileId)
    }

    /**
     * Ночной хаос-тест 0.9.16: в повреждённой конфигурации две плитки с одним id; по id находилась классическая «АКБ»,
     * и раскрытие плитки «Сеть» роняло панель (checkNotNull размера карточки). Теперь ничего не раскрывается и не падает.
     */
    @Test fun duplicateTileIdDoesNotCrashExpansion() {
        open(listOf(
            TileEntity("dup", "battery", "grid", "Classic", 40, 140, 1, 1,
                config = """{"entity_id":"sensor.bms_soc","button_size":260,"widget_height":210}"""),
            TileEntity("dup", PowerModernConfig.TYPE, "grid", "Сеть", 700, 140, 1, 1,
                config = PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID, """{"button_size":420,"widget_height":300}"""))
        ))
        val children = content.childCount
        val power = (0 until panel.childCount).map { panel.getChildAt(it) }.last { it.tag == "dup" }
        listOf(true, false).forEach { hold ->
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
                try { power.dispatchTouchEvent(e) } finally { e.recycle() }
                if (hold && action == MotionEvent.ACTION_DOWN) advance(96)
            }
            advance(400)
            assertFalse(expansion.isOpen)
            assertTrue("Заранее собранная карточка не осталась в overlay", hiddenCards().isEmpty())
            assertEquals(children, content.childCount)
        }
    }

    @Test fun quickTapWorksAsBeforeWithoutLeftovers() {
        open()
        val children = content.childCount
        tapTile("modern")
        advance(400)
        assertTrue(expansion.isOpen)
        assertTrue(hiddenCards().isEmpty())
        tapWindow(5f, 5f)
        advance(400)
        assertEquals(children, content.childCount)
    }

    @Test fun movingFingerCancelsPreparationBeforeAndAfterBuild() {
        open()
        val children = content.childCount
        // Сдвиг до сборки: карточка не собирается вовсе.
        event("modern", MotionEvent.ACTION_DOWN)
        event("modern", MotionEvent.ACTION_MOVE, x = 80f)
        advance(200)
        assertTrue(hiddenCards().isEmpty())
        event("modern", MotionEvent.ACTION_UP, x = 80f)
        advance(400)
        assertFalse("Сдвиг — не нажатие", expansion.isOpen)
        // Сдвиг после сборки: собранная карточка удаляется.
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        assertEquals(1, hiddenCards().size)
        event("modern", MotionEvent.ACTION_MOVE, x = 80f)
        assertTrue(hiddenCards().isEmpty())
        event("modern", MotionEvent.ACTION_UP, x = 80f)
        advance(400)
        assertFalse(expansion.isOpen)
        assertEquals(children, content.childCount)
    }

    @Test fun cancelAndLongHoldLeaveNothingBehind() {
        open()
        val children = content.childCount
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        event("modern", MotionEvent.ACTION_CANCEL)
        assertTrue(hiddenCards().isEmpty())
        assertEquals(children, content.childCount)
        // Долгое удержание: карточка устаревает и удаляется; отпускание раскрывает новой, как обычно.
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        val stale = hiddenCards().single()
        advance(DashboardPanelController.PREPARED_MAX_AGE_MS + 100)
        assertTrue(hiddenCards().isEmpty())
        event("modern", MotionEvent.ACTION_UP)
        advance(400)
        assertTrue(expansion.isOpen)
        assertNotSame(stale, expansion.card)
        assertTrue(hiddenCards().isEmpty())
    }

    @Test fun changedSettingsOrPanelRebuildDiscardPreparedCard() {
        open()
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        val ready = hiddenCards().single()
        // Настройки плитки изменились (другое устройство, импорт) — заранее собранная карточка не годится.
        val tiles = manager.getAllTiles().map { if (it.id == "modern") it.copy(title = "АКБ 2", config = it.config.replace("520", "521")) else it }
        manager.saveTiles(tiles)
        event("modern", MotionEvent.ACTION_UP)
        assertNotSame(ready, expansion.card)
        advance(400)
        assertTrue(expansion.isOpen)
        assertTrue(hiddenCards().isEmpty())
        tapWindow(5f, 5f)
        advance(400)
        // Перестроение панели во время удержания.
        event("modern", MotionEvent.ACTION_DOWN)
        advance(96)
        assertEquals(1, hiddenCards().size)
        ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)
        assertTrue(hiddenCards().isEmpty())
    }

    @Test fun noPreparationInEditModeOrWhileExpanded() {
        open()
        tapTile("modern")
        advance(400)
        assertTrue(expansion.isOpen)
        // Карточка уже открыта: касание другой плитки не собирает ещё одну.
        event("power", MotionEvent.ACTION_DOWN)
        advance(96)
        assertTrue(hiddenCards().isEmpty())
        event("power", MotionEvent.ACTION_CANCEL)
    }
}
