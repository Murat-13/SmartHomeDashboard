package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

// Настоящие listeners и ValueAnimator; время виртуальное, сеть и XML/onCreate не запускаются.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorTransitionIntegrationTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val dim by lazy { View(activity).apply { visibility = View.GONE } }

    private data class Bounds(val width: Int, val height: Int, val x: Float, val y: Float)

    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun invoke(name: String, vararg args: Any?): Any? = try {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun tile(id: String, type: String = "sensor", width: Int = 260, height: Int = 210,
        x: Int = 40, y: Int = 140) =
        TileEntity(id, type, "grid", id, x, y, 1, 1,
            config = """{"button_size":$width,"widget_height":$height,"custom":"keep",
                "entity_id":"weather.home",
                "collapsed_sensor_configs":[{"entity_id":"sensor.temp","display_name":"T","decimals":1}],
                "expanded_sensor_configs":[{"entity_id":"sensor.temp","display_name":"Подробное значение","decimals":2}]}""")

    private fun open(weather: Boolean = false) {
        // Vsync продвигается только вместе с виртуальным временем теста.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.resources.displayMetrics.widthPixels = 1920
        activity.resources.displayMetrics.heightPixels = 1200
        manager.saveTiles(listOf(
            tile("a", if (weather) "weather" else "sensor"),
            tile("b", width = 340, height = 260, x = 520, y = 250),
            TileEntity("anchor", "button", "bottom_panel", "anchor", 100, 1000, 1, 1,
                config = """{"entity_id":"light.test_only"}""")
        ))
        activity.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("use_yandex_weather", false).commit()
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("dimOverlay", dim)
        set("yandexWeatherManager", YandexWeatherManager(activity))
        val root = FrameLayout(activity)
        root.addView(panel)
        root.addView(dim)
        activity.window.setContentView(root)
        ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
        ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
        get<MutableMap<String, String>>("singleStates").putAll(mapOf("sensor.temp" to "12.5", "weather.home" to "sunny"))
        get<MutableMap<String, JSONObject>>("entityAttributes")["weather.home"] =
            JSONObject("""{"temperature":20,"forecast":[{"time":"09:00","temperature":18}]}""")
        invoke("refreshBottomPanel")
        layoutCards()
    }

    private fun card(id: String): FrameLayout = panel.findViewWithTag(id)
    private fun bounds(v: View) = Bounds(v.layoutParams.width, v.layoutParams.height, v.x, v.y)
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
        // Каждый шаг даёт отдельный vsync: большой скачок часов доставил бы лишь один кадр.
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
        layoutCards()
    }
    private fun tap(v: View) {
        val throughWindow = sensorSessionFixture(activity).current != null
        val location = IntArray(2)
        if (throughWindow) v.getLocationInWindow(location)
        val time = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, location[0] + 5f, location[1] + 5f, 0)
        val up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, location[0] + 5f, location[1] + 5f, 0)
        try {
            // Раскрытое дерево больше не решает, является ли жест закрывающим тапом.
            if (throughWindow) {
                activity.dispatchTouchEvent(down)
                activity.dispatchTouchEvent(up)
            } else {
                v.dispatchTouchEvent(down)
                v.dispatchTouchEvent(up)
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }
    private fun tap(id: String) = tap(card(id))
    private fun expanded(): View? = sensorSessionFixture(activity).current
    private fun prefs() = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()

    private fun animations(): List<ValueAnimator> {
        // Проверяем и исходный набор, и реестр по View, не меняя ожиданий поведения.
        return when (val registry = ReflectionHelpers.getField<Any>(sensorSessionFixture(activity), "animations")) {
            is Map<*, *> -> registry.values.filterIsInstance<ValueAnimator>()
            is Collection<*> -> registry.filterIsInstance<ValueAnimator>()
            else -> error("Неизвестный реестр анимаций")
        }
    }

    @Test fun closingAUsesItsOwnBoundsWhileBStartsOpening() {
        open()
        val a = card("a")
        val b = card("b")
        val originalA = bounds(a)
        val originalB = bounds(b)
        val before = prefs()
        tap("a")
        advance(400)
        assertSame(a, expanded())
        tap("b")
        assertNull("Первый тап только сворачивает A", expanded())
        advance(64)
        tap("b")
        assertSame(b, expanded())
        advance(400)
        assertEquals(originalA, bounds(a))
        tap("b")
        advance(400)
        assertEquals(originalB, bounds(b))
        assertEquals(before, prefs())
    }

    @Test fun reverseBeforeExpansionEndsHasOnlyOneAnimatorForThatView() {
        open()
        tap("a")
        advance(32)
        tap("a")
        assertNull(expanded())
        assertEquals(1, animations().size)
        advance(400)
        assertTrue(animations().joinToString { "running=${it.isRunning}, time=${it.currentPlayTime}, fraction=${it.animatedFraction}, duration=${it.duration}" }, animations().isEmpty())
    }

    @Test fun reverseBeforeExpansionEndsRestoresOriginalBoundsAndWorkingTouch() {
        open()
        val a = card("a")
        val original = bounds(a)
        val before = prefs()
        tap("a")
        advance(16)
        tap("a")
        advance(400)
        assertEquals(original, bounds(a))
        assertNull(expanded())
        tap("a")
        advance(400)
        assertSame(a, expanded())
        tap("a")
        advance(400)
        assertEquals(original, bounds(a))
        assertEquals(before, prefs())
    }

    @Test fun windowTapOnClosingAOnlyClosesCurrentBWithoutReopeningA() {
        open()
        tap("a")
        advance(400)
        tap("b")
        advance(64)
        tap("b")
        assertSame(card("b"), expanded())
        tap("a")
        // Новый контракт: любой оконный тап закрывает B и не запускает действие A.
        assertNull(expanded())
        advance(400)
        tap("b")
        advance(400)
        assertSame(card("b"), expanded())
        tap("b")
        advance(400)
        assertNull(expanded())
    }

    @Test fun rapidAlternatingCyclesDoNotAccumulateAnimatorsOrChangeSavedSizes() {
        open()
        val a = bounds(card("a"))
        val b = bounds(card("b"))
        val before = prefs()
        repeat(5) {
            tap("a")
            advance(32)
            tap("a")
            assertEquals(1, animations().size)
            tap("b")
            advance(32)
            tap("b")
            assertEquals(2, animations().size)
            advance(400)
            assertTrue(animations().joinToString { "running=${it.isRunning}, time=${it.currentPlayTime}, fraction=${it.animatedFraction}, duration=${it.duration}" }, animations().isEmpty())
            assertEquals(a, bounds(card("a")))
            assertEquals(b, bounds(card("b")))
        }
        assertEquals(before, prefs())
    }

    @Test fun cleanupDuringReverseAndNewOpeningCannotChangeReplacementTree() {
        open()
        val oldA = card("a")
        val oldB = card("b")
        tap("a")
        advance(32)
        tap("a")
        tap("b")
        advance(32)
        invoke("refreshBottomPanel")
        layoutCards()
        val stoppedA = bounds(oldA)
        val stoppedB = bounds(oldB)
        assertTrue(animations().joinToString { "running=${it.isRunning}, time=${it.currentPlayTime}, fraction=${it.animatedFraction}, duration=${it.duration}" }, animations().isEmpty())
        tap("b")
        advance(400)
        assertSame(card("b"), expanded())
        assertEquals(stoppedA, bounds(oldA))
        assertEquals(stoppedB, bounds(oldB))
        assertNull(oldA.parent)
        assertNull(oldB.parent)
        assertNull(shadowOf(oldA).onTouchListener)
        assertNull(shadowOf(oldB).onTouchListener)
        advance(28000)
        invoke("resetSensorCollapseTimer")
        advance(2000)
        assertSame(card("b"), expanded())
    }

    @Test fun deletingClosingAAndRebuildingDoesNotRecreateItOrAffectNewB() {
        open()
        val oldA = card("a")
        tap("a")
        advance(400)
        tap("b")
        advance(32)
        manager.deleteTile("a")
        invoke("refreshBottomPanel")
        layoutCards()
        val stopped = bounds(oldA)
        tap("b")
        advance(400)
        assertSame(card("b"), expanded())
        assertNull(panel.findViewWithTag<View>("a"))
        assertFalse(manager.loadTiles().any { it.id == "a" })
        assertEquals(stopped, bounds(oldA))
        assertNull(shadowOf(oldA).onTouchListener)
    }

    @Test fun weatherReverseRestoresCompactCurrentTreeAndCustomHeight() {
        open(weather = true)
        val a = card("a")
        val original = bounds(a)
        val before = prefs()
        tap("a")
        advance(16)
        tap("a")
        invoke("updateTilesForEntity", "weather.home", "snow", JSONObject("""{"temperature":-3}"""))
        advance(400)
        assertEquals(original, bounds(a))
        val c = a.findViewWithTag<LinearLayout>("sensor_text_container")
        assertEquals(listOf("weather_icon", "weather_temp", "weather_desc"),
            (0 until c.childCount).map { c.getChildAt(it).tag })
        assertEquals(listOf("❄️", "-3°C", "a"), (0 until c.childCount).map { (c.getChildAt(it) as TextView).text.toString() })
        assertEquals(android.view.Gravity.CENTER, c.gravity)
        assertEquals(listOf(8, 8, 8, 8), listOf(c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom))
        assertEquals(210 * 0.25f, a.findViewWithTag<TextView>("weather_temp").textSize, 0.001f)
        assertEquals(before, prefs())
        tap("a")
        advance(400)
        assertSame(a, expanded())
        advance(31000)
        assertNull(expanded())
        assertEquals(original, bounds(a))
    }
}
