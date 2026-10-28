package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PanelSubscriptionPlanTest {
    private fun tile(type: String, config: String = "{}") =
        TileEntity("test-$type", type, "grid", "Тест", 0, 0, 1, 1, config = config)

    @Test fun keepsMotionFirstAndDeduplicatesSourcesInEncounterOrder() {
        val plan = PanelSubscriptionPlan.create(listOf(
            tile("button", """{"entity_id":"light.one","entity_ids":["switch.two","light.one"]}"""),
            tile("sensor", """{"entity_id":"sensor.main","entity_ids":["sensor.extra","switch.two"]}"""),
            tile("group", """{"entity_ids":["light.three","sensor.motion"]}""")
        ), "sensor.motion", false)
        assertEquals(listOf("sensor.motion", "light.one", "switch.two", "sensor.main", "sensor.extra", "light.three"),
            plan.entityIds)
        assertTrue(plan.forecastIds.isEmpty())
    }

    @Test fun forecastUsesRendererPriorityAndFallbackOnlyForWeatherTiles() {
        val plan = PanelSubscriptionPlan.create(listOf(
            tile("weather"),
            tile("weather", """{"entity_ids":["weather.first","weather.ignored"],"entity_id":"weather.other"}"""),
            tile("weather", """{"entity_id":"weather.single"}"""),
            tile("weather", """{"entity_ids":["sensor.outdoor","weather.not_selected"]}"""),
            tile("sensor", """{"entity_id":"weather.sensor_source"}"""),
            tile("weather", """{"entity_id":"weather.single"}""")
        ), null, false)
        assertEquals(listOf("weather.home", "weather.first", "weather.single"), plan.forecastIds.toList())
        assertTrue("sensor.outdoor" in plan.entityIds)
        assertTrue("weather.sensor_source" in plan.entityIds)
    }

    @Test fun yandexRemovesOnlyForecastRequestsAndFreshPlanDoesNotAccumulateOldSources() {
        val tiles = listOf(tile("weather", """{"entity_id":"weather.one"}"""))
        val ha = PanelSubscriptionPlan.create(tiles, "binary_sensor.motion", false)
        val yandex = PanelSubscriptionPlan.create(tiles, "binary_sensor.motion", true)
        assertEquals(ha.entityIds, yandex.entityIds)
        assertEquals(setOf("weather.one"), ha.forecastIds)
        assertTrue(yandex.forecastIds.isEmpty())
        val next = PanelSubscriptionPlan.create(listOf(tile("weather", """{"entity_id":"weather.two"}""")), null, false)
        assertEquals(setOf("weather.two"), next.forecastIds)
        assertEquals(setOf("weather.one"), ha.forecastIds)
    }

    @Test fun malformedButtonsAndGroupsNeverContributePartialIds() {
        val plan = PanelSubscriptionPlan.create(listOf(
            tile("button", """{"entity_id":"light.rejected","entity_ids":["switch.rejected",null]}"""),
            tile("group", """{"entity_ids":["light.rejected",42]}"""),
            tile("group", "{broken"),
            tile("sensor", """{"entity_id":"sensor.valid"}""")
        ), null, false)
        assertEquals(listOf("sensor.valid"), plan.entityIds)
        assertTrue(plan.forecastIds.isEmpty())
    }

    @Test fun ordinarySourcesKeepExistingEmptyFieldAndMalformedJsonBehavior() {
        val plan = PanelSubscriptionPlan.create(listOf(
            tile("sensor", "{broken"),
            tile("sensor", """{"entity_id":"","entity_ids":[]}"""),
            tile("battery", """{"entity_id":"sensor.battery","entity_ids":["sensor.extra"]}""")
        ), null, false)
        assertEquals(listOf("sensor.battery", "sensor.extra"), plan.entityIds)
        assertTrue(plan.forecastIds.isEmpty())
        assertEquals(PanelSubscriptionPlan(emptyList(), emptySet()),
            PanelSubscriptionPlan.create(emptyList(), null, false))
    }

    @Test fun calculationDoesNotChangeTilesOrReadDisplayOnlySources() {
        val source = tile("sensor", """{"entity_id":"sensor.primary","expanded_sensor_configs":[{"entity_id":"sensor.expanded"}],"unknown":true}""")
            .copy(colorRules = """[{"entity_id":"sensor.color"}]""", collapsedSensorIds = """["sensor.legacy"]""")
        val before = source.copy()
        val plan = PanelSubscriptionPlan.create(listOf(source), null, false)
        assertEquals(listOf("sensor.primary"), plan.entityIds)
        assertEquals(before, source)
    }
}
