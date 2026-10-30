package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/** Данные HA одного ViewModel. Не содержит Context, View или обработчиков экрана. */
internal class DashboardDeviceState {
    val singleStates = mutableMapOf<String, String>()
    val groupStates = mutableMapOf<String, MutableMap<String, String>>()
    val entityAttributes = mutableMapOf<String, JSONObject>()
    val syntheticForecasts = mutableMapOf<String, JSONArray?>()

    val staleEntities = mutableSetOf<String>()

    // Почасовой прогноз — отдельно от атрибута forecast (там дневной). Нет записи — данные ещё не пришли.
    val hourlyForecasts = mutableMapOf<String, JSONArray?>()
    /** Сущности, для которых HA отказал в почасовом прогнозе в текущей сессии. */
    val hourlyUnsupported = mutableSetOf<String>()

    fun hourlyForecastChanged(id: String, forecast: JSONArray?, supported: Boolean) {
        if (supported) {
            hourlyUnsupported.remove(id)
            hourlyForecasts[id] = forecast
        } else {
            hourlyUnsupported.add(id)
            hourlyForecasts.remove(id)
        }
    }

    val dailyForecasts = mutableMapOf<String, JSONArray?>()
    val dailyUnsupported = mutableSetOf<String>()

    fun clearClimateForecasts() {
        hourlyForecasts.clear(); hourlyUnsupported.clear()
        dailyForecasts.clear(); dailyUnsupported.clear()
    }

    fun confirmedState(id: String): ConfirmedState = when {
        id in staleEntities -> ConfirmedState.UNAVAILABLE
        singleStates[id] == "on" -> ConfirmedState.ON
        singleStates[id] == "off" -> ConfirmedState.OFF
        else -> ConfirmedState.UNAVAILABLE
    }

    fun markUnconfirmed(id: String) { staleEntities.add(id) }

    fun markDisconnected() { staleEntities.addAll(singleStates.keys); clearClimateForecasts() }

    fun applySnapshot(states: List<HaEntityState>) {
        states.forEach { entityChanged(it.entityId, it.state, it.attributes) }
    }

    fun entityChanged(id: String, state: String, attributes: JSONObject?) {
        staleEntities.remove(id)
        singleStates[id] = state
        val complete = copyAttributes(attributes)
        if (complete.has("forecast")) {
            if (id.startsWith("weather.")) {
                dailyForecasts[id] = complete.optJSONArray("forecast")
                dailyUnsupported.remove(id)
            }
            syntheticForecasts.remove(id)
        } else if (syntheticForecasts.containsKey(id)) {
            complete.put("forecast", syntheticForecasts[id] ?: JSONArray())
        }
        entityAttributes[id] = complete
        groupStates.values.forEach { states -> if (id in states) states[id] = state }
    }

    fun forecastChanged(id: String, forecast: JSONArray?) {
        dailyUnsupported.remove(id)
        dailyForecasts[id] = forecast
        syntheticForecasts[id] = forecast
        entityAttributes[id] = copyAttributes(entityAttributes[id]).put("forecast", forecast ?: JSONArray())
    }

    fun updateGroup(group: String, id: String, state: String) {
        staleEntities.remove(id)
        singleStates[id] = state
        groupStates.values.forEach { if (id in it) it[id] = state }
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
        syntheticForecasts = syntheticForecasts.toMap(),
        hourlyForecasts = hourlyForecasts.toMap(), hourlyUnsupported = hourlyUnsupported.toSet(),
        dailyForecasts = dailyForecasts.toMap(), dailyUnsupported = dailyUnsupported.toSet(),
        staleEntities = staleEntities.toSet()
    )

    private fun copyAttributes(source: JSONObject?) = JSONObject().also { copy ->
        source?.keys()?.forEach { key -> copy.put(key, source.get(key)) }
    }
}
