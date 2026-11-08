package com.example.smarthomedashboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue

/**
 * Вертикальная прокрутка раскрытых Modern-карточек, которая сначала **вписывает** содержимое: если оно выше
 * видимой области, оно равномерно уменьшается (до [minScale]), а прокрутка остаётся только когда даже так не влезает.
 *
 * Содержимое раскладывается на ширину `W / s` и рисуется с масштабом `s` от левого верхнего угла — текст и отступы
 * уменьшаются пропорционально, а переносы строк те же, что у «полной» раскладки. Касания проходят штатно
 * (ViewGroup пересчитывает координаты для масштабированного ребёнка).
 *
 * Подбор `s` — 1–3 измерения содержимого: старт с прежнего значения (обновление показаний обычно не меняет высоту).
 */
internal class FitScrollView(context: Context, private val minScale: Float = MIN_SCALE) : ScrollView(context) {
    private val box = Box(context)
    private var viewport = 0

    /**
     * Верхний предел масштаба (по умолчанию 1 — только уменьшение). Больше 1 — содержимое может и увеличиваться,
     * заполняя высоту большой карточки (0.9.19: «Климат» на экранах крупнее Huawei).
     */
    var maxScale = 1f
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(1f)
            box.requestLayout()
        }

    /** Текущий масштаб содержимого (1 — без уменьшения). */
    val fitScale: Float get() = box.scale

    init {
        isFillViewport = false
        super.addView(box, -1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Всё, что карточки кладут в прокрутку, идёт внутрь вписывающего контейнера.
    override fun addView(child: View) = box.addView(child)
    override fun addView(child: View, index: Int) = box.addView(child, index)
    override fun addView(child: View, params: ViewGroup.LayoutParams) = box.addView(child, params)
    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (child === box) super.addView(child, index, params) else box.addView(child, index, params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewport = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 0
        else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private inner class Box(context: Context) : FrameLayout(context) {
        var scale = 1f
        private var naturalHeight = 0

        private fun measureContent(child: View, width: Int, s: Float): Int {
            val lp = child.layoutParams
            val w = (width / s).toInt().coerceAtLeast(1)
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                if (lp != null && lp.height >= 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return child.measuredHeight
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val child = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.visibility != GONE }
            if (child == null || width <= 0) {
                scale = 1f
                setMeasuredDimension(width, 0)
                return
            }
            val v = viewport
            // Предел читаемости: самый мелкий видимый текст не мельче READABLE_SP (Material: подпись 11 sp).
            val floor = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, READABLE_SP, resources.displayMetrics) /
                smallestText(child).coerceAtLeast(1f)).coerceIn(minScale, 1f)
            val low = maxOf(minScale, floor)
            val high = maxScale
            var s = if (v <= 0) 1f else scale.coerceIn(low, high)
            var h = measureContent(child, width, s)
            if (v > 0) {
                // Цель — наибольший s, при котором h(s)·s ≤ v. h(s) почти не зависит от ширины, поэтому s = v / h
                // сходится за 1–2 шага; финальная проверка вниз — на случай, если при меньшей ширине появился перенос.
                repeat(3) {
                    val fits = h * s <= v + 0.5f
                    val target = (v / h.toFloat().coerceAtLeast(1f)).coerceIn(low, high)
                    val tight = fits && (s >= high || h * s >= v * 0.985f)
                    if (tight || (!fits && s <= low) || kotlin.math.abs(target - s) < 0.004f) return@repeat
                    s = if (fits) target else minOf(target, s - 0.004f).coerceAtLeast(low)
                    h = measureContent(child, width, s)
                }
                if (h * s > v + 0.5f && s > low) {
                    s = (v / h.toFloat()).coerceIn(low, s)
                    h = measureContent(child, width, s)
                }
            }
            scale = s
            naturalHeight = h
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = s
            child.scaleY = s
            setMeasuredDimension(width, kotlin.math.ceil(h * s).toInt())
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility != GONE) child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }
    }

    /** Самый мелкий видимый непустой текст содержимого, px (без учёта масштаба вписывания). */
    private fun smallestText(view: View): Float {
        if (view.visibility != View.VISIBLE) return Float.MAX_VALUE
        if (view is TextView) return if (view.text.isNullOrEmpty()) Float.MAX_VALUE else view.textSize
        if (view !is ViewGroup) return Float.MAX_VALUE
        var min = Float.MAX_VALUE
        for (i in 0 until view.childCount) min = minOf(min, smallestText(view.getChildAt(i)))
        return min
    }

    companion object {
        /** Ниже — текст на настенном планшете читать тяжело: дальше прокрутка. */
        const val MIN_SCALE = 0.7f
        /** Самый мелкий текст после уменьшения — не мельче этого (Material 3: самая мелкая подпись 11 sp). */
        const val READABLE_SP = 11f
    }
}
