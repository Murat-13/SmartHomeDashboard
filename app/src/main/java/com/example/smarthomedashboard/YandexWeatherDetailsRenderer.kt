package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object YandexWeatherDetailsRenderer {
    fun render(
        context: Context,
        container: LinearLayout,
        yandexWeatherManager: YandexWeatherManager,
        resetSensorCollapseTimer: () -> Unit
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.TOP
        container.setPadding(30, 30, 30, 30)

        // 1. Заголовок и текущая погода
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bigIcon = TextView(context).apply {
            textSize = 48f
            text = yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState)
        }
        topRow.addView(bigIcon)

        val mainInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 0, 0)
        }
        mainInfo.addView(TextView(context).apply {
            text = "Погода"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        mainInfo.addView(TextView(context).apply {
            text = yandexWeatherManager.translateCondition(yandexWeatherManager.currentState)
            setTextColor(Color.LTGRAY)
            textSize = 16f
        })
        topRow.addView(mainInfo)

        val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        topRow.addView(spacer)

        val bigTemp = TextView(context).apply {
            text = "${yandexWeatherManager.currentTemp}°C"
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        topRow.addView(bigTemp)
        container.addView(topRow)

        // 2. Доп. параметры
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

        addDetail("💧", "Влажность", "${yandexWeatherManager.humidity}%")
        addDetail("⏲️", "Давление", "${yandexWeatherManager.pressure} мм")
        addDetail("🌬️", "Ветер", "${yandexWeatherManager.windSpeed} м/с")
        addDetail("🧭", "Напр.", yandexWeatherManager.windDir)

        container.addView(detailsGrid)

        // 3. Почасовой прогноз
        if (yandexWeatherManager.hourlyForecasts.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = "Сегодня:"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 10, 0, 10)
            })

            val hourlyScroll = HorizontalScrollView(context).apply {
                tag = WeatherForecastScrollState.Section.YANDEX_HOURLY
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
            val hourlyRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            yandexWeatherManager.hourlyForecasts.forEach { h ->
                val hourItem = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(15, 10, 15, 10)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 10, 0)
                    layoutParams = lp
                }
                hourItem.addView(TextView(context).apply {
                    text = "${h.hour}:00"
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                })
                hourItem.addView(TextView(context).apply {
                    text = yandexWeatherManager.getWeatherIcon(h.condition)
                    textSize = 20f
                })
                hourItem.addView(TextView(context).apply {
                    text = "${h.temp}°"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                })
                hourlyRow.addView(hourItem)
            }
            hourlyScroll.addView(hourlyRow)
            container.addView(hourlyScroll)
        }

        // 4. Прогноз на неделю
        if (yandexWeatherManager.forecasts.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = "На неделю:"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 20, 0, 10)
            })

            val forecastScroll = HorizontalScrollView(context).apply {
                tag = WeatherForecastScrollState.Section.YANDEX_DAILY
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

            yandexWeatherManager.forecasts.forEach { f ->
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

                val dateLabel = try {
                    val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val outSdf = SimpleDateFormat("E d", Locale.getDefault())
                    outSdf.format(sdfIn.parse(f.date) ?: Date()).uppercase()
                } catch(_: Exception) { f.date }

                day.addView(TextView(context).apply {
                    text = dateLabel
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                })
                day.addView(TextView(context).apply {
                    text = yandexWeatherManager.getWeatherIcon(f.condition)
                    textSize = 24f
                    setPadding(0, 8, 0, 8)
                })
                day.addView(TextView(context).apply {
                    text = "${f.tempDay}°/${f.tempNight}°"
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13f
                })
                forecastRow.addView(day)
            }
            forecastScroll.addView(forecastRow)
            container.addView(forecastScroll)
        }
    }
}
