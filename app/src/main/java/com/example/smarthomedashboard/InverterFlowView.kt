package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * Схема потоков энергии плитки «Инвертор» (решения владельца 05.11.2026): крестом — солнце сверху,
 * сеть слева, дом справа, АКБ снизу, инвертор в центре. По активным линиям бегут точки в направлении
 * потока; скорость растёт с мощностью. Анимация идёт только у видимой схемы (окно, родители, не скрыта
 * раскрытием, экран не затемнён, системные анимации включены). Отрисовка без выделения памяти в кадре.
 */
internal class InverterFlowView(context: Context) : View(context), ModernHideAware, PanelMotion.Listener {
    private val density = resources.displayMetrics.density
    private var snapshot: InverterModel.Snapshot? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PowerModernCard.SECONDARY }
    private val rect = RectF()
    private val batteryPath = Path()
    private val pylon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_power_pylon)?.mutate()
    private val inverter: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_power_inverter)?.mutate()

    /** Цвет состояния плитки: кольцо инвертора и его значок. */
    var accent: Int = PowerModernConfig.NEUTRAL
        set(value) { if (field != value) { field = value; invalidate() } }

    // Геометрия (пересчитывается при смене размера).
    private var nodeR = 0f
    private var cx = 0f
    private var cy = 0f
    private val solarAt = FloatArray(2)
    private val gridAt = FloatArray(2)
    private val homeAt = FloatArray(2)
    private val batteryAt = FloatArray(2)

    // Анимация: смещение точек каждой линии (px вдоль линии) и один аниматор на схему.
    private val offsets = FloatArray(4)
    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L
    private var hiddenBySource = false
    private var windowShown = true

    // Подписи считаются при привязке данных, а не в кадре: анимация точек не создаёт строк.
    private var solarText = ""
    private var gridText = ""
    private var loadText = ""
    private var batteryText = ""
    private var socText: String? = null
    private var tintedPylon = 0
    private var tintedInverter = 0

    fun bind(next: InverterModel.Snapshot) {
        if (next == snapshot) return
        snapshot = next
        solarText = InverterModel.power(next.solar)
        gridText = InverterModel.power(next.grid)
        loadText = InverterModel.power(next.load)
        batteryText = InverterModel.power(next.battery, signed = true)
        socText = next.soc?.let { BatteryFormat.decimal(it, 0, java.util.Locale.ROOT) + "%" }
        contentDescription = describe(next)
        updateAnimation()
        invalidate()
    }

    val boundSnapshot get() = snapshot
    internal val animating get() = animator != null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h).toFloat()
        nodeR = side * 0.095f
        cx = w / 2f
        cy = h / 2f
        // Подписи сети и дома — под значками, солнца и АКБ — справа: крест вписан в квадрат с запасом под текст.
        val vertical = h / 2f - nodeR * 1.15f
        val horizontal = min(w / 2f - nodeR * 1.6f, side * 0.39f)
        solarAt[0] = cx; solarAt[1] = cy - vertical
        batteryAt[0] = cx; batteryAt[1] = cy + vertical
        gridAt[0] = cx - horizontal; gridAt[1] = cy
        homeAt[0] = cx + horizontal; homeAt[1] = cy
        valuePaint.textSize = nodeR * 0.7f
        captionPaint.textSize = nodeR * 0.5f
        linePaint.strokeWidth = (nodeR * 0.07f).coerceAtLeast(density)
        ringPaint.strokeWidth = (nodeR * 0.075f).coerceAtLeast(density)
    }

    override fun onDraw(canvas: Canvas) {
        val snap = snapshot ?: return
        if (nodeR <= 0f) return
        val hub = nodeR * 1.05f
        // Линии: от края узла к краю инвертора. Направление точек — по знаку потока.
        drawLink(canvas, 0, solarAt, snap.solarActive, forward = true, SOLAR, hub)
        drawLink(canvas, 1, gridAt, snap.gridActive, forward = (snap.grid ?: 0.0) >= 0, GRID, hub)
        drawLink(canvas, 2, homeAt, snap.loadActive, forward = false, HOME, hub)
        drawLink(canvas, 3, batteryAt, snap.batteryActive, forward = (snap.battery ?: 0.0) < 0, BATTERY, hub)

        drawNode(canvas, solarAt, snap.solarActive, SOLAR) { x, y, s, p -> ModernIcon.SUN.draw(canvas, x, y, s, p) }
        drawNode(canvas, gridAt, snap.gridActive, GRID) { x, y, s, p ->
            if (tintedPylon != p.color) { tintedPylon = p.color; pylon?.setTint(p.color) }
            drawDrawable(canvas, pylon, x, y, s)
        }
        drawNode(canvas, homeAt, snap.loadActive, HOME) { x, y, s, p -> ModernIcon.HOME.draw(canvas, x, y, s, p) }
        drawNode(canvas, batteryAt, snap.batteryActive || snap.soc != null, BATTERY) { x, y, s, p -> drawBattery(canvas, x, y, s, p, snap.soc) }

        // Инвертор: скруглённый квадрат в цвете состояния.
        rect.set(cx - hub, cy - hub, cx + hub, cy + hub)
        fillPaint.color = Color.argb(140, 12, 22, 30)
        canvas.drawRoundRect(rect, hub * 0.32f, hub * 0.32f, fillPaint)
        ringPaint.color = accent
        canvas.drawRoundRect(rect, hub * 0.32f, hub * 0.32f, ringPaint)
        if (tintedInverter != accent) { tintedInverter = accent; inverter?.setTint(accent) }
        drawDrawable(canvas, inverter, cx - hub * 0.62f, cy - hub * 0.62f, hub * 1.24f)

        // Подписи: солнце и АКБ — справа от значка, сеть и дом — под значком.
        valuePaint.textAlign = Paint.Align.LEFT
        val sideX = cx + nodeR * 1.35f
        canvas.drawText(solarText, sideX, solarAt[1] + valuePaint.textSize * 0.36f, valuePaint)
        canvas.drawText(batteryText, sideX, batteryAt[1] + valuePaint.textSize * 0.36f, valuePaint)
        socText?.let { soc ->
            captionPaint.textAlign = Paint.Align.LEFT
            val socX = sideX + valuePaint.measureText(batteryText) + nodeR * 0.3f
            canvas.drawText(soc, socX, batteryAt[1] + valuePaint.textSize * 0.36f, captionPaint)
        }
        valuePaint.textAlign = Paint.Align.CENTER
        val below = nodeR * 1.15f + valuePaint.textSize * 0.9f
        canvas.drawText(gridText, gridAt[0], gridAt[1] + below, valuePaint)
        canvas.drawText(loadText, homeAt[0], homeAt[1] + below, valuePaint)
        captionPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Сеть", gridAt[0], gridAt[1] + below + captionPaint.textSize * 1.25f, captionPaint)
        canvas.drawText("Дом", homeAt[0], homeAt[1] + below + captionPaint.textSize * 1.25f, captionPaint)
    }

    private fun drawLink(canvas: Canvas, index: Int, node: FloatArray, active: Boolean, forward: Boolean, color: Int,
                         hub: Float) {
        val dx = cx - node[0]
        val dy = cy - node[1]
        val length = hypot(dx, dy)
        if (length <= 0f) return
        val ux = dx / length
        val uy = dy / length
        // Отрезок между краем значка узла и краем инвертора.
        val startInset = nodeR * 1.08f
        val endInset = hub * if (dx == 0f || dy == 0f) 1.08f else 1.45f
        val x1 = node[0] + ux * startInset
        val y1 = node[1] + uy * startInset
        val x2 = cx - ux * endInset
        val y2 = cy - uy * endInset
        val span = length - startInset - endInset
        if (span <= 0f) return
        linePaint.color = if (active) withAlpha(color, 90) else Color.argb(38, 255, 255, 255)
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        if (!active) return
        // Точки: шаг вдоль линии; смещение растёт со временем, направление — по знаку.
        val step = nodeR * 0.62f
        val radius = nodeR * 0.085f
        var d = offsets[index] % step
        while (d < span) {
            val along = if (forward) d else span - d
            val x = x1 + ux * along
            val y = y1 + uy * along
            dotPaint.color = withAlpha(color, 60)
            canvas.drawCircle(x, y, radius * 2.2f, dotPaint)
            dotPaint.color = color
            canvas.drawCircle(x, y, radius, dotPaint)
            d += step
        }
    }

    private inline fun drawNode(canvas: Canvas, at: FloatArray, active: Boolean, color: Int,
                                icon: (Float, Float, Float, Paint) -> Unit) {
        fillPaint.color = Color.argb(150, 12, 22, 30)
        canvas.drawCircle(at[0], at[1], nodeR, fillPaint)
        ringPaint.color = if (active) withAlpha(color, 200) else Color.argb(60, 255, 255, 255)
        canvas.drawCircle(at[0], at[1], nodeR, ringPaint)
        iconPaint.color = if (active) color else Color.argb(110, 200, 210, 220)
        val size = nodeR * 1.15f
        icon(at[0] - size / 2, at[1] - size / 2, size, iconPaint)
    }

    /** АКБ: корпус с клеммой и заливкой по заряду. */
    private fun drawBattery(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint, soc: Double?) {
        val w = size * 0.56f
        val h = size * 0.86f
        val left = x + (size - w) / 2
        val top = y + (size - h) / 2 + size * 0.05f
        val stroke = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.085f
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, w * 0.18f, w * 0.18f, paint)
        rect.set(left + w * 0.3f, top - size * 0.1f, left + w * 0.7f, top)
        paint.style = Paint.Style.FILL
        canvas.drawRect(rect, paint)
        soc?.coerceIn(0.0, 100.0)?.let { level ->
            val inset = size * 0.12f
            val fill = (h - inset * 2) * (level / 100.0).toFloat()
            rect.set(left + inset, top + h - inset - fill, left + w - inset, top + h - inset)
            canvas.drawRect(rect, paint)
        }
        paint.strokeWidth = stroke
    }

    /** Оттенок задаётся только при смене цвета (setTint создаёт объект) — в кадре ничего не выделяется. */
    private fun drawDrawable(canvas: Canvas, drawable: Drawable?, x: Float, y: Float, size: Float) {
        drawable ?: return
        drawable.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        drawable.draw(canvas)
    }

    // ---- Анимация ----

    private fun shouldAnimate(): Boolean {
        val snap = snapshot ?: return false
        val moving = snap.solarActive || snap.gridActive || snap.loadActive || snap.batteryActive
        return moving && isAttachedToWindow && isShown && windowShown && !hiddenBySource && !PanelMotion.dimmed &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
    }

    private fun updateAnimation() {
        val run = shouldAnimate()
        if (run == (animator != null)) return
        if (run) {
            lastFrameMs = 0L
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = null
                addUpdateListener { advance(it) }
                start()
            }
        } else stopAnimation()
    }

    private fun advance(animation: ValueAnimator) {
        if (animator !== animation) return
        val snap = snapshot ?: return
        val frame = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((frame - lastFrameMs).coerceIn(0L, 100L)) / 1000f
        lastFrameMs = frame
        offsets[0] += dt * speed(snap.solar)
        offsets[1] += dt * speed(snap.grid)
        offsets[2] += dt * speed(snap.load)
        offsets[3] += dt * speed(snap.battery)
        // Ограничиваем рост: шаг точек кратен, вид не меняется.
        val step = nodeR * 0.62f
        if (step > 0f) for (i in offsets.indices) offsets[i] %= step * 1_000f
        invalidate()
    }

    /** px/с: от ~0,6 значка при сотнях ватт до ~3 значков при 10 кВт (логарифмически). */
    private fun speed(watts: Double?): Float {
        val value = kotlin.math.abs(watts ?: 0.0)
        if (value <= InverterModel.IDLE_W) return 0f
        val k = (ln(1 + value / 100.0) / ln(101.0)).coerceIn(0.0, 1.0).toFloat()
        return nodeR * (0.6f + 2.4f * k)
    }

    private fun stopAnimation() {
        animator?.let {
            animator = null
            it.removeAllUpdateListeners()
            it.cancel()
        }
    }

    override fun setHiddenBySource(hidden: Boolean) {
        hiddenBySource = hidden
        updateAnimation()
    }

    override fun motionChanged() = updateAnimation()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PanelMotion.add(this)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        PanelMotion.remove(this)
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun describe(snap: InverterModel.Snapshot) = "Инвертор. ${snap.status}. Солнце ${InverterModel.power(snap.solar)}, " +
        "сеть ${InverterModel.power(snap.grid)}, дом ${InverterModel.power(snap.load)}, " +
        "АКБ ${InverterModel.power(snap.battery, signed = true)}" + (snap.soc?.let { ", заряд ${it.toInt()}%" } ?: "")

    companion object {
        val SOLAR = PowerModernConfig.YELLOW
        val GRID = InverterModel.SKY
        val HOME = Color.rgb(226, 236, 244)
        val BATTERY = PowerModernConfig.EMERALD

        private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}

/**
 * Пауза анимаций панели, пока экран затемнён: на стене 24/7 незачем двигать точки при яркости 1%.
 * Только главный поток. Слушатели — живые View в окне; отписываются при отсоединении.
 */
internal object PanelMotion {
    fun interface Listener { fun motionChanged() }
    private val listeners = LinkedHashSet<Listener>()

    var dimmed = false
        set(value) {
            if (field == value) return
            field = value
            listeners.toList().forEach { it.motionChanged() }
        }

    fun add(listener: Listener) { listeners += listener }
    fun remove(listener: Listener) { listeners -= listener }
}
