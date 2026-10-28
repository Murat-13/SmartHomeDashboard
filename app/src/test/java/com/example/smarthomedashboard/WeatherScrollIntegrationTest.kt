package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
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

// Реальные Views/layout, MotionEvent и callbacks Activity; XML/onCreate и сеть не запускаются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherScrollIntegrationTest {
    private val controller by lazy { Robolectric.buildActivity(MainActivity::class.java) }
    private val activity get() = controller.get()
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val yandex by lazy { YandexWeatherManager(activity) }
    private var useYandex = false

    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun weather(id: String, entity: String) =
        TileEntity(id, "weather", "grid", id, 20, 100, 1, 1,
            config = """{"entity_id":"$entity","button_size":240,"widget_height":200}""")

    private fun open(y: Boolean = false) {
        useYandex = y
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 500
        activity.resources.displayMetrics.heightPixels = 1400
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", y).commit()
        manager.saveTiles(listOf(weather("a", "weather.home"), weather("b", "weather.other"),
            TileEntity("clock", "clock", "grid", "Часы", 500, 800, 1, 1),
            TileEntity("anchor", "button", "bottom_panel", "anchor", 600, 1000, 1, 1,
                config = """{"entity_id":"light.test_only"}""")))
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", View(activity).apply { visibility = View.GONE })
        set("yandexWeatherManager", yandex)
        val root = FrameLayout(activity)
        (panel.parent as? ViewGroup)?.removeView(panel)
        root.addView(panel)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        get<MutableMap<String, String>>("singleStates").putAll(mapOf("weather.home" to "sunny", "weather.other" to "snow"))
        get<MutableMap<String, JSONObject>>("entityAttributes")["weather.home"] = attributes(20, 14)
        get<MutableMap<String, JSONObject>>("entityAttributes")["weather.other"] = attributes(-2, 14)
        populateYandex(20, 14)
        invoke("refreshBottomPanel")
        layoutCards()
        tap(card("a"))
        advance(400)
    }

    private fun attributes(temp: Int, count: Int) =
        JSONObject().put("temperature", temp).put("forecast", JSONArray().apply {
            repeat(count) { put(JSONObject().put("time", "12:00").put("temperature", 18).put("condition", "clear")) }
        })
    private fun populateYandex(temp: Int, count: Int) {
        yandex.currentTemp = temp.toString()
        yandex.currentState = "clear"
        yandex.forecasts = List(count) { YandexWeatherManager.ForecastDay("2026-10-27", "18", "10", "clear") }
        yandex.hourlyForecasts = List(count) { YandexWeatherManager.ForecastHour("12", "18", "clear") }
    }
    private fun update(temp: Int, count: Int = 14) {
        if (useYandex) {
            populateYandex(temp, count)
            // Точка входа, вызываемая onWeatherUpdated после обновления данных менеджера.
            invoke("updateWeatherViews")
        } else {
            invoke("updateTilesForEntity", "weather.home", "sunny", attributes(temp, count))
        }
    }
    private fun card(id: String = "a"): FrameLayout = panel.findViewWithTag(id)
    private fun content(id: String = "a"): LinearLayout = card(id).findViewWithTag("sensor_text_container")
    private fun scrolls(id: String = "a") = (0 until content(id).childCount)
        .map { content(id).getChildAt(it) }.filterIsInstance<HorizontalScrollView>()
    private fun layoutCards() {
        for (i in 0 until panel.childCount) layout(panel.getChildAt(i))
    }
    private fun layout(view: View) {
        view.forceLayout()
        val width = view.layoutParams.width.takeIf { it > 0 } ?: 400
        val height = view.layoutParams.height.takeIf { it > 0 } ?: 300
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
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
    private fun tap(view: View) {
        val throughWindow = ReflectionHelpers.getField<View?>(activity, "expandedSensorView") != null
        val location = IntArray(2)
        if (throughWindow) view.getLocationInWindow(location)
        val time = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, location[0] + 5f, location[1] + 5f, 0)
        val up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, location[0] + 5f, location[1] + 5f, 0)
        try {
            // Раскрытое дерево больше не решает, является ли жест закрывающим тапом.
            if (throughWindow) {
                activity.dispatchTouchEvent(down)
                activity.dispatchTouchEvent(up)
            } else {
                view.dispatchTouchEvent(down)
                view.dispatchTouchEvent(up)
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }
    private fun position() {
        scrolls().forEachIndexed { index, scroll ->
            val x = if (index == 0) 160 else 240
            scroll.scrollTo(x, 0)
            assertEquals("Фикстура должна иметь настоящий горизонтальный диапазон", x, scroll.scrollX)
        }
    }
    private fun expectedPositions() = if (useYandex) listOf(160, 240) else listOf(160)
    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun prefs() = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()

    private fun clockDoesNotReplaceDetailedTree(y: Boolean) {
        open(y)
        position()
        val old = scrolls()
        val children = (0 until content().childCount).map { content().getChildAt(it) }
        val handler = get<Handler>("clockHandler")
        handler.post(get<Runnable>("clockRunnable"))
        advance(10000)
        assertEquals(children, (0 until content().childCount).map { content().getChildAt(it) })
        assertEquals(old, scrolls())
        assertEquals(expectedPositions(), scrolls().map { it.scrollX })
        handler.removeCallbacks(get<Runnable>("clockRunnable"))
    }
    @Test fun haClockTickDoesNotReplaceDetailedTreeOrScroll() = clockDoesNotReplaceDetailedTree(false)
    @Test fun yandexClockTickDoesNotReplaceDetailedTreeOrScroll() = clockDoesNotReplaceDetailedTree(true)

    @Test fun realUpdatesChangeWeatherAndPreserveEverySectionAfterLayout() {
        for (y in listOf(false, true)) {
            open(y)
            position()
            val old = scrolls()
            val before = prefs()
            update(21)
            assertNotSame(old.first(), scrolls().first())
            layoutCards()
            assertTrue(texts(content()).contains("21°C"))
            assertEquals(expectedPositions(), scrolls().map { it.scrollX })
            assertEquals(before, prefs())
        }
    }

    @Test fun shorterForecastClampsToActualNativeRangeIncludingZero() {
        for (y in listOf(false, true)) {
            open(y)
            scrolls().forEach { it.scrollTo(100000, 0) }
            val oldPositions = scrolls().map { it.scrollX }
            update(21, 12)
            layoutCards()
            scrolls().forEachIndexed { i, scroll ->
                val restored = scroll.scrollX
                // Эталон границы даёт сам Android ScrollView, а не копия helper.
                scroll.scrollTo(100000, 0)
                assertTrue(scroll.scrollX > 0)
                assertTrue(scroll.scrollX < oldPositions[i])
                assertEquals(scroll.scrollX, restored)
            }
            update(22, 1)
            layoutCards()
            assertTrue(scrolls().all { it.scrollX == 0 })
        }
    }

    @Test fun updatesBeforeLayoutKeepPendingPositionAndOnlyLatestValues() {
        for (y in listOf(false, true)) {
            open(y)
            position()
            update(21)
            val superseded = scrolls()
            update(22)
            update(23)
            layoutCards()
            assertTrue(texts(content()).contains("23°C"))
            assertFalse(texts(content()).contains("21°C"))
            assertEquals(expectedPositions(), scrolls().map { it.scrollX })
            superseded.forEach { layout(it) }
            assertTrue(superseded.all { it.scrollX == 0 })
            assertEquals(expectedPositions(), scrolls().map { it.scrollX })
        }
    }

    @Test fun collapseCancelsPendingRestorationAndNextOpeningStartsAtZero() {
        for (y in listOf(false, true)) {
            open(y)
            position()
            update(21)
            val old = scrolls()
            invoke("collapseSensor")
            old.forEach { layout(it) }
            assertTrue(old.all { it.scrollX == 0 })
            advance(400)
            assertEquals("21°C", card().findViewWithTag<TextView>("weather_temp").text.toString())
            tap(card())
            advance(400)
            assertTrue(scrolls().all { it.scrollX == 0 })
        }
    }

    @Test fun rebuildCancelsPendingRestorationOfRemovedTree() {
        open()
        position()
        update(21)
        val old = scrolls()
        invoke("refreshBottomPanel")
        old.forEach { layout(it) }
        assertTrue(old.all { it.scrollX == 0 })
        tap(card())
        advance(400)
        assertTrue(scrolls().all { it.scrollX == 0 })
    }

    @Test fun otherCardDoesNotInheritPendingOrAppliedPosition() {
        for (y in listOf(false, true)) {
            open(y)
            position()
            update(21)
            val old = scrolls()
            tap(card("b"))
            advance(64)
            tap(card("b"))
            advance(400)
            old.forEach { layout(it) }
            assertTrue(old.all { it.scrollX == 0 })
            assertSame(card("b"), get<View>("expandedSensorView"))
            assertTrue(scrolls("b").all { it.scrollX == 0 })
        }
    }

    @Test fun changedEntityOrProviderStartsWithItsOwnZeroPosition() {
        open()
        position()
        update(21)
        manager.updateTile(manager.loadTiles().single { it.id == "a" }.copy(
            config = """{"entity_id":"weather.other","button_size":240,"widget_height":200}"""))
        invoke("updateWeatherViews")
        layoutCards()
        assertTrue(texts(content()).contains("-2°C"))
        assertTrue(scrolls().all { it.scrollX == 0 })
        scrolls().single().scrollTo(160, 0)
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", true).commit()
        invoke("updateWeatherViews")
        layoutCards()
        assertEquals(2, scrolls().size)
        assertTrue(scrolls().all { it.scrollX == 0 })
    }

    @Test fun missingHourlySectionCannotTransferItsPositionToDailySection() {
        open(true)
        position()
        yandex.hourlyForecasts = emptyList()
        invoke("updateWeatherViews")
        layoutCards()
        assertEquals(1, scrolls().size)
        assertEquals(240, scrolls().single().scrollX)
        yandex.hourlyForecasts = List(14) { YandexWeatherManager.ForecastHour("12", "18", "clear") }
        invoke("updateWeatherViews")
        layoutCards()
        assertEquals(listOf(0, 240), scrolls().map { it.scrollX })
    }

    @Test fun clockAndCompactWeatherStillUpdateWithoutTouchingOpenForecast() {
        for (y in listOf(false, true)) {
            open(y)
            val tree = content().getChildAt(0)
            card("clock").findViewWithTag<TextView>("clock_text").text = "old"
            if (y) yandex.currentTemp = "37" else {
                get<MutableMap<String, JSONObject>>("entityAttributes")["weather.other"] = attributes(37, 14)
            }
            invoke("updateClockViews")
            assertSame(tree, content().getChildAt(0))
            assertEquals("37°C", card("b").findViewWithTag<TextView>("weather_temp").text.toString())
            assertTrue(card("clock").findViewWithTag<TextView>("clock_text").text.matches(Regex("\\d{2}:\\d{2}")))
        }
    }

    @Test fun scrollTouchExtendsThirtySecondAutoCollapse() {
        for (y in listOf(false, true)) {
            open(y)
            advance(20000)
            val scroll = scrolls().first()
            val location = IntArray(2)
            scroll.getLocationInWindow(location)
            val time = android.os.SystemClock.uptimeMillis()
            // Тап теперь закрывает карточку; таймер продлеваем настоящим движением.
            for ((action, dx) in listOf(MotionEvent.ACTION_DOWN to 100f,
                MotionEvent.ACTION_MOVE to 50f, MotionEvent.ACTION_MOVE to 0f, MotionEvent.ACTION_UP to 0f)) {
                val e = MotionEvent.obtain(time, time + 1, action, location[0] + dx, location[1] + 5f, 0)
                try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            advance(15000)
            assertSame(card(), get<View>("expandedSensorView"))
            advance(16000)
            assertNull(get<View?>("expandedSensorView"))
            assertNotNull(card().findViewWithTag<TextView>("weather_temp"))
        }
    }

    @Test fun destroyCancelsPendingScrollRestoration() {
        open()
        position()
        update(21)
        val old = scrolls()
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
        ReflectionHelpers.getField<LifecycleRegistry>(activity, "mFragmentLifecycleRegistry").currentState = Lifecycle.State.CREATED
        controller.destroy()
        old.forEach { layout(it) }
        assertTrue(old.all { it.scrollX == 0 })
        advance(1000)
        assertNull(get<View?>("expandedSensorView"))
    }
}
