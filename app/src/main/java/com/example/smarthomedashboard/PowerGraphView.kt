package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Полотно графика мощности: линия с заливкой до нуля — голубая выше 0 Вт (потребление), зелёная ниже
 * (отдача), цвет меняется точно в точке пересечения нуля. Разрывы данных — разрывы линии и заливки.
 * Минутные агрегаты (данные старше 7 дней) — полоса мин–макс со средней линией.
 *
 * Жесты: один палец — прокрутка истории; два — масштаб по времени вокруг середины жеста; касание — курсор
 * на ближайшем реальном замере; перетаскивание от линии курсора — только курсор. Все касания внутри
 * графика забирает график (родительская прокрутка их не перехватывает).
 */
internal class PowerGraphView(context: Context, private val state: PowerGraphState) : View(context) {
    /** Источник времени (в тестах — подменяется). */
    var clock: () -> Long = System::currentTimeMillis
    /** Участок сдвинут/увеличен — владелец подгружает данные. */
    var onViewportChanged: (() -> Unit)? = null
    /** Любое касание графика (перезапуск таймера автозакрытия карточки). */
    var onInteraction: (() -> Unit)? = null
    /** Курсор поставлен/сдвинут/убран. */
    var onCursorChanged: (() -> Unit)? = null

    var series: PowerSeries = PowerSeries(emptyList(), emptyList())
        set(value) { field = value; invalidate() }
    /** Подпись поверх пустого графика («Выберите источник…», «Нет данных…»). */
    var message: String? = null
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val positive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POSITIVE; style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeJoin = Paint.Join.ROUND }
    private val negative = Paint(positive).apply { color = NEGATIVE }
    private val positiveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POSITIVE; alpha = 70; style = Paint.Style.FILL }
    private val negativeFill = Paint(positiveFill).apply { color = NEGATIVE; alpha = 70 }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Color.argb(45, 255, 255, 255); strokeWidth = dp(1f) }
    private val zero = Paint().apply { color = Color.argb(150, 255, 255, 255); strokeWidth = dp(1.5f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = dp(11f) }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = dp(14f); textAlign = Paint.Align.CENTER }
    private val cursorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2f) }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 34, 52) }
    private val bubbleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POSITIVE; style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f) }
    /** Подписи времени: форматтеры и метка создаются один раз, а не на каждый кадр прокрутки. */
    private val timeFormats = HashMap<String, SimpleDateFormat>()
    private val stamp = Date()
    private fun formatTime(pattern: String, t: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = timeFormats.getOrPut(pattern) { SimpleDateFormat(pattern, RU) }
        if (format.timeZone.id != zone.id) format.timeZone = zone
        stamp.time = t
        return format.format(stamp)
    }
    private val line = Path()
    private val fill = Path()
    private val rect = RectF()
    private val plot = RectF()

    /** Вертикальный диапазон с гистерезисом: не пересчитывается от каждого небольшого изменения. */
    private var yMin = -100.0
    private var yMax = 100.0
    private var yStep = 50.0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previous = detector.previousSpanX
            val current = detector.currentSpanX
            if (previous <= 0f || current <= 0f) return true
            state.zoom(current / previous, timeAt(detector.focusX), clock())
            scaled = true
            onViewportChanged?.invoke()
            invalidate()
            return true
        }
    })
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var lastX = 0f
    private var mode = Mode.NONE
    private var scaled = false
    private enum class Mode { NONE, PENDING, PAN, CURSOR }

    init {
        isClickable = true
        contentDescription = "График мощности"
    }

    // ---- Координаты ----

    private fun from() = state.viewport(clock()).first
    private fun to() = state.viewport(clock()).last + 1
    fun timeAt(x: Float): Long {
        val a = from(); val b = to()
        return a + ((x - plot.left) / plot.width().coerceAtLeast(1f) * (b - a)).toLong()
    }
    private fun xOf(ts: Long, a: Long, b: Long) = plot.left + (ts - a).toFloat() / (b - a).coerceAtLeast(1) * plot.width()
    private fun yOf(watts: Double) = (plot.bottom - (watts - yMin) / (yMax - yMin) * plot.height()).toFloat()

    // ---- Отрисовка ----

    /** Область построения считается при смене размера: касания до первой отрисовки попадают точно. */
    private fun layoutPlot() {
        val labelWidth = label.measureText("−10,0 кВт") + dp(6f)
        plot.set(labelWidth, dp(8f), width - dp(6f), height - dp(22f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPlot()
    }

    override fun onDraw(canvas: Canvas) {
        layoutPlot()
        if (plot.width() <= 0 || plot.height() <= 0) return
        val now = clock()
        val a = from(); val b = to()
        updateScale(a, b)
        drawGrid(canvas, a, b)
        val count = canvas.save()
        canvas.clipRect(plot)
        drawBuckets(canvas, a, b)
        drawSamples(canvas, a, b, now)
        canvas.restoreToCount(count)
        message?.let { canvas.drawText(it, plot.centerX(), plot.centerY(), messagePaint) }
        drawCursor(canvas, a, b)
        if (state.live) postInvalidateDelayed(((b - a) / plot.width().coerceAtLeast(1f)).toLong().coerceIn(250L, 10_000L))
    }

    private fun updateScale(a: Long, b: Long) {
        var lo = 0.0
        var hi = 0.0
        series.buckets.forEach { if (it.end > a && it.start < b) { lo = minOf(lo, it.min); hi = maxOf(hi, it.max) } }
        val samples = series.samples
        for (i in samples.indices) {
            val v = samples[i].watts ?: continue
            val next = samples.getOrNull(i + 1)?.ts ?: Long.MAX_VALUE
            if (next > a && samples[i].ts < b) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        }
        if (hi - lo < 10) hi = lo + 10
        // Держим прежний диапазон, пока данные в него помещаются и занимают хотя бы половину.
        val fits = lo >= yMin && hi <= yMax && (hi - lo) >= (yMax - yMin) * 0.45
        if (fits) return
        val step = niceStep((hi - lo) * 1.1 / 4)
        yStep = step
        yMin = floor(lo * 1.05 / step) * step
        yMax = ceil(hi * 1.05 / step) * step
        if (yMax <= yMin) yMax = yMin + step
    }

    private fun drawGrid(canvas: Canvas, a: Long, b: Long) {
        var v = yMin
        while (v <= yMax + yStep / 2) {
            val y = yOf(v)
            canvas.drawLine(plot.left, y, plot.right, y, if (abs(v) < yStep / 1000) zero else grid)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(axisLabel(v), plot.left - dp(4f), y + dp(4f), label)
            v += yStep
        }
        canvas.drawLine(plot.left, yOf(0.0), plot.right, yOf(0.0), zero)
        val step = timeStep(b - a)
        val zone = TimeZone.getDefault()
        val offset = zone.getOffset(a).toLong()
        var t = ceil((a + offset).toDouble() / step).toLong() * step - offset
        label.textAlign = Paint.Align.CENTER
        val pattern = timeFormat(b - a)
        while (t < b) {
            val x = xOf(t, a, b)
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            canvas.drawText(formatTime(pattern, t, zone), x, height - dp(6f), label)
            t += step
        }
    }

    private fun drawBuckets(canvas: Canvas, a: Long, b: Long) {
        val y0 = yOf(0.0)
        series.buckets.forEach { bucket ->
            if (bucket.end <= a || bucket.start >= b || bucket.coveredMs <= 0) return@forEach
            val x1 = xOf(bucket.start, a, b)
            val x2 = maxOf(xOf(bucket.end, a, b), x1 + 1f)
            band.color = if (bucket.avg >= 0) POSITIVE else NEGATIVE
            band.alpha = 60
            rect.set(x1, yOf(bucket.max), x2, yOf(bucket.min))
            canvas.drawRect(rect, band)
            val ya = yOf(bucket.avg)
            rect.set(x1, minOf(ya, y0), x2, maxOf(ya, y0))
            band.alpha = 50
            canvas.drawRect(rect, band)
        }
    }

    /**
     * Линия между соседними замерами — прямая (решение владельца); после последнего замера значение
     * держится до текущего момента. Разрыв (null) прерывает и линию, и заливку.
     */
    private fun drawSamples(canvas: Canvas, a: Long, b: Long, now: Long) {
        val samples = series.samples
        if (samples.isEmpty()) return
        val y0 = yOf(0.0)
        line.reset(); fill.reset()
        var open = false
        var firstX = 0f
        var lastX = 0f
        fun close() {
            if (!open) return
            fill.lineTo(lastX, y0); fill.lineTo(firstX, y0); fill.close()
            open = false
        }
        for (i in samples.indices) {
            val s = samples[i]
            val v = s.watts
            if (v == null) { close(); continue }
            val x = xOf(s.ts, a, b)
            val y = yOf(v)
            if (!open) {
                line.moveTo(x, y); fill.moveTo(x, y); firstX = x; open = true
            } else {
                line.lineTo(x, y); fill.lineTo(x, y)
            }
            lastX = x
            val next = samples.getOrNull(i + 1)
            if (next == null || next.watts == null) {
                // Значение держится до разрыва или (последний замер) до текущего момента.
                val until = next?.ts ?: minOf(now, b)
                if (until > s.ts) {
                    val xe = xOf(until, a, b)
                    line.lineTo(xe, y); fill.lineTo(xe, y); lastX = xe
                }
            }
        }
        close()
        val count = canvas.save()
        canvas.clipRect(plot.left, plot.top, plot.right, y0)
        canvas.drawPath(fill, positiveFill)
        canvas.drawPath(line, positive)
        canvas.restoreToCount(count)
        canvas.save()
        canvas.clipRect(plot.left, y0, plot.right, plot.bottom)
        canvas.drawPath(fill, negativeFill)
        canvas.drawPath(line, negative)
        canvas.restoreToCount(count)
    }

    /** Ближайший к моменту [ts] реальный замер (или минутный агрегат) в видимом участке. */
    fun nearest(ts: Long): Pair<Long, Double>? {
        val a = from(); val b = to()
        var best: Pair<Long, Double>? = null
        fun consider(t: Long, v: Double) {
            if (t < a || t >= b) return
            if (best == null || abs(t - ts) < abs(best!!.first - ts)) best = t to v
        }
        series.samples.forEach { s -> s.watts?.let { consider(s.ts, it) } }
        series.buckets.forEach { if (it.coveredMs > 0) consider(it.start + PowerHistory.MINUTE_MS / 2, it.avg) }
        return best
    }

    /** Значение под курсором: точный замер или агрегат (с пометкой «≈», точность 1 мин). */
    fun cursorValue(): Pair<Long, String>? {
        val t = state.cursor ?: return null
        series.samples.firstOrNull { it.ts == t && it.watts != null }?.let { return t to PowerHistory.formatWatts(it.watts) }
        series.buckets.firstOrNull { it.start + PowerHistory.MINUTE_MS / 2 == t }?.let {
            return t to "≈${PowerHistory.formatWatts(it.avg)} (1 мин)"
        }
        return null
    }

    private fun drawCursor(canvas: Canvas, a: Long, b: Long) {
        val (t, text) = cursorValue() ?: return
        if (t < a || t >= b) return
        val x = xOf(t, a, b)
        canvas.drawLine(x, plot.top, x, plot.bottom, cursorLine)
        val time = formatTime(if (b - a <= 2 * PowerHistory.MINUTE_MS) "HH:mm:ss" else if (b - a <= 2 * PowerHistory.DAY_MS) "HH:mm" else "dd.MM HH:mm", t)
        val content = "$time   $text"
        val w = bubbleText.measureText(content) + dp(20f)
        val left = (x - w / 2).coerceIn(plot.left, (plot.right - w).coerceAtLeast(plot.left))
        rect.set(left, plot.top, left + w, plot.top + dp(28f))
        canvas.drawRoundRect(rect, dp(8f), dp(8f), bubble)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), bubbleBorder)
        canvas.drawText(content, left + dp(10f), rect.bottom - dp(9f), bubbleText)
    }

    // ---- Жесты ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        onInteraction?.invoke()
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; lastX = event.x; scaled = false
                val cursorX = state.cursor?.let { xOf(it, from(), to()) }
                mode = if (cursorX != null && abs(event.x - cursorX) <= dp(CURSOR_GRAB_DP)) Mode.CURSOR else Mode.PENDING
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = Mode.NONE
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaled) {
                when (mode) {
                    Mode.PENDING -> if (abs(event.x - downX) > touchSlop) { mode = Mode.PAN; lastX = event.x }
                    Mode.PAN -> {
                        val dt = ((lastX - event.x) / plot.width().coerceAtLeast(1f) * state.span).toLong()
                        lastX = event.x
                        if (dt != 0L) { state.pan(dt, clock()); onViewportChanged?.invoke(); invalidate() }
                    }
                    Mode.CURSOR -> moveCursor(event.x)
                    Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.PENDING && !scaled) { moveCursor(event.x); performClick() }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveCursor(x: Float) {
        val hit = nearest(timeAt(x))
        state.cursor = hit?.first
        onCursorChanged?.invoke()
        invalidate()
    }

    companion object {
        private val RU = Locale("ru")
        val POSITIVE = Color.rgb(79, 209, 245)
        val NEGATIVE = Color.rgb(74, 222, 128)
        /** Зона захвата линии курсора по обе стороны (палец, а не стилус). */
        const val CURSOR_GRAB_DP = 24f

        /** Подпись оси: «0 Вт», «500 Вт», «−1 кВт», «2,5 кВт» — без лишних нулей. */
        fun axisLabel(watts: Double): String {
            val sign = if (watts < 0) "−" else ""
            val abs = abs(watts)
            fun plain(v: Double) = java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString().replace('.', ',')
            return if (abs >= 1_000) "$sign${plain(abs / 1_000)} кВт" else "$sign${plain(abs)} Вт"
        }

        fun niceStep(raw: Double): Double {
            if (raw <= 0 || !raw.isFinite()) return 1.0
            val magnitude = 10.0.pow(floor(log10(raw)))
            val n = raw / magnitude
            return magnitude * when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 2.5 -> 2.5; n <= 5 -> 5.0; else -> 10.0 }
        }

        private val TIME_STEPS = longArrayOf(1_000, 5_000, 10_000, 15_000, 30_000, 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000,
            30 * 60_000, 3_600_000, 2 * 3_600_000, 3 * 3_600_000, 6 * 3_600_000, 12 * 3_600_000, 86_400_000, 2 * 86_400_000, 7 * 86_400_000)

        /** Шаг подписей времени: 4–6 подписей на экран. */
        fun timeStep(span: Long): Long = TIME_STEPS.firstOrNull { span / it <= 6 } ?: TIME_STEPS.last()

        fun timeFormat(span: Long) = when {
            span <= 2 * PowerHistory.MINUTE_MS -> "HH:mm:ss"
            span <= 2 * PowerHistory.DAY_MS -> "HH:mm"
            else -> "dd.MM"
        }
    }
}
