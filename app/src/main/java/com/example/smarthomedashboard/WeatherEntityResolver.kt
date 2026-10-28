package com.example.smarthomedashboard

import org.json.JSONObject

internal object WeatherEntityResolver {
    // Одинаковый выбор для компактной погоды, подробного прогноза и его подписки.
    // Пустые строки и преобразование JSON-значений сохраняют прежнее поведение.
    fun resolve(config: String): String {
        return try {
            val json = JSONObject(config)
            val arr = json.optJSONArray("entity_ids")
            if (arr != null && arr.length() > 0) arr.getString(0)
            else json.optString("entity_id", "weather.home")
        } catch (_: Exception) { "weather.home" }
    }
}
