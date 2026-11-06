package com.example.smarthomedashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Рамка Modern для домофона: тёмное тело, ореол, свечение и рамка в цвете состояния (как у «Инвертора»),
 * плавная смена цвета, пульсация при звонке. Содержимое ([content]) обрезано по скруглению — видео не выходит
 * за углы. Рамка рисуется поверх видео. Анимации — только у видимой карточки.
 */
internal open class DoorbellFrame(context: Context, private val large: Boolean) : FrameLayout(context), ModernHideAware {
    protected val density = resources.displayMetrics.density
    protected var scale = 1f
        private set
    private var haloPx = PowerModernCard.haloFor(1f)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 14, 20) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    var accent = NEUTRAL
        private set
    private var targetAccent: Int? = null
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var wantPulse = false
    private var glowLevel = 1f
    protected var hiddenBySource = false
        private set
    private var windowShown = true
    private val scaled = mutableListOf<Pair<TextView, Float>>()

    /** Видео и подписи; обрезаются по скруглённой рамке. */
    val content = FrameLayout(context).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius())
        }
    }

    init {
        setWillNotDraw(false)
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = haloPx.toInt()
                outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, radius())
            }
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    protected fun radius() = (if (large) 36f else 30f) * scale.coerceIn(0.6f, 1.6f)

    /** Базовые размеры: компакт — от 340 по короткой стороне, крупно — от 700. */
    protected open fun scaleFor(w: Int, h: Int) = (minOf(w, h) / if (large) 700f else 340f).coerceIn(0.35f, 2.6f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val next = scaleFor(w, h)
        if (next != scale || oldw == 0) post { applyScale(next) }
    }

    protected open fun applyScale(next: Float) {
        scale = next
        haloPx = PowerModernCard.haloFor(next)
        scaled.forEach { (view, base) -> view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * next) }
        val inset = haloPx.toInt()
        (content.layoutParams as LayoutParams).setMargins(inset, inset, inset, inset)
        content.requestLayout()
        content.invalidateOutline()
        invalidateOutline()
        invalidate()
    }

    fun applyAccent(color: Int, pulse: Boolean, animate: Boolean) {
        if (targetAccent != color) {
            targetAccent = color
            colorAnimator?.cancel()
            colorAnimator = null
            if (!animate || accent == color || !visiblyShown()) setAccentNow(color)
            else colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), accent, color).apply {
                duration = 450
                addUpdateListener { if (colorAnimator === it) setAccentNow(it.animatedValue as Int) }
                start()
            }
        }
        wantPulse = pulse
        updatePulse()
    }

    protected open fun setAccentNow(color: Int) {
        accent = color
        invalidate()
    }

    protected fun visiblyShown() = isAttachedToWindow && isShown && windowShown && !hiddenBySource && !PanelMotion.dimmed

    override fun setHiddenBySource(hidden: Boolean) {
        if (hiddenBySource == hidden) return
        hiddenBySource = hidden
        visibilityChanged()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibilityChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        visibilityChanged()
    }

    protected open fun visibilityChanged() {
        if (!visiblyShown()) colorAnimator?.let { a ->
            colorAnimator = null; a.removeAllUpdateListeners(); a.cancel(); targetAccent?.let(::setAccentNow)
        }
        updatePulse()
    }

    private fun updatePulse() {
        val run = wantPulse && visiblyShown()
        if (run == (pulseAnimator != null)) return
        if (run) {
            pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 520
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { if (pulseAnimator === it) { glowLevel = it.animatedValue as Float; invalidate() } }
                start()
            }
        } else stopPulse()
    }

    private fun stopPulse() {
        pulseAnimator?.let { pulseAnimator = null; it.removeAllUpdateListeners(); it.cancel() }
        if (glowLevel != 1f) { glowLevel = 1f; invalidate() }
    }

    val pulsing get() = pulseAnimator != null

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = radius(); val c = accent; val halo = haloPx
        if (halo >= 1f) {
            val band = halo / 3f
            glowPaint.strokeWidth = band
            for (i in 1..3) {
                val offset = band * (i - 0.5f)
                glowPaint.color = Color.argb((80 * glowLevel * (4 - i) / 3f).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
                rect.set(halo - offset, halo - offset, w - halo + offset, h - halo + offset)
                canvas.drawRoundRect(rect, r + offset, r + offset, glowPaint)
            }
        }
        rect.set(halo, halo, w - halo, h - halo)
        canvas.drawRoundRect(rect, r, r, basePaint)
    }

    /** Рамка — поверх видео. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = radius(); val c = accent; val halo = haloPx
        for (i in 3 downTo 1) {
            val stroke = 2.2f * i * scale * density
            glowPaint.strokeWidth = stroke
            glowPaint.color = Color.argb((30 * glowLevel * (4 - i)).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
            rect.set(halo + stroke / 2, halo + stroke / 2, w - halo - stroke / 2, h - halo - stroke / 2)
            canvas.drawRoundRect(rect, r, r, glowPaint)
        }
        val border = (1.6f * scale * density).coerceAtLeast(1.5f)
        borderPaint.strokeWidth = border
        borderPaint.color = Color.argb((235 * glowLevel).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
        rect.set(halo + border / 2, halo + border / 2, w - halo - border / 2, h - halo - border / 2)
        canvas.drawRoundRect(rect, r, r, borderPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        colorAnimator?.let { a -> colorAnimator = null; a.removeAllUpdateListeners(); a.cancel(); targetAccent?.let(::setAccentNow) }
        super.onDetachedFromWindow()
    }

    protected fun releaseAnimations() {
        wantPulse = false
        stopPulse()
        colorAnimator?.let { it.removeAllUpdateListeners(); it.cancel() }
        colorAnimator = null
    }

    protected fun text(base: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(6f, 0f, 1f, Color.argb(200, 0, 0, 0))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
        scaled += this to base
    }

    protected fun set(view: TextView, value: CharSequence) {
        if (view.text.toString() != value.toString()) view.text = value
    }

    companion object {
        val NEUTRAL = 0xFF5FA8D3.toInt()
        val PERSON = 0xFFFFB74D.toInt()
        val RING = 0xFF34E89E.toInt()
        val OFFLINE = 0xFFFF5252.toInt()
        val SECONDARY = Color.rgb(196, 208, 216)
    }
}

/**
 * Плитка «Домофон» (docs/doorbell.md). Компактная: видео на всю плитку (субпоток, без звука), поверх — название,
 * состояние, последний звонок, счётчик пропущенных и значки событий; рамка: обычная, янтарная (человек),
 * ярко-зелёная с пульсом (звонок), красная «Нет сигнала». Раскрытая (касание): крупное видео со звуком —
 * только слушать; касание сворачивает. Только отображение: события и команды — [DoorbellHub].
 */
