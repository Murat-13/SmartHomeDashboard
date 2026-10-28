package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SensorConfigResolverTest {
    // These cases first passed against the original methods before extraction.
    // Also check that Activity wrappers keep the same tile and mode.
    private val activity by lazy { MainActivity() }

    private fun tile(config: String = "{}", legacy: String = "[]", title: String = "Other") =
        TileEntity("tile-id", "sensor", "grid", title, 1, 2, 100, 80,
            config = config, collapsedSensorIds = legacy)

    @Suppress("UNCHECKED_CAST")
    private fun ids(tile: TileEntity, expanded: Boolean): List<String> {
        val method = MainActivity::class.java.getDeclaredMethod(
            if (expanded) "getExpandedSensorIds" else "getCollapsedSensorIds", TileEntity::class.java)
        method.isAccessible = true
        val before = tile.copy()
        val resolved = if (expanded) SensorConfigResolver.getExpandedSensorIds(tile)
            else SensorConfigResolver.getCollapsedSensorIds(tile)
        assertEquals(resolved, method.invoke(activity, tile) as List<String>)
        assertEquals(before, tile)
        return resolved
    }

    @Suppress("UNCHECKED_CAST")
    private fun configs(tile: TileEntity, expanded: Boolean): List<SensorConfig> {
        val method = MainActivity::class.java.getDeclaredMethod(
            "loadSensorConfigs", TileEntity::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val before = tile.copy()
        val resolved = SensorConfigResolver.loadSensorConfigs(tile, expanded)
        assertEquals(resolved, method.invoke(activity, tile, expanded) as List<SensorConfig>)
        assertEquals(before, tile)
        return resolved
    }

    private fun key(expanded: Boolean) =
        if (expanded) "expanded_sensor_configs" else "collapsed_sensor_configs"

    private fun withNew(expanded: Boolean, array: String, title: String = "Other") =
        tile("""{"${key(expanded)}":$array,"entity_ids":["sensor.old"]}""",
            """["sensor.old"]""", title)

    @Test fun newConfigsHavePriorityOverBothLegacySources() {
        val tile = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.collapsed"}],
            "expanded_sensor_configs":[{"entity_id":"sensor.expanded"}],"entity_ids":["sensor.old.expanded"]}""",
            """["sensor.old.collapsed"]""")
        assertEquals(listOf("sensor.collapsed"), ids(tile, false))
        assertEquals(listOf("sensor.expanded"), ids(tile, true))
    }

    @Test fun missingNewArraysUseLegacySources() {
        val tile = tile("""{"entity_ids":["sensor.expanded.old"]}""", """["sensor.collapsed.old"]""")
        assertEquals(listOf("sensor.collapsed.old"), ids(tile, false))
        assertEquals(listOf("sensor.expanded.old"), ids(tile, true))
    }

    @Test fun emptyNewArraysUseLegacySources() {
        for (expanded in listOf(false, true)) {
            assertEquals(listOf("sensor.old"), ids(withNew(expanded, "[]"), expanded))
        }
    }

    @Test fun wrongTypeNewArraysUseLegacySources() {
        for (expanded in listOf(false, true)) {
            for (array in listOf("{}", "\"not an array\"", "42", "null")) {
                assertEquals(listOf("sensor.old"), ids(withNew(expanded, array), expanded))
                assertEquals(emptyList<SensorConfig>(), configs(withNew(expanded, array), expanded))
            }
        }
    }

    @Test fun networkFallbackHasExactOrderForEachMode() {
        val tile = tile(title = "⚡ СеТь")
        assertEquals(listOf("sensor.pzem_energy_monitor_pzem_voltage",
            "sensor.pzem_energy_monitor_pzem_power"), ids(tile, false))
        assertEquals(listOf("sensor.pzem_energy_monitor_pzem_voltage",
            "sensor.pzem_energy_monitor_pzem_power", "sensor.pzem_energy_monitor_pzem_current",
            "sensor.pzem_energy_monitor_pzem_frequency", "sensor.pzem_energy_monitor_pzem_energy"),
            ids(tile, true))
    }

    @Test fun temperatureFallbackMatchesTitleSubstringInBothModes() {
        for (expanded in listOf(false, true)) {
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"),
                ids(tile(title = "Датчик ТЕМПЕРАТУРА комнаты"), expanded))
        }
    }

    @Test fun unknownAndEmptyTitlesHaveNoFallback() {
        for (expanded in listOf(false, true)) {
            assertEquals(emptyList<String>(), ids(tile(), expanded))
            assertEquals(emptyList<String>(), ids(tile(title = ""), expanded))
        }
    }

    @Test fun networkTitleTakesPriorityOverTemperatureSubstring() {
        val tile = tile(title = "Температура и Сеть")
        assertEquals(listOf("sensor.pzem_energy_monitor_pzem_voltage",
            "sensor.pzem_energy_monitor_pzem_power"), ids(tile, false))
        assertEquals(listOf("sensor.pzem_energy_monitor_pzem_voltage",
            "sensor.pzem_energy_monitor_pzem_power", "sensor.pzem_energy_monitor_pzem_current",
            "sensor.pzem_energy_monitor_pzem_frequency", "sensor.pzem_energy_monitor_pzem_energy"),
            ids(tile, true))
    }

    @Test fun emptyLegacyArraysFallBackInBothModes() {
        val tile = tile("""{"collapsed_sensor_configs":[],"expanded_sensor_configs":[],"entity_ids":[]}""",
            title = "Температура")
        for (expanded in listOf(false, true)) {
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"), ids(tile, expanded))
        }
    }

    @Test fun newIdsKeepOrderDuplicatesAndEmptyStrings() {
        for (expanded in listOf(false, true)) {
            val tile = withNew(expanded,
                """[{"entity_id":"sensor.b"},{"entity_id":""},{"entity_id":"sensor.a"},{"entity_id":"sensor.b"}]""")
            assertEquals(listOf("sensor.b", "", "sensor.a", "sensor.b"), ids(tile, expanded))
        }
    }

    @Test fun legacyIdsKeepOrderDuplicatesAndEmptyStrings() {
        val tile = tile("""{"entity_ids":["sensor.b","","sensor.a","sensor.b"]}""",
            """["sensor.b","","sensor.a","sensor.b"]""")
        for (expanded in listOf(false, true)) {
            assertEquals(listOf("sensor.b", "", "sensor.a", "sensor.b"), ids(tile, expanded))
        }
    }

    @Test fun malformedConfigSkipsEvenValidLegacyCollapsedIds() {
        for (expanded in listOf(false, true)) {
            val tile = tile("{", """["sensor.old"]""", "Температура")
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"), ids(tile, expanded))
            assertEquals(emptyList<SensorConfig>(), configs(tile, expanded))
        }
    }

    @Test fun invalidNewElementDiscardsPartialListAndSkipsLegacy() {
        for (expanded in listOf(false, true)) {
            for (array in listOf(
                """[{"entity_id":"sensor.valid"},42]""",
                """[{"entity_id":"sensor.valid"},null]""",
                """[{"entity_id":"sensor.valid"},"bad"]""")) {
                val tile = withNew(expanded, array, "Температура")
                assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"), ids(tile, expanded))
                assertEquals(emptyList<SensorConfig>(), configs(tile, expanded))
            }
        }
    }

    @Test fun missingRequiredEntityIdDiscardsPartialListAndSkipsLegacy() {
        for (expanded in listOf(false, true)) {
            val tile = withNew(expanded, """[{"entity_id":"sensor.valid"},{}]""", "Температура")
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"), ids(tile, expanded))
            assertEquals(emptyList<SensorConfig>(), configs(tile, expanded))
        }
    }

    @Test fun malformedCollapsedLegacyJsonUsesTitleFallback() {
        assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"),
            ids(tile(legacy = "{", title = "Температура"), false))
    }

    @Test fun wrongTypeLegacyArraysUseTitleFallback() {
        for (legacy in listOf("{}", "\"bad\"", "42", "null")) {
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"),
                ids(tile(legacy = legacy, title = "Температура"), false))
            assertEquals(listOf("sensor.pzem_energy_monitor_temperatura_tekhpomeshcheniia"),
                ids(tile("""{"entity_ids":$legacy}""", title = "Температура"), true))
        }
    }

    @Test fun jsonGetStringCoercionIsPreservedForIds() {
        for (expanded in listOf(false, true)) {
            assertEquals(listOf("42", "null"),
                ids(withNew(expanded, """[{"entity_id":42},{"entity_id":null}]"""), expanded))
            assertEquals(listOf(SensorConfig("42", "42", 0), SensorConfig("null", "null", 0)),
                configs(withNew(expanded, """[{"entity_id":42},{"entity_id":null}]"""), expanded))
        }
        val tile = tile("""{"entity_ids":[42,null,{}]}""", """[42,null,{}]""")
        assertEquals(listOf("42", "null", "{}"), ids(tile, false))
        assertEquals(listOf("42", "null", "{}"), ids(tile, true))
    }

    @Test fun configsLoadSelectedModeOnly() {
        val tile = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.c","display_name":"C","decimals":1}],
            "expanded_sensor_configs":[{"entity_id":"sensor.e","display_name":"E","decimals":2}]}""")
        assertEquals(listOf(SensorConfig("sensor.c", "C", 1)), configs(tile, false))
        assertEquals(listOf(SensorConfig("sensor.e", "E", 2)), configs(tile, true))
    }

    @Test fun configsKeepOrderDuplicatesAndEmptyValues() {
        for (expanded in listOf(false, true)) {
            val tile = withNew(expanded, """[{"entity_id":"sensor.b","display_name":"","decimals":-2},
                {"entity_id":"","display_name":"Empty","decimals":7},
                {"entity_id":"sensor.b","display_name":"Again","decimals":2}]""")
            assertEquals(listOf(SensorConfig("sensor.b", "", -2), SensorConfig("", "Empty", 7),
                SensorConfig("sensor.b", "Again", 2)), configs(tile, expanded))
        }
    }

    @Test fun configDefaultsUseLastUnderscoreSuffixAndZeroDecimals() {
        for (expanded in listOf(false, true)) {
            val tile = withNew(expanded, """[{"entity_id":"sensor.room_air_temp"},
                {"entity_id":"sensor.plain"},{"entity_id":""},{"entity_id":"sensor.trailing_"}]""")
            assertEquals(listOf(SensorConfig("sensor.room_air_temp", "temp", 0),
                SensorConfig("sensor.plain", "sensor.plain", 0), SensorConfig("", "", 0),
                SensorConfig("sensor.trailing_", "", 0)), configs(tile, expanded))
        }
    }

    @Test fun optStringAndOptIntKeepExistingCoercionAndDefaults() {
        for (expanded in listOf(false, true)) {
            val tile = withNew(expanded, """[{"entity_id":"sensor.a","display_name":42,"decimals":"2"},
                {"entity_id":"sensor.b","display_name":null,"decimals":"bad"},
                {"entity_id":"sensor.c","decimals":null}]""")
            assertEquals(listOf(SensorConfig("sensor.a", "42", 2),
                SensorConfig("sensor.b", "null", 0), SensorConfig("sensor.c", "sensor.c", 0)),
                configs(tile, expanded))
        }
    }

    @Test fun configsDoNotUseLegacySourcesOrTitleFallback() {
        for (expanded in listOf(false, true)) {
            assertEquals(emptyList<SensorConfig>(),
                configs(tile("""{"entity_ids":["sensor.old"]}""", """["sensor.old"]""", "Сеть"), expanded))
            assertEquals(emptyList<SensorConfig>(), configs(withNew(expanded, "[]", "Сеть"), expanded))
        }
    }

    @Test fun emptyConfigStringFallsBackWithoutLegacyRecovery() {
        for (expanded in listOf(false, true)) {
            val tile = tile("", """["sensor.old"]""")
            assertEquals(emptyList<String>(), ids(tile, expanded))
            assertEquals(emptyList<SensorConfig>(), configs(tile, expanded))
        }
    }

    @Test fun resolvingDoesNotModifyAnyTileFields() {
        val tile = tile("""{"collapsed_sensor_configs":[{"entity_id":"sensor.c"}],
            "expanded_sensor_configs":[{"entity_id":"sensor.e"}],"entity_ids":["sensor.old"]}""",
            """["sensor.old"]""", "Сеть")
        val before = tile.copy()
        ids(tile, false)
        ids(tile, true)
        configs(tile, false)
        configs(tile, true)
        assertEquals(before, tile)
    }
}
