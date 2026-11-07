package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Временный генератор: данные, которые записывала бы старая версия (её собственными шаблонами плиток). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CompatFixtureGenerator {
    private var n = 0
    private fun fill(config: String, prefix: String): String {
        val domains = mapOf("camera" to "camera", "visitor" to "binary_sensor", "person" to "binary_sensor", "animal" to "binary_sensor",
            "vehicle" to "binary_sensor", "motion" to "binary_sensor", "lock" to "lock", "light" to "light", "quick_reply" to "select")
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().asSequence().toList().forEach { k ->
                    val x = v.opt(k)
                    if (x is String && x.isEmpty() && (k.contains("entity") || k in domains)) v.put(k, "${domains[k] ?: "sensor"}.${prefix}_${n++}")
                    else walk(x)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        return JSONObject(config).also(::walk).toString()
    }

    private fun companion(cls: String, vararg args: Any): String? = runCatching {
        val k = Class.forName("com.example.smarthomedashboard.$cls")
        val c = k.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        c.javaClass.methods.first { it.name == "newTileConfig" && it.parameterTypes.size == args.size }.invoke(c, *args) as String
    }.getOrNull()

    @Test fun generate() {
        val ctx = RuntimeEnvironment.getApplication()
        val tiles = mutableListOf<TileEntity>()
        fun add(id: String, type: String, container: String, title: String, x: Int, y: Int, config: String) {
            tiles += TileEntity(id, type, container, title, x, y, 1, 1, config = config)
        }
        add("c_sensor", "sensor", "grid", "Датчик", 20, 120, JSONObject().put("button_size", 300).put("widget_height", 220)
            .put("collapsed_sensor_configs", JSONArray().put(JSONObject().put("entity_id", "sensor.c_temp").put("display_name", "Т").put("decimals", 1)))
            .put("expanded_sensor_configs", JSONArray().put(JSONObject().put("entity_id", "sensor.c_hum").put("display_name", "В"))).toString())
        add("c_battery", "battery", "grid", "Батарея", 340, 120, """{"entity_id":"sensor.c_soc","button_size":260,"widget_height":210}""")
        add("c_weather", "weather", "grid", "Погода", 620, 120, """{"entity_id":"weather.c_home","widget_height":200}""")
        add("c_clock", "clock", "grid", "Часы", 900, 120, """{"widget_height":200}""")
        add("c_button", "button", "bottom_panel", "Кухня", 20, 800, """{"entity_id":"light.c_kitchen"}""")
        add("c_button_m", "button", "bottom_panel", "Свет", 200, 800, """{"entity_id":"light.c_hall","button_style":"modern"}""")
        add("c_group", "group", "bottom_panel", "Двор", 380, 800, """{"entity_ids":["light.c_y1","switch.c_pump"],"child_names":["Фонарь","Насос"],"button_style":"modern"}""")
        companion("BatteryModernConfig", "{}")?.let { add("c_bms", "battery_modern", "grid", "АКБ", 20, 360, fill(it, "c_bms")) }
        companion("PowerModernConfig", "grid_in", "{}")?.let { add("c_grid", "power_modern", "grid", "Сеть", 560, 360, fill(it, "c_grid")) }
        companion("PowerModernConfig", "inverter_out", "{}")?.let { add("c_out", "power_modern", "grid", "Выход", 880, 360, fill(it, "c_out")) }
        companion("HomeClimateConfig", "{}")?.let { cfg ->
            val json = JSONObject(cfg)
            json.optJSONObject("home_climate")?.apply {
                optJSONObject("weather")?.put("entity_id", "weather.c_home")
                put("rooms", JSONArray().put(JSONObject().put("id", "r1").put("name", "Гостиная").put("temperature", "sensor.c_temp").put("humidity", "sensor.c_hum")))
                put("favorites", JSONArray().put("r1"))
            }
            add("c_climate", "home_climate_modern", "grid", "Климат", 1200, 360, json.toString())
        }
        companion("InverterModernConfig", "{}")?.let { add("c_inv", "inverter_modern", "grid", "Инвертор", 1200, 700, fill(it, "c_inv")) }
        companion("DoorbellConfig", "{}")?.let { add("c_door", "doorbell_modern", "grid", "Калитка", 1500, 120, fill(it, "c_door")) }
        TileManager(ctx).saveTiles(tiles)
        ctx.getSharedPreferences("dashboard_prefs", 0).edit().putString("app_theme", "dark").putString("temp_unit", "C")
            .putInt("screen_timeout_minutes", 7).putInt("pin_session_minutes", 30).putBoolean("kiosk_mode", false)
            .putString("motion_sensor_id", "binary_sensor.c_motion").putString("ha_local_host", "http://ha.example.invalid:8123")
            .putBoolean("setup_completed", true).commit()
        fun dump(name: String) = JSONObject().also { o ->
            ctx.getSharedPreferences(name, 0).all.forEach { (k, v) ->
                o.put(k, JSONObject().put("type", v?.javaClass?.simpleName).put("value", v))
            }
        }
        val out = JSONObject().put("version", "VERSION")
            .put("dashboard_prefs", dump("dashboard_prefs")).put("tiles_prefs", dump("tiles_prefs"))
            .put("backup", ConfigBackup.build(ctx))
        File("build/compat-fixture.json").writeText(out.toString(2))
    }
}
