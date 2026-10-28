package com.example.smarthomedashboard

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

// Настоящий listener создаётся Activity; сеть и onCreate не запускаются.
// Подмена drawable ручки не проверяет её оформление на устройстве.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SensorCardResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorResizeIntegrationTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            pinGestureFixture(this)
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun create(tile: TileEntity): FrameLayout = try {
        MainActivity::class.java.getDeclaredMethod("createSensorView", TileEntity::class.java)
            .apply { isAccessible = true }.invoke(activity, tile) as FrameLayout
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun tile(type: String = "sensor", config: String = """{"entity_id":"sensor.soc","custom":"keep"}""") =
        TileEntity("card", type, "grid", "Датчик", 20, 30, 1, 1, config = config)

    private fun open(tile: TileEntity = tile()): FrameLayout {
        manager.saveTiles(listOf(tile))
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("isEditMode", true)
        return create(tile).also {
            panel.addView(it)
            layout(it)
            it.x = 20f
            it.y = 30f
        }
    }

    private fun layout(card: FrameLayout) {
        card.forceLayout()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, card.layoutParams.width, card.layoutParams.height)
    }

    private fun handle(card: FrameLayout) =
        (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ImageView>().single()

    private fun send(view: View, action: Int, x: Float = 5f, y: Float = 5f) {
        val event = MotionEvent.obtain(0, 1, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun start(card: FrameLayout) {
        send(handle(card), MotionEvent.ACTION_DOWN)
        send(handle(card), MotionEvent.ACTION_MOVE, 85f, 65f)
        layout(card)
        assertEquals(300, card.width)
        assertEquals(240, card.height)
    }

    private fun prefs() = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
    private fun assertIdle() = assertFalse(ReflectionHelpers.getField<Boolean>(activity, "isResizing"))
    private fun assertOriginal(card: FrameLayout) {
        layout(card)
        assertEquals(220, card.width)
        assertEquals(180, card.height)
        assertEquals(20f, card.x, 0f)
        assertEquals(30f, card.y, 0f)
        assertIdle()
    }

    private fun clearInteractions() {
        MainActivity::class.java.getDeclaredMethod("clearPanelInteractions").apply {
            isAccessible = true
            invoke(activity)
        }
    }

    @Test fun cleanupDuringResizeRollsBackAndLateUpCannotSaveOldCard() {
        val card = open()
        val before = prefs()
        start(card)
        clearInteractions()
        send(handle(card), MotionEvent.ACTION_UP)
        assertOriginal(card)
        assertEquals(before, prefs())
        assertNull(shadowOf(handle(card)).onTouchListener)
        clearInteractions()
        assertEquals(before, prefs())
    }

    @Test fun oldResizeListenerCannotChangeNewPanelOrItsSettings() {
        val old = open()
        start(old)
        val oldHandle = handle(old)
        val oldListener = shadowOf(oldHandle).onTouchListener!!
        clearInteractions()
        panel.removeAllViews()
        val fresh = open(tile(config = """{"button_size":260,"widget_height":200,"custom":"fresh"}"""))
        val before = prefs()
        fun stale(action: Int) {
            val event = MotionEvent.obtain(0, 1, action, 100f, 100f, 0)
            try { oldListener.onTouch(oldHandle, event) } finally { event.recycle() }
        }
        stale(MotionEvent.ACTION_UP)
        stale(MotionEvent.ACTION_DOWN)
        stale(MotionEvent.ACTION_MOVE)
        stale(MotionEvent.ACTION_UP)
        assertEquals(before, prefs())
        assertEquals(260, fresh.layoutParams.width)
        assertEquals(200, fresh.layoutParams.height)
        assertIdle()
    }

    @Test fun cleanupRestoresWeatherTextAndBatteryFillWithoutWriting() {
        for (type in listOf("weather", "battery")) {
            panel.removeAllViews()
            ReflectionHelpers.getField<MutableMap<String, String>>(activity, "singleStates")["sensor.soc"] = "50"
            val card = open(tile(type))
            shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
            val textSize = card.findViewWithTag<TextView>("weather_temp")?.textSize
            val before = prefs()
            start(card)
            clearInteractions()
            send(handle(card), MotionEvent.ACTION_UP)
            assertOriginal(card)
            if (type == "weather") assertEquals(textSize, card.findViewWithTag<TextView>("weather_temp").textSize)
            else assertEquals(110, card.findViewWithTag<View>("battery_progress_fill").layoutParams.width)
            assertEquals(before, prefs())
        }
    }

    @Test fun upPersistsDimensionsAndIndependentSettings() {
        val card = open()
        start(card)
        send(handle(card), MotionEvent.ACTION_UP)
        val saved = manager.loadTiles().single()
        val config = JSONObject(saved.config)
        assertEquals(300, config.getInt("button_size"))
        assertEquals(240, config.getInt("widget_height"))
        assertEquals("sensor.soc", config.getString("entity_id"))
        assertEquals("keep", config.getString("custom"))
        assertEquals(tile().copy(config = saved.config), saved)
        assertIdle()
    }

    @Test fun cancelRestoresGeometryWithoutWritingAndUnblocksSettingsTap() {
        val card = open()
        val before = prefs()
        start(card)
        send(handle(card), MotionEvent.ACTION_CANCEL)
        assertIdle()
        assertOriginal(card)
        assertEquals(before, prefs())
        // Повторное завершение отменённого жеста не должно сохранять данные.
        send(handle(card), MotionEvent.ACTION_UP)
        assertEquals(before, prefs())
        send(card, MotionEvent.ACTION_DOWN)
        send(card, MotionEvent.ACTION_UP)
        awaitPinGate(activity)
        assertEquals(TileSettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun corruptConfigSurvivesUpByteForByteAndReportsOnlyOnUp() {
        val broken = "{broken"
        val card = open(tile(config = broken))
        val before = prefs()
        start(card)
        assertEquals(0, ShadowToast.shownToastCount())
        send(handle(card), MotionEvent.ACTION_UP)
        assertOriginal(card)
        assertEquals(before, prefs())
        assertEquals(broken, manager.loadTiles().single().config)
        assertEquals("Не удалось сохранить размер: повреждены настройки плитки", ShadowToast.getTextOfLatestToast())
    }

    @Test fun clockAndWeatherCancelRestoreTextScale() {
        listOf("clock", "weather").forEach { type ->
            panel.removeAllViews()
            val card = open(tile(type))
            val tags = if (type == "clock") listOf("clock_text", "clock_date")
                else listOf("weather_icon", "weather_temp", "weather_desc")
            val initial = tags.map { card.findViewWithTag<TextView>(it).textSize }
            val before = prefs()
            start(card)
            assertNotEquals(initial, tags.map { card.findViewWithTag<TextView>(it).textSize })
            send(handle(card), MotionEvent.ACTION_CANCEL)
            assertOriginal(card)
            assertEquals(initial, tags.map { card.findViewWithTag<TextView>(it).textSize })
            assertEquals(before, prefs())
        }
    }

    @Test fun clockAndWeatherCorruptConfigRestoreTextScaleOnUp() {
        listOf("clock", "weather").forEach { type ->
            panel.removeAllViews()
            val card = open(tile(type, "{broken"))
            val tags = if (type == "clock") listOf("clock_text", "clock_date")
                else listOf("weather_icon", "weather_temp", "weather_desc")
            val sizes = tags.map { card.findViewWithTag<TextView>(it).textSize }
            start(card)
            send(handle(card), MotionEvent.ACTION_UP)
            assertOriginal(card)
            assertEquals(sizes, tags.map { card.findViewWithTag<TextView>(it).textSize })
            assertEquals("{broken", manager.loadTiles().single().config)
        }
    }

    @Test fun upMergesIntoCurrentTileRatherThanCapturedTile() {
        val card = open()
        start(card)
        val current = manager.loadTiles().single().copy(
            title = "Новое имя", x = 45, y = 55, fontSize = 24, sourceType = "manual",
            config = """{"entity_ids":["sensor.new"],"other":{"enabled":true}}""")
        manager.updateTile(current)
        send(handle(card), MotionEvent.ACTION_UP)
        val saved = manager.loadTiles().single()
        assertEquals(current.copy(config = saved.config), saved)
        val config = JSONObject(saved.config)
        assertEquals("sensor.new", config.getJSONArray("entity_ids").getString(0))
        assertTrue(config.getJSONObject("other").getBoolean("enabled"))
        assertEquals(300, config.getInt("button_size"))
        assertFalse(config.has("entity_id"))
    }

    @Test fun newlyCorruptCurrentConfigIsNotOverwritten() {
        val card = open()
        start(card)
        manager.updateTile(manager.loadTiles().single().copy(config = "{new broken", title = "Новое"))
        val before = prefs()
        send(handle(card), MotionEvent.ACTION_UP)
        assertOriginal(card)
        assertEquals(before, prefs())
    }

    @Test fun deletedTileIsNotRecreated() {
        val card = open()
        start(card)
        manager.deleteTile("card")
        val before = prefs()
        send(handle(card), MotionEvent.ACTION_UP)
        assertTrue(manager.loadTiles().isEmpty())
        assertEquals(before, prefs())
        assertIdle()
    }

    @Test fun batteryFillFollowsMoveCancelAndSuccessfulUp() {
        val states = ReflectionHelpers.getField<MutableMap<String, String>>(activity, "singleStates")
        states["sensor.soc"] = "50"
        val card = open(tile("battery"))
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        val fill = card.findViewWithTag<View>("battery_progress_fill")
        assertEquals(110, fill.layoutParams.width)
        start(card)
        assertEquals(150, fill.layoutParams.width)
        send(handle(card), MotionEvent.ACTION_CANCEL)
        assertOriginal(card)
        assertEquals(110, fill.layoutParams.width)
        start(card)
        send(handle(card), MotionEvent.ACTION_UP)
        assertEquals(150, fill.layoutParams.width)
        assertEquals(300, JSONObject(manager.loadTiles().single().config).getInt("button_size"))
    }

    @Test fun edgeWithLessSpaceThanMinimumDoesNotCrash() {
        val card = open()
        card.x = 1820f
        card.y = 1100f
        send(handle(card), MotionEvent.ACTION_DOWN)
        send(handle(card), MotionEvent.ACTION_MOVE, 85f, 65f)
        assertEquals(120, card.layoutParams.width)
        assertEquals(120, card.layoutParams.height)
        send(handle(card), MotionEvent.ACTION_CANCEL)
        layout(card)
        assertEquals(220, card.width)
        assertEquals(180, card.height)
        assertEquals(1820f, card.x, 0f)
        assertEquals(1100f, card.y, 0f)
        assertIdle()
    }

    @Test fun snapshotIsTakenOnEveryDownAndCancelDoesNotWrite() {
        val card = open()
        card.layoutParams.width = 260
        card.layoutParams.height = 200
        layout(card)
        val before = prefs()
        send(handle(card), MotionEvent.ACTION_DOWN)
        send(handle(card), MotionEvent.ACTION_MOVE, 85f, 65f)
        layout(card)
        send(handle(card), MotionEvent.ACTION_CANCEL)
        layout(card)
        assertEquals(260, card.width)
        assertEquals(200, card.height)
        assertEquals(before, prefs())
        assertIdle()
    }

    @Test fun consecutiveMovesAndUpBeforeLayoutSaveLatestRequestedSize() {
        val card = open()
        send(handle(card), MotionEvent.ACTION_DOWN)
        send(handle(card), MotionEvent.ACTION_MOVE, 45f, 35f)
        send(handle(card), MotionEvent.ACTION_MOVE, 85f, 65f)
        send(handle(card), MotionEvent.ACTION_UP)
        val config = JSONObject(manager.loadTiles().single().config)
        assertEquals(300, config.getInt("button_size"))
        assertEquals(240, config.getInt("widget_height"))
        assertIdle()
    }

    @Test fun batteryRollbackAfterCurrentConfigBecomesCorruptKeepsKnownFill() {
        ReflectionHelpers.getField<MutableMap<String, String>>(activity, "singleStates")["sensor.soc"] = "50"
        val card = open(tile("battery"))
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        start(card)
        manager.updateTile(manager.loadTiles().single().copy(config = "{broken"))
        val before = prefs()
        send(handle(card), MotionEvent.ACTION_UP)
        assertOriginal(card)
        assertEquals(110, card.findViewWithTag<View>("battery_progress_fill").layoutParams.width)
        assertEquals(before, prefs())
    }

}
