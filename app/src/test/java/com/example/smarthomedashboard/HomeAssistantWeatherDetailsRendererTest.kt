package com.example.smarthomedashboard

import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeAssistantWeatherDetailsRendererTest {
    private val context = RuntimeEnvironment.getApplication()
    private val attributes = linkedMapOf<String, JSONObject>()
    private val states = linkedMapOf<String, String>()
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }

    @Before fun selectHomeAssistant() {
        context.getSharedPreferences("dashboard_prefs", 0).edit()
            .putBoolean("use_yandex_weather", false).commit()
    }

    private fun tile(config: String = "{}") =
        TileEntity("test", "weather", "grid", "Моя погода", 0, 0, 100, 100, config = config)

    @Suppress("UNCHECKED_CAST")
    private fun invokeActivity(tile: TileEntity, container: LinearLayout) {
        for ((name, values) in listOf("entityAttributes" to attributes, "singleStates" to states)) {
            ArchitectureTestAccess.field(name).apply {
                isAccessible = true
                val target = get(activity) as MutableMap<String, Any>
                target.clear()
                target.putAll(values)
            }
        }
        try {
            ArchitectureTestAccess.method(activity, "buildWeatherDetailedContent",
                TileEntity::class.java, LinearLayout::class.java).apply {
                isAccessible = true
                invoke(activity, tile, container)
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    // These scenarios passed on the original method before extracting the HA branch.
    private fun render(tile: TileEntity, container: LinearLayout) =
        HomeAssistantWeatherDetailsRenderer.render(context, tile, container,
            attributes::get, states::get) {}

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun forecastRow(container: LinearLayout): LinearLayout =
        (container.getChildAt(3) as HorizontalScrollView).getChildAt(0) as LinearLayout

    private fun fullAttributes() = JSONObject("""{
        "temperature":22.5,"humidity":65,"pressure":1012,"wind_speed":4.5,"wind_bearing":180,
        "forecast":[
            {"datetime":"2026-10-26T03:00:00","condition":"SUNNY","temperature":10,"templow":2},
            {"time":"08:00","condition":"snow","temp":-1}
        ]}""")

    private fun withUsLocale(block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun fullWeatherPreservesOrderedValuesAndViewStructure() = withUsLocale {
        attributes["weather.home"] = fullAttributes()
        states["weather.home"] = "PARTLYCLOUDY"
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(4, container.childCount)
        assertEquals(listOf("⛅", "Моя погода", "Переменная облачность", "22.5°C",
            "💧", "Влажность", "65%", "⏲️", "Давление", "1012 hPa",
            "🌬️", "Ветер", "4.5 км/ч", "🧭", "Направление", "180",
            "Прогноз:", "MON 26", "☀️", "10°/2°", "08:00", "❄️", "-1°"), texts(container))
        assertEquals(LinearLayout.VERTICAL, container.orientation)
        assertEquals(Gravity.TOP or Gravity.START, container.gravity)
        assertEquals(listOf(30, 30, 30, 30), listOf(container.paddingLeft,
            container.paddingTop, container.paddingRight, container.paddingBottom))
        val top = container.getChildAt(0) as LinearLayout
        assertEquals(4, top.childCount)
        assertEquals(LinearLayout.HORIZONTAL, top.orientation)
        assertEquals(2, (container.getChildAt(1) as GridLayout).columnCount)
        val title = (top.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        assertEquals(Color.WHITE, title.currentTextColor)
        val scroll = container.getChildAt(3) as HorizontalScrollView
        assertTrue(scroll.isFillViewport)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, scroll.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, scroll.layoutParams.height)
        assertEquals(2, forecastRow(container).childCount)
    }

    @Test fun missingAttributesAndStateKeepPlaceholdersAndUnavailableForecast() {
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("🌡️", "Моя погода", "Unknown", "—°C",
            "💧", "Влажность", "—%", "⏲️", "Давление", "— hPa",
            "🌬️", "Ветер", "— км/ч", "🧭", "Направление", "—", "Прогноз:",
            "Прогноз временно недоступен\n(Проверьте атрибуты в HA)"), texts(container))
        assertEquals(4, container.childCount)
        assertEquals(1, forecastRow(container).childCount)
    }

    @Test fun emptyPrimaryForecastDoesNotFallThroughToDailyForecast() {
        attributes["weather.home"] = JSONObject("""{"forecast":[],
            "forecast_daily":[{"time":"10:00","temperature":9}]}""")
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"),
            texts(forecastRow(container)))
    }

    @Test fun entityIdsArrayTakesPriorityAndUsesFirstEntryOnly() {
        attributes["weather.first"] = JSONObject("""{"temperature":1}""")
        attributes["weather.second"] = JSONObject("""{"temperature":2}""")
        attributes["weather.single"] = JSONObject("""{"temperature":3}""")
        states["weather.first"] = "rain"
        val container = LinearLayout(context)
        render(tile("""{"entity_ids":["weather.first","weather.second"],"entity_id":"weather.single"}"""), container)
        assertEquals(listOf("🌧️", "Моя погода", "Дождь", "1°C"), texts(container.getChildAt(0)))
    }

    @Test fun missingEmptyOrWrongTypeArrayUsesSingleEntityField() {
        attributes["weather.single"] = JSONObject("""{"temperature":3}""")
        for (config in listOf("""{"entity_id":"weather.single"}""",
            """{"entity_ids":[],"entity_id":"weather.single"}""",
            """{"entity_ids":{},"entity_id":"weather.single"}""")) {
            val container = LinearLayout(context)
            render(tile(config), container)
            assertEquals("3°C", texts(container.getChildAt(0)).last())
        }
    }

    @Test fun missingOrMalformedConfigFallsBackToWeatherHome() {
        attributes["weather.home"] = JSONObject("""{"temperature":8}""")
        for (config in listOf("{}", "{", "", """{"entity_ids":[]}""")) {
            val container = LinearLayout(context)
            render(tile(config), container)
            assertEquals("8°C", texts(container.getChildAt(0)).last())
        }
    }

    @Test fun emptyAndCoercedEntityIdsAreNotReplacedWithFallback() {
        attributes[""] = JSONObject("""{"temperature":4}""")
        attributes["42"] = JSONObject("""{"temperature":5}""")
        attributes["null"] = JSONObject("""{"temperature":6}""")
        attributes["weather.home"] = JSONObject("""{"temperature":8}""")
        for ((config, expected) in listOf(
            """{"entity_id":""}""" to "4°C",
            """{"entity_ids":[""]}""" to "4°C",
            """{"entity_ids":[42]}""" to "5°C",
            """{"entity_ids":[null]}""" to "6°C")) {
            val container = LinearLayout(context)
            render(tile(config), container)
            assertEquals(expected, texts(container.getChildAt(0)).last())
        }
    }

    @Test fun forecastSourcesUseNullFallbackAndPreservePriority() {
        val cases = listOf(
            """{"forecast":[{"time":"01:00","temperature":1}],
                "forecast_daily":[{"time":"02:00","temperature":2}],
                "forecast_hourly":[{"time":"03:00","temperature":3}]}""" to listOf("01:00", "🌡️", "1°"),
            """{"forecast_daily":[{"time":"02:00","temperature":2}],
                "forecast_hourly":[{"time":"03:00","temperature":3}]}""" to listOf("02:00", "🌡️", "2°"),
            """{"forecast":{},"forecast_daily":"bad",
                "forecast_hourly":[{"time":"03:00","temperature":3}]}""" to listOf("03:00", "🌡️", "3°"))
        for ((json, expected) in cases) {
            attributes["weather.home"] = JSONObject(json)
            val container = LinearLayout(context)
            render(tile(), container)
            assertEquals(expected, texts(forecastRow(container)))
        }
    }

    @Test fun forecastKeepsDuplicatesAndLimitsOutputToFirstFourteenItems() {
        val forecast = JSONArray()
        for (i in 0..15) forecast.put(JSONObject().put("time", "09:00").put("temperature", i))
        attributes["weather.home"] = JSONObject().put("forecast", forecast)
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(14, forecastRow(container).childCount)
        assertEquals(listOf("0°", "1°", "2°", "3°", "4°", "5°", "6°",
            "7°", "8°", "9°", "10°", "11°", "12°", "13°"),
            (0 until forecastRow(container).childCount).map {
                texts(forecastRow(container).getChildAt(it)).last()
            })
        assertEquals(14, texts(forecastRow(container)).count { it == "09:00" })
    }

    @Test fun invalidForecastElementIsSkippedAndContainerIsComplete() {
        attributes["weather.home"] = JSONObject("""{"forecast":[{"time":"10:00"},42]}""")
        val container = LinearLayout(context)
        // Раньше тест закреплял исключение и оборванное построение. Теперь неверная
        // запись не должна скрывать корректную запись с прежними fallback полей.
        render(tile(), container)
        assertEquals(4, container.childCount)
        assertEquals(listOf("10:00", "🌡️", "—°"), texts(forecastRow(container)))
    }

    @Test fun forecastMissingFieldsAndMalformedDateKeepOriginalDefaults() {
        attributes["weather.home"] = JSONObject("""{"forecast":[{},
            {"datetime":"bad-date-T","condition":"lightning","temp":4,"templow":-2},
            {"datetime":"","time":"10:00","temperature":"","temp":9}]}""")
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("", "🌡️", "—°", "ate-T", "⚡", "4°/-2°", "", "🌡️", "°"),
            texts(forecastRow(container)))
    }

    @Test fun jsonNullValuesAndUnknownStateRetainExistingText() {
        attributes["weather.home"] = JSONObject("""{"temperature":null,"humidity":null,
            "forecast":[{"temperature":null,"templow":null}]}""")
        states["weather.home"] = "CUSTOM"
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("🌡️", "Моя погода", "Custom", "null°C"), texts(container.getChildAt(0)))
        assertTrue(texts(container).contains("null%"))
        assertEquals(listOf("", "🌡️", "null°/null°"), texts(forecastRow(container)))
    }

    @Test fun rebuildingUsesLatestMapsWithoutDuplicatingViewsOrModifyingJson() = withUsLocale {
        val original = fullAttributes()
        val originalJson = original.toString()
        attributes["weather.home"] = original
        states["weather.home"] = "clear"
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(originalJson, original.toString())
        val oldTop = container.getChildAt(0)
        attributes["weather.home"] = JSONObject("""{"temperature":-3,"forecast":[]}""")
        states["weather.home"] = "snow"
        render(tile(), container)
        assertEquals(4, container.childCount)
        assertNull(oldTop.parent)
        assertEquals(listOf("❄️", "Моя погода", "Снег", "-3°C"), texts(container.getChildAt(0)))
        assertEquals(1, texts(container).count { it == "Моя погода" })
        assertEquals(originalJson, original.toString())
    }

    @Test fun providersReadAfterClearingAndTouchCallbackRunsOnlyAtEventTime() {
        val container = LinearLayout(context).apply {
            addView(TextView(context).apply { text = "old content" })
        }
        val trace = mutableListOf<String>()
        var action = "initial"
        HomeAssistantWeatherDetailsRenderer.render(
            context, tile("""{"entity_id":"weather.pick"}"""), container,
            { entity ->
                assertEquals(0, container.childCount)
                trace.add("attributes:$entity")
                JSONObject("""{"temperature":5}""")
            },
            { entity ->
                assertEquals(0, container.childCount)
                trace.add("state:$entity")
                "clear"
            },
            { trace.add("touch:$action") }
        )
        assertEquals(listOf("attributes:weather.pick", "state:weather.pick"), trace)
        action = "later"
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        try {
            (container.getChildAt(3) as HorizontalScrollView).dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
        assertEquals(listOf("attributes:weather.pick", "state:weather.pick", "touch:later"), trace)
    }

    @Test fun scrollTouchResetsExistingCollapseTimer() {
        var runs = 0
        val session = sensorSessionFixture(activity)
        val source = android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(220, 180)
        }
        session.expand(source, SensorExpansionSession.Bounds(220, 180, 0f, 0f)) { runs++ }
        val container = LinearLayout(context)
        invokeActivity(tile(), container)
        val scroll = container.getChildAt(3) as HorizontalScrollView
        fun touch() {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            try {
                scroll.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        touch()
        assertEquals(0, runs)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        touch()
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals(0, runs)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(1, runs)
        session.clear()
    }

    @Test fun activityKeepsYandexBranchAndItsEarlyReturn() {
        val manager = YandexWeatherManager(context).apply { currentTemp = "11"; currentState = "clear" }
        ArchitectureTestAccess.field("yandexWeatherManager").apply {
            isAccessible = true
            set(activity, manager)
        }
        context.getSharedPreferences("dashboard_prefs", 0).edit()
            .putBoolean("use_yandex_weather", true).commit()
        attributes["weather.home"] = JSONObject("""{"temperature":99}""")
        val container = LinearLayout(context)
        invokeActivity(tile(), container)
        assertEquals(2, container.childCount)
        assertEquals(listOf("☀️", "Погода", "Ясно", "11°C"), texts(container.getChildAt(0)))
        assertFalse(texts(container).contains("Прогноз:"))
    }

    @Test fun invalidElementsAtBeginningMiddleAndEndKeepValidOrderAndInput() {
        val badElements = listOf<Any>(42, JSONObject.NULL, true, "broken", JSONArray())
        for (bad in badElements) {
            for (position in 0..2) {
                val values = mutableListOf<Any>(
                    JSONObject("""{"time":"09:00","condition":"clear","temperature":7}"""),
                    JSONObject("""{"time":"10:00","condition":"rain","temperature":5}"""))
                values.add(position, bad)
                val attrs = JSONObject().put("temperature", 12).put("forecast", JSONArray(values))
                val before = attrs.toString()
                attributes["weather.home"] = attrs
                states["weather.home"] = "clear"
                val container = LinearLayout(context)
                repeat(2) {
                    render(tile(), container)
                    assertEquals(4, container.childCount)
                    assertEquals(listOf("☀️", "Моя погода", "Ясно", "12°C"), texts(container.getChildAt(0)))
                    assertEquals(listOf("09:00", "☀️", "7°", "10:00", "🌧️", "5°"), texts(forecastRow(container)))
                    assertEquals(2, forecastRow(container).childCount)
                    assertEquals(before, attrs.toString())
                }
            }
        }
        assertEquals(0, org.robolectric.shadows.ShadowToast.shownToastCount())
    }

    @Test fun allInvalidElementsUseEmptyForecastMessageWithoutLosingCurrentWeather() {
        val attrs = JSONObject("""{"temperature":-3,"humidity":80,"forecast":[null,42,true,"broken",[]],
            "forecast_daily":[{"time":"12:00","temperature":99}]}""")
        val before = attrs.toString()
        attributes["weather.home"] = attrs
        states["weather.home"] = "snow"
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("❄️", "Моя погода", "Снег", "-3°C"), texts(container.getChildAt(0)))
        assertTrue(texts(container).contains("80%"))
        assertEquals(listOf("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"), texts(forecastRow(container)))
        assertEquals(4, container.childCount)
        assertEquals(before, attrs.toString())
    }

    @Test fun missingOrWrongTypeForecastUsesExistingEmptyMessageAndCurrentValues() {
        for (json in listOf(
            """{"temperature":9}""",
            """{"temperature":9,"forecast":null}""",
            """{"temperature":9,"forecast":{}}""",
            """{"temperature":9,"forecast":"broken"}""",
            """{"temperature":9,"forecast":42}""",
            """{"temperature":9,"forecast":true}""")) {
            val attrs = JSONObject(json)
            val before = attrs.toString()
            attributes["weather.home"] = attrs
            states["weather.home"] = "rain"
            val container = LinearLayout(context)
            render(tile(), container)
            assertEquals(listOf("🌧️", "Моя погода", "Дождь", "9°C"), texts(container.getChildAt(0)))
            assertEquals(listOf("Прогноз временно недоступен\n(Проверьте атрибуты в HA)"), texts(forecastRow(container)))
            assertEquals(before, attrs.toString())
        }
    }

    @Test fun invalidPositionsDoNotHideValidForecastAfterFirstFourteenPositions() {
        val forecast = JSONArray()
        repeat(14) { forecast.put(JSONObject.NULL) }
        forecast.put(JSONObject("""{"time":"15:00","temperature":3}"""))
        forecast.put(JSONObject("""{"time":"16:00","temperature":2}"""))
        val attrs = JSONObject().put("forecast", forecast)
        val before = attrs.toString()
        attributes["weather.home"] = attrs
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(listOf("15:00", "🌡️", "3°", "16:00", "🌡️", "2°"), texts(forecastRow(container)))
        assertEquals(before, attrs.toString())
    }

    @Test fun mixedForecastStillLimitsOutputToFourteenValidEntries() {
        val forecast = JSONArray()
        for (i in 0..15) {
            forecast.put(false)
            forecast.put(JSONObject().put("time", "09:00").put("temperature", i))
        }
        attributes["weather.home"] = JSONObject().put("forecast", forecast)
        val container = LinearLayout(context)
        render(tile(), container)
        assertEquals(14, forecastRow(container).childCount)
        assertEquals(listOf("0°", "1°", "2°", "3°", "4°", "5°", "6°",
            "7°", "8°", "9°", "10°", "11°", "12°", "13°"),
            (0 until forecastRow(container).childCount).map { texts(forecastRow(container).getChildAt(it)).last() })
    }

}
