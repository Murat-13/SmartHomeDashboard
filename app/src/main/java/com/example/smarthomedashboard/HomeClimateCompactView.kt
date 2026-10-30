package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * Свёрнутая плитка «Часы · Погода · Климат дома», строго 2:1. Рисует всё на Canvas: часы (главный
 * элемент), текущую погоду и до пяти избранных комнат (3 + 2). Размеры шрифтов считаются от размеров
 * плитки и подгоняются по ширине, поэтому масштаб шрифта Android не вызывает наложений.
 * Время — системные часы Android; своя перерисовка раз в минуту (или секунду), без обновления панели.
 */
internal class HomeClimateCompactView(context: Context) : View(context), ModernHideAware {
    private var snapshot: HomeClimateSnapshot? = null
    private var hiddenBySource = false
    private val density = resources.displayMetrics.density

    private val body = RectF()
    private val tmp = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var bodyShader: Shader? = null
    private val tick = Runnable { invalidate(); scheduleTick() }

    init {
        tag = "hc_compact"
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        // Ореол — в собственном отступе карточки; тень — по телу.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt(), radius())
            }
        }
    }

    fun bind(next: HomeClimateSnapshot) {
        snapshot = next
        contentDescription = describe(next)
        invalidate()
        scheduleTick()
    }

    val boundSnapshot get() = snapshot

    override fun setHiddenBySource(hidden: Boolean) {
        hiddenBySource = hidden
        scheduleTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        scheduleTick()
    }

    /** Следующая перерисовка — ровно на границе минуты (секунды); невидимая плитка не тикает. */
    private fun scheduleTick() {
        removeCallbacks(tick)
        val clock = snapshot?.config?.clock ?: return
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown || hiddenBySource) return
        postDelayed(tick, delayToNextTick(clock.seconds))
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (snapshot != null) scheduleTick()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halo = halo()
        body.set(halo, halo, w - halo, h - halo)
        bodyShader = LinearGradient(0f, body.top, 0f, body.bottom, BODY_TOP, BODY_BOTTOM, Shader.TileMode.CLAMP)
        invalidateOutline()
    }

    private fun halo() = min(width, height) * 0.035f
    private fun radius() = min(body.width(), body.height()) * 0.11f

    override fun onDraw(canvas: Canvas) {
        val snap = snapshot ?: return
        val r = radius()
        val halo = halo()
        // Мягкое свечение: три кольца с убывающей яркостью в отступе карточки.
        for (i in 1..3) {
            stroke.color = ColorUtils.setAlphaComponent(NEON, 80 - i * 22)
            stroke.strokeWidth = halo / 3f
            val grow = halo * (i - 0.5f) / 3f
            tmp.set(body.left - grow, body.top - grow, body.right + grow, body.bottom + grow)
            canvas.drawRoundRect(tmp, r + grow, r + grow, stroke)
        }
        fill.shader = bodyShader
        canvas.drawRoundRect(body, r, r, fill)
        fill.shader = null
        stroke.color = NEON
        stroke.strokeWidth = max(1.5f * density, body.height() * 0.008f)
        canvas.drawRoundRect(body, r, r, stroke)

        val pad = body.height() * 0.08f
        val area = RectF(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        val rooms = snap.favorites
        val clockShare = if (rooms.isEmpty()) 0.56f else 0.33f
        val weatherShare = if (rooms.isEmpty()) 0.44f else 0.22f
        val gap = pad * 0.6f
        val clockRect = RectF(area.left, area.top, area.left + area.width() * clockShare, area.bottom)
        val weatherRect = RectF(clockRect.right + gap, area.top, clockRect.right + gap + area.width() * weatherShare - gap, area.bottom)
        drawClock(canvas, snap, clockRect)
        divider(canvas, clockRect.right + gap / 2, area)
        drawWeather(canvas, snap, weatherRect)
        if (rooms.isNotEmpty()) {
            divider(canvas, weatherRect.right + gap / 2, area)
            drawRooms(canvas, rooms, RectF(weatherRect.right + gap, area.top, area.right, area.bottom))
        }
    }

    private fun divider(canvas: Canvas, x: Float, area: RectF) {
        stroke.color = DIVIDER
        stroke.strokeWidth = max(1f, density * 0.75f)
        canvas.drawLine(x, area.top + area.height() * 0.06f, x, area.bottom - area.height() * 0.06f, stroke)
    }

    private fun drawClock(canvas: Canvas, snap: HomeClimateSnapshot, rect: RectF) {
        val clock = snap.config.clock
        val now = Date()
        val system24 = DateFormat.is24HourFormat(context)
        val requested = HomeClimateData.time(clock, system24, now)
        text.typeface = bold
        text.textSize = rect.height() * 0.42f
        val secondsFit = text.measureText(requested) <= rect.width() * 1.15f
        val time = HomeClimateData.time(clock, system24, now, seconds = clock.seconds && secondsFit)
        val amPm = HomeClimateData.amPm(clock, system24, now)
        text.typeface = bold
        text.color = TIME
        val timeSize = fit(text, time, rect.width() * (if (amPm != null) 0.8f else 0.96f), rect.height() * 0.42f)
        text.textSize = timeSize
        val dateLayout = layout(HomeClimateData.date(clock, now).uppercase(), rect.width(), rect.height() * 0.13f, DATE, medium, 2)
        val total = timeSize * 0.92f + rect.height() * 0.06f + dateLayout.height
        val top = rect.centerY() - total / 2
        val baseline = top + timeSize * 0.84f
        if (amPm != null) {
            val timeWidth = text.measureText(time)
            val small = TextPaint(text).apply { textSize = timeSize * 0.3f; textAlign = Paint.Align.LEFT }
            val total12 = timeWidth + small.measureText(amPm) + timeSize * 0.08f
            val start = rect.centerX() - total12 / 2
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(time, start, baseline, text)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(amPm, start + timeWidth + timeSize * 0.08f, baseline, small)
        } else {
            canvas.drawText(time, rect.centerX(), baseline, text)
        }
        drawLayout(canvas, dateLayout, rect.centerX(), top + timeSize * 0.92f + rect.height() * 0.06f)
    }

    private fun drawWeather(canvas: Canvas, snap: HomeClimateSnapshot, rect: RectF) {
        val current = snap.current
        val glyphSize = min(rect.width() * 0.62f, rect.height() * 0.34f)
        val temp = current.temperature?.let { HomeClimateData.number(it.value, 0) + it.unit } ?: HomeClimateData.DASH
        text.typeface = bold
        text.color = if (current.temperature != null) TEMP else MUTED
        val tempSize = fit(text, temp, rect.width() * 0.96f, rect.height() * 0.24f)
        val label = current.status ?: HomeClimateData.conditionText(current.condition)
        val labelLayout = layout(label, rect.width(), rect.height() * 0.11f, if (current.status == null) DATE else MUTED, medium, 2)
        val total = glyphSize + rect.height() * 0.04f + tempSize + rect.height() * 0.03f + labelLayout.height
        var y = rect.centerY() - total / 2
        current.glyph.draw(canvas, rect.centerX() - glyphSize / 2, y, glyphSize, iconPaint, dim = !current.available)
        y += glyphSize + rect.height() * 0.04f
        text.textSize = tempSize
        text.typeface = bold
        text.color = if (current.temperature != null) TEMP else MUTED
        canvas.drawText(temp, rect.centerX(), y + tempSize * 0.84f, text)
        y += tempSize + rect.height() * 0.03f
        drawLayout(canvas, labelLayout, rect.centerX(), y)
    }

    /** 1–3 в один ряд, 4 — 2 × 2, 5 — 3 сверху и 2 снизу. Пустых заглушек нет. */
    private fun drawRooms(canvas: Canvas, rooms: List<HomeClimateSnapshot.RoomLine>, rect: RectF) {
        val rows: List<List<HomeClimateSnapshot.RoomLine>> = when (rooms.size) {
            4 -> listOf(rooms.take(2), rooms.drop(2))
            5 -> listOf(rooms.take(3), rooms.drop(3))
            else -> listOf(rooms)
        }
        val gap = rect.height() * 0.05f
        val rowH = (rect.height() - gap * (rows.size - 1)) / rows.size
        var index = 0
        rows.forEachIndexed { rowIndex, row ->
            val cellW = (rect.width() - gap * (row.size - 1)) / row.size
            // Один ряд не растягивается на всю высоту: ячейка не выше, чем при двух рядах.
            val cellH = if (rows.size == 1) min(rowH, rect.height() * 0.62f) else rowH
            val top = rect.top + rowIndex * (rowH + gap) + (rowH - cellH) / 2
            row.forEachIndexed { i, line ->
                val left = rect.left + i * (cellW + gap)
                drawRoom(canvas, line, RectF(left, top, left + cellW, top + cellH), ROOM_COLORS[index % ROOM_COLORS.size])
                index++
            }
        }
    }

    private fun drawRoom(canvas: Canvas, line: HomeClimateSnapshot.RoomLine, cell: RectF, accent: Int) {
        val r = min(cell.width(), cell.height()) * 0.16f
        fill.color = CELL
        canvas.drawRoundRect(cell, r, r, fill)
        stroke.color = CELL_BORDER
        stroke.strokeWidth = max(1f, density * 0.75f)
        canvas.drawRoundRect(cell, r, r, stroke)
        val pad = cell.height() * 0.08f
        val iconSize = cell.height() * 0.26f
        val icon = line.room.modernIcon
        text.typeface = medium
        text.color = NAME
        val nameSize = fit(text, line.room.name, cell.width() - 2 * pad, cell.height() * 0.15f, minSize = cell.height() * 0.085f)
        val temp = line.temperature?.compact() ?: HomeClimateData.DASH
        text.typeface = bold
        val tempSize = fit(text, temp, cell.width() - 2 * pad, cell.height() * 0.25f)
        val total = (if (icon != null) iconSize + pad * 0.5f else 0f) + nameSize + pad * 0.4f + tempSize
        var y = cell.centerY() - total / 2
        if (icon != null) {
            iconPaint.color = accent
            icon.draw(canvas, cell.centerX() - iconSize / 2, y, iconSize, iconPaint)
            y += iconSize + pad * 0.5f
        }
        text.typeface = medium
        text.textSize = nameSize
        text.color = NAME
        val name = TextUtils.ellipsize(line.room.name, text, cell.width() - 2 * pad, TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, cell.centerX(), y + nameSize * 0.84f, text)
        y += nameSize + pad * 0.4f
        text.typeface = bold
        text.textSize = tempSize
        text.color = if (line.temperature != null) TIME else MUTED
        canvas.drawText(temp, cell.centerX(), y + tempSize * 0.84f, text)
    }

    private fun layout(value: String, width: Float, size: Float, color: Int, face: Typeface, lines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = face
            textSize = size
        }
        val w = width.toInt().coerceAtLeast(1)
        // Слово не разрывается посередине: сначала шрифт уменьшается (не меньше 55%) до ширины
        // самого длинного слова и общей длины, затем перенос по словам и многоточие.
        val longest = value.split(' ').maxByOrNull { paint.measureText(it) }.orEmpty()
        var current = size
        while (current > size * 0.55f && (paint.measureText(longest) > w || paint.measureText(value) > w * lines * 0.95f)) {
            current *= 0.92f
            paint.textSize = current
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, centerX: Float, top: Float) {
        canvas.save()
        canvas.translate(centerX - layout.width / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun describe(snap: HomeClimateSnapshot): String {
        val parts = mutableListOf(snap.current.status ?: HomeClimateData.conditionText(snap.current.condition))
        snap.current.temperature?.let { parts += it.full() }
        snap.favorites.forEach { parts += "${it.room.name}: ${it.temperature?.full() ?: "нет данных"}" }
        return snap.title + ". " + parts.joinToString(", ")
    }

    companion object {
        private const val NEON = 0xFF22D3F5.toInt()
        private const val BODY_TOP = 0xFF0C1E3A.toInt()
        private const val BODY_BOTTOM = 0xFF07142A.toInt()
        private const val DIVIDER = 0x6635A7D6
        private const val TIME = 0xFFF4F8FF.toInt()
        private const val DATE = 0xFFC6D4E8.toInt()
        private const val TEMP = 0xFF5EEBFF.toInt()
        private const val MUTED = 0xFF7F93AA.toInt()
        private const val NAME = 0xFFDCE6F2.toInt()
        private const val CELL = 0x8010274A.toInt()
        private const val CELL_BORDER = 0xFF1F4C78.toInt()
        val ROOM_COLORS = intArrayOf(0xFF4FD8FF.toInt(), 0xFF47E6A0.toInt(), 0xFFFFC94D.toInt(), 0xFFB48CFF.toInt(), 0xFF5AA9FF.toInt())

        /** Задержка до следующей границы минуты/секунды по системным часам (+ небольшой запас). */
        fun delayToNextTick(seconds: Boolean, now: Long = System.currentTimeMillis()): Long {
            val period = if (seconds) 1_000L else 60_000L
            return period - now % period + 30L
        }

        /** Наибольший размер не больше [maxSize], при котором [value] помещается в [width]. */
        fun fit(paint: Paint, value: String, width: Float, maxSize: Float, minSize: Float = maxSize * 0.35f): Float {
            paint.textSize = maxSize
            val measured = paint.measureText(value)
            val size = if (measured <= width || measured == 0f) maxSize else max(minSize, maxSize * width / measured)
            paint.textSize = size
            return size
        }

        /** Плитка на панели: карточка 2:1 и ручка resize (видна только в режиме редактирования). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(width, height)
                tag = tileId
                elevation = 8f
                clipToOutline = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val inset = (min(view.width, view.height) * 0.035f).toInt()
                        outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, min(view.width, view.height) * 0.1f)
                    }
                }
            }
            tile.addView(HomeClimateCompactView(context))
            val handle = ImageView(context).apply {
                setImageResource(R.drawable.ic_resize)
                layoutParams = FrameLayout.LayoutParams(40, 40).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END }
                visibility = if (isEditMode) VISIBLE else GONE
                setPadding(8, 8, 8, 8)
            }
            tile.addView(handle)
            return tile to handle
        }
    }
}
