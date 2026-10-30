package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Сетка выбора иконки Modern: каждая иконка нарисована так же, как на плитке (OFF — обычный вид,
 * выбранная — цвета ON с ореолом). Первый вариант — «без выбора» (null): прежнее поведение плитки.
 * Только UI: ничего не сохраняет, о выборе сообщает [onPick].
 */
internal class ModernIconPicker(context: Context, private val onPick: (ModernIcon?) -> Unit) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val cellW = (76 * density).toInt()
    private val cellH = (86 * density).toInt()
    private val gap = (6 * density).toInt()
    private val cells = (listOf<ModernIcon?>(null) + ModernIcon.choices).map { icon ->
        Cell(context, icon).also { cell ->
            cell.setOnClickListener { select(icon); onPick(icon) }
            addView(cell)
        }
    }

    var selected: ModernIcon? = null
        private set

    fun select(icon: ModernIcon?) {
        selected = icon
        cells.forEach { it.chosen = it.icon == icon }
    }

    /** Подпись варианта «без выбора»: у кнопки — автоподбор по устройству, у группы — без иконки. */
    fun setNoneLabel(label: String, mark: String) {
        cells.first().apply { this.label = label; this.mark = mark; contentDescription = label; invalidate() }
    }

    internal fun cellFor(icon: ModernIcon?): View = cells.first { it.icon == icon }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (cellW + gap) * 5
        val cols = max(1, (width + gap) / (cellW + gap))
        val rows = (cells.size + cols - 1) / cols
        cells.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, rows * cellH + (rows - 1).coerceAtLeast(0) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cols = max(1, (r - l + gap) / (cellW + gap))
        cells.forEachIndexed { i, cell ->
            val x = (i % cols) * (cellW + gap)
            val y = (i / cols) * (cellH + gap)
            cell.layout(x, y, x + cellW, y + cellH)
        }
    }

    private class Cell(context: Context, val icon: ModernIcon?) : View(context) {
        private val density = resources.displayMetrics.density
        var chosen = false
            set(value) { if (field != value) { field = value; isSelected = value; invalidate() } }
        var label: String = icon?.label ?: "Авто"
        var mark: String = "A"
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val square = RectF()

        init {
            contentDescription = label
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val visual = if (chosen) ModernTileView.Visual.ON else ModernTileView.Visual.OFF
            val side = min(width - 8 * density, height - 26 * density)
            square.set((width - side) / 2, 4 * density, (width + side) / 2, 4 * density + side)
            val r = side * 0.22f
            if (chosen) {
                // Тот же статичный ореол, что у плитки ON.
                for (i in 1..3) {
                    stroke.strokeWidth = density
                    stroke.color = ColorUtils.setAlphaComponent(ModernTileView.borderOf(visual), 70 - i * 20)
                    val grow = i * 1.2f * density
                    canvas.drawRoundRect(square.left - grow, square.top - grow, square.right + grow, square.bottom + grow,
                        r + grow, r + grow, stroke)
                }
            }
            fill.color = ModernTileView.backgroundOf(visual)
            canvas.drawRoundRect(square, r, r, fill)
            stroke.strokeWidth = (if (chosen) 2f else 1f) * density
            stroke.color = ModernTileView.borderOf(visual)
            canvas.drawRoundRect(square, r, r, stroke)
            val color = ModernTileView.iconOf(visual)
            if (icon != null) {
                val size = side * 0.5f
                iconPaint.color = color
                icon.draw(canvas, square.centerX() - size / 2, square.centerY() - size / 2, size, iconPaint)
            } else {
                markPaint.color = color
                markPaint.textSize = side * 0.3f
                canvas.drawText(mark, square.centerX(), square.centerY() - (markPaint.ascent() + markPaint.descent()) / 2, markPaint)
            }
            text.color = if (chosen) ModernTileView.TEXT else 0xFFB6C3D1.toInt()
            // Подпись уменьшается до 9sp, и только потом сокращается многоточием.
            val available = width - 4 * density
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
            val minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
            while (text.measureText(label) > available && text.textSize > minimum) text.textSize = max(minimum, text.textSize - density / 2)
            val shown = TextUtils.ellipsize(label, text, available, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, width / 2f, height - 6 * density, text)
        }
    }
}
