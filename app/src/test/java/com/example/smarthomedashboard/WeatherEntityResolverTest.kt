package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WeatherEntityResolverTest {
    @Test fun preservesArrayPrioritySingleFieldAndSensorSource() {
        val cases = listOf(
            """{"entity_ids":["weather.first","weather.second"],"entity_id":"weather.single"}""" to "weather.first",
            """{"entity_id":"weather.single"}""" to "weather.single",
            """{"entity_ids":[],"entity_id":"weather.single"}""" to "weather.single",
            """{"entity_ids":{},"entity_id":"weather.single"}""" to "weather.single",
            """{"entity_ids":["sensor.temperature"],"entity_id":"weather.single"}""" to "sensor.temperature")
        cases.forEach { (config, expected) -> assertEquals(expected, WeatherEntityResolver.resolve(config)) }
    }

    @Test fun preservesFallbackForMissingAndMalformedConfig() {
        listOf("{}", "", "{broken", """{"entity_ids":[]}""").forEach {
            assertEquals("weather.home", WeatherEntityResolver.resolve(it))
        }
    }

    @Test fun doesNotNormalizeEmptyOrCoercedEntityIds() {
        val cases = listOf(
            """{"entity_id":""}""" to "",
            """{"entity_ids":[""]}""" to "",
            """{"entity_ids":[42]}""" to "42",
            """{"entity_ids":[null]}""" to "null",
            """{"entity_ids":[{"x":1}]}""" to """{"x":1}""",
            """{"entity_id":" Weather.Home "}""" to " Weather.Home ")
        cases.forEach { (config, expected) -> assertEquals(expected, WeatherEntityResolver.resolve(config)) }
    }
}
