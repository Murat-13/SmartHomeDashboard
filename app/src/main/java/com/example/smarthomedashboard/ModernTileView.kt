package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Плитка Modern: одиночная кнопка, материнская плитка группы или дочерняя кнопка раскрытой панели.
 * Рисует всё сама (фон, рамку, ореол ON, иконку, подпись, миниатюры, индикатор ожидания),
 * поэтому 20+ миниатюр не создают отдельных View. Состояние — только из [bind]: View не знает HA.
 */
internal class ModernTileView(context: Context, val kind: Kind) : View(context) {
    enum class Kind { SINGLE, GROUP, CHILD }

    /** Визуальное состояние. ERROR — временная индикация ошибки команды поверх фактического состояния. */
    enum class Visual { ON, OFF, UNAVAILABLE, ERROR }

    data class Item(val icon: ModernIcon, val state: ConfirmedState, val failedAtMs: Long? = null)

    data class Model(
        val title: String,
        val icon: ModernIcon,
        val state: ConfirmedState,
        /** elapsedRealtime начала неподтверждённой команды: spinner появляется через [SPINNER_DELAY_MS]. */
        val pendingSinceMs: Long? = null,
        /** elapsedRealtime обнаруженной ошибки команды: индикация [ERROR_MS]. */
        val failedAtMs: Long? = null,
        /** Миниатюры группы в порядке конфигурации; пустой список — пустая группа. */
        val items: List<Item> = emptyList(),
        /** Выбранная иконка группы рядом с названием; null — прежняя компоновка без иконки. */
        val groupIcon: ModernIcon? = null
    )

    private data class Palette(val background: Int, val border: Int, val icon: Int)

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private var model: Model? = null
    var editOutline = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Дополнительный бирюзово-белый контур родителя раскрытой панели. Цвет ON/OFF не меняется. */
    var highlight = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var thumbnailsHidden = false
        set(value) { if (field != value) { field = value; invalidate() } }

    // Отображаемые (возможно, промежуточные) цвета и цели перехода.
    private var shown: Palette? = null
    private var from: Palette? = null
    private var target: Palette? = null
    private var thumbsShown = IntArray(0)
    private var thumbsFrom = IntArray(0)
    private var thumbsTarget = IntArray(0)
    private var transition: ValueAnimator? = null

