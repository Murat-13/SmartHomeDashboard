package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class YandexWeatherManager(private val context: Context) {
    private val client = OkHttpClient()
    private val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    var currentTemp: String = "—"
    var currentState: String = "unknown"
    var humidity: String = "—"
    var pressure: String = "—"
    var windSpeed: String = "—"
    var windDir: String = "—"
    var lastUpdate: Long = 0
    var forecasts: List<ForecastDay> = emptyList()
    var hourlyForecasts: List<ForecastHour> = emptyList()

    data class ForecastDay(
        val date: String,
        val tempDay: String,
        val tempNight: String,
        val condition: String
    )

    data class ForecastHour(
        val hour: String,
        val temp: String,
        val condition: String
    )

    interface WeatherCallback {
        fun onWeatherUpdated()
        fun onError(error: String)
    }

    fun translateCondition(condition: String): String {
        return when (condition) {
            "clear" -> "Ясно"
            "partly-cloudy" -> "Малооблачно"
            "cloudy" -> "Облачно с прояснениями"
            "overcast" -> "Пасмурно"
            "drizzle" -> "Морось"
            "light-rain" -> "Небольшой дождь"
            "rain" -> "Дождь"
            "moderate-rain" -> "Умеренно сильный дождь"
            "heavy-rain" -> "Сильный дождь"
            "continuous-heavy-rain" -> "Длительный сильный дождь"
            "showers" -> "Ливень"
            "wet-snow" -> "Дождь со снегом"
            "light-snow" -> "Небольшой снег"
            "snow" -> "Снег"
            "snow-showers" -> "Снегопад"
            "hail" -> "Град"
            "thunderstorm" -> "Гроза"
            "thunderstorm-with-rain" -> "Дождь с грозой"
            "thunderstorm-with-hail" -> "Гроза с градом"
            else -> condition
        }
    }

    fun translateWindDir(dir: String): String {
        return when (dir) {
            "nw" -> "СЗ"
            "n" -> "С"
            "ne" -> "СВ"
            "e" -> "В"
            "se" -> "ЮВ"
            "s" -> "Ю"
            "sw" -> "ЮЗ"
            "w" -> "З"
            "c" -> "Штиль"
            else -> dir
        }
    }

    fun getWeatherIcon(condition: String): String {
        return when (condition) {
            "clear" -> "☀️"
            "partly-cloudy" -> "⛅"
            "cloudy", "overcast" -> "☁️"
            "drizzle", "light-rain" -> "🌦️"
            "rain", "moderate-rain", "heavy-rain", "continuous-heavy-rain" -> "🌧️"
            "showers" -> "🚿"
            "wet-snow", "light-snow" -> "🌨️"
            "snow", "snow-showers" -> "❄️"
            "hail" -> "☄️"
            "thunderstorm", "thunderstorm-with-rain", "thunderstorm-with-hail" -> "⛈️"
            else -> "⛅"
        }
    }

    fun fetchWeather(callback: WeatherCallback? = null) {
        val useYandex = prefs.getBoolean("use_yandex_weather", false)
        if (!useYandex) return

        val apiKey = prefs.getString("yandex_api_key", "") ?: ""
        val lat = prefs.getString("yandex_lat", "43.09") ?: "43.09"
        val lon = prefs.getString("yandex_lon", "46.38") ?: "46.38"

        if (apiKey.isEmpty()) {
            callback?.onError("API Key is empty")
            return
        }

        val url = "https://api.weather.yandex.ru/v2/forecast?lat=$lat&lon=$lon&limit=7"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Yandex-API-Key", apiKey)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("YandexWeather", "Failed to fetch weather", e)
                callback?.onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback?.onError("Response not successful: ${response.code}")
                        return
                    }

                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        val fact = json.getJSONObject("fact")
                        currentTemp = fact.optInt("temp").toString()
                        currentState = fact.optString("condition", "unknown")
                        humidity = fact.optInt("humidity").toString()
                        pressure = fact.optInt("pressure_mm").toString()
                        windSpeed = fact.optDouble("wind_speed").toString()
                        windDir = translateWindDir(fact.optString("wind_dir", "—"))
                        
                        val forecastList = mutableListOf<ForecastDay>()
                        val forecastsArray = json.optJSONArray("forecasts")
                        if (forecastsArray != null) {
                            for (i in 0 until forecastsArray.length()) {
                                val dayJson = forecastsArray.getJSONObject(i)
                                val parts = dayJson.getJSONObject("parts")
                                
                                // Пробуем day_short, если нет - берем day или morning+evening
                                val dayPart = parts.optJSONObject("day_short") ?: parts.optJSONObject("day") 
                                val nightPart = parts.optJSONObject("night_short") ?: parts.optJSONObject("night")

                                if (dayPart != null && nightPart != null) {
                                    forecastList.add(ForecastDay(
                                        date = dayJson.optString("date"),
                                        tempDay = dayPart.optInt("temp", dayPart.optInt("temp_avg")).toString(),
                                        tempNight = nightPart.optInt("temp", nightPart.optInt("temp_avg")).toString(),
                                        condition = dayPart.optString("condition")
                                    ))
                                }
                            }
                        }
                        forecasts = forecastList

                        val hourlyList = mutableListOf<ForecastHour>()
                        if (forecastsArray != null && forecastsArray.length() > 0) {
                            val firstDay = forecastsArray.getJSONObject(0)
                            val hours = firstDay.optJSONArray("hours")
                            if (hours != null) {
                                for (j in 0 until hours.length()) {
                                    val hourJson = hours.getJSONObject(j)
                                    hourlyList.add(ForecastHour(
                                        hour = hourJson.optString("hour"),
                                        temp = hourJson.optInt("temp").toString(),
                                        condition = hourJson.optString("condition")
                                    ))
                                }
                            }
                        }
                        hourlyForecasts = hourlyList

                        lastUpdate = System.currentTimeMillis()
                        
                        // Сохраняем в кэш
                        prefs.edit().apply {
                            putString("yandex_last_temp", currentTemp)
                            putString("yandex_last_state", currentState)
                            putString("yandex_last_humidity", humidity)
                            putString("yandex_last_pressure", pressure)
                            putString("yandex_last_wind_speed", windSpeed)
                            putString("yandex_last_wind_dir", windDir)
                            
                            val forecastJson = org.json.JSONArray()
                            forecasts.forEach {
                                val d = JSONObject()
                                d.put("date", it.date)
                                d.put("tempDay", it.tempDay)
                                d.put("tempNight", it.tempNight)
                                d.put("condition", it.condition)
                                forecastJson.put(d)
                            }
                            putString("yandex_last_forecast", forecastJson.toString())

                            val hourlyJson = org.json.JSONArray()
                            hourlyForecasts.forEach {
                                val h = JSONObject()
                                h.put("hour", it.hour)
                                h.put("temp", it.temp)
                                h.put("condition", it.condition)
                                hourlyJson.put(h)
                            }
                            putString("yandex_last_hourly", hourlyJson.toString())
                            
                            putLong("yandex_last_update", lastUpdate)
                            apply()
                        }

                        callback?.onWeatherUpdated()
                    } catch (e: Exception) {
                        Log.e("YandexWeather", "Failed to parse weather", e)
                        callback?.onError("Parse error")
                    }
                }
            }
        })
    }
    
    fun loadFromCache() {
        currentTemp = prefs.getString("yandex_last_temp", "—") ?: "—"
        currentState = prefs.getString("yandex_last_state", "unknown") ?: "unknown"
        humidity = prefs.getString("yandex_last_humidity", "—") ?: "—"
        pressure = prefs.getString("yandex_last_pressure", "—") ?: "—"
        windSpeed = prefs.getString("yandex_last_wind_speed", "—") ?: "—"
        windDir = prefs.getString("yandex_last_wind_dir", "—") ?: "—"
        
        val forecastStr = prefs.getString("yandex_last_forecast", null)
        if (forecastStr != null) {
            try {
                val arr = org.json.JSONArray(forecastStr)
                val list = mutableListOf<ForecastDay>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(ForecastDay(
                        obj.getString("date"),
                        obj.getString("tempDay"),
                        obj.getString("tempNight"),
                        obj.getString("condition")
                    ))
                }
                forecasts = list
            } catch (e: Exception) {}
        }

        val hourlyStr = prefs.getString("yandex_last_hourly", null)
        if (hourlyStr != null) {
            try {
                val arr = org.json.JSONArray(hourlyStr)
                val list = mutableListOf<ForecastHour>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(ForecastHour(
                        obj.getString("hour"),
                        obj.getString("temp"),
                        obj.getString("condition")
                    ))
                }
                hourlyForecasts = list
            } catch (e: Exception) {}
        }

        lastUpdate = prefs.getLong("yandex_last_update", 0)
    }

    fun loadLat() = prefs.getString("yandex_lat", "43.09") ?: "43.09"
    fun loadLon() = prefs.getString("yandex_lon", "46.38") ?: "46.38"
}