internal class DoorbellCard(context: Context, val expanded: Boolean) : DoorbellFrame(context, expanded), ModernOverlayCard {
    val video = DoorbellVideoView(context).apply { muted = !expanded }
    private val topScrim = View(context)
    private val bottomScrim = View(context)
    private val bell = ImageView(context).apply { setImageResource(R.drawable.ic_doorbell) }
    private val title = text(if (expanded) 34f else 22f, Color.WHITE, bold = true).apply { tag = "door_title" }
    private val status = text(if (expanded) 24f else 16f, SECONDARY).apply { tag = "door_status" }
    private val last = text(if (expanded) 22f else 15f, SECONDARY).apply { tag = "door_last" }
    private val missedBadge = text(if (expanded) 22f else 16f, Color.WHITE, bold = true).apply {
        tag = "door_missed"
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(OFFLINE); cornerRadius = 999f }
        visibility = GONE
    }
    private val icons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "door_icons" }
    private val iconViews = DoorbellConfig.Detection.entries.associateWith { d ->
        ImageView(context).apply {
            setImageResource(iconFor(d))
            tag = "door_icon_${d.name.lowercase()}"
            visibility = GONE
            contentDescription = d.title
        }
    }
    private val offlineVeil = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(170, 8, 10, 14))
        visibility = GONE
        tag = "door_offline"
    }
    private val offlineIcon = ImageView(context).apply { setImageResource(R.drawable.ic_door_offline); setColorFilter(OFFLINE) }
    private val offlineText = text(if (expanded) 30f else 20f, OFFLINE, bold = true).apply { text = "Нет сигнала"; gravity = Gravity.CENTER }
    private val connecting = text(if (expanded) 22f else 15f, SECONDARY).apply { gravity = Gravity.CENTER; visibility = GONE; tag = "door_connecting" }
    private val sound = text(22f, SECONDARY).apply { text = "🔊 Звук включён · касание — свернуть"; visibility = if (expanded) VISIBLE else GONE }
    private var lastBound: DoorbellModel.Snapshot? = null

    /** Карточка в окне звонка: без подсказки «касание — свернуть» и без счётчика пропущенных. */
    var ringMode = false
        set(value) { field = value; sound.visibility = if (expanded && !value) VISIBLE else GONE }

    init {
        tag = if (expanded) "door_expanded" else "door_compact"
        content.addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topScrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
        bottomScrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
        content.addView(topScrim, LayoutParams(LayoutParams.MATCH_PARENT, 1, Gravity.TOP))
        content.addView(bottomScrim, LayoutParams(LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(bell, LinearLayout.LayoutParams(1, 1))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(title); names.addView(status)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(missedBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(last)
        if (expanded) left.addView(sound)
        footer.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        iconViews.values.forEach { icons.addView(it, LinearLayout.LayoutParams(1, 1)) }
        footer.addView(icons)
        content.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        content.addView(connecting, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        offlineVeil.addView(offlineIcon, LinearLayout.LayoutParams(1, 1))
        offlineVeil.addView(offlineText)
        content.addView(offlineVeil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.onStatus = { renderVideoStatus() }
        setAccentNow(NEUTRAL)
    }

    override fun scaleFor(w: Int, h: Int) = (if (expanded) minOf(w / 1000f, h / 750f) else minOf(w, h) / 340f).coerceIn(0.35f, 2.6f)

    override fun applyScale(next: Float) {
        super.applyScale(next)
        val pad = ((if (expanded) 22 else 14) * next).toInt()
        (content.getChildAt(3) as? ViewGroup)?.setPadding(pad, pad, pad, pad / 2)
        (content.getChildAt(4) as? ViewGroup)?.setPadding(pad, pad / 2, pad, pad)
        val bellSide = ((if (expanded) 46 else 28) * next).toInt().coerceAtLeast(1)
        bell.layoutParams = (bell.layoutParams as LinearLayout.LayoutParams).apply { width = bellSide; height = bellSide; rightMargin = (10 * next).toInt() }
        val iconSide = ((if (expanded) 40 else 26) * next).toInt().coerceAtLeast(1)
        iconViews.values.forEach { v ->
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { width = iconSide; height = iconSide; leftMargin = (8 * next).toInt() }
        }
        val off = ((if (expanded) 72 else 40) * next).toInt().coerceAtLeast(1)
        offlineIcon.layoutParams = (offlineIcon.layoutParams as LinearLayout.LayoutParams).apply { width = off; height = off; bottomMargin = (8 * next).toInt() }
        missedBadge.minWidth = (30 * next).toInt()
        missedBadge.setPadding((10 * next).toInt(), (3 * next).toInt(), (10 * next).toInt(), (3 * next).toInt())
        topScrim.layoutParams = (topScrim.layoutParams as LayoutParams).apply { height = (height(0.36f)) }
        bottomScrim.layoutParams = (bottomScrim.layoutParams as LayoutParams).apply { height = (height(0.32f)) }
        setAccentNow(accent)
        requestLayout()
    }

    private fun height(fraction: Float) = ((content.height.takeIf { it > 0 } ?: height) * fraction).toInt().coerceAtLeast(1)

    /** Показания и звонки — из [DoorbellHub]; [now] — настенное время для «последнего звонка». */
    fun bind(tileTitle: String, config: DoorbellConfig, snapshot: DoorbellModel.Snapshot, offline: Boolean, missed: Int,
             lastRing: Long?, now: Long, animate: Boolean = true) {
        lastBound = snapshot
        set(title, tileTitle.ifBlank { DoorbellConfig.DEFAULT_TITLE })
        val noSignal = offline || snapshot.online == false
        val (color, text) = when {
            snapshot.haStatus != null -> NEUTRAL to snapshot.haStatus
            !config.hasVideo && config.visitor.isBlank() -> NEUTRAL to "Не настроен — откройте редактор"
            noSignal -> OFFLINE to "Нет сигнала"
            snapshot.ringing -> RING to "Звонят в калитку"
            snapshot.person -> PERSON to "Человек у калитки"
            snapshot.detections.isNotEmpty() -> NEUTRAL to snapshot.detections.joinToString(" · ") { it.title }
            else -> NEUTRAL to (if (config.mode == DoorbellConfig.VideoMode.SNAPSHOT) "Снимок раз в минуту" else "В сети")
        }
        set(status, text)
        applyAccent(color, pulse = snapshot.ringing && !noSignal, animate = animate)
        set(last, lastRing?.let { "Звонок: " + DoorbellModel.ago(it, now) } ?: "Звонков не было")
        missedBadge.visibility = if (missed > 0 && !ringMode) VISIBLE else GONE
        if (missed > 0) set(missedBadge, if (missed > 99) "99+" else missed.toString())
        iconViews.forEach { (d, v) ->
            val on = d in snapshot.detections
            v.visibility = if (on) VISIBLE else GONE
            v.setColorFilter(if (d == DoorbellConfig.Detection.PERSON) PERSON else Color.WHITE)
        }
        offlineVeil.visibility = if (noSignal && snapshot.haStatus == null) VISIBLE else GONE
        video.bind(DoorbellVideoView.Source(
            rtsp = if (expanded) config.rtspMain.ifBlank { config.rtspSub } else config.rtspSub.ifBlank { config.rtspMain },
            user = config.rtspUser, camera = config.camera,
            snapshotOnly = !expanded && config.mode == DoorbellConfig.VideoMode.SNAPSHOT
        ), snapshot.picture)
        renderVideoStatus()
    }

    private fun renderVideoStatus() {
        val s = video.status
        val show = offlineVeil.visibility != VISIBLE && !video.hasGuest && (s == DoorbellVideoView.Status.CONNECTING ||
            s == DoorbellVideoView.Status.ERROR || s == DoorbellVideoView.Status.NO_SOURCE)
        connecting.visibility = if (show) VISIBLE else GONE
        if (show) set(connecting, when (s) {
            DoorbellVideoView.Status.NO_SOURCE -> "Видео не настроено"
            DoorbellVideoView.Status.ERROR -> "Нет видео · переподключение…"
            else -> "Подключение к домофону…"
        })
    }

    override fun setAccentNow(color: Int) {
        super.setAccentNow(color)
        bell.setColorFilter(color)
    }

    // Под раскрытием и окном звонка видео плитки не останавливается (docs/doorbell.md): скрытие источника
    // влияет только на анимации рамки.

    override fun dispose() {
        releaseAnimations()
        video.dispose()
    }

    companion object {
        fun iconFor(d: DoorbellConfig.Detection) = when (d) {
            DoorbellConfig.Detection.PERSON -> R.drawable.ic_door_person
            DoorbellConfig.Detection.ANIMAL -> R.drawable.ic_door_animal
            DoorbellConfig.Detection.VEHICLE -> R.drawable.ic_door_vehicle
            DoorbellConfig.Detection.MOTION -> R.drawable.ic_door_motion
        }

        /** Плитка панели: как у остальных Modern (тег, размер, ручка resize). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean): Pair<FrameLayout, ImageView> {
            val tile = FrameLayout(context).apply {
                layoutParams = LayoutParams(width, height)
                tag = tileId
                elevation = 8f
                clipToOutline = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val side = minOf(view.width, view.height).coerceAtLeast(1)
                        val inset = PowerModernCard.haloFor((side / 340f).coerceIn(0.35f, 2.6f)).toInt()
                        outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, 30f)
                    }
                }
            }
            tile.addView(DoorbellCard(context, expanded = false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val handle = ImageView(context).apply {
                setImageResource(R.drawable.ic_resize)
                layoutParams = LayoutParams(40, 40).apply { gravity = Gravity.BOTTOM or Gravity.END }
                visibility = if (isEditMode) VISIBLE else GONE
                setPadding(8, 8, 8, 8)
            }
            tile.addView(handle)
            return tile to handle
        }
    }
}
