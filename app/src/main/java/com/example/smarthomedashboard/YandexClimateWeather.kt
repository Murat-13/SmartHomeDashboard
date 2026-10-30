package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Нормализованные публичные показания Яндекса. Ключ и координаты сюда не попадают. */
data class YandexClimateWeather(
    val condition: String = "unknown",
    val attributes: JSONObject = JSONObject(),
    val hourly: JSONArray = JSONArray(),
    val daily: JSONArray = JSONArray(),
    val updatedAt: Long = 0,
    val cached: Boolean = false,
    val error: String? = null
) {
    fun status(now: Long = System.currentTimeMillis()): String? = when {
        updatedAt == 0L -> error ?: "Погода Яндекса ещё не загружена"
        error != null -> "$error — показан кэш Яндекса"
        cached -> "Показан сохранённый кэш Яндекса"
        now - updatedAt > 2 * 60 * 60 * 1000L -> "Данные Яндекса устарели"
        else -> null
    }

    fun toJson(): String = JSONObject().put("condition", condition).put("attributes", attributes)
        .put("hourly", hourly).put("daily", daily).put("updated_at", updatedAt).toString()

    companion object {
        fun fromCache(raw: String?): YandexClimateWeather? = runCatching {
            val json = JSONObject(raw ?: return null)
            YandexClimateWeather(json.optString("condition", "unknown"), json.optJSONObject("attributes") ?: JSONObject(),
                json.optJSONArray("hourly") ?: JSONArray(), json.optJSONArray("daily") ?: JSONArray(),
                json.optLong("updated_at"), cached = true)
        }.getOrNull()

        /** Старый кеш не содержит полных временных меток: часы прогнозов по нему не выдумываем. */
        fun legacy(temp: String, condition: String, humidity: String, pressure: String, wind: String, direction: String, updatedAt: Long) =
            YandexClimateWeather(condition(condition), JSONObject().put("temperature_unit", "°C").apply {
                temp.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put("temperature", it) }
                humidity.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put("humidity", it) }
                pressure.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put("pressure", it); put("pressure_unit", "mmHg") }
                wind.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put("wind_speed", it); put("wind_speed_unit", "m/s") }
                if (direction != "—") put("wind_bearing", direction)
            }, updatedAt = updatedAt, cached = true)

        fun parse(json: JSONObject, now: Long = System.currentTimeMillis()): YandexClimateWeather {
            val fact = json.optJSONObject("fact") ?: error("Weather fact missing")
            val attrs = JSONObject().put("temperature_unit", "°C")
            fun copyNumber(from: JSONObject, source: String, into: JSONObject, target: String = source) {
                number(from, source)?.let { into.put(target, it) }
            }
            copyNumber(fact, "temp", attrs, "temperature")
            copyNumber(fact, "feels_like", attrs, "apparent_temperature")
            copyNumber(fact, "humidity", attrs)
            copyNumber(fact, "pressure_mm", attrs, "pressure")
            if (attrs.has("pressure")) attrs.put("pressure_unit", "mmHg")
            copyNumber(fact, "wind_speed", attrs)
            if (attrs.has("wind_speed")) attrs.put("wind_speed_unit", "m/s")
            val bearing = mapOf("n" to 0, "ne" to 45, "e" to 90, "se" to 135, "s" to 180, "sw" to 225, "w" to 270, "nw" to 315)
            bearing[fact.optString("wind_dir")]?.let { attrs.put("wind_bearing", it) }
            copyNumber(fact, "prec_mm", attrs, "precipitation")
            if (attrs.has("precipitation")) attrs.put("precipitation_unit", "mm")
            val tz = json.optJSONObject("info")?.optJSONObject("tzinfo")
            val zone = tz?.optString("name")?.takeIf { it in TimeZone.getAvailableIDs() }?.let(TimeZone::getTimeZone)
                ?: number(tz, "offset")?.takeIf { it in -50400.0..50400.0 }?.let { java.util.SimpleTimeZone((it * 1000).toInt(), "Yandex") }
            val hourly = JSONArray()
            val daily = JSONArray()
            val days = json.optJSONArray("forecasts") ?: JSONArray()
            for (i in 0 until days.length()) {
                val day = days.optJSONObject(i) ?: continue
                val date = day.optString("date")
                val parts = day.optJSONObject("parts") ?: JSONObject()
                val temperatures = listOf("night", "morning", "day", "evening").mapNotNull(parts::optJSONObject)
                val high = temperatures.mapNotNull { number(it, "temp_max") }.maxOrNull()
                    ?: number(parts.optJSONObject("day_short"), "temp")
                val low = temperatures.mapNotNull { number(it, "temp_min") }.minOrNull()
                    ?: number(parts.optJSONObject("night_short"), "temp")
                val dayTime = calendarTime(date, "12", zone ?: TimeZone.getTimeZone("UTC"))
                if (dayTime != null) daily.put(JSONObject().put("datetime", iso(dayTime)).apply {
                    high?.let { put("temperature", it) }; low?.let { put("templow", it) }
                    put("condition", condition((parts.optJSONObject("day") ?: parts.optJSONObject("day_short"))?.optString("condition")))
                })
                val hours = day.optJSONArray("hours") ?: continue
                for (h in 0 until hours.length()) {
                    val hour = hours.optJSONObject(h) ?: continue
                    val timestamp = number(hour, "hour_ts")?.takeIf { it > 0 && it < 32_503_680_000.0 }?.let { Date((it * 1000).toLong()) }
                        ?: zone?.let { calendarTime(date, hour.optString("hour"), it) } ?: continue
                    hourly.put(JSONObject().put("datetime", iso(timestamp)).apply {
                        copyNumber(hour, "temp", this, "temperature")
                        copyNumber(hour, "prec_prob", this, "precipitation_probability")
                        put("condition", condition(hour.optString("condition"), hour.optString("daytime")))
                    })
                }
            }
            return YandexClimateWeather(condition(fact.optString("condition"), fact.optString("daytime")), attrs, hourly, daily, now)
        }

        private fun number(json: JSONObject?, key: String): Double? = json?.opt(key)?.let {
            when (it) { is Number -> it.toDouble(); is String -> it.toDoubleOrNull(); else -> null }
        }?.takeIf { it.isFinite() }

        private fun calendarTime(date: String, hour: String, zone: TimeZone): Date? {
            if (!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || hour.toIntOrNull() !in 0..23) return null
            val parser = SimpleDateFormat("yyyy-MM-dd H", Locale.ROOT).apply { timeZone = zone; isLenient = false }
            return runCatching { parser.parse("$date $hour") }.getOrNull()
        }
        private fun iso(date: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        private fun condition(value: String?, daytime: String = "") = when (value) {
            "clear" -> if (daytime == "n") "clear-night" else "sunny"
            "partly-cloudy" -> "partlycloudy"
            "cloudy", "overcast" -> "cloudy"
            "drizzle", "light-rain", "rain", "moderate-rain", "heavy-rain", "continuous-heavy-rain" -> "rainy"
            "showers" -> "pouring"
            "wet-snow" -> "snowy-rainy"
            "light-snow", "snow", "snow-showers" -> "snowy"
            "hail" -> "hail"
            "thunderstorm", "thunderstorm-with-rain", "thunderstorm-with-hail" -> "lightning-rainy"
            else -> "unknown"
        }
    }
}
