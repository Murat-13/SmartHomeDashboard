package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed class ButtonConfigResult<out T> {
    data class Valid<T>(val value: T) : ButtonConfigResult<T>()
    object Invalid : ButtonConfigResult<Nothing>()
}

internal object TileButtonConfig {
    data class Group(val entityIds: List<String>, val childNames: List<String>)

    fun entityId(config: String): ButtonConfigResult<String?> = read(config) { singleId(it) }

    fun group(config: String): ButtonConfigResult<Group> = read(config) { json ->
        val ids = multipleIds(json)
        val names = json.optJSONArray("child_names")
        Group(ids, ids.indices.map { i ->
            if (names != null && i < names.length()) names.getString(i) else (i + 1).toString()
        })
    }

    fun subscriptionIds(config: String): ButtonConfigResult<List<String>> = read(config) { json ->
        val single = singleId(json)
        val multiple = multipleIds(json)
        // Возвращаем результат только после проверки всего списка: частичный источник недостоверен.
        listOfNotNull(single) + multiple
    }

    private fun singleId(json: JSONObject): String? {
        if (!json.has("entity_id")) return null
        val value = json.get("entity_id")
        if (value !is String) throw JSONException("entity_id must be a string")
        return value.takeIf { it.isNotBlank() }
    }

    private fun multipleIds(json: JSONObject): List<String> {
        if (!json.has("entity_ids")) return emptyList()
        val array = json.get("entity_ids")
        if (array !is JSONArray) throw JSONException("entity_ids must be an array")
        return (0 until array.length()).map { i ->
            val value = array.get(i)
            // getString/optString могут преобразовать объект, число или null в строку.
            // Для команд нужен именно непустой строковый идентификатор без подстановок.
            if (value !is String || value.isBlank()) throw JSONException("Invalid group entity_id")
            value
        }
    }

    private fun <T> read(config: String, parse: (JSONObject) -> T): ButtonConfigResult<T> {
        return try {
            ButtonConfigResult.Valid(parse(JSONObject(config)))
        } catch (_: JSONException) {
            ButtonConfigResult.Invalid
        }
    }
}
