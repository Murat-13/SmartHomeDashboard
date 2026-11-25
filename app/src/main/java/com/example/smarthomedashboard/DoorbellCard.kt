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
import android.view.MotionEvent
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
internal class DoorbellCard(context: Context, val expanded: Boolean, val camera: Boolean = false) : DoorbellFrame(context, expanded), ModernOverlayCard,
    ModernInteractiveArea {
    val video = DoorbellVideoView(context).apply { muted = !expanded }
    private val topScrim = View(context)
    private val bottomScrim = View(context)
    /** Значок: колокольчик у домофона, камера у плитки «Камера» (docs/camera-tz.md). */
    private val bell = ImageView(context).apply { setImageResource(if (camera) R.drawable.ic_camera else R.drawable.ic_doorbell) }
    private val title = text(if (expanded) 34f else 22f, Color.WHITE, bold = true).apply { tag = "door_title" }
    private val status = text(if (expanded) 24f else 16f, SECONDARY).apply { tag = "door_status" }
    private val last = text(if (expanded) 22f else 15f, SECONDARY).apply { tag = "door_last" }
    private val missedBadge = text(if (expanded) 22f else 16f, Color.WHITE, bold = true).apply {
        tag = "door_missed"
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(OFFLINE); cornerRadius = 999f }
        visibility = GONE
    }
    /** «HD»: на экране основной поток (решение владельца — только для себя, ненавязчиво). Триггер — сам поток. */
    private val hd = text(if (expanded) 18f else 13f, Color.WHITE, bold = true).apply {
        text = "HD"
        tag = "door_hd"
        alpha = 0f
        visibility = GONE
        contentDescription = "Основной поток"
    }
    /** Запись на этом планшете (ТЗ записи): красная «● REC» — идёт, янтарная точка — включена, но не идёт. */
    private val rec = text(if (expanded) 18f else 13f, OFFLINE, bold = true).apply { tag = "door_rec"; visibility = GONE }
    /** «Архив» — только у раскрытой карточки и в окне звонка ([onArchive]). */
    private val archive = text(20f, Color.WHITE, bold = true).apply {
        text = "Архив"
        tag = "door_archive"
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.argb(150, 20, 34, 44)); cornerRadius = 999f; setStroke(2, Color.argb(140, 95, 168, 211)) }
        visibility = GONE
        contentDescription = "Архив записей"
        setOnClickListener { onArchive?.invoke() }
    }

    /** «Звонки» — журнал звонков со снимками посетителя ([RingLog]); у раскрытой карточки, не в окне звонка. */
    private val calls = text(20f, Color.WHITE, bold = true).apply {
        text = "Звонки"
        tag = "door_calls"
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.argb(150, 20, 34, 44)); cornerRadius = 999f; setStroke(2, Color.argb(140, 95, 168, 211)) }
        visibility = GONE
        contentDescription = "Звонки со снимками"
        setOnClickListener { onCalls?.invoke() }
    }
    var onCalls: (() -> Unit)? = null
        set(value) { field = value; calls.visibility = if (value != null && expanded && !ringMode) VISIBLE else GONE }

    /**
     * «Автоответ: вкл/выкл» (docs/ring-away-tz.md) — без PIN, чтобы включить перед уходом. null — на этом планшете
     * фраза для автоответа не выбрана, кнопки нет. [onAutoReply] получает новое состояние.
     */
    private val autoReplyButton = text(20f, Color.WHITE, bold = true).apply {
        tag = "door_autoreply"
        gravity = Gravity.CENTER
        visibility = GONE
        setOnClickListener { val next = !autoReplyOn; onAutoReply?.invoke(next); autoReply(next) }
    }
    private var autoReplyOn = false
    var onAutoReply: ((Boolean) -> Unit)? = null

    /** «Охрана: вкл/выкл» (docs/guard-tz.md) — без PIN; null — охрана на этом планшете не включена в редакторе. */
    private val guardButton = text(20f, Color.WHITE, bold = true).apply {
        tag = "door_guard"
        gravity = Gravity.CENTER
        visibility = GONE
        setOnClickListener { onGuard?.invoke() }
    }
    var onGuard: (() -> Unit)? = null

    /** «Прожектор: вкл/выкл» камеры (docs/camera-tz.md, часть 3); null — прожектора нет. */
    private val lightButton = text(20f, Color.WHITE, bold = true).apply {
        tag = "door_floodlight"
        gravity = Gravity.CENTER
        visibility = GONE
        setOnClickListener { onFloodlight?.invoke(floodlightOn != true) }
    }
    private var floodlightOn: Boolean? = null
    var onFloodlight: ((Boolean) -> Unit)? = null

    fun floodlight(on: Boolean?) {
        floodlightOn = on
        if (on == null || !expanded || !camera) { lightButton.visibility = GONE; return }
        lightButton.visibility = VISIBLE
        set(lightButton, if (on) "💡 Прожектор: вкл" else "Прожектор: выкл")
        lightButton.contentDescription = if (on) "Прожектор включён — коснитесь, чтобы выключить" else "Прожектор выключен — коснитесь, чтобы включить"
        lightButton.background = GradientDrawable().apply {
            setColor(if (on) Color.argb(170, 110, 95, 20) else Color.argb(150, 20, 34, 44)); cornerRadius = 999f
            setStroke(2, if (on) Color.argb(220, 255, 214, 90) else Color.argb(140, 95, 168, 211))
        }
    }

    /** Пометка 🛡 (охрана включена) и кнопка у раскрытой карточки. [armed] null — охраны на этом планшете нет. */
    fun guard(armed: Boolean?) {
        set(guardMark, "🛡")
        guardMark.visibility = if (armed == true) VISIBLE else GONE
        if (armed == null || !expanded || ringMode) { guardButton.visibility = GONE; return }
        guardButton.visibility = VISIBLE
        set(guardButton, if (armed) "🛡 Охрана: вкл" else "Охрана: выкл")
        guardButton.contentDescription = if (armed) "Охрана включена — коснитесь, чтобы выключить" else "Охрана выключена — коснитесь, чтобы включить"
        guardButton.background = GradientDrawable().apply {
            setColor(if (armed) Color.argb(170, 90, 60, 20) else Color.argb(150, 20, 34, 44)); cornerRadius = 999f
            setStroke(2, if (armed) Color.argb(220, 255, 183, 77) else Color.argb(140, 95, 168, 211))
        }
    }
    private val guardMark = text(if (expanded) 18f else 13f, Color.WHITE).apply { tag = "door_guard_mark"; visibility = GONE; contentDescription = "Охрана включена" }

    /**
     * Значок «проход открыт» (docs/gate-tz.md): зелёный — открыт, красный — открыт дольше порога («оставили открытым»).
     * Только по положению (датчик, замок, привод); слушает проходы сам — без перерисовки всей плитки.
     */
    private val gateMark = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.ic_door_unlock)
        tag = "door_gate_mark"
        visibility = GONE
    }
    private var markGates: List<String> = emptyList()
    private val gateListener: () -> Unit = { renderGateMark() }

    /** Проходы плитки — для значка (у раскрытой — ещё и кнопки). */
    fun gateMark(ids: List<String>) {
        if (markGates == ids) return
        markGates = ids
        renderGateMark()
    }

    private fun renderGateMark() {
        val gates = markGates.mapNotNull { GateHub.gate(it, context) }
        val left = gates.any { GateWatch.isLeftOpen(it.id) }
        val open = left || gates.any { GateHub.status(it).let { s -> s.open && s.source != GateStatus.Source.NONE } }
        gateMark.visibility = if (open) VISIBLE else GONE
        gateMark.setColorFilter(if (left) DoorbellFrame.OFFLINE else DoorbellFrame.RING)
        gateMark.contentDescription = when {
            left -> "Проход открыт слишком долго"
            open -> "Проход открыт"
            else -> null
        }
    }

    /** Показать кнопку автоответа с состоянием [on]; null — скрыть. */
    fun autoReply(on: Boolean?) {
        if (on == null || !expanded || ringMode) { autoReplyButton.visibility = GONE; return }
        autoReplyOn = on
        autoReplyButton.visibility = VISIBLE
        set(autoReplyButton, if (on) "Автоответ: вкл" else "Автоответ: выкл")
        autoReplyButton.contentDescription = if (on) "Автоответ включён — коснитесь, чтобы выключить" else "Автоответ выключен — коснитесь, чтобы включить"
        autoReplyButton.background = GradientDrawable().apply {
            setColor(if (on) Color.argb(170, 20, 90, 60) else Color.argb(150, 20, 34, 44)); cornerRadius = 999f
            setStroke(2, if (on) Color.argb(220, 52, 232, 158) else Color.argb(140, 95, 168, 211))
        }
    }
    /** Свернуть карточку (одиночное касание по неувеличенному видео). */
    var onCollapse: (() -> Unit)? = null

    /** Быстрые фразы своим голосом ([DoorbellPhrases]) — у раскрытой карточки; в окне звонка — в «Ответить». */
    private val phraseRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "door_phrases"; visibility = GONE }
    private var shownPhrases: List<DoorbellPhrases.Phrase> = emptyList()

    /** Плитка этой карточки — для состояния записи. */
    var tileId: String? = null
        set(value) { field = value; renderRecording() }
    var onArchive: (() -> Unit)? = null
        set(value) { field = value; archive.visibility = if (value != null && expanded) VISIBLE else GONE }
    private val recordingListener: () -> Unit = { renderRecording() }

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
    private val sound = text(22f, SECONDARY).apply { text = SOUND_HINT; visibility = if (expanded) VISIBLE else GONE }
    private var lastBound: DoorbellModel.Snapshot? = null

    /** Разговор (docs/talk-tz.md) — у раскрытой карточки; в окне звонка кнопка своя, у окна. */
    // У камеры — свой пароль плитки (docs/camera-tz.md); разговор — если у камеры есть динамик (включается в редакторе).
    private val talk: DoorbellTalk? = if (expanded) DoorbellTalk(context) { c, r ->
        if (camera) CameraPasswords.read(c, tileId.orEmpty(), r) else DoorbellHub.cameraPassword(c, r)
    } else null
    private val talkButton: TalkButton? = talk?.let { t ->
        t.onDuck = { video.ducked = it }
        t.freshStreetSound = { video.soundSeparate }
        // Разговор как по телефону: звук с улицы — разговорным потоком; пока говорят, карточка не сворачивается.
        t.onCall = { on ->
            video.callAudio = on
            if (on) keepOpen.run() else removeCallbacks(keepOpen)
            RecordingEvents.session(tileId, t, on) // разговор — в ролик события целиком
            PanelBusy.talk(t, on)
        }
        TalkButton(context, t).apply {
            visibility = GONE
            onActivity = { this@DoorbellCard.onActivity?.invoke() }
            // «Ответить» — пока звонок свежий (окно звонка столько же держится само), иначе «Говорить».
            answering = { tileId?.let { id -> DoorbellHub.lastRing(id) }?.let { System.currentTimeMillis() - it in 0 until RING_ANSWER_MS } == true }
        }
    }
    private val keepOpen = object : Runnable {
        override fun run() {
            if (talk?.inCall != true) return
            onActivity?.invoke()
            postDelayed(this, 10_000)
        }
    }
    /** Касание кнопок карточки — для таймера автосворачивания владельца. */
    var onActivity: (() -> Unit)? = null

    /**
     * Калитки и ворота (docs/gate-tz.md) — кнопки проходов этой плитки, и без звонка. Только у раскрытой карточки на
     * панели: у окна звонка свои, крупнее.
     */
    private val gateRow: GateButtons? = if (expanded) GateButtons(context, vertical = false).apply {
        onTouch = { this@DoorbellCard.onActivity?.invoke() }
    } else null
    /** Нажали кнопку прохода (PIN и исполнение — у панели). */
    var onGate: ((Gate, Gate.Action) -> Unit)?
        get() = gateRow?.onAction
        set(value) { gateRow?.onAction = value }

    /** Проходы этой плитки: только настроенные; в окне звонка — нет. */
    fun gates(list: List<Gate>) { gateRow?.bind(if (ringMode) emptyList() else list) }

    /** Карточка в окне звонка: без подсказки «касание — свернуть» и без счётчика пропущенных. */
    var ringMode = false
        set(value) {
            field = value
            sound.visibility = if (expanded && !value) VISIBLE else GONE
            if (value) { talkButton?.visibility = GONE; calls.visibility = GONE; phraseRow.visibility = GONE; autoReplyButton.visibility = GONE; guardButton.visibility = GONE; gateRow?.bind(emptyList()); zoom?.reset() }
        }

    /** Увеличение пальцами — только у раскрытой карточки на панели (решение владельца: не в окне звонка). */
    private val zoom: ZoomGestures? = if (expanded) ZoomGestures(context, video, onTap = { onCollapse?.invoke() }) { z ->
        if (!ringMode) set(sound, if (z.zoomed) "🔍 ${z.title} · двойное касание — обычный вид" else SOUND_HINT)
        onActivity?.invoke()
    } else null
    private val zoomEnabled get() = zoom != null && !ringMode
    private var gestureOnButton = false

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
        // События (человек, машина…) — сверху, рядом с названием: из HA и напрямую с камеры.
        iconViews.values.forEach { icons.addView(it, LinearLayout.LayoutParams(1, 1)) }
        header.addView(icons)
        header.addView(rec, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(hd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(lightButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(guardMark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(gateMark, LinearLayout.LayoutParams(1, 1))
        header.addView(guardButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(autoReplyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(calls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(archive, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(missedBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(last)
        if (expanded) { left.addView(phraseRow); left.addView(sound) }
        footer.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gateRow?.let { footer.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        talkButton?.let { footer.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        content.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        content.addView(connecting, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        offlineVeil.addView(offlineIcon, LinearLayout.LayoutParams(1, 1))
        offlineVeil.addView(offlineText)
        content.addView(offlineVeil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.onStatus = { refresh() }
        video.onQuality = { showHd(it) }
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
        hd.setPadding((10 * next).toInt(), 0, (10 * next).toInt(), 0)
        rec.setPadding((10 * next).toInt(), 0, (6 * next).toInt(), 0)
        archive.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
        archive.minHeight = (48 * next).toInt()
        calls.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
        calls.minHeight = (48 * next).toInt()
        autoReplyButton.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
        autoReplyButton.minHeight = (48 * next).toInt()
        guardButton.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
        guardButton.minHeight = (48 * next).toInt()
        lightButton.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
        lightButton.minHeight = (48 * next).toInt()
        (lightButton.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        (guardButton.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        guardMark.setPadding((8 * next).toInt(), 0, (4 * next).toInt(), 0)
        gateMark.layoutParams = (gateMark.layoutParams as LinearLayout.LayoutParams).apply {
            width = iconSide; height = iconSide; leftMargin = (8 * next).toInt()
        }
        (autoReplyButton.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        (calls.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        for (i in 0 until phraseRow.childCount) styleChip(phraseRow.getChildAt(i) as TextView, next)
        gateRow?.applySize((300 * next).toInt(), (72 * next).toInt(), (12 * next).toInt())
        (gateRow?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        talkButton?.let { b ->
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * next)
            b.minHeight = (56 * next).toInt(); b.setPadding((22 * next).toInt(), (10 * next).toInt(), (22 * next).toInt(), (10 * next).toInt())
            (b.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        }
        (archive.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = (12 * next).toInt()
        missedBadge.minWidth = (30 * next).toInt()
        missedBadge.setPadding((10 * next).toInt(), (3 * next).toInt(), (10 * next).toInt(), (3 * next).toInt())
        topScrim.layoutParams = (topScrim.layoutParams as LayoutParams).apply { height = (height(0.36f)) }
        bottomScrim.layoutParams = (bottomScrim.layoutParams as LayoutParams).apply { height = (height(0.32f)) }
        setAccentNow(accent)
        requestLayout()
    }

    private fun height(fraction: Float) = ((content.height.takeIf { it > 0 } ?: height) * fraction).toInt().coerceAtLeast(1)

    /** Показания и звонки — из [DoorbellHub]; [now] — настенное время для «последнего звонка». */
    private var boundConfig = DoorbellConfig()
    private var boundSnapshot: DoorbellModel.Snapshot? = null
    private var boundOffline = false
    private var boundAnimate = true

    /** Фразы своим голосом, записанные для этой плитки (задаёт владелец карточки). */
    var phrases: List<DoorbellPhrases.Phrase> = emptyList()

    fun bind(tileTitle: String, config: DoorbellConfig, haSnapshot: DoorbellModel.Snapshot, offline: Boolean, missed: Int,
             lastRing: Long?, now: Long, animate: Boolean = true) {
        // События с камеры напрямую (docs/direct-events-tz.md) — значками наравне с датчиками HA.
        val direct = DirectEvents.activeDetections(tileId)
        val snapshot = if (direct.isEmpty()) haSnapshot else haSnapshot.copy(detections = haSnapshot.detections + direct)
        lastBound = snapshot
        boundConfig = config
        boundSnapshot = snapshot
        boundOffline = offline
        boundAnimate = animate
        set(title, tileTitle.ifBlank { if (camera) DoorbellConfig.CAMERA_TITLE else DoorbellConfig.DEFAULT_TITLE })
        // У камеры нет звонка: ни «Звонок: …», ни пропущенных.
        if (camera) last.visibility = GONE
        else set(last, lastRing?.let { "Звонок: " + DoorbellModel.ago(it, now) } ?: "Звонков не было")
        missedBadge.visibility = if (missed > 0 && !ringMode && !camera) VISIBLE else GONE
        if (missed > 0) set(missedBadge, if (missed > 99) "99+" else missed.toString())
        iconViews.forEach { (d, v) ->
            val on = d in snapshot.detections
            v.visibility = if (on) VISIBLE else GONE
            v.setColorFilter(if (d == DoorbellConfig.Detection.PERSON) PERSON else Color.WHITE)
        }
        // Недописанный адрес (нет узла) плееру не отдаётся: такая плитка — «не настроена», а не «нет сигнала».
        val mainRtsp = DoorbellConfig.playableRtsp(config.rtspMain)
        val subRtsp = DoorbellConfig.playableRtsp(config.rtspSub)
        // Облегчённый режим (docs/diagnostics-tz.md, решение 4): живое видео — у первых двух плиток страницы, остальные — снимок.
        val limited = !expanded && !LightMode.liveAllowed(context, tileId)
        video.bind(DoorbellVideoView.Source(
            rtsp = if (expanded) mainRtsp.ifBlank { subRtsp } else subRtsp.ifBlank { mainRtsp },
            user = config.rtspUser, camera = config.camera,
            snapshotOnly = !expanded && (config.mode == DoorbellConfig.VideoMode.SNAPSHOT || limited),
            // Камера только со снимком: раскрытая — раз в секунду, плитка — раз в 5 с.
            snapshotEveryMs = when {
                mainRtsp.isBlank() && subRtsp.isBlank() && config.camera.isBlank() -> if (expanded) CameraSnapshot.EXPANDED_MS else CameraSnapshot.TILE_MS
                limited -> LightMode.SNAPSHOT_MS
                else -> DoorbellVideoView.SNAPSHOT_EVERY_MS
            },
            // Основной поток не по силам декодеру планшета — раскрытие показывает субпоток.
            fallbackRtsp = if (expanded) subRtsp else "",
            secretKey = if (camera) tileId.orEmpty() else "",
            snapshotUrl = config.snapshotUrl
        ), snapshot.picture)
        // HA точно знает, что домофона нет: видео не подключается, пока он не вернётся (без лишних попыток).
        video.deviceOffline = snapshot.haStatus == null && (offline || snapshot.online == false)
        talkButton?.let { button ->
            val show = !ringMode && config.canTalk
            button.visibility = if (show) VISIBLE else GONE
            button.callMode = config.talkCall
            button.refresh()
            if (show) talk?.prepare(config.talkRtsp, config.rtspUser)
            fillPhrases(if (show) phrases else emptyList())
        }
        refresh()
    }

    /**
     * «Нет сигнала»: по HA (камера/кнопка звонка недоступны) или — если сущностей HA в плитке нет — по самому видео
     * (адрес не отвечает несколько попыток подряд). Без связи с HA судить о домофоне по HA нельзя.
     */
    private fun refresh() {
        val config = boundConfig
        val snapshot = boundSnapshot ?: return
        val haKnows = config.camera.isNotBlank() || config.visitor.isNotBlank()
        val noSignal = boundOffline || snapshot.online == false || (!haKnows && video.unreachable)
        val (color, text) = when {
            snapshot.haStatus != null && haKnows -> NEUTRAL to snapshot.haStatus
            !config.hasVideo && (camera || config.visitor.isBlank()) -> NEUTRAL to "Не настроен — откройте редактор"
            noSignal -> OFFLINE to "Нет сигнала"
            snapshot.ringing -> RING to "Звонят в калитку"
            snapshot.person -> PERSON to "Человек у калитки"
            snapshot.detections.isNotEmpty() -> NEUTRAL to snapshot.detections.joinToString(" · ") { it.title }
            else -> NEUTRAL to (if (config.mode == DoorbellConfig.VideoMode.SNAPSHOT) "Снимок раз в минуту" else "В сети")
        }
        set(status, text)
        applyAccent(color, pulse = snapshot.ringing && !noSignal, animate = boundAnimate)
        val veil = noSignal && (snapshot.haStatus == null || !haKnows)
        offlineVeil.visibility = if (veil) VISIBLE else GONE
        if (veil) set(offlineText, video.lastFrameAt?.let { "Нет сигнала с " + clock(it) } ?: "Нет сигнала")
        renderVideoStatus()
    }

    private fun fillPhrases(list: List<DoorbellPhrases.Phrase>) {
        if (list == shownPhrases) return
        shownPhrases = list
        phraseRow.removeAllViews()
        list.forEach { phrase ->
            val chip = TextView(context).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                text = phrase.title
                tag = "door_phrase_${phrase.id}"
                gravity = Gravity.CENTER
                contentDescription = "Фраза своим голосом: ${phrase.title}"
                setOnClickListener {
                    onActivity?.invoke()
                    val id = tileId ?: return@setOnClickListener
                    val pcm = DoorbellPhrases.load(context, id, phrase.id)
                    if (pcm == null || talk?.play(pcm) != true) set(sound, "Фраза не отправлена")
                }
            }
            phraseRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            styleChip(chip, scale)
        }
        phraseRow.visibility = if (list.isEmpty()) GONE else VISIBLE
    }

    private fun styleChip(chip: TextView, k: Float) {
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19 * k)
        chip.minHeight = (48 * k).toInt()
        chip.setPadding((16 * k).toInt(), (8 * k).toInt(), (16 * k).toInt(), (8 * k).toInt())
        chip.background = GradientDrawable().apply { setColor(Color.argb(150, 20, 34, 44)); cornerRadius = 999f; setStroke(2, Color.argb(140, 52, 232, 158)) }
        (chip.layoutParams as? LinearLayout.LayoutParams)?.let { it.rightMargin = (8 * k).toInt(); it.bottomMargin = (8 * k).toInt() }
    }

    /** Плавно 0,25 с; полупрозрачная белая надпись. */
    internal fun showHd(on: Boolean) {
        hd.animate().cancel()
        if (on) {
            hd.visibility = VISIBLE
            hd.animate().alpha(HD_ALPHA).setDuration(HD_FADE_MS).start()
        } else if (hd.visibility == VISIBLE) {
            hd.animate().alpha(0f).setDuration(HD_FADE_MS).withEndAction { hd.visibility = GONE }.start()
        }
    }

    private fun renderVideoStatus() {
        val s = video.status
        // Застывший последний кадр не должен выглядеть живым: при ошибке и недоступности он приглушён.
        video.markStale(s == DoorbellVideoView.Status.ERROR || s == DoorbellVideoView.Status.OFFLINE ||
            offlineVeil.visibility == VISIBLE)
        val show = offlineVeil.visibility != VISIBLE && !video.hasGuest && (s == DoorbellVideoView.Status.CONNECTING ||
            s == DoorbellVideoView.Status.ERROR || s == DoorbellVideoView.Status.NO_SOURCE)
        connecting.visibility = if (show) VISIBLE else GONE
        if (show) set(connecting, when (s) {
            DoorbellVideoView.Status.NO_SOURCE -> "Видео не настроено"
            DoorbellVideoView.Status.ERROR -> video.lastFrameAt?.let { "Нет видео с ${clock(it)} · переподключение…" }
                ?: "Нет видео · переподключение…"
            else -> "Подключение к домофону…"
        })
    }

    private fun clock(at: Long): String = java.util.Calendar.getInstance().apply { timeInMillis = at }.let {
        "%02d:%02d".format(it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE))
    }

    override fun setAccentNow(color: Int) {
        super.setAccentNow(color)
        bell.setColorFilter(color)
    }

    // Под раскрытием и окном звонка видео плитки не останавливается (docs/doorbell.md): скрытие источника
    // влияет только на анимации рамки.

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RecordingStatus.listen(recordingListener)
        renderRecording()
        GateHub.listen(gateListener)
        renderGateMark()
    }

    override fun onDetachedFromWindow() {
        RecordingStatus.unlisten(recordingListener)
        GateHub.unlisten(gateListener)
        super.onDetachedFromWindow()
    }

    private fun renderRecording() {
        val id = tileId
        if (id == null) { rec.visibility = GONE; return }
        val states = listOfNotNull(RecordingStatus.of(id), RecordingStatus.of(id + RecorderService.HD)).map { it.state }
        val recording = CameraRecorder.State.RECORDING in states
        val enabled = recording || RecordingDevice(context).enabled(id)
        rec.visibility = if (enabled) VISIBLE else GONE
        if (!enabled) return
        set(rec, if (recording) "● REC" else "●")
        rec.setTextColor(if (recording) OFFLINE else PERSON)
        rec.contentDescription = if (recording) "Идёт запись" else "Запись включена, но не идёт"
    }

    /**
     * Раскрытая карточка ловит касание как «свернуть» ([SensorWindowTouchHandler]); кнопки «Архив» и «Говорить»
     * объявлены своей областью — касание доходит до них.
     */
    override fun interactiveAt(windowX: Float, windowY: Float): Boolean {
        // С увеличением карточка сама разбирает жесты по всей площади: два пальца, сдвиг, двойное касание,
        // одиночное касание — свернуть ([onCollapse]).
        if (zoomEnabled && hit(this, windowX, windowY)) return true
        return buttons().any { hit(it, windowX, windowY) }
    }

    private fun hitOnScreen(v: View, rawX: Float, rawY: Float): Boolean {
        if (!v.isShown) return false
        v.getLocationOnScreen(hitLocation)
        return rawX >= hitLocation[0] && rawX < hitLocation[0] + v.width * scaleX &&
            rawY >= hitLocation[1] && rawY < hitLocation[1] + v.height * scaleY
    }

    private fun buttons(): List<View> = listOfNotNull(archive, calls, autoReplyButton, guardButton, lightButton, talkButton) +
        (0 until phraseRow.childCount).map { phraseRow.getChildAt(it) } + gateRow?.shown.orEmpty()

    private fun hit(v: View, windowX: Float, windowY: Float): Boolean {
        if (!v.isShown) return false
        v.getLocationInWindow(hitLocation)
        // Раскрытие движет карточку масштабом: размер на экране — с учётом scale карточки (как у PowerModernCard).
        return windowX >= hitLocation[0] && windowX < hitLocation[0] + v.width * scaleX &&
            windowY >= hitLocation[1] && windowY < hitLocation[1] + v.height * scaleY
    }
    private val hitLocation = IntArray(2)

    /** Жест на кнопке — кнопке; остальное на раскрытой карточке — увеличению видео. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val z = zoom
        if (z == null || !zoomEnabled) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureOnButton = buttons().any { hitOnScreen(it, event.rawX, event.rawY) }
        }
        if (gestureOnButton) return super.dispatchTouchEvent(event)
        // Координаты — в системе видео без увеличения (видео лежит внутри content с отступом под ореол).
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-(content.left + video.left).toFloat(), -(content.top + video.top).toFloat())
        z.onTouch(local)
        local.recycle()
        return true
    }

    override fun dispose() {
        releaseAnimations()
        talk?.dispose()
        video.dispose()
    }

    /**
     * Карточка переезжает на новый экран (поворот, [DoorbellMove]): видео, звук и разговор продолжаются. Всё, что
     * привязал прежний экран, снимается — новый привяжет своё; иначе закрытый экран остался бы в памяти.
     */
    fun prepareMove() {
        video.prepareMove()
        setHiddenBySource(false)
        // Увеличение считалось в размерах прежнего экрана — на новом карточка другого размера.
        zoom?.reset()
        onArchive = null
        onCalls = null
        onAutoReply = null
        onGuard = null
        onFloodlight = null
        onCollapse = null
        onActivity = null
        onGate = null
    }

    /** Идёт разговор (для проверки, что поворот его не оборвал). */
    internal val inCall get() = talk?.inCall == true

    companion object {
        const val SOUND_HINT = "🔊 Звук включён · два пальца — увеличить · касание — свернуть"
        /** Сколько после звонка кнопка разговора в раскрытой плитке — «Ответить» (окно звонка держится минуту). */
        const val RING_ANSWER_MS = 60_000L
        const val HD_ALPHA = 0.55f
        const val HD_FADE_MS = 250L

        fun iconFor(d: DoorbellConfig.Detection) = when (d) {
            DoorbellConfig.Detection.PERSON -> R.drawable.ic_door_person
            DoorbellConfig.Detection.ANIMAL -> R.drawable.ic_door_animal
            DoorbellConfig.Detection.VEHICLE -> R.drawable.ic_door_vehicle
            DoorbellConfig.Detection.MOTION -> R.drawable.ic_door_motion
        }

        /** Плитка панели: как у остальных Modern (тег, размер, ручка resize). */
        fun createTile(context: Context, tileId: String, width: Int, height: Int, isEditMode: Boolean,
                       camera: Boolean = false): Pair<FrameLayout, ImageView> {
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
            // После поворота — та же карточка, что была на прежнем экране: её плеер не подключается заново ([DoorbellMove]).
            val screen = Movable.activityOf(context)
            val card = screen?.let { DoorbellMove.take(tileId, expanded = false, camera = camera, activity = it) }
                ?: DoorbellCard(screen?.let(Movable::context) ?: context, expanded = false, camera = camera)
            tile.addView(card.apply { this.tileId = tileId }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
