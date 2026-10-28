package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SensorCardFactory {
    data class CardViews(val card: FrameLayout, val resizeHandle: ImageView)

    // Два шага позволяют Activity применить известный заряд после слоя батареи,
    // до построения текста и подключения обработчиков, как в исходном методе.
    fun createBase(
        context: Context,
        tileId: String,
        type: String,
        savedWidth: Int,
        savedHeight: Int
    ): FrameLayout {
        // Основной контейнер виджета
        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(savedWidth, savedHeight)
            tag = tileId
            alpha = 0.85f
            elevation = 8f
            setBackgroundColor("#80333333".toColorInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 32f)
                }
            }
        }

        // Слой наполнения для батареи
        if (type == "battery") {
            val progressFill = View(context).apply {
                tag = "battery_progress_fill"
                layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor("#8033CC33".toColorInt())
            }
            card.addView(progressFill)
        }

        return card
    }

    fun addContent(
        context: Context,
        card: FrameLayout,
        type: String,
        title: String,
        fontSize: Int,
        savedHeight: Int,
        isEditMode: Boolean,
        currentTime: () -> Date = { Date() }
    ): CardViews {
        // Контейнер для текста
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(8, 8, 8, 8)
            tag = "sensor_text_container"
        }

        if (type == "clock") {
            val clockView = TextView(context).apply {
                tag = "clock_text"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.4f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(clockView)

            val dateView = TextView(context).apply {
                tag = "clock_date"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.15f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(dateView)

            val now = currentTime()
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        } else if (type == "weather") {
            buildCollapsedWeatherContent(context, textContainer, savedHeight)
        } else {
            // Заголовок виджета
            val titleView = TextView(context).apply {
                text = title
                textSize = fontSize.toFloat()
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tag = "sensor_title"
            }
            textContainer.addView(titleView)
        }

        card.addView(textContainer)

        // Уголок изменения размера
        val resizeHandle = ImageView(context).apply {
            setImageResource(R.drawable.ic_resize)
            layoutParams = FrameLayout.LayoutParams(40, 40).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            visibility = if (isEditMode) View.VISIBLE else View.GONE
            setPadding(8, 8, 8, 8)
        }
        card.addView(resizeHandle)

        return CardViews(card, resizeHandle)
    }

    fun buildCollapsedWeatherContent(
        context: Context,
        textContainer: LinearLayout,
        savedHeight: Int
    ) {
        // Подробный прогноз меняет содержимое и свойства этого же контейнера.
        textContainer.removeAllViews()
        textContainer.orientation = LinearLayout.VERTICAL
        textContainer.gravity = Gravity.CENTER
        textContainer.setPadding(8, 8, 8, 8)
        val weatherIcon = TextView(context).apply {
            tag = "weather_icon"
            text = "☀️"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.35f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        textContainer.addView(weatherIcon)

        val tempView = TextView(context).apply {
            tag = "weather_temp"
            text = "—°C"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.25f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        textContainer.addView(tempView)

        val descView = TextView(context).apply {
            tag = "weather_desc"
            text = "Загрузка..."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, savedHeight * 0.12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        textContainer.addView(descView)
    }
}
