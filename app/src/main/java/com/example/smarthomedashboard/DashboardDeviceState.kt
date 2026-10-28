package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/** Данные HA одного ViewModel. Не содержит Context, View или обработчиков экрана. */
internal class DashboardDeviceState {
    val singleStates = mutableMapOf<String, String>()
    val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    val entityAttributes = mutableMapOf<String, JSONObject>()
    val syntheticForecasts = mutableMapOf<String, JSONArray?>()

    fun entityChanged(id: String, state: String, attributes: JSONObject?) {
        singleStates[id] = state
        val complete = copyAttributes(attributes)
        if (complete.has("forecast")) {
            syntheticForecasts.remove(id)
        } else if (syntheticForecasts.containsKey(id)) {
            complete.put("forecast", syntheticForecasts[id] ?: JSONArray())
        }
        entityAttributes[id] = complete
        groupStates.values.forEach { states -> if (id in states) states[id] = state }
    }

    fun forecastChanged(id: String, forecast: JSONArray?) {
        syntheticForecasts[id] = forecast
        entityAttributes[id] = copyAttributes(entityAttributes[id]).put("forecast", forecast ?: JSONArray())
    }

    fun updateGroup(group: String, id: String, state: String) {
        groupStates.getOrPut(group) { mutableMapOf() }[id] = state
    }

    fun groupIsOn(group: String, ids: List<String>): Boolean {
        val states = groupStates.getOrPut(group) { mutableMapOf() }
        states.keys.retainAll(ids.toSet())
        ids.forEach { id -> if (id !in states) singleStates[id]?.let { states[id] = it } }
        return ids.any { states[it] == "on" }
    }

    // StateFlow получает снимки: ранее опубликованное состояние не меняется при новом событии.
    fun snapshot(current: MainUiState) = current.copy(
        singleStates = singleStates.toMap(),
        groupStates = groupStates.mapValues { it.value.toMap() },
        entityAttributes = entityAttributes.toMap(),
        syntheticForecasts = syntheticForecasts.toMap()
    )

    private fun copyAttributes(source: JSONObject?) = JSONObject().also { copy ->
        source?.keys()?.forEach { key -> copy.put(key, source.get(key)) }
    }
}
