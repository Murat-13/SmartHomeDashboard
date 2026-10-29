package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PanelDependencyIndexTest {
    private fun tile(id: String, type: String = "sensor", config: String = "{}", rules: String = "[]") =
        TileEntity(id, type, "grid", id, 0, 0, 1, 1, config = config, colorRules = rules)
    private fun ids(index: PanelDependencyIndex, vararg entities: String) = index.affected(entities.toList()).map { it.id }

    @Test fun collapsedExpandedLegacyAndRuleSourcesShareOneDefinition() {
        val first = tile("first", config = """{"collapsed_sensor_configs":[{"entity_id":"sensor.collapsed"}],
            "expanded_sensor_configs":[{"entity_id":"sensor.expanded"}]}""",
            rules = """[{"entity_id":"sensor.rule","from":"10","color_hex":"#FF112233"}]""")
            .copy(collapsedSensorIds = """["sensor.legacy"]""",
                appearance = """{"extra":"sensor.appearance"}""", conditions = """{"source":"sensor.condition"}""")
        val index = PanelDependencyIndex(listOf(first, tile("unrelated")))
        for (entity in listOf("sensor.collapsed", "sensor.expanded", "sensor.rule", "sensor.legacy", "sensor.appearance", "sensor.condition")) {
            assertEquals(entity, listOf("first"), ids(index, entity))
        }
        assertTrue(ids(index, "sensor.elsewhere").isEmpty())
        assertEquals(PanelEntityIds.collect(listOf(first, tile("unrelated")), emptyList()), index.entityIds)
    }

    @Test fun batterySourceIsIndexedEvenWhenItsTextShowsAnotherEntity() {
        val index = PanelDependencyIndex(listOf(tile("battery", "battery", """{"entity_ids":["sensor.any_soc"],
            "collapsed_sensor_configs":[{"entity_id":"sensor.power"}]}"""), tile("other")))
        assertEquals(listOf("battery"), ids(index, "sensor.any_soc"))
        assertEquals(listOf("battery"), ids(index, "sensor.power"))
    }

    @Test fun buttonsGroupsAndWeatherHaveOnlyTheirOwnDependencies() {
        val index = PanelDependencyIndex(listOf(
            tile("button", "button", """{"entity_id":"custom.one"}"""),
            tile("group", "group", """{"entity_ids":["light.one","light.two"]}"""),
            tile("weather", "weather"), tile("weather2", "weather", """{"entity_id":"weather.other"}"""),
            tile("sensor")))
        assertEquals(listOf("button"), ids(index, "custom.one"))
        assertEquals(listOf("group"), ids(index, "light.two"))
        assertEquals(listOf("weather"), ids(index, "weather.home"))
        assertEquals(listOf("weather2"), ids(index, "weather.other"))
    }

    @Test fun sharedSourcesAndBatchUnionNeverRenderTheSameTileTwice() {
        val index = PanelDependencyIndex(listOf(
            tile("first", config = """{"entity_ids":["sensor.one","sensor.two","sensor.one"]}"""),
            tile("second", config = """{"entity_id":"sensor.one"}""")))
        assertEquals(listOf("first", "second"), ids(index, "sensor.one", "sensor.two", "sensor.one"))
    }

    @Test fun malformedConfigurationHasLimitedFallbackWithoutTouchingValidOtherCards() {
        val index = PanelDependencyIndex(listOf(tile("broken", config = "{"),
            tile("valid", config = """{"entity_id":"sensor.valid"}""")))
        assertEquals(listOf("broken"), ids(index, "sensor.unrelated"))
        assertEquals(listOf("valid", "broken"), ids(index, "sensor.valid"))
    }

    @Test fun replacementIndexDoesNotKeepRemovedSourcesOrOldGroupMembers() {
        val before = listOf(tile("group", "group", """{"entity_ids":["light.old"]}"""))
        assertEquals(listOf("group"), ids(PanelDependencyIndex(before), "light.old"))
        val index = PanelDependencyIndex(listOf(tile("group", "group", """{"entity_ids":["light.new"]}""")))
        assertTrue(ids(index, "light.old").isEmpty())
        assertEquals(listOf("group"), ids(index, "light.new"))
        assertTrue(PanelDependencyIndex(emptyList()).entityIds.isEmpty())
    }

    @Test fun advancedArrayFieldsAreNotMistakenForMalformedConfiguration() {
        val advanced = tile("advanced").copy(conditions = """[{"entity_id":"sensor.condition"}]""",
            appearance = """["sensor.appearance"]""")
        val index = PanelDependencyIndex(listOf(advanced))
        assertEquals(listOf("advanced"), ids(index, "sensor.condition"))
        assertTrue(ids(index, "sensor.unrelated").isEmpty())
    }
}
