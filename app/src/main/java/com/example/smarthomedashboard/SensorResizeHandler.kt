package com.example.smarthomedashboard

import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Один resize-listener одной карточки. Живёт только до очистки дерева Activity.
 * Сохранение актуальной плитки и общий флаг жестов остаются у вызывающего экрана.
 */
internal class SensorResizeHandler(
    card: View,
    handle: View,
    type: String,
    private val screenWidth: Int,
    private val screenHeight: Int,
    onResizingChanged: (Boolean) -> Unit,
    saveSize: (Int, Int) -> Boolean,
    /** Ширина : высота, которую resize сохраняет; null — свободный размер, как прежде. */
    private val aspectRatio: Float? = null
) : View.OnTouchListener {
    private var card: View? = card
    private var handle: View? = handle
    private var onResizingChanged: ((Boolean) -> Unit)? = onResizingChanged
    private var saveSize: ((Int, Int) -> Boolean)? = saveSize
    private var active = false
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var originalWidth = 0
    private var originalHeight = 0
    private var originalX = 0f
    private var originalY = 0f
    private var originalTextSizes = emptyList<Pair<TextView, Float>>()
    private val textScales = when (type) {
        "clock" -> listOf("clock_text" to 0.4f, "clock_date" to 0.15f)
        "weather" -> listOf("weather_icon" to 0.35f, "weather_temp" to 0.25f, "weather_desc" to 0.12f)
        else -> emptyList()
    }

    init {
        handle.setOnTouchListener(this)
    }

    // У ручки нет click-действия: UP завершает resize и не должен запускать соседнее действие.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val card = card ?: return true // Остаток жеста старого дерева не получает нового действия.
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onResizingChanged?.invoke(true)
                active = true
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                // Каждый жест начинает новый снимок, в том числе после успешного resize.
                originalWidth = card.layoutParams.width
                originalHeight = card.layoutParams.height
                originalX = card.x
                originalY = card.y
                originalTextSizes = textScales.mapNotNull { (tag, _) ->
                    card.findViewWithTag<TextView>(tag)?.let { it to it.textSize }
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                // Сохраняем прежний минимум и отступы resize, даже у самого края.
                val maxW = (screenWidth - card.x.toInt() - 20).coerceAtLeast(120)
                val maxH = (screenHeight - card.y.toInt() - 80).coerceAtLeast(120)
                // layout мог ещё не выполниться после предыдущего MOVE.
                val width = (card.layoutParams.width + event.rawX - resizeStartX).toInt().coerceIn(120, maxW)
                val height = (card.layoutParams.height + event.rawY - resizeStartY).toInt().coerceIn(120, maxH)
                val ratio = aspectRatio
                if (ratio == null) applyResize(width, height)
                else {
                    // Ведущее — большее смещение пальца; итог всегда в пропорции и в границах экрана.
                    val byWidth = width.toFloat()
                    val byHeight = height * ratio
                    val dw = kotlin.math.abs(event.rawX - resizeStartX)
                    val dh = kotlin.math.abs(event.rawY - resizeStartY) * ratio
                    val wanted = if (dw >= dh) byWidth else byHeight
                    val lower = 120 * ratio
                    val w = wanted.coerceIn(lower, maxOf(lower, minOf(maxW.toFloat(), maxH * ratio)))
                    val proportionalHeight = (w / ratio).toInt()
                    applyResize((proportionalHeight * ratio).toInt(), proportionalHeight)
                }
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                true
            }
            MotionEvent.ACTION_UP -> {
                onResizingChanged?.invoke(false)
                if (!active) return true
                active = false
                if (saveSize?.invoke(card.layoutParams.width, card.layoutParams.height) != true) restore()
                originalTextSizes = emptyList()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel()
                true
            }
            else -> false
        }
    }

    private fun applyResize(width: Int, height: Int) {
        val card = card ?: return
        card.layoutParams.width = width
        card.layoutParams.height = height
        textScales.forEach { (tag, scale) ->
            card.findViewWithTag<TextView>(tag)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * scale)
        }
        // Layout-listener батареи сам пересчитывает долю заполнения по новой ширине.
        card.requestLayout()
    }

    private fun restore() {
        val card = card ?: return
        applyResize(originalWidth, originalHeight)
        card.x = originalX
        card.y = originalY
        originalTextSizes.forEach { (view, size) ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    fun cancel() {
        if (active) restore()
        active = false
        originalTextSizes = emptyList()
        onResizingChanged?.invoke(false)
    }

    fun dispose() {
        cancel()
        handle?.setOnTouchListener(null)
        handle = null
        card = null
        // Даже сохранённая извне ссылка на старый listener больше не удерживает экран/карточку.
        onResizingChanged = null
        saveSize = null
    }
}
