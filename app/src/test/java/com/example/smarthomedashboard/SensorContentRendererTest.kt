package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.util.IllegalFormatException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SensorContentRendererTest {
    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        android.R.style.Theme_Material)
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val states = linkedMapOf<String, String>()
    private val attributes = linkedMapOf<String, JSONObject>()
    private var targetTemperature = "C"

    private fun tile(config: String = "{}", fontSize: Int = 16) =
        TileEntity("test", "sensor", "grid", "Header", 0, 0, 100, 100,
            config = config, fontSize = fontSize)

    private fun container() = LinearLayout(context).apply {
        addView(TextView(context).apply { text = "Header"; tag = "preserved"; id = 123 })
    }

    private fun rows(container: LinearLayout): List<String> =
        (1 until container.childCount).map { (container.getChildAt(it) as TextView).text.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun invokeActivity(tile: TileEntity, ids: List<String>, expanded: Boolean, container: LinearLayout) {
        for ((name, values) in listOf("entityAttributes" to attributes, "singleStates" to states)) {
            ArchitectureTestAccess.field(name).apply {
                isAccessible = true
                val target = get(activity) as MutableMap<String, Any>
                target.clear()
                target.putAll(values)
            }
        }
        ArchitectureTestAccess.field("tempUnit").apply {
            isAccessible = true
            set(activity, targetTemperature)
        }
        try {
            ArchitectureTestAccess.method(activity, "buildSensorContent", TileEntity::class.java,
                List::class.java, Boolean::class.javaPrimitiveType, LinearLayout::class.java).apply {
                isAccessible = true
                invoke(activity, tile, ids, expanded, container)
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    // These scenarios passed before extraction and continue through the Activity wrapper.
    private fun render(tile: TileEntity, ids: List<String>, expanded: Boolean, container: LinearLayout) =
        invokeActivity(tile, ids, expanded, container)

    private fun withUsLocale(block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun providersKeepArgumentsOrderAndFormattingResultBeforeReplacingViews() {
        val tile = tile()
        val container = container()
        val oldRow = TextView(context).apply { text = "old" }
        container.addView(oldRow)
        val calls = mutableListOf<String>()
        var value = 0
        SensorContentRenderer.render(
            tile, listOf("sensor.a_temp", "sensor.a_temp", "sensor.b"), true, container,
            { actualTile, expanded ->
                assertSame(tile, actualTile)
                assertEquals(2, container.childCount)
                calls.add("config:$expanded")
                listOf(SensorConfig("sensor.a_temp", "T", 2), SensorConfig("sensor.b", "B", 0))
            },
            { id ->
                assertEquals(2, container.childCount)
                calls.add("state:$id")
                (++value).toString()
            },
            { raw, decimals, id ->
                assertEquals(2, container.childCount)
                calls.add("format:$raw:$decimals:$id")
                "$raw!"
            }
        )
        assertEquals(listOf("config:true",
            "state:sensor.a_temp", "format:1:2:sensor.a_temp",
            "state:sensor.a_temp", "format:2:2:sensor.a_temp",
            "state:sensor.b", "format:3:0:sensor.b"), calls)
        assertEquals(listOf("T  1!", "T  2!", "B  3!"), rows(container))
        assertNull(oldRow.parent)
    }

    @Test fun emptyIdsStillLoadConfigurationOnceWithoutReadingOrFormattingState() {
        val tile = tile()
        val container = LinearLayout(context)
        var loads = 0
        SensorContentRenderer.render(tile, emptyList(), false, container,
            { actualTile, expanded ->
                assertSame(tile, actualTile)
                assertFalse(expanded)
                loads++
                emptyList()
            },
            { error("State must not be read") },
            { _, _, _ -> error("Value must not be formatted") }
        )
        assertEquals(1, loads)
        assertEquals(0, container.childCount)
    }

    @Test fun preservesFirstChildAndReplacesAllOtherChildren() = withUsLocale {
        val container = container()
        val first = container.getChildAt(0)
        val old = TextView(context).apply { text = "old" }
        container.addView(old)
        container.addView(View(context))
        states["sensor.a"] = "1"
        states["sensor.b"] = "2"
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.a","display_name":"Long"},
            {"entity_id":"sensor.b","display_name":"B"}]}""")
        render(tile, listOf("sensor.a", "sensor.b"), false, container)
        assertSame(first, container.getChildAt(0))
        assertEquals("preserved", first.tag)
        assertEquals(123, first.id)
        assertNull(old.parent)
        assertEquals(listOf("Long  1", "B     2"), rows(container))
    }

    @Test fun repeatedBuildReplacesRowsWithoutDuplicatingThem() = withUsLocale {
        val container = container()
        val header = container.getChildAt(0)
        states["sensor.a"] = "1"
        render(tile(), listOf("sensor.a"), false, container)
        val oldRow = container.getChildAt(1)
        states["sensor.a"] = "2"
        render(tile(), listOf("sensor.a"), false, container)
        assertEquals(2, container.childCount)
        assertSame(header, container.getChildAt(0))
        assertNull(oldRow.parent)
        assertEquals(listOf("sensor.a  2"), rows(container))
    }

    @Test fun entityOrderDuplicatesAndFirstMatchingConfigurationArePreserved() = withUsLocale {
        states["sensor.a"] = "1"
        states["sensor.b"] = "2.25"
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.b","display_name":"B-first","decimals":1},
            {"entity_id":"sensor.b","display_name":"B-later","decimals":2},
            {"entity_id":"sensor.a","display_name":"A","decimals":0}]}""")
        val container = container()
        render(tile, listOf("sensor.b", "sensor.a", "sensor.b"), false, container)
        assertEquals(listOf("B-first  2.3", "A        1", "B-first  2.3"), rows(container))
    }

    @Test fun userNamesAndPrecisionCoexistWithCurrentFallbackName() = withUsLocale {
        states["sensor.room_air_temp"] = "12.5"
        states["sensor.unit_rpm"] = "10"
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.room_air_temp","display_name":"User","decimals":2}]}""")
        val container = container()
        render(tile, listOf("sensor.room_air_temp", "sensor.unit_rpm"), false, container)
        assertEquals(listOf("User  12.50", "rpm   10"), rows(container))
        render(tile(), listOf("sensor.plain"), false, container)
        assertEquals(listOf("sensor.plain  —"), rows(container))
    }

    @Test fun configuredEmptyDisplayNameAndEmptyEntityIdAreNotReplaced() = withUsLocale {
        val container = container()
        states["sensor.a"] = "5"
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.a","display_name":"","decimals":0}]}""")
        render(tile, listOf("sensor.a"), false, container)
        assertEquals(listOf("  5"), rows(container))
        render(tile(), listOf(""), false, container)
        assertEquals(listOf("  —"), rows(container))
    }

    @Test fun defaultPrecisionUsesExistingCaseSensitiveIdentifierChecks() = withUsLocale {
        for ((id, expected) in listOf(
            "sensor.room_temp" to "temp  1.2",
            "sensor.grid_freq" to "freq  1.2",
            "sensor.grid_current" to "current  1.23",
            "sensor.grid_energy" to "energy  1.23",
            "sensor.unit_other" to "other  1",
            "sensor.current_temp" to "temp  1.2",
            "sensor.TEMP" to "sensor.TEMP  1")) {
            states[id] = "1.234"
            val container = container()
            render(tile(), listOf(id), false, container)
            assertEquals(listOf(expected), rows(container))
        }
    }

    @Test fun missingAndNonNumericStatesKeepExistingText() = withUsLocale {
        val container = container()
        render(tile(), listOf("sensor.a"), false, container)
        assertEquals(listOf("sensor.a  —"), rows(container))
        states["sensor.a"] = "unknown"
        render(tile(), listOf("sensor.a"), false, container)
        assertEquals(listOf("sensor.a  unknown"), rows(container))
    }

    @Test fun emptyIdsKeepOnlyFirstChildAndEmptyContainerRemainsEmpty() {
        val container = container()
        val header = container.getChildAt(0)
        container.addView(TextView(context))
        render(tile(), emptyList(), false, container)
        assertEquals(1, container.childCount)
        assertSame(header, container.getChildAt(0))
        val empty = LinearLayout(context)
        render(tile(), emptyList(), true, empty)
        assertEquals(0, empty.childCount)
    }

    @Test fun initiallyEmptyContainerKeepsItsFirstGeneratedRowOnNextBuild() = withUsLocale {
        val container = LinearLayout(context)
        states["sensor.a"] = "1"
        render(tile(), listOf("sensor.a"), false, container)
        assertEquals(1, container.childCount)
        assertEquals("sensor.a  1", (container.getChildAt(0) as TextView).text.toString())
        val firstRow = container.getChildAt(0)
        states["sensor.a"] = "2"
        render(tile(), listOf("sensor.a"), false, container)
        assertEquals(2, container.childCount)
        assertSame(firstRow, container.getChildAt(0))
        assertEquals(listOf("sensor.a  2"), rows(container))
    }

    @Test fun collapsedAndExpandedModesUseTheirOwnConfiguration() = withUsLocale {
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.a","display_name":"C","decimals":0}],
            "expanded_sensor_configs":[{"entity_id":"sensor.a","display_name":"E","decimals":2}]}""")
        states["sensor.a"] = "1.25"
        val container = container()
        render(tile, listOf("sensor.a"), false, container)
        assertEquals(listOf("C  1"), rows(container))
        render(tile, listOf("sensor.a"), true, container)
        assertEquals(listOf("E  1.25"), rows(container))
    }

    @Test fun formatterUsesAttributesOfEachEntityAndCurrentTargetTemperature() = withUsLocale {
        states["sensor.room_temp"] = "0"
        states["sensor.outdoor_temp"] = "10"
        attributes["sensor.room_temp"] = JSONObject("""{"unit_of_measurement":"°C"}""")
        attributes["sensor.outdoor_temp"] = JSONObject("""{"unit_of_measurement":"°F"}""")
        targetTemperature = "F"
        val container = container()
        render(tile(), listOf("sensor.room_temp", "sensor.outdoor_temp"), false, container)
        assertEquals(listOf("temp  32.0", "temp  10.0"), rows(container))
        targetTemperature = "C"
        states["sensor.outdoor_temp"] = "212"
        render(tile(), listOf("sensor.outdoor_temp"), false, container)
        assertEquals(listOf("temp  100.0"), rows(container))
    }

    @Test fun textViewsKeepContainerContextFontShadowAndLayoutParameters() = withUsLocale {
        val container = container()
        states["sensor.a"] = "1"
        render(tile(fontSize = 23), listOf("sensor.a"), false, container)
        val row = container.getChildAt(1) as TextView
        assertSame(container.context, row.context)
        assertEquals(Color.WHITE, row.currentTextColor)
        assertEquals(Gravity.CENTER, row.gravity)
        assertEquals(23f * context.resources.displayMetrics.scaledDensity, row.textSize, 0.01f)
        assertEquals(Typeface.create("monospace", Typeface.BOLD), row.typeface)
        assertEquals(4f, row.shadowRadius, 0f)
        assertEquals(2f, row.shadowDx, 0f)
        assertEquals(2f, row.shadowDy, 0f)
        assertEquals(Color.BLACK, row.shadowColor)
        assertTrue(row.layoutParams is LinearLayout.LayoutParams)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, row.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.layoutParams.height)
    }

    @Test fun formattingFailureLeavesExistingChildrenUntouched() = withUsLocale {
        states["sensor.a"] = "1"
        val tile = tile("""{"collapsed_sensor_configs":[
            {"entity_id":"sensor.a","display_name":"A","decimals":-1}]}""")
        val container = container()
        val old = TextView(context).apply { text = "old" }
        container.addView(old)
        assertThrows(IllegalFormatException::class.java) {
            render(tile, listOf("sensor.a"), false, container)
        }
        assertEquals(2, container.childCount)
        assertSame(old, container.getChildAt(1))
    }

    @Test fun onlyConfiguredBatterySocUsesDashForMissingOrNonFiniteReadings() = withUsLocale {
        val battery = tile("""{"entity_ids":["sensor.any_charge","sensor.other"],
            "entity_id":"sensor.not_selected"}""").copy(type = "battery")
        val container = container()
        states["sensor.other"] = "unknown"
        for (value in listOf(null, "unknown", "unavailable", "", "bad", "NaN", "Infinity", "-Infinity", "1e100")) {
            states.remove("sensor.any_charge")
            if (value != null) states["sensor.any_charge"] = value
            for (expanded in listOf(false, true)) {
                render(battery, listOf("sensor.any_charge", "sensor.other"), expanded, container)
                assertEquals(value, listOf("charge        —", "sensor.other  unknown"), rows(container))
            }
        }
        for (value in listOf("0", "10", "50", "100")) {
            states["sensor.any_charge"] = value
            render(battery, listOf("sensor.any_charge"), false, container)
            assertEquals(listOf("charge  $value"), rows(container))
        }
        states["sensor.any_charge"] = "50%"
        render(battery, listOf("sensor.any_charge"), false, container)
        assertEquals(listOf("charge  50%"), rows(container))
        states["sensor.any_charge"] = "unknown"
        render(battery.copy(type = "sensor"), listOf("sensor.any_charge"), false, container)
        assertEquals(listOf("charge  unknown"), rows(container))
    }
}
