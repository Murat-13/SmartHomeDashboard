package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

// Проверяем последовательности настоящих методов Activity и ValueAnimator на реальных Views.
// onCreate и XML не запускаются; иконка resize подменяется существующим SensorCardResources.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SensorCardResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherCardIntegrationTest {
    private val activity by lazy {
        Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            resources.displayMetrics.widthPixels = 1920
            resources.displayMetrics.heightPixels = 1200
        }
    }
    private val manager by lazy { TileManager(activity) }
    private val panel by lazy { FrameLayout(activity) }
    private val yandex by lazy { YandexWeatherManager(activity) }

    private fun set(name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        try {
            return MainActivity::class.java.declaredMethods.single {
                it.name == name && it.parameterTypes.size == args.size
            }.apply { isAccessible = true }.invoke(activity, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun tile(type: String = "weather", config: String = """{"entity_id":"weather.home"}""") =
        TileEntity("card", type, "grid", "Моя погода", 20, 30, 1, 1, config = config)

    private fun open(tile: TileEntity = tile(), useYandex: Boolean = false): FrameLayout {
        manager.saveTiles(listOf(tile))
        activity.getSharedPreferences("dashboard_prefs", 0).edit()
            .putBoolean("use_yandex_weather", useYandex).commit()
        set("tileManager", manager)
        set("bottomPanel", panel)
        set("yandexWeatherManager", yandex)
        val card = invoke("createSensorView", tile) as FrameLayout
        panel.addView(card)
        card.x = 20f
        card.y = 30f
        layout(card)
        return card
    }

    private fun layout(card: FrameLayout) {
        card.forceLayout()
        for (i in 0 until card.childCount) card.getChildAt(i).forceLayout()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.height, View.MeasureSpec.EXACTLY)
        )
        card.layout(0, 0, card.layoutParams.width, card.layoutParams.height)
    }

    private fun finishAnimation(card: FrameLayout) {
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        layout(card)
    }

    private fun content(card: FrameLayout): LinearLayout = card.findViewWithTag("sensor_text_container")

    private fun resizeHandle(card: FrameLayout): ImageView =
        (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ImageView>().single()

    private fun ha(state: String = "sunny", temperature: Int = 20) {
        invoke("updateTilesForEntity", "weather.home", state,
            JSONObject().put("temperature", temperature).put("humidity", 60)
                .put("forecast", org.json.JSONArray("""[{"time":"09:00","temperature":18,"condition":"clear"}]""")))
    }

    private fun sensor() {
        invoke("updateTilesForEntity", "sensor.test_temperature", "12.5", JSONObject())
    }

    private fun populateYandex() {
        yandex.currentTemp = "7"
        yandex.currentState = "clear"
        yandex.humidity = "60"
        yandex.forecasts = listOf(YandexWeatherManager.ForecastDay("2026-10-26", "8", "2", "clear"))
        yandex.hourlyForecasts = listOf(YandexWeatherManager.ForecastHour("9", "6", "rain"))
        invoke("updateWeatherViews")
    }

    private fun textValues(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { textValues(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertCompact(card: FrameLayout, temperature: String, description: String,
        icon: String = "☀️", height: Int = 180) {
        val c = content(card)
        assertEquals(3, c.childCount)
        assertEquals(listOf("weather_icon", "weather_temp", "weather_desc"),
            (0 until c.childCount).map { c.getChildAt(it).tag })
        assertEquals(listOf(icon, temperature, description), textValues(c))
        assertEquals(LinearLayout.VERTICAL, c.orientation)
        assertEquals(Gravity.CENTER, c.gravity)
        assertEquals(listOf(8, 8, 8, 8), listOf(c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom))
        assertEquals(FrameLayout.LayoutParams::class.java, c.layoutParams.javaClass)
        assertEquals(-1, c.layoutParams.width)
        assertEquals(-1, c.layoutParams.height)
        val iconView = c.getChildAt(0) as TextView
        val tempView = c.getChildAt(1) as TextView
        val descView = c.getChildAt(2) as TextView
        assertEquals(height * 0.35f, iconView.textSize, 0.001f)
        assertEquals(height * 0.25f, tempView.textSize, 0.001f)
        assertEquals(height * 0.12f, descView.textSize, 0.001f)
        assertEquals(Typeface.BOLD, tempView.typeface.style)
        listOf(iconView, tempView, descView).forEach {
            assertEquals(Color.WHITE, it.currentTextColor)
            assertEquals(Gravity.CENTER, it.gravity)
            assertEquals(LinearLayout.LayoutParams::class.java, it.layoutParams.javaClass)
            assertEquals(-1, it.layoutParams.width)
            assertEquals(-2, it.layoutParams.height)
        }
    }

    private fun expand(card: FrameLayout) {
        invoke("expandSensor", manager.loadTiles().single(), card)
        finishAnimation(card)
        assertSame(card, ReflectionHelpers.getField<View>(activity, "expandedSensorView"))
        if (manager.loadTiles().single().type == "weather") {
            assertEquals(Gravity.TOP or Gravity.START, content(card).gravity)
            assertEquals(30, content(card).paddingLeft)
        }
    }

    private fun collapse(card: FrameLayout) {
        invoke("collapseSensor")
        finishAnimation(card)
        assertNull(ReflectionHelpers.getField<View?>(activity, "expandedSensorView"))
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

    @Test fun sensorEventKeepsCollapsedHaWeatherTreeAndValues() {
        val card = open()
        ha()
        sensor()
        assertCompact(card, "20°C", "Моя погода")
        ha("snow", -3)
        assertCompact(card, "-3°C", "Моя погода", "❄️")
    }

    @Test fun haFullCycleRestoresCurrentValuesContainerAndOuterCard() {
        val card = open()
        val c = content(card)
        val params = c.layoutParams
        val handle = resizeHandle(card)
        ha()
        expand(card)
        assertTrue(textValues(c).contains("Прогноз:"))
        invoke("collapseSensor")
        // Компактные значения нужны сразу, пока внешняя геометрия ещё анимируется.
        assertCompact(card, "20°C", "Моя погода")
        finishAnimation(card)
        assertSame(c, content(card))
        assertSame(params, c.layoutParams)
        assertSame(handle, resizeHandle(card))
        assertSame(panel, card.parent)
        assertEquals("card", card.tag)
        assertEquals(2, card.childCount)
        assertEquals(220, card.layoutParams.width)
        assertEquals(180, card.layoutParams.height)
        assertEquals(20f, card.x, 0f)
        assertEquals(30f, card.y, 0f)
        assertEquals(0.85f, card.alpha, 0f)
        ha("rainy", 14)
        assertCompact(card, "14°C", "Моя погода", "🌧️")
    }

    @Test fun yandexCycleKeepsItsOwnDisplayAndUpdatesAfterCollapse() {
        val card = open(useYandex = true)
        populateYandex()
        sensor()
        assertCompact(card, "7°C", "Ясно")
        expand(card)
        assertTrue(textValues(content(card)).contains("Сегодня:"))
        assertTrue(textValues(content(card)).contains("На неделю:"))
        yandex.currentTemp = "-2"
        yandex.currentState = "snow"
        sensor()
        collapse(card)
        assertCompact(card, "-2°C", "Снег", "❄️")
        yandex.currentTemp = "9"
        yandex.currentState = "clear"
        sensor()
        assertCompact(card, "9°C", "Ясно")
    }

    @Test fun repeatedCyclesUseRestoredTouchHandlerWithoutDuplicates() {
        val card = open()
        ha()
        repeat(3) {
            tap(card)
            finishAnimation(card)
            assertSame(card, ReflectionHelpers.getField<View>(activity, "expandedSensorView"))
            tap(card)
            finishAnimation(card)
            assertCompact(card, "20°C", "Моя погода")
            assertNull(ReflectionHelpers.getField<View?>(activity, "expandedSensorView"))
            assertEquals(2, card.childCount)
        }
    }

    @Test fun haUpdateWhileExpandedStillAllowsCorrectCollapse() {
        val card = open()
        ha()
        expand(card)
        ha("snow", -5)
        assertTrue(textValues(content(card)).contains("-5°C"))
        sensor()
        assertTrue(textValues(content(card)).contains("Прогноз:"))
        collapse(card)
        assertCompact(card, "-5°C", "Моя погода", "❄️")
    }

    @Test fun automaticCollapseReturnsCompactWeather() {
        val card = open()
        ha()
        expand(card)
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        layout(card)
        assertNull(ReflectionHelpers.getField<View?>(activity, "expandedSensorView"))
        assertCompact(card, "20°C", "Моя погода")
    }

    @Test fun compactWeatherKeepsUserResizedHeightAfterCycle() {
        open()
        set("isEditMode", true)
        // В реальном экране вход в редактирование перестраивает Views.
        panel.removeAllViews()
        var card = invoke("createSensorView", manager.loadTiles().single()) as FrameLayout
        panel.addView(card)
        layout(card)
        val handle = resizeHandle(card)
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(0, 0, action, x, y, 0)
            try { handle.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 5f, 5f)
        send(MotionEvent.ACTION_MOVE, 85f, 65f)
        layout(card)
        send(MotionEvent.ACTION_UP, 85f, 65f)
        assertEquals(240, manager.loadTiles().single().config.let { JSONObject(it).getInt("widget_height") })
        set("isEditMode", false)
        panel.removeAllViews()
        card = invoke("createSensorView", manager.loadTiles().single()) as FrameLayout
        panel.addView(card)
        layout(card)
        ha()
        expand(card)
        collapse(card)
        assertEquals(300, card.layoutParams.width)
        assertEquals(240, card.layoutParams.height)
        assertCompact(card, "20°C", "Моя погода", height = 240)
    }

    @Test fun sensorAndBatteryStillRestoreTheirOwnRows() {
        listOf("sensor", "battery").forEach { type ->
            panel.removeAllViews()
            val config = """{"entity_id":"sensor.soc",
                "collapsed_sensor_configs":[{"entity_id":"sensor.value","display_name":"Кратко","decimals":1}],
                "expanded_sensor_configs":[{"entity_id":"sensor.detail","display_name":"Подробно","decimals":0}]}"""
            val card = open(tile(type, config))
            invoke("updateTilesForEntity", "sensor.value", "12.5", JSONObject())
            invoke("updateTilesForEntity", "sensor.detail", "8", JSONObject())
            expand(card)
            assertTrue(textValues(content(card)).contains("Подробно  8"))
            collapse(card)
            assertEquals(listOf("Моя погода", "Кратко  12.5"), textValues(content(card)))
            if (type == "battery") assertNotNull(card.findViewWithTag<View>("battery_progress_fill"))
        }
    }

    private class RecordingSocket : WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://localhost/test-only").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return true }
        override fun send(bytes: ByteString): Boolean = error("Unexpected binary message")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            // Сетевого соединения у записывающего сокета нет.
        }
    }

    @Test fun restorationDoesNotWriteSettingsOrSendCommands() {
        val socket = RecordingSocket()
        val client = HomeAssistantWebSocket("http://localhost/test-only", "test-token", { _, _, _ -> }, {}, {})
        HomeAssistantWebSocket::class.java.getDeclaredField("authenticated").apply {
            isAccessible = true; set(client, true)
        }
        HomeAssistantWebSocket::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true; set(client, socket)
        }
        set("webSocket", client)
        listOf(false, true).forEach { useYandex ->
            panel.removeAllViews()
            val card = open(useYandex = useYandex)
            if (useYandex) populateYandex() else ha()
            val tilePrefs = activity.getSharedPreferences("tiles_prefs", 0).all.toMap()
            val prefs = activity.getSharedPreferences("dashboard_prefs", 0).all.toMap()
            repeat(2) {
                expand(card)
                collapse(card)
            }
            assertEquals(tilePrefs, activity.getSharedPreferences("tiles_prefs", 0).all)
            assertEquals(prefs, activity.getSharedPreferences("dashboard_prefs", 0).all)
            assertTrue(socket.messages.isEmpty())
        }
    }

    @Test fun haMalformedForecastUpdateCanCollapseAndRecoverOnNextValidForecast() {
        val card = open()
        val badForecasts = listOf<Any>(
            org.json.JSONArray("""[42,{"time":"11:00","condition":"snow","temperature":-1},null]"""),
            org.json.JSONArray("""[42,null,[]]"""),
            JSONObject("""{"wrong":"type"}"""))
        for ((index, bad) in badForecasts.withIndex()) {
            ha()
            expand(card)
            val attrs = JSONObject().put("temperature", -3).put("humidity", 80).put("forecast", bad)
            val before = attrs.toString()
            invoke("updateTilesForEntity", "weather.home", "snow", attrs)
            val c = content(card)
            assertEquals(4, c.childCount)
            assertEquals(listOf("❄️", "Моя погода", "Снег", "-3°C"), textValues(c.getChildAt(0)))
            val row = (c.getChildAt(3) as android.widget.HorizontalScrollView).getChildAt(0)
            assertEquals(if (index == 0) listOf("11:00", "❄️", "-1°")
                else listOf("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"), textValues(row))
            assertEquals(before, attrs.toString())
            val cache = ReflectionHelpers.getField<Map<String, JSONObject>>(activity, "entityAttributes")
            assertSame(bad, cache.getValue("weather.home").get("forecast"))
            collapse(card)
            assertCompact(card, "-3°C", "Моя погода", "❄️")
            ha()
            assertCompact(card, "20°C", "Моя погода")
            expand(card)
            val restoredRow = (content(card).getChildAt(3) as android.widget.HorizontalScrollView).getChildAt(0)
            assertEquals(listOf("09:00", "☀️", "18°"), textValues(restoredRow))
            collapse(card)
            assertCompact(card, "20°C", "Моя погода")
        }
        assertEquals(0, org.robolectric.shadows.ShadowToast.shownToastCount())
    }

}
