package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Дом глазами Home Assistant: комнаты, устройства и сущности — для первых плиток (docs/setup-tz.md, часть 2). Только
 * чтение: ничего не переключается и не меняется. Читается один раз коротким соединением после входа.
 */
internal class HaHome(val areas: List<Area>, val floors: Map<String, Floor>, val devices: Map<String, Device>, val entities: List<Entity>) {
    /** [temperature]/[humidity] — датчики комнаты, выбранные в самом Home Assistant ("" — не выбраны). */
    class Area(val id: String, val name: String, val floor: String, val temperature: String, val humidity: String)
    class Floor(val id: String, val name: String, val level: Int?)
    /** [service] — не устройство, а служба (интеграция без железа). */
    class Device(val id: String, val area: String, val name: String, val manufacturer: String, val model: String, val service: Boolean,
                 /** Адрес настройки устройства: у камер — адрес самой камеры (для поиска камер, этап 3). */
                 val configUrl: String)

    class Entity(
        val id: String, val name: String, val device: String, val area: String, val platform: String,
        /** Служебная («config», "diagnostic"), скрытая или выключенная в Home Assistant — не предлагается. */
        val hidden: Boolean, val state: String, val attributes: JSONObject
    ) {
        val domain get() = id.substringBefore('.')
        val deviceClass get() = attributes.optString("device_class")
        val unit get() = attributes.optString("unit_of_measurement")
        val available get() = state != "unavailable" && state.isNotEmpty()
    }

    /** Комната сущности: своя или её устройства. */
    fun areaOf(entity: Entity): String = entity.area.ifEmpty { devices[entity.device]?.area.orEmpty() }

    companion object {
        /** Прочитать дом. Первый отвечающий адрес из [origins]; отказ — [HaLogin.LoginException]. */
        fun read(origins: List<String>, token: String): HaHome {
            var last: HaLogin.LoginException? = null
            for (origin in origins) {
                try { return HaCalls.open(origin, token) { call -> read(call) } } catch (e: HaLogin.LoginException) { last = e }
            }
            throw last ?: HaLogin.LoginException("Адрес Home Assistant не указан")
        }

        internal fun read(call: (String, JSONObject) -> Any?): HaHome {
            /** Чего нет в старом Home Assistant или не разрешено пользователю — пусто, остальное читается. */
            fun list(type: String): JSONArray = try { call(type, JSONObject()) as? JSONArray ?: JSONArray() } catch (_: HaCalls.Refused) { JSONArray() }
            return parse(list("config/area_registry/list"), list("config/floor_registry/list"), list("config/device_registry/list"),
                list("config/entity_registry/list"), list("get_states"))
        }

        internal fun parse(areas: JSONArray, floors: JSONArray, devices: JSONArray, registry: JSONArray, states: JSONArray): HaHome {
            fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
            fun <T> JSONArray.items(make: (JSONObject) -> T?): List<T> = (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(make) }
            val byId = states.items { it }.associateBy { it.text("entity_id") }.filterKeys { it.contains('.') }
            val known = registry.items { r ->
                val id = r.text("entity_id").takeIf { it.contains('.') } ?: return@items null
                val state = byId[id]
                val attributes = state?.optJSONObject("attributes") ?: JSONObject()
                Entity(id, attributes.text("friendly_name").ifEmpty { r.text("name").ifEmpty { r.text("original_name") } }.ifEmpty { id },
                    r.text("device_id"), r.text("area_id"), r.text("platform"),
                    r.text("entity_category").isNotEmpty() || r.text("hidden_by").isNotEmpty() || r.text("disabled_by").isNotEmpty(),
                    state?.text("state").orEmpty(), attributes)
            }
            // Сущности без записи в реестре (YAML без unique_id): комнаты у них нет, но предложить можно.
            val rest = (byId.keys - known.mapTo(HashSet()) { it.id }).sorted().map { id ->
                val state = byId.getValue(id)
                val attributes = state.optJSONObject("attributes") ?: JSONObject()
                Entity(id, attributes.text("friendly_name").ifEmpty { id }, "", "", "", false, state.text("state"), attributes)
            }
            return HaHome(
                areas.items { a -> a.text("area_id").ifEmpty { null }?.let { Area(it, a.text("name").ifEmpty { it }, a.text("floor_id"),
                    a.text("temperature_entity_id"), a.text("humidity_entity_id")) } },
                floors.items { f -> f.text("floor_id").ifEmpty { null }?.let { Floor(it, f.text("name"), if (f.isNull("level")) null else f.optInt("level")) } }
                    .associateBy { it.id },
                devices.items { d -> d.text("id").ifEmpty { null }?.let { Device(it, d.text("area_id"), d.text("name_by_user").ifEmpty { d.text("name") },
                    d.text("manufacturer"), d.text("model"), d.text("entry_type") == "service", d.text("configuration_url")) } }.associateBy { it.id },
                known + rest)
        }
    }
}
