package com.example.smarthomedashboard

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CompactWeatherRendererTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun views() = LinearLayout(context).apply {
        listOf("weather_icon", "weather_temp", "weather_desc").forEach { tag ->
            addView(TextView(context).apply {
                this.tag = tag
                setTextColor(Color.WHITE)
                textSize = 19f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(200, 30)
            })
        }
    }
    private fun tile(config: String = "{}") = TileEntity("test-weather", "weather", "grid",
        "Погода", 20, 100, 1, 1, config = config)
    private fun values(view: LinearLayout) =
        (0 until view.childCount).map { (view.getChildAt(it) as TextView).text.toString() }

    @Test fun haUsesResolvedSourceAndTemperatureAttributeInExistingOrder() {
        val root = views()
        val calls = mutableListOf<String>()
        CompactWeatherRenderer.renderHomeAssistant(root,
            tile("""{"entity_ids":["weather.test"],"entity_id":"sensor.other"}"""),
            { calls += it; "PARTLYCLOUDY" },
            { calls += it; JSONObject().put("temperature", "-3.5") })
        assertEquals(listOf("weather.test", "weather.test"), calls)
        assertEquals(listOf("⛅", "-3.5°C", "Погода"), values(root))
    }

    @Test fun nonWeatherSourceUsesItsStateForTemperatureWithoutAttributesFallback() {
        val root = views()
        CompactWeatherRenderer.renderHomeAssistant(root, tile("""{"entity_id":"sensor.test_temp"}"""),
            { assertEquals("sensor.test_temp", it); "7.25" },
            { JSONObject().put("temperature", "999") })
        assertEquals(listOf("🌡️", "7.25°C", "Погода"), values(root))
    }

    @Test fun absentHaStateAndTemperatureKeepExistingMissingValuePresentation() {
        val root = views()
        CompactWeatherRenderer.renderHomeAssistant(root, tile(), { null }, { null })
        assertEquals(listOf("🌡️", "—", "Погода"), values(root))
    }

    @Test fun yandexUsesAlreadyFormattedIconAndDescriptionWithoutChangingExistingViews() {
        val root = views()
        val children = (0 until root.childCount).map { root.getChildAt(it) as TextView }
        val params = children.map { it.layoutParams }
        val sizes = children.map { it.textSize }
        repeat(3) {
            CompactWeatherRenderer.renderYandex(root, "12", "☀️", "Ясно")
        }
        assertEquals(listOf("☀️", "12°C", "Ясно"), values(root))
        assertEquals(children, (0 until root.childCount).map { root.getChildAt(it) })
        children.forEachIndexed { i, child ->
            assertSame(params[i], child.layoutParams)
            assertEquals(sizes[i], child.textSize)
            assertEquals(Color.WHITE, child.currentTextColor)
            assertEquals(Gravity.CENTER, child.gravity)
        }
    }

    @Test fun repeatedHaUpdateReplacesOldValuesAndKeepsNoDataText() {
        val root = views()
        CompactWeatherRenderer.renderHomeAssistant(root, tile(), { "snowy" }, { JSONObject().put("temperature", 5) })
        assertEquals(listOf("❄️", "5°C", "Погода"), values(root))
        CompactWeatherRenderer.renderHomeAssistant(root, tile(), { "unknown" }, { JSONObject() })
        assertEquals(listOf("🌡️", "—", "Погода"), values(root))
        assertEquals(3, root.childCount)
        CompactWeatherRenderer.renderYandex(root, "—", "⛅", "unknown")
        assertEquals(listOf("⛅", "—°C", "unknown"), values(root))
    }
}
