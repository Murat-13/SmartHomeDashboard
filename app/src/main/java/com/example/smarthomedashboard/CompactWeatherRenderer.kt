package com.example.smarthomedashboard

import android.view.View
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/** Обновляет только существующие три компактных TextView, не строит дерево и не хранит Views. */
internal object CompactWeatherRenderer {
    fun renderYandex(view: View, temperature: String, icon: String, description: String) {
        view.findViewWithTag<TextView>("weather_temp")?.text = "$temperature°C"
        view.findViewWithTag<TextView>("weather_icon")?.text = icon
        view.findViewWithTag<TextView>("weather_desc")?.text = description
    }

    fun renderHomeAssistant(
        view: View,
        tile: TileEntity,
        stateForEntity: (String) -> String?,
        attributesForEntity: (String) -> JSONObject?
    ) {
        val entityId = WeatherEntityResolver.resolve(tile.config)
        val state = stateForEntity(entityId)?.lowercase() ?: "unknown"
        val attrs = attributesForEntity(entityId)
        val temp = if (entityId.startsWith("weather.")) {
            if (attrs != null && attrs.has("temperature")) {
                attrs.opt("temperature")?.toString() ?: "—"
            } else "—"
        } else {
            stateForEntity(entityId) ?: "—"
        }
        view.findViewWithTag<TextView>("weather_temp")?.text = if (temp != "—") "$temp°C" else temp
        val icon = when {
            state.contains("sunny") || state.contains("clear") -> "☀️"
            state.contains("partlycloudy") -> "⛅"
            state.contains("cloudy") -> "☁️"
            state.contains("rain") || state.contains("pouring") -> "🌧️"
            state.contains("snow") -> "❄️"
            state.contains("lightning") || state.contains("thunder") -> "⚡"
            state.contains("fog") || state.contains("mist") -> "🌫️"
            else -> "🌡️"
        }
        view.findViewWithTag<TextView>("weather_icon")?.text = icon
        view.findViewWithTag<TextView>("weather_desc")?.text = tile.title
    }
}
