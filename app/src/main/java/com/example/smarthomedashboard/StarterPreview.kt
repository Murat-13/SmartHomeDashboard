package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.smarthomedashboard.data.TileEntity

/** Схема страниц, которые получатся (docs/setup-tz.md, часть 2): прямоугольники плиток, без надписей и данных дома. */
internal class StarterPreview(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var pages: List<List<Pair<RectF, Int>>> = emptyList()
    private var ratio = 1.6f

    fun show(plan: StarterPlan.Plan, width: Int, height: Int) {
        val w = maxOf(width, height)
        val h = minOf(width, height).coerceAtLeast(1)
        ratio = w.toFloat() / h
        pages = (0 until plan.pages).map { page -> plan.tiles.filter { it.page == page }.map { t ->
            val size = TilePlacement.size(t.container, t.config)
            RectF(t.x / w.toFloat(), t.y / h.toFloat(), (t.x + size.width) / w.toFloat(), (t.y + size.height) / h.toFloat()) to color(t)
        } }
        contentDescription = "Страниц: ${plan.pages}, плиток: ${plan.tiles.size}"
        invalidate()
    }

    private fun color(t: TileEntity) = when (t.type) {
        HomeClimateConfig.TYPE -> 0xFF5FA8D3.toInt()
        DoorbellConfig.CAMERA_TYPE -> 0xFF7A8794.toInt()
        GateTileConfig.TYPE -> 0xFFE0A458.toInt()
        "group" -> 0xFFF2D06B.toInt()
        else -> 0xFF4C7A9C.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), (HEIGHT_DP * density).toInt())

    override fun onDraw(canvas: Canvas) {
        if (pages.isEmpty()) return
        val gap = 12 * density
        // Все страницы в строку; много — мельче.
        val w = minOf(height * ratio, (width - gap * (pages.size - 1)) / pages.size)
        val h = w / ratio
        pages.forEachIndexed { i, rects ->
            val left = i * (w + gap)
            paint.color = Color.argb(255, 22, 34, 44)
            box.set(left, 0f, left + w, h)
            canvas.drawRoundRect(box, 6 * density, 6 * density, paint)
            rects.forEach { (r, color) ->
                paint.color = color
                box.set(left + r.left * w, r.top * h, left + r.right * w, r.bottom * h)
                canvas.drawRoundRect(box, 2 * density, 2 * density, paint)
            }
        }
    }

    private companion object { const val HEIGHT_DP = 110 }
}