    private var spinnerVisible = false
    private var spinnerAngle = 0f
    private var spinner: ValueAnimator? = null
    private val refresh = Runnable { model?.let { bind(it, animate = true) } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = EDIT_OUTLINE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var titleLayout: StaticLayout? = null
    private var titleKey: Any? = null

    private val card = RectF()
    private val tmp = RectF()
    private var thumbCell = 0f
    private var thumbCols = 1
    private var thumbOriginX = 0f
    private var thumbOriginY = 0f
    private var thumbGap = 0f

    init {
        // Мягкая статичная тень по форме карточки.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(card.left.toInt(), card.top.toInt(), card.right.toInt(), card.bottom.toInt(), radius())
            }
        }
        elevation = dp(6f)
        isClickable = true
        isFocusable = true
    }

    fun bind(next: Model, animate: Boolean) {
        removeCallbacks(refresh)
        val now = SystemClock.elapsedRealtime()
        val error = next.failedAtMs?.let { now - it < ERROR_MS } == true
        val visual = when {
            error && kind != Kind.GROUP -> Visual.ERROR
            next.state == ConfirmedState.ON -> Visual.ON
            next.state == ConfirmedState.OFF -> Visual.OFF
            else -> Visual.UNAVAILABLE
        }
        val palette = palette(visual)
        val thumbColors = IntArray(next.items.size) { i ->
            val item = next.items[i]
            if (item.failedAtMs?.let { now - it < ERROR_MS } == true) ERROR_BORDER else thumbKey(item.state)
        }
        val previous = model
        model = next
        displayedVisual = visual
        val structureChanged = previous == null || previous.items.size != next.items.size
        if (shown == null || !animate || !isAttachedToWindow || structureChanged && previous?.items?.isNotEmpty() == true) {
            transition?.cancel()
            shown = palette; target = palette
            thumbsShown = thumbColors; thumbsTarget = thumbColors
        } else if (palette != target || !thumbColors.contentEquals(thumbsTarget)) {
            startTransition(palette, thumbColors)
        }

        // Spinner — только для команды дольше SPINNER_DELAY_MS; ошибка — ровно ERROR_MS.
        var nextCheck = Long.MAX_VALUE
        val pendingAge = next.pendingSinceMs?.let { now - it }
        val showSpinner = pendingAge != null && pendingAge >= SPINNER_DELAY_MS
        if (pendingAge != null && !showSpinner) nextCheck = min(nextCheck, SPINNER_DELAY_MS - pendingAge)
        (listOfNotNull(next.failedAtMs) + next.items.mapNotNull { it.failedAtMs }).forEach { at ->
            val left = ERROR_MS - (now - at)
            if (left > 0) nextCheck = min(nextCheck, left)
        }
        setSpinner(showSpinner)
        if (nextCheck != Long.MAX_VALUE) postDelayed(refresh, nextCheck + 16)

        contentDescription = "${next.title}: " + when {
            visual == Visual.ERROR -> "ошибка команды"
            showSpinner -> "выполняется"
            visual == Visual.ON -> "включено"
            visual == Visual.OFF -> "выключено"
            else -> "нет связи"
        }
        if (previous?.title != next.title || previous?.groupIcon != next.groupIcon) titleKey = null
        invalidate()
    }

    /** Текущий вид (для тестов и раскрытой панели). */
    var displayedVisual: Visual = Visual.OFF
        private set
    val isSpinnerVisible get() = spinnerVisible
    /** Последняя привязанная модель (предпросмотр и тесты). */
    val boundModel: Model? get() = model

    private fun thumbKey(state: ConfirmedState) = when (state) {
        ConfirmedState.ON -> ON.border
        ConfirmedState.OFF -> OFF.border
        ConfirmedState.UNAVAILABLE -> UNAVAILABLE.border
    }

    private fun thumbPalette(key: Int) = when (key) {
        ON.border -> ON
        UNAVAILABLE.border -> UNAVAILABLE
        ERROR_BORDER -> ERROR
        else -> OFF
    }

    private fun startTransition(palette: Palette, thumbs: IntArray) {
        transition?.cancel()
        from = shown
        target = palette
        thumbsFrom = if (thumbsShown.size == thumbs.size) thumbsShown.copyOf() else thumbs
        thumbsTarget = thumbs
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_MS
            addUpdateListener {
                val f = it.animatedFraction
                val a = from ?: palette
                shown = Palette(blend(a.background, palette.background, f), blend(a.border, palette.border, f),
                    blend(a.icon, palette.icon, f))
                thumbsShown = IntArray(thumbs.size) { i -> blend(thumbsFrom[i], thumbs[i], f) }
                invalidate()
            }
            start()
        }
    }

    private fun blend(a: Int, b: Int, f: Float) = ColorUtils.blendARGB(a, b, f)

    private fun setSpinner(visible: Boolean) {
        spinnerVisible = visible
        if (visible && isAttachedToWindow && windowVisibility == VISIBLE) {
            if (spinner == null) spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { spinnerAngle = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            spinner?.cancel()
            spinner = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        model?.let { bind(it, animate = false) }
    }

    override fun onDetachedFromWindow() {
        // Ни аниматоров, ни отложенных задач у отсоединённой View: Activity не удерживается.
        removeCallbacks(refresh)
        transition?.cancel()
        transition = null
        target?.let { shown = it }
        thumbsShown = thumbsTarget
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setSpinner(spinnerVisible)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = min(dp(INSET_DP), min(w, h) * 0.06f)
        card.set(inset, inset, w - inset, h - inset)
        titleKey = null
        invalidateOutline()
    }

    private fun radius() = min(dp(16f), min(card.width(), card.height()) / 4f)

    override fun onDraw(canvas: Canvas) {
        val current = model ?: return
        val palette = shown ?: return
        val r = radius()

        // Ореол ON: статичный, без пульсации; рисуется в собственном отступе карточки.
        if (target == ON) {
            for (i in 1..3) {
                stroke.color = ColorUtils.setAlphaComponent(palette.border, 70 - i * 20)
                stroke.strokeWidth = dp(1f)
                val grow = i * card.left / 3.5f
                tmp.set(card.left - grow, card.top - grow, card.right + grow, card.bottom + grow)
                canvas.drawRoundRect(tmp, r + grow, r + grow, stroke)
            }
        }
        fill.color = palette.background
        canvas.drawRoundRect(card, r, r, fill)
        stroke.color = palette.border
        stroke.strokeWidth = dp(1f)
        tmp.set(card); tmp.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(tmp, r, r, stroke)
        if (highlight) {
            stroke.color = HIGHLIGHT
            stroke.strokeWidth = dp(1.5f)
            val out = card.left * 0.6f
            tmp.set(card.left - out, card.top - out, card.right + out, card.bottom + out)
            canvas.drawRoundRect(tmp, r + out, r + out, stroke)
        }

        if (kind == Kind.GROUP) drawGroup(canvas, current, palette) else drawSingle(canvas, current, palette)

        if (spinnerVisible) drawSpinner(canvas)
        if (editOutline) {
            val half = dashPaint.strokeWidth / 2
            tmp.set(half, half, width - half, height - half)
            canvas.drawRoundRect(tmp, r + card.left, r + card.left, dashPaint)
        }
    }

    private fun drawSingle(canvas: Canvas, model: Model, palette: Palette) {
        val side = min(card.width(), card.height())
        val pad = side * 0.08f
        val iconSize = side * 0.32f
        val layout = title(model.title, card.width() - 2 * pad, card.height() - iconSize - 3 * pad, iconSize)
        val gap = side * 0.07f
        val total = iconSize + gap + layout.height
        var top = card.centerY() - total / 2
        val icon = when (displayedVisual) {
            Visual.ERROR -> ModernIcon.WARNING
            Visual.UNAVAILABLE -> ModernIcon.HELP
            else -> model.icon
        }
        iconPaint.color = palette.icon
        icon.draw(canvas, card.centerX() - iconSize / 2, top, iconSize, iconPaint)
        top += iconSize + gap
        canvas.save()
        canvas.translate(card.centerX() - layout.width / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawGroup(canvas: Canvas, model: Model, palette: Palette) {
        val side = min(card.width(), card.height())
        val pad = side * 0.07f
        val layout = groupTitle(model)
        val groupIcon = model.groupIcon
        // Однострочное название вместе с иконкой центрируется как одна строка «иконка + текст».
        val shift = if (groupIcon != null && layout.lineCount == 1) (groupIconSize() + groupIconGap()) / 2 else 0f
        val originX = card.centerX() - layout.width / 2f + shift
        val originY = card.top + pad
        canvas.save()
        canvas.translate(originX, originY)
        layout.draw(canvas)
        canvas.restore()
        if (groupIcon != null) {
            val size = groupIconSize()
            val x = originX + layout.getLineLeft(0) - groupIconGap() - size
            val y = originY + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f - size / 2
            iconPaint.color = palette.icon
            groupIcon.draw(canvas, x, y, size, iconPaint)
        }
        val areaTop = card.top + pad + layout.height + pad * 0.8f
        val area = RectF(card.left + pad, areaTop, card.right - pad, card.bottom - pad)
        if (model.items.isEmpty()) {
            val size = min(area.width(), area.height()) * 0.5f
            iconPaint.color = palette.icon
            ModernIcon.EMPTY_GRID.draw(canvas, area.centerX() - size / 2, area.centerY() - size / 2, size, iconPaint)
            return
        }
        layoutThumbnails(model.items.size, area)
        if (thumbnailsHidden) return
        val tr = thumbCell * 0.25f
        model.items.forEachIndexed { i, item ->
            thumbRect(i, tmp)
            val key = thumbsShown.getOrElse(i) { thumbKey(item.state) }
            val failed = thumbsTarget.getOrNull(i) == ERROR_BORDER
            val base = thumbPalette(thumbsTarget.getOrElse(i) { key })
            fill.color = if (failed) ERROR.background else base.background
            canvas.drawRoundRect(tmp, tr, tr, fill)
            stroke.color = key
            stroke.strokeWidth = max(1f, min(dp(1f), thumbCell * 0.04f))
            canvas.drawRoundRect(tmp, tr, tr, stroke)
            val iconSize = thumbCell * 0.62f
            iconPaint.color = if (failed) ERROR.icon else base.icon
            val icon = if (item.state == ConfirmedState.UNAVAILABLE && !failed) ModernIcon.HELP else item.icon
            icon.draw(canvas, tmp.centerX() - iconSize / 2, tmp.centerY() - iconSize / 2, iconSize, iconPaint)
        }
    }

    /**
     * Самая крупная сетка, в которую помещаются ВСЕ миниатюры. Плитка не растёт;
     * при большом количестве значки уменьшаются, но не скрываются.
     */
    private fun layoutThumbnails(count: Int, area: RectF) {
        var best = 0f
        var bestCols = 1
        for (cols in 1..count) {
            val rows = ceil(count / cols.toFloat())
            val cell = min(area.width() / (cols + GAP * (cols - 1)), area.height() / (rows + GAP * (rows - 1)))
            if (cell > best) { best = cell; bestCols = cols }
        }
        val cell = min(best, card.width() * 0.3f).coerceAtLeast(1f)
        val rows = ceil(count / bestCols.toFloat()).toInt()
        thumbCell = cell
        thumbCols = bestCols
        thumbGap = cell * GAP
        val gridW = bestCols * cell + (bestCols - 1) * thumbGap
        val gridH = rows * cell + (rows - 1) * thumbGap
        thumbOriginX = area.centerX() - gridW / 2
        thumbOriginY = area.top + (area.height() - gridH) / 2
    }

    /** Прямоугольник миниатюры [index] в координатах этой View (для анимации раскрытия). */
    fun thumbRect(index: Int, out: RectF): Boolean {
        val items = model?.items ?: return false
        if (kind != Kind.GROUP || index !in items.indices || width == 0) return false
        if (thumbCell == 0f) {
            // Миниатюры ещё не рисовались: та же раскладка, что и в onDraw.
            val side = min(card.width(), card.height())
            val pad = side * 0.07f
            val layout = groupTitle(model!!)
            layoutThumbnails(items.size, RectF(card.left + pad, card.top + pad + layout.height + pad * 0.8f,
                card.right - pad, card.bottom - pad))
        }
        val row = index / thumbCols
        val col = index % thumbCols
        val rowCount = ceil(items.size / thumbCols.toFloat()).toInt()
        // Последняя неполная строка центрируется.
        val inRow = if (row == rowCount - 1) items.size - row * thumbCols else thumbCols
        val shift = (thumbCols - inRow) * (thumbCell + thumbGap) / 2
        val x = thumbOriginX + shift + col * (thumbCell + thumbGap)
        val y = thumbOriginY + row * (thumbCell + thumbGap)
        out.set(x, y, x + thumbCell, y + thumbCell)
        return true
    }

    /**
     * Название группы по центру. Первая строка оставляет по бокам место под spinner,
     * вторая использует всю ширину: длинные названия не уменьшаются ради индикатора.
     */
    private fun groupTitle(model: Model): StaticLayout {
        val side = min(card.width(), card.height())
        val pad = side * 0.07f
        var indent = 2 * spinnerRadius() + side * 0.02f
        // Иконка группы стоит слева от первой строки: отступ симметричный, справа остаётся место под spinner.
        if (model.groupIcon != null) indent = max(indent, groupIconSize() + groupIconGap())
        return title(model.title, card.width() - 2 * pad, card.height() * 0.4f, 0f, indent.toInt())
    }

    private fun groupIconSize() = min(card.width(), card.height()) * 0.13f
    private fun groupIconGap() = min(card.width(), card.height()) * 0.03f

    private fun spinnerRadius() = (min(card.width(), card.height()) * 0.06f).coerceIn(dp(4f), dp(10f))

    private fun drawSpinner(canvas: Canvas) {
        val side = min(card.width(), card.height())
        val radius = spinnerRadius()
        val pad = side * 0.07f
        val cx = card.right - pad - radius
        val cy = card.top + pad + radius
        stroke.color = if (displayedVisual == Visual.ON) ON.icon else SPINNER
        stroke.strokeWidth = max(dp(1.5f), radius * 0.28f)
        stroke.strokeCap = Paint.Cap.ROUND
        tmp.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(tmp, spinnerAngle, 270f, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * Название до двух строк. Базовый размер 14sp учитывает системный масштаб шрифта,
     * но уменьшается в маленьких плитках, чтобы иконка и подпись не обрезались.
     */
    private fun title(text: String, maxWidth: Float, maxHeight: Float, iconSize: Float, firstLineIndent: Int = 0): StaticLayout {
        val width = maxWidth.toInt().coerceAtLeast(1)
        val key = listOf(text, width, maxHeight.toInt(), iconSize.toInt(), resources.configuration.fontScale, firstLineIndent)
        titleLayout?.takeIf { titleKey == key }?.let { return it }
        val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        val side = min(card.width(), card.height())
        var size = min(base, side * 0.12f)
        val minimum = max(dp(7f), side * 0.06f)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .apply {
                    if (firstLineIndent > 0 && width > 4 * firstLineIndent) {
                        setIndents(intArrayOf(firstLineIndent, 0), intArrayOf(firstLineIndent, 0))
                    }
                }
                .build()
            val brokenWord = (0 until layout.lineCount).any { line ->
                val end = layout.getLineEnd(line)
                line < layout.lineCount - 1 && end in 1 until text.length && !text[end - 1].isWhitespace() && text[end - 1] != '-'
            }
            val fits = layout.height <= maxHeight && layout.getEllipsisCount(layout.lineCount - 1) == 0 && !brokenWord
            if (fits || size <= minimum) break
            size = max(minimum, size * 0.92f)
        }
        titleLayout = layout
        titleKey = key
        return layout
    }

    companion object {
        const val SPINNER_DELAY_MS = 300L
        const val ERROR_MS = 1_500L
        const val COLOR_MS = 300L
        private const val INSET_DP = 4f
        private const val GAP = 0.18f

        const val TEXT = 0xFFFFFFFF.toInt()
        const val EDIT_OUTLINE = 0xFF4FC3F7.toInt()
        const val HIGHLIGHT = 0xFFCFFFF4.toInt()
        const val SPINNER = 0xFFB6CBE0.toInt()
        private val ON = Palette(0xFF10382E.toInt(), 0xFF35D99C.toInt(), 0xFFA5FFDA.toInt())
        private val OFF = Palette(0xFF142638.toInt(), 0xFF435E78.toInt(), 0xFF8B9EB2.toInt())
        private val UNAVAILABLE = Palette(0xFF262A30.toInt(), 0xFF4A5059.toInt(), 0xFF8E949C.toInt())
        private val ERROR = Palette(0xFF3A1F24.toInt(), 0xFFD0645C.toInt(), 0xFFFF9C94.toInt())
        private val ERROR_BORDER = ERROR.border

        private fun palette(visual: Visual) = when (visual) {
            Visual.ON -> ON
            Visual.OFF -> OFF
            Visual.UNAVAILABLE -> UNAVAILABLE
            Visual.ERROR -> ERROR
        }

        fun backgroundOf(visual: Visual) = palette(visual).background
        fun borderOf(visual: Visual) = palette(visual).border
        fun iconOf(visual: Visual) = palette(visual).icon
    }
}
