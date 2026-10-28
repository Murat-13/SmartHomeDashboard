package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object HomeAssistantWeatherDetailsRenderer {
    fun render(
        context: Context,
        tile: TileEntity,
        container: LinearLayout,
        attributesForEntity: (String) -> JSONObject?,
        stateForEntity: (String) -> String?,
        resetSensorCollapseTimer: () -> Unit
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.TOP
        container.setPadding(30, 30, 30, 30)

        val entityId = WeatherEntityResolver.resolve(tile.config)

        val attrs = attributesForEntity(entityId) ?: JSONObject()
        val state = stateForEntity(entityId)?.lowercase() ?: "unknown"

        // 1. Заголовок и текущая погода
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bigIcon = TextView(context).apply {
            textSize = 48f
            text = when {
                state.contains("sunny") || state.contains("clear") -> "☀️"
                state.contains("partlycloudy") -> "⛅"
                state.contains("cloudy") -> "☁️"
                state.contains("rain") || state.contains("pouring") -> "🌧️"
                state.contains("snow") -> "❄️"
                state.contains("lightning") || state.contains("thunder") -> "⚡"
                state.contains("fog") || state.contains("mist") -> "🌫️"
                else -> "🌡️"
            }
        }
        topRow.addView(bigIcon)

        val mainInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 0, 0)
        }
        mainInfo.addView(TextView(context).apply {
            text = tile.title
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        mainInfo.addView(TextView(context).apply {
            val stRus = when {
                state.contains("sunny") || state.contains("clear") -> "Ясно"
                state.contains("partlycloudy") -> "Переменная облачность"
                state.contains("cloudy") -> "Облачно"
                state.contains("rain") || state.contains("pouring") -> "Дождь"
                state.contains("snow") -> "Снег"
                state.contains("lightning") || state.contains("thunder") -> "Гроза"
                state.contains("fog") || state.contains("mist") -> "Туман"
                else -> state.replaceFirstChar { it.uppercase() }
            }
            text = stRus
            setTextColor(Color.LTGRAY)
            textSize = 16f
        })
        topRow.addView(mainInfo)

        val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        topRow.addView(spacer)

        val bigTemp = TextView(context).apply {
            val t = attrs.opt("temperature")?.toString() ?: "—"
            text = "$t°C"
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        topRow.addView(bigTemp)
        container.addView(topRow)

        // 2. Доп. параметры (Влажность, Давление, Ветер)
        val detailsGrid = GridLayout(context).apply {
            columnCount = 2
            setPadding(0, 30, 0, 20)
        }

        val addDetail = { icon: String, label: String, value: String ->
            val row = LinearLayout(context).apply {
                setPadding(0, 10, 40, 10)
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply { text = icon; textSize = 18f })
            val txt = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(15, 0, 0, 0)
            }
            txt.addView(TextView(context).apply { text = label; setTextColor(Color.LTGRAY); textSize = 12f })
            txt.addView(TextView(context).apply { text = value; setTextColor(Color.WHITE); textSize = 14f })
            row.addView(txt)
            detailsGrid.addView(row)
        }

        addDetail("💧", "Влажность", "${attrs.optString("humidity", "—")}%")
        addDetail("⏲️", "Давление", "${attrs.optString("pressure", "—")} hPa")
        addDetail("🌬️", "Ветер", "${attrs.optString("wind_speed", "—")} км/ч")
        addDetail("🧭", "Направление", attrs.optString("wind_bearing", "—"))

        container.addView(detailsGrid)

        // 3. Прогноз
        val forecastLabel = TextView(context).apply {
            text = "Прогноз:"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }
        container.addView(forecastLabel)

        val forecastScroll = HorizontalScrollView(context).apply {
            tag = WeatherForecastScrollState.Section.HA_FORECAST
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
            setOnTouchListener { _, _ ->
                resetSensorCollapseTimer()
                false
            }
        }
        val forecastRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        var forecastArr = attrs.optJSONArray("forecast")
        if (forecastArr == null) forecastArr = attrs.optJSONArray("forecast_daily")
        if (forecastArr == null) forecastArr = attrs.optJSONArray("forecast_hourly")

        // Отладочный лог: выведем все ключи атрибутов, если прогноз не найден
        if (forecastArr == null) {
            val keys = mutableListOf<String>()
            val it = attrs.keys()
            while (it.hasNext()) keys.add(it.next())
            Log.d("WeatherDetailed", "Forecast not found. Available keys: ${keys.joinToString()}")
        } else {
            Log.d("WeatherDetailed", "Forecast array length: ${forecastArr.length()}")
        }

        if (forecastArr != null && forecastArr.length() > 0) {
            for (i in 0 until forecastArr.length()) {
                // Лимит относится к показанным записям: повреждённые элементы
                // не должны скрывать корректные записи дальше в массиве.
                if (forecastRow.childCount >= 14) break
                val f = forecastArr.optJSONObject(i) ?: continue
                val day = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(20, 15, 20, 15)
                    background = GradientDrawable().apply {
                        setColor("#25FFFFFF".toColorInt())
                        cornerRadius = 16f
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 15, 0)
                    layoutParams = lp
                }

                val dt = f.optString("datetime", f.optString("time", ""))
                val dateLabel = try {
                    if (dt.contains("T")) {
                        val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        val outSdf = SimpleDateFormat("E d", Locale.getDefault())
                        outSdf.format(sdfIn.parse(dt) ?: Date()).uppercase()
                    } else if (dt.length >= 10) {
                        val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val outSdf = SimpleDateFormat("E d", Locale.getDefault())
                        outSdf.format(sdfIn.parse(dt.substring(0, 10)) ?: Date()).uppercase()
                    } else {
                        dt.takeLast(5)
                    }
                } catch(_: Exception) { dt.takeLast(5) }

                day.addView(TextView(context).apply {
                    text = dateLabel
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                })

                day.addView(TextView(context).apply {
                    val fst = f.optString("condition", "").lowercase()
                    text = when {
                        fst.contains("sun") || fst.contains("clear") -> "☀️"
                        fst.contains("partlycloudy") -> "⛅"
                        fst.contains("cloudy") -> "☁️"
                        fst.contains("rain") || fst.contains("pouring") -> "🌧️"
                        fst.contains("snow") -> "❄️"
                        fst.contains("lightning") || fst.contains("thunder") -> "⚡"
                        fst.contains("fog") || fst.contains("mist") -> "🌫️"
                        else -> "🌡️"
                    }
                    textSize = 24f
                    setPadding(0, 8, 0, 8)
                })

                val tempHigh = f.optString("temperature", f.optString("temp", "—"))
                val tempLow = f.optString("templow", "")

                day.addView(TextView(context).apply {
                    text = if (tempLow.isNotEmpty()) "$tempHigh°/$tempLow°" else "$tempHigh°"
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13f
                })
                forecastRow.addView(day)
            }
        }
        // Непустой массив тоже может не содержать ни одной пригодной записи.
        if (forecastRow.childCount == 0) {
            forecastRow.addView(TextView(context).apply {
                text = "Прогноз временно недоступен\n(Проверьте атрибуты в HA)"
                setTextColor(Color.GRAY)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            })
        }
        forecastScroll.addView(forecastRow)
        container.addView(forecastScroll)
    }
}
