package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json в юнит-тестах доступен только через Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PanelEntityIdsTest {
    private fun tile(type: String, title: String = "T", config: String = "{}", colorRules: String = "[]",
                     collapsed: String = "[]") =
        TileEntity("id-$type", type, "grid", title, 0, 0, 1, 1, config = config,
            colorRules = colorRules, collapsedSensorIds = collapsed)

    @Test fun collectsEverySourceThatRenderersRead() {
        val ids = PanelEntityIds.collect(listOf(
            tile("button", config = """{"entity_id":"switch.one"}"""),
            tile("group", config = """{"entity_ids":["light.a","light.b"],"child_names":["A","B"]}"""),
            tile("sensor", config = """{"collapsed_sensor_configs":[{"entity_id":"sensor.c","display_name":"C"}],
                "expanded_sensor_configs":[{"entity_id":"sensor.e"}]}""",
                colorRules = """[{"entity_id":"sensor.rule","from":"1","to":"2","color_hex":"#FF0000"}]""",
                collapsed = """["sensor.legacy"]"""),
            tile("battery", config = """{"entity_ids":["sensor.soc"]}"""),
            tile("weather", config = """{"entity_id":"sensor.outdoor"}""")
        ), listOf("binary_sensor.motion"))
        listOf("switch.one", "light.a", "light.b", "sensor.c", "sensor.e", "sensor.rule", "sensor.legacy",
            "sensor.soc", "sensor.outdoor", "binary_sensor.motion").forEach {
            assertTrue("$it должен обновлять панель", it in ids)
        }
        assertFalse("sensor.unrelated" in ids)
    }

    @Test fun includesWeatherDefaultButNothingFromTitles() {
        val ids = PanelEntityIds.collect(listOf(
            tile("weather", config = "{}"),
            tile("sensor", title = "⚡ Сеть"),
            tile("sensor", title = "Температура котла")
        ), emptyList())
        assertTrue("weather.home" in ids)
        assertFalse(ids.any { it.contains("pzem") })
    }

    @Test fun brokenOrMissingJsonDoesNotThrow() {
        val broken = tile("sensor", config = "{not json", colorRules = "oops", collapsed = "")
        val ids = PanelEntityIds.collect(listOf(broken), listOf("sensor.extra"))
        assertTrue("sensor.extra" in ids)
        assertTrue("weather.home" in ids)
    }
}
