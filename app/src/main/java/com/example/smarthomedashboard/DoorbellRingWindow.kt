package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Окно звонка (docs/doorbell.md, решения владельца): слой поверх текущего экрана, растёт из плитки (или баннера),
 * остальное приглушается. Видео со звуком (после мелодии и голоса), кнопки: проходы плитки (калитки и ворота,
 * docs/gate-tz.md: удержание 1 с, «Стоп» — сразу; ожидание → факт HA), «Свет у входа», «Ответить» (готовые фразы
 * домофона), «Закрыть». Закрывается само через [RING_MS]; повторный звонок продлевает. Калитку открыли (здесь или на
 * другом планшете) — звонок отвечен, но окно остаётся, пока гость проходит (решение владельца 10): видно, что вошёл,
 * можно «Стоп» или «Закрыть»; закрывается, когда проход снова закрыт (без датчика — через [PASS_UNKNOWN_MS]).
 * В тихие часы яркость окна снижается до заданной.
 */
@SuppressLint("ViewConstructor")
internal class DoorbellRingWindow private constructor(
    val host: Activity,
    val tileId: String,
    private val origin: RectF?,
    private val onClosed: (DoorbellRingWindow) -> Unit
) : FrameLayout(host) {
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val scrim = View(host).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private val panel = LinearLayout(host).apply { tag = "door_ring_panel" }
    val card = DoorbellCard(host, expanded = true).apply { ringMode = true; video.muted = true }
    private val controls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; tag = "door_ring_controls" }
    private val heading = label(30f, Color.WHITE, bold = true).apply { text = DoorbellHub.RING_PHRASE; tag = "door_ring_heading" }
    private val sub = label(19f, DoorbellFrame.SECONDARY).apply { tag = "door_ring_sub" }
    private val buttons = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val spacer = View(host)
    /** Проходы плитки (калитки и ворота); PIN — только если выбран «Всегда» ([GatePin]). */
    val gateButtons = GateButtons(host, vertical = true)
    /** Разговор (docs/talk-tz.md): удержание — голос в домофон; сессия открывается вместе с окном. */
    private val talk = DoorbellTalk(host)
    private val talkButton = TalkButton(host, talk).apply {
        answering = { true } // окно звонка — всегда звонок
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        visibility = GONE
    }
    private val lightButton = pill("Свет у входа", R.drawable.ic_door_light).apply { tag = "door_light" }
    /** Фразы (свои и готовые HA). «Ответить» — теперь кнопка разговора (этап 2). */
    private val replyButton = pill("Фразы", R.drawable.ic_door_reply).apply { tag = "door_reply" }
    private val closeButton = pill("Закрыть", R.drawable.ic_close).apply { tag = "door_close" }
    private val replies = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = GONE; tag = "door_replies" }
    private var animator: ValueAnimator? = null
    private var closing = false
    var isShowing = false
        private set
    private var openedAt = 0L
    private var savedBrightness: Float? = null
    private var interacted = false
    /** Проход этой плитки открыли (здесь или где угодно): звонок отвечен, ждём, пока гость пройдёт. */
    private var passedAt: Long? = null
    private var passedGate: com.example.smarthomedashboard.Gate? = null
    private val gateListener: () -> Unit = { onGateChanged() }

    /** Состояние кнопки с удержанием. UNCONFIRMED — HA принял команду, но факта панель не увидела: повтор не предлагаем. */
    enum class Gate { IDLE, SENDING, WAITING, OPENED, FAILED, UNCONFIRMED }

    private val autoClose = Runnable { close() }
    private val unmute = object : Runnable {
        override fun run() {
            if (!isShowing) return
            if (PanelAlerts.speaking) main.postDelayed(this, 400) else card.video.muted = false
        }
    }

    init {
        tag = "door_ring_window"
        isClickable = true
        isFocusable = true
        elevation = 200f
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        controls.background = GradientDrawable().apply {
            setColor(Color.rgb(12, 18, 25)); cornerRadius = 36f * density / 2
            setStroke((1.5f * density).toInt(), Color.argb(90, 95, 168, 211))
        }
        controls.addView(heading)
        controls.addView(sub)
        // Кнопки — внизу панели, на уровне руки и рядом с нижним краем видео (как у Ring/Nest).
        controls.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        buttons.addView(gateButtons)
        buttons.addView(talkButton)
        buttons.addView(lightButton)
        buttons.addView(replyButton)
        buttons.addView(closeButton)
        controls.addView(buttons)
        controls.addView(replies)
        panel.addView(card)
        panel.addView(controls)
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        scrim.setOnClickListener { touched() }

        gateButtons.onAction = { gate, action -> touched(); runGate(gate, action) }
        gateButtons.onTouch = { touched() }
        lightButton.setOnClickListener { touched(); toggleLight() }
        talkButton.onActivity = { touched() }
        talk.onDuck = { card.video.ducked = it }
        talk.freshStreetSound = { card.video.soundSeparate }
        // Разговор как по телефону: звук с улицы — разговорным потоком; окно не закрывается, пока говорят.
        talk.onCall = { on ->
            card.video.callAudio = on
            if (on) inCallKeep.run() else main.removeCallbacks(inCallKeep)
            RecordingEvents.session(tileId, talk, on) // разговор — в ролик события целиком
        }
        replyButton.setOnClickListener { touched(); showReplies(replies.visibility != VISIBLE) }
        closeButton.setOnClickListener { touched(); close() }
        card.setOnClickListener { touched() }
        // Архив (docs/recording.md): сразу последний законченный ролик; идущий сейчас появится после дозаписи.
        card.tileId = tileId
        card.onArchive = { touched(); close(); ArchiveActivity.open(host, tileId, latest = true) }
    }

    // ---- Размеры: видео по пропорции домофона, кнопки справа (альбом) или снизу (портрет) ----

    private fun layoutFor(w: Int, h: Int) {
        val config = DoorbellHub.config(tileId) ?: DoorbellConfig()
        val aspect = config.aspect
        val gap = (18 * density).toInt()
        val landscape = w >= h
        panel.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val unit = (minOf(w, h) / 800f).coerceIn(0.7f, 1.8f)
        applyText(unit)
        if (landscape) {
            val controlsW = (w * 0.27f).toInt().coerceIn((260 * density).toInt(), (440 * density).toInt())
            var videoH = (h * 0.86f).toInt()
            var videoW = (videoH * aspect).toInt()
            val maxW = (w * 0.94f).toInt() - controlsW - gap
            if (videoW > maxW) { videoW = maxW; videoH = (videoW / aspect).toInt() }
            card.layoutParams = LinearLayout.LayoutParams(videoW, videoH)
            controls.layoutParams = LinearLayout.LayoutParams(controlsW, videoH).apply { leftMargin = gap }
            spacer.layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        } else {
            val videoW = (w * 0.94f).toInt()
            val videoH = (videoW / aspect).toInt().coerceAtMost((h * 0.55f).toInt())
            card.layoutParams = LinearLayout.LayoutParams(videoW, videoH)
            controls.layoutParams = LinearLayout.LayoutParams(videoW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap }
            // Под видео панель по содержимому: без растягивающего промежутка.
            spacer.layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
        }
        val pad = (22 * unit * density / 1.5f).toInt()
        controls.setPadding(pad, pad, pad, pad)
        for (i in 0 until buttons.childCount) {
            (buttons.getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                val base = (72 * unit * density / 1.5f).toInt().coerceAtLeast((56 * density).toInt())
                // Главное действие — крупнее: удерживать удобно и с первого взгляда понятно, где оно.
                height = when {
                    buttons.getChildAt(i) === gateButtons -> LinearLayout.LayoutParams.WRAP_CONTENT
                    buttons.getChildAt(i) === talkButton -> (base * 1.4f).toInt()
                    else -> base
                }
                topMargin = (12 * unit * density / 1.5f).toInt()
            }
        }
        // Главное действие — крупнее: удерживать удобно и с первого взгляда понятно, где оно.
        val primary = (72 * unit * density / 1.5f).toInt().coerceAtLeast((56 * density).toInt())
        gateButtons.applySize(0, (primary * 1.4f).toInt(), (12 * unit * density / 1.5f).toInt())
    }

    private fun applyText(unit: Float) {
        val k = unit * density / 1.5f
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * k)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * k)
        listOf(lightButton, replyButton, closeButton, talkButton).forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k) }
        for (i in 0 until replies.childCount) (replies.getChildAt(i) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * k)
    }

    private var laidFor = 0L

    /** Раскладка — до измерения детей (а не в onSizeChanged): поворот экрана меняет её за один проход. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val key = (w.toLong() shl 32) or h.toLong()
        if (w > 0 && h > 0 && key != laidFor) { laidFor = key; layoutFor(w, h) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Во время анимации положение задаёт она; потом окно всегда по центру (в том числе после поворота).
        if (animator == null && !closing) center()
    }

    private fun center() {
        panel.x = (width - panel.width) / 2f
        panel.y = (height - panel.height) / 2f
    }

    // ---- Показ и закрытие ----

    private fun open() {
        isShowing = true
        RecordingEvents.session(tileId, this, true) // пока окно звонка открыто, ролик события пишется дальше
        GateHub.listen(gateListener)
        openedAt = SystemClock.uptimeMillis()
        render()
        // Плитка видна на панели — окно сразу показывает её живой субпоток, пока подключается основной.
        if (host is MainActivity) DoorbellHub.actions?.tileVideo(tileId)?.let { tile ->
            card.video.partner = tile
            tile.lendTo(card.video)
        }
        panel.alpha = 0f
        panel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                center()
                animateIn()
                return true
            }
        })
        applyNightBrightness()
        main.post(keepAwake)
        main.postDelayed(betterPhoto, PHOTO_CHECK_MS)
        main.postDelayed(autoClose, RING_MS)
        main.postDelayed(unmute, 600)
    }

    private fun animateIn() {
        val tx = panel.x; val ty = panel.y
        val from = origin
        val sx = from?.let { (it.width() / panel.width).coerceIn(0.05f, 1f) } ?: 0.85f
        val sy = from?.let { (it.height() / panel.height).coerceIn(0.05f, 1f) } ?: 0.85f
        val fx = from?.left ?: (tx + panel.width * 0.075f)
        val fy = from?.top ?: (ty + panel.height * 0.075f)
        panel.pivotX = 0f; panel.pivotY = 0f
        run(OPEN_MS, null) { t ->
            panel.x = fx + (tx - fx) * t
            panel.y = fy + (ty - fy) * t
            panel.scaleX = sx + (1f - sx) * t
            panel.scaleY = sy + (1f - sy) * t
            panel.alpha = (0.4f + 0.6f * t * 1.6f).coerceAtMost(1f)
            scrim.alpha = 0.72f * t
        }
    }

    /** Повторный звонок: окно остаётся, отсчёт начинается заново. */
    fun extend() {
        if (!isShowing || closing) return
        // Звонят снова после «Калитку открыли»: окно остаётся, звонок снова не отвечен.
        main.removeCallbacks(closeAfterOpened)
        main.removeCallbacks(closeAfterShut)
        shutPending = false
        passedAt = null
        passedGate = null
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, RING_MS)
        card.video.muted = true
        main.removeCallbacks(unmute)
        main.postDelayed(unmute, 600)
        render()
    }

    fun close() {
        if (!isShowing || closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        card.video.muted = true
        PanelAlerts.cancel(DoorbellHub.ringTag(tileId))
        val sx = panel.scaleX; val sy = panel.scaleY; val x0 = panel.x; val y0 = panel.y; val dim = scrim.alpha
        val to = origin
        run(CLOSE_MS, ::closeNow) { t ->
            if (to != null) {
                panel.x = x0 + (to.left - x0) * t
                panel.y = y0 + (to.top - y0) * t
                panel.scaleX = sx + ((to.width() / panel.width.coerceAtLeast(1)) - sx) * t
                panel.scaleY = sy + ((to.height() / panel.height.coerceAtLeast(1)) - sy) * t
            } else {
                panel.scaleX = sx - 0.08f * t
                panel.scaleY = sy - 0.08f * t
                panel.x = x0 + panel.width * 0.04f * t
                panel.y = y0 + panel.height * 0.04f * t
            }
            panel.alpha = 1f - t
            scrim.alpha = dim * (1f - t)
        }
    }

    /** Немедленно убрать (экран закрывается, новый звонок другого домофона). */
    fun closeNow() {
        if (!isShowing && parent == null) return
        val wasShowing = isShowing
        isShowing = false
        RecordingEvents.session(tileId, this, false)
        RecordingEvents.session(tileId, talk, false)
        GateHub.unlisten(gateListener)
        closing = true
        main.removeCallbacksAndMessages(null)
        animator?.let { animator = null; it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        restoreBrightness()
        talk.dispose()
        // Экраны поверх панели держат экран сами ([PanelIdle]); прочие (заставка, первая настройка) — нет: флаг снимается.
        if (host !is MainActivity && !PanelIdle.managed(host)) host.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        card.dispose()
        (parent as? ViewGroup)?.removeView(this)
        if (wasShowing) onClosed(this)
    }

    private fun run(duration: Long, end: (() -> Unit)?, frame: (Float) -> Unit) {
        animator?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { if (animator === it) frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) { animator = null; end?.invoke() }
                }
            })
            start()
        }
    }

    // ---- Данные ----

    fun render() {
        if (closing) return
        val tile = DoorbellHub.tile(tileId) ?: return closeNow()
        val config = DoorbellConfig.from(tile.config)
        val readings = DoorbellHub.readings ?: return
        val snapshot = DoorbellModel.snapshot(config, readings)
        val now = System.currentTimeMillis()
        card.bind(tile.title, config, snapshot.copy(ringing = snapshot.ringing || passedAt == null),
            DoorbellHub.offline(tileId), 0, DoorbellHub.lastRing(tileId), now)
        gateButtons.bind(GateHub.forTile(host, tileId, config))
        lightButton.visibility = if (config.light.isNotBlank()) VISIBLE else GONE
        talkButton.visibility = if (config.canTalk) VISIBLE else GONE
        talkButton.callMode = config.talkCall
        if (config.canTalk) talk.prepare(config.talkRtsp, config.rtspUser)
        val lightOn = snapshot.lightOn == true
        lightButton.text = if (lightOn) "Свет у входа · вкл" else "Свет у входа"
        style(lightButton, if (lightOn) DoorbellFrame.PERSON else null)
        val phrases = if (config.canTalk) DoorbellPhrases.ready(host, tileId) else emptyList()
        replyButton.visibility = if (snapshot.quickReplies.isNotEmpty() || phrases.isNotEmpty()) VISIBLE else GONE
        if (replies.visibility == VISIBLE) fillReplies(config, snapshot.quickReplies, phrases)
        val passed = passedGate
        sub.text = if (passedAt != null) passed?.let { GateLogic.verb("Открыли", it.name) } ?: "Открыли"
            else DoorbellHub.lastRing(tileId)?.let { "Звонок " + DoorbellModel.ago(it, now) } ?: ""
        sub.setTextColor(if (passedAt != null) DoorbellFrame.RING else DoorbellFrame.SECONDARY)
    }

    private fun touched() {
        if (!interacted) {
            interacted = true
            DoorbellHub.acknowledge(tileId)
            DoorbellHub.lastRingAt?.takeIf { it.first == tileId }?.let { RingLog.markAnswered(host, tileId, it.second) }
        }
        // Человек смотрит: мелодию и голос можно прервать, окно не закрывается раньше, чем через минуту после касания.
        PanelAlerts.cancel(DoorbellHub.ringTag(tileId))
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, RING_MS)
    }

    private fun runGate(gate: com.example.smarthomedashboard.Gate, action: com.example.smarthomedashboard.Gate.Action) {
        if (!GateStore.pin(host).required(inRingWindow = true)) { GateHub.run(gate.id, action); return }
        val owner = host as? androidx.appcompat.app.AppCompatActivity ?: return
        PinSecurityUi.authorize(owner, { GateHub.run(gate.id, action) })
    }

    /** Калитку открыли (здесь или на другом планшете): звонок отвечен; окно остаётся, пока гость проходит. */
    fun gateOpened(gate: com.example.smarthomedashboard.Gate? = null) {
        if (!isShowing || closing) return
        DoorbellHub.acknowledge(tileId)
        DoorbellHub.lastRingAt?.takeIf { it.first == tileId }?.let { RingLog.markAnswered(host, tileId, it.second) }
        PanelAlerts.cancel(DoorbellHub.ringTag(tileId))
        passedAt = SystemClock.uptimeMillis()
        passedGate = gate ?: GateHub.forTile(host, tileId, DoorbellHub.config(tileId) ?: DoorbellConfig()).firstOrNull()
        render()
        card.applyAccent(DoorbellFrame.RING, pulse = false, animate = true)
        main.removeCallbacks(closeAfterOpened)
        // Где проход, неизвестно (кнопка без датчика) — закрыть позже; иначе — когда снова закрыт ([onGateChanged]).
        if (passedGate?.let { GateHub.status(it).source == GateStatus.Source.NONE } != false) main.postDelayed(closeAfterOpened, PASS_UNKNOWN_MS)
        onGateChanged()
    }

    /** Гость прошёл: проходы плитки с известным положением снова закрыты — окно закрывается. */
    private fun onGateChanged() {
        if (!isShowing || closing || passedAt == null) return
        val gates = GateHub.forTile(host, tileId, DoorbellHub.config(tileId) ?: return)
        val known = gates.map { GateHub.status(it) }.filter { it.source != GateStatus.Source.NONE }
        val shut = known.isNotEmpty() && known.all { it.position == GateStatus.Position.CLOSED }
        if (shut && !shutPending) {
            shutPending = true
            main.removeCallbacks(closeAfterOpened)
            main.postDelayed(closeAfterShut, OPENED_SHOW_MS)
        } else if (!shut && shutPending) {
            shutPending = false
            main.removeCallbacks(closeAfterShut)
        }
    }

    private val closeAfterOpened = Runnable { close() }
    /** Проход снова закрыт: окно закрывается один раз через [OPENED_SHOW_MS]; снова открыли — отсчёт отменён. */
    private val closeAfterShut = Runnable { close() }
    private var shutPending = false

    private val inCallKeep = object : Runnable {
        override fun run() {
            if (!talk.inCall || !isShowing) return
            touched()
            main.postDelayed(this, 10_000)
        }
    }

    /**
     * Снимок посетителя получше ([RingLog]): когда в окне пошёл основной поток (HD), его кадр заменяет снимок с
     * плитки. Проверка раз в секунду первые [PHOTO_TRIES] секунд.
     */
    private var photoTries = 0
    private val betterPhoto = object : Runnable {
        override fun run() {
            if (!isShowing || closing) return
            val (ringTile, at) = DoorbellHub.lastRingAt ?: return
            if (ringTile != tileId || System.currentTimeMillis() - at > RING_MS) return
            if (card.video.shownHeight >= DoorbellVideoView.HD_HEIGHT) {
                card.video.currentFrame()?.let { RingLog.improve(host, tileId, at, it); return }
            }
            if (++photoTries < PHOTO_TRIES) main.postDelayed(this, PHOTO_CHECK_MS)
        }
    }

    private fun toggleLight() {
        val config = DoorbellHub.config(tileId) ?: return
        val readings = DoorbellHub.readings ?: return
        if (config.light.isBlank()) return
        DoorbellHub.lightTouched(tileId)
        val on = DoorbellModel.snapshot(config, readings).lightOn == true
        DoorbellHub.actions?.call(config.light.substringBefore('.'), if (on) "turn_off" else "turn_on", config.light, emptyMap()) {}
    }

    private fun showReplies(show: Boolean) {
        val config = DoorbellHub.config(tileId) ?: return
        val readings = DoorbellHub.readings ?: return
        replies.visibility = if (show) VISIBLE else GONE
        replyButton.text = if (show) "Скрыть фразы" else "Фразы"
        val phrases = if (config.canTalk) DoorbellPhrases.ready(host, tileId) else emptyList()
        if (show) fillReplies(config, DoorbellModel.snapshot(config, readings).quickReplies, phrases)
    }

    private var shownReplies: List<String> = emptyList()
    private var shownPhrases: List<DoorbellPhrases.Phrase> = emptyList()

    private fun fillReplies(config: DoorbellConfig, options: List<String>, phrases: List<DoorbellPhrases.Phrase>) {
        if (options == shownReplies && phrases == shownPhrases && replies.childCount > 0) return
        shownReplies = options
        shownPhrases = phrases
        replies.removeAllViews()
        // Свои фразы (голос владельца) — первыми: звучат сразу через динамик домофона, без HA.
        phrases.forEach { phrase ->
            replies.addView(pill("🎙 " + phrase.title, null).apply {
                tag = "door_phrase_${phrase.id}"
                setOnClickListener {
                    touched()
                    val pcm = DoorbellPhrases.load(host, tileId, phrase.id)
                    sub.text = if (pcm != null && talk.play(pcm)) "Фраза: ${phrase.title}" else "Фраза не отправлена"
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            })
        }
        options.filter { it != "off" }.forEach { option ->
            replies.addView(pill(option, null).apply {
                setOnClickListener {
                    touched()
                    DoorbellHub.actions?.call("select", "select_option", config.quickReply, mapOf("option" to option)) { result ->
                        sub.text = if (result == ServiceCommandResult.ACKNOWLEDGED) "Фраза отправлена: $option" else "Фраза не отправлена"
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            })
        }
        applyText((minOf(width, height).takeIf { it > 0 } ?: 800) / 800f)
    }

    // ---- Яркость в тихие часы ----

    private var appliedBrightness = -1f

    private fun applyNightBrightness() {
        val config = DoorbellHub.config(tileId) ?: return
        if (!config.isQuiet(DoorbellHub.minuteOfDay())) return
        val window = host.window ?: return
        val attrs = window.attributes
        savedBrightness = attrs.screenBrightness
        appliedBrightness = config.nightBrightness.coerceIn(5, 100) / 100f
        attrs.screenBrightness = appliedBrightness
        window.attributes = attrs
    }

    /** Возвращаем яркость, только если её не поменял кто-то другой (например, затемнение панели). */
    private fun restoreBrightness() {
        val saved = savedBrightness ?: return
        savedBrightness = null
        val window = host.window ?: return
        val attrs = window.attributes
        if (attrs.screenBrightness != appliedBrightness) return
        attrs.screenBrightness = saved
        window.attributes = attrs
    }

    /** Пока окно открыто, панель не затемняется (иначе видео встанет, а яркость потом собьётся). */
    private val keepAwake = object : Runnable {
        override fun run() {
            if (!isShowing) return
            DoorbellHub.actions?.keepAwake()
            main.postDelayed(this, KEEP_AWAKE_MS)
        }
    }

    // ---- Вспомогательное ----

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(host).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        setPadding(0, 0, 0, (6 * density).toInt())
    }

    private fun pill(text: String, icon: Int?) = TextView(host).apply {
        this.text = text
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        minHeight = (56 * density).toInt()
        setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
        if (icon != null) {
            val d = context.getDrawable(icon)?.mutate()
            d?.setTint(Color.WHITE)
            d?.setBounds(0, 0, (26 * density).toInt(), (26 * density).toInt())
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = (10 * density).toInt()
        }
        style(this, null)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun style(view: TextView, accent: Int?) {
        val c = accent ?: DoorbellFrame.NEUTRAL
        view.background = GradientDrawable().apply {
            cornerRadius = 18f * density
            setColor(if (accent != null) Color.argb(70, Color.red(c), Color.green(c), Color.blue(c)) else Color.argb(60, 30, 44, 56))
            setStroke((1.5f * density).toInt(), Color.argb(if (accent != null) 220 else 90, Color.red(c), Color.green(c), Color.blue(c)))
        }
    }

    override fun onDetachedFromWindow() {
        if (isShowing) closeNow()
        super.onDetachedFromWindow()
    }

    companion object {
        const val RING_MS = 60_000L
        const val OPEN_MS = 340L
        const val CLOSE_MS = 260L
        /** Проход снова закрыт после «Открыли» — окно закрывается через столько. */
        const val OPENED_SHOW_MS = 2_500L
        /** Где проход, неизвестно (кнопка без датчика): окно после «Открыли» закрывается через столько (решение 10). */
        const val PASS_UNKNOWN_MS = 30_000L
        private const val KEEP_AWAKE_MS = 20_000L
        private const val PHOTO_CHECK_MS = 1_000L
        private const val PHOTO_TRIES = 12

        fun show(activity: Activity, tileId: String, origin: RectF?, onClosed: (DoorbellRingWindow) -> Unit): DoorbellRingWindow {
            val window = DoorbellRingWindow(activity, tileId, origin, onClosed)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.addView(window, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.open()
            return window
        }
    }
}

/**
 * Кнопка с удержанием: заполнение слева направо за [HOLD_MS]; отпустили раньше — откат без действия.
 * Готово — короткая вибрация и [onComplete]. Состояние калитки меняет подпись и цвет.
 */
internal class HoldButton(context: Context, label: String, icon: Int) : View(context) {
    /** Подпись в покое (проходы меняют её: «Открыть калитку» / «Закрыть ворота»). */
    var label: String = label
        set(value) { if (field != value) { field = value; contentDescription = if (instant) value else "$value: удерживайте"; invalidate() } }
    /** Подпись и подсказка состояния вместо стандартных («Открываю…», «нет подтверждения…»); null — стандартные, "" — без подсказки. */
    var stateCaption: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var stateHint: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Цвет кнопки в покое (у проходов: «Открыть» — зелёный, «Закрыть» — синий, «Стоп» — янтарный); null — зелёный. */
    var idleAccent: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Сразу по касанию, без удержания («Стоп» привода: останавливать надо немедленно). */
    var instant = false
        set(value) { field = value; contentDescription = if (value) label else "$label: удерживайте"; invalidate() }
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val iconDrawable = context.getDrawable(icon)?.mutate()?.apply { setTint(Color.WHITE) }
    private val rect = RectF()
    private var progress = 0f
    var onComplete: (() -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    /** Короткое касание (отпустили сразу, на месте) — у плитки «Проход»: раскрыть. null — касание ничего не делает. */
    var onTap: (() -> Unit)? = null
    /** Касания не принимает — их получает родитель (режим редактирования панели: перенос и размер плитки). */
    var passThrough = false
    var textPx = 23f * density / 1.5f
        set(value) { field = value; invalidate() }
    /**
     * Размер надписи, подсказки и значка — от высоты самой кнопки (у проходов: одно правило на все экраны, от
     * телефона до планшета 13"); по ширине надпись затем ужимается ([onDraw]).
     */
    var autoSize = false
        set(value) { field = value; if (value && height > 0) textPx = height * AUTO_TEXT }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (autoSize && h > 0) textPx = h * AUTO_TEXT
    }
    var state = DoorbellRingWindow.Gate.IDLE
        set(value) { if (field != value) { field = value; if (value != DoorbellRingWindow.Gate.IDLE && value != DoorbellRingWindow.Gate.FAILED) progress = 1f else if (progress >= 1f) progress = 0f; invalidate() } }

    init {
        isClickable = true
        contentDescription = "$label: удерживайте"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * density).toInt())
    }

    private val accent get() = when (state) {
        DoorbellRingWindow.Gate.OPENED -> DoorbellFrame.RING
        DoorbellRingWindow.Gate.FAILED -> DoorbellFrame.OFFLINE
        DoorbellRingWindow.Gate.UNCONFIRMED -> DoorbellFrame.PERSON
        DoorbellRingWindow.Gate.IDLE, DoorbellRingWindow.Gate.SENDING, DoorbellRingWindow.Gate.WAITING -> idleAccent ?: DoorbellFrame.RING
    }

    private val caption get() = stateCaption ?: when (state) {
        DoorbellRingWindow.Gate.SENDING, DoorbellRingWindow.Gate.WAITING -> "Открываю…"
        DoorbellRingWindow.Gate.OPENED -> "Калитка открыта"
        else -> label
    }

    private val hint get() = if (holding && progress > 0f) "держите…" else stateHint?.ifBlank { null } ?: if (stateHint != null) null else when (state) {
        DoorbellRingWindow.Gate.IDLE, DoorbellRingWindow.Gate.FAILED -> if (instant) null else "удерживайте 1 секунду"
        DoorbellRingWindow.Gate.UNCONFIRMED -> "нет подтверждения · коснитесь, чтобы повторить"
        else -> null
    }

    /**
     * Время удержания считается по часам, а не по аниматору: при отключённых в системе анимациях
     * (масштаб длительности 0) калитка не должна открываться от простого касания.
     */
    private var holdStart = 0L
    private var releaseStart = 0L
    private var releaseFrom = 0f
    private var holding = false
    private val complete = Runnable {
        if (!holding) return@Runnable
        holding = false
        progress = 1f
        invalidate()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onComplete?.invoke()
    }
    private val frame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (holding) {
                progress = ((now - holdStart).toFloat() / HOLD_MS).coerceIn(0f, 0.999f)
                invalidate()
                postOnAnimation(this)
            } else if (progress > 0f && progress < 1f) {
                progress = (releaseFrom * (1f - (now - releaseStart).toFloat() / RELEASE_MS)).coerceAtLeast(0f)
                invalidate()
                if (progress > 0f) postOnAnimation(this)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (passThrough) return false
        // Нет связи — удержание не принимается, но плитку «Проход» можно раскрыть (там видно почему).
        if (!isEnabled) {
            if (onTap != null && event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < TAP_MS) onTap?.invoke()
            return onTap != null
        }
        val busy = state == DoorbellRingWindow.Gate.SENDING || state == DoorbellRingWindow.Gate.WAITING || state == DoorbellRingWindow.Gate.OPENED
        if (instant) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { onTouchStart?.invoke(); isPressed = true }
                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    if (!busy && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onComplete?.invoke()
                    }
                }
                MotionEvent.ACTION_CANCEL -> isPressed = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart?.invoke()
                if (busy) return true
                parent?.requestDisallowInterceptTouchEvent(true)
                if (state == DoorbellRingWindow.Gate.FAILED) progress = 0f
                holding = true
                holdStart = SystemClock.uptimeMillis() - (progress * HOLD_MS).toLong()
                removeCallbacks(complete)
                postDelayed(complete, ((1f - progress) * HOLD_MS).toLong().coerceAtLeast(1))
                removeCallbacks(frame)
                postOnAnimation(frame)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (busy && event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < TAP_MS) onTap?.invoke()
                if (!holding) return true
                holding = false
                val tap = event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < TAP_MS &&
                    event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                if (tap && onTap != null) {
                    // Касание, а не удержание: полоса не заполняется, плитка раскрывается.
                    removeCallbacks(complete); progress = 0f; invalidate(); onTap?.invoke(); return true
                }
                removeCallbacks(complete)
                releaseFrom = progress
                releaseStart = SystemClock.uptimeMillis()
                removeCallbacks(frame)
                postOnAnimation(frame)
                return true
            }
        }
        return true
    }

    /** Недоступна (нет связи) — приглушена, касания не принимает. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = 18f * density
        val c = accent
        rect.set(0f, 0f, w, h)
        bg.color = Color.argb(70, Color.red(c), Color.green(c), Color.blue(c))
        canvas.drawRoundRect(rect, r, r, bg)
        if (progress > 0f) {
            fill.color = Color.argb(170, Color.red(c), Color.green(c), Color.blue(c))
            canvas.save()
            canvas.clipRect(0f, 0f, w * progress, h)
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.restore()
        }
        stroke.strokeWidth = 1.8f * density
        stroke.color = Color.argb(230, Color.red(c), Color.green(c), Color.blue(c))
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, stroke)
        // Надпись и подсказка всегда целиком внутри кнопки: длинное — мельче (до 60 %), не помещается и так — без значка,
        // и только потом — с многоточием.
        val room = w - 24 * density
        textPaint.textSize = textPx
        val wanted = textPaint.measureText(caption) + textPx * 1.3f + 10 * density
        if (wanted > room) textPaint.textSize = textPx * maxOf(0.6f, room / wanted)
        val withIcon = textPaint.measureText(caption) + textPaint.textSize * 1.3f + 10 * density <= room
        val iconSide = if (withIcon) (textPaint.textSize * 1.3f).toInt() else 0
        val iconGap = if (withIcon) 10 * density else 0f
        val text = fit(caption, room - iconSide - iconGap)
        val textW = textPaint.measureText(text)
        val total = textW + iconSide + iconGap
        val left = ((w - total) / 2f).coerceAtLeast(12 * density)
        val lineH = textPaint.descent() - textPaint.ascent()
        val smallSize = textPaint.textSize * 0.72f
        val small = hint?.let { h ->
            val size = textPaint.textSize
            textPaint.textSize = smallSize
            fit(h, room).also { textPaint.textSize = size }
        }
        val blockH = if (small != null) lineH + smallSize * 1.25f else lineH
        val top = (h - blockH) / 2f
        if (withIcon) {
            iconDrawable?.setBounds(left.toInt(), (top + (lineH - iconSide) / 2).toInt(), left.toInt() + iconSide,
                (top + (lineH + iconSide) / 2).toInt())
            iconDrawable?.draw(canvas)
        }
        canvas.drawText(text, left + iconSide + iconGap + textW / 2, top - textPaint.ascent(), textPaint)
        if (small != null) {
            val size = textPaint.textSize
            textPaint.textSize = smallSize
            textPaint.alpha = 190
            canvas.drawText(small, w / 2f, top + lineH + smallSize * 1.1f, textPaint)
            textPaint.alpha = 255
            textPaint.textSize = size
        }
    }

    private fun fit(text: String, room: Float): String =
        if (textPaint.measureText(text) <= room) text
        else android.text.TextUtils.ellipsize(text, android.text.TextPaint(textPaint), room.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()

    override fun onDetachedFromWindow() {
        holding = false
        removeCallbacks(complete)
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    internal val holdProgress get() = progress

    companion object {
        const val HOLD_MS = 1_000L
        const val TAP_MS = 300L
        private const val RELEASE_MS = 220L
        /** Надпись — 30 % высоты кнопки: с подсказкой под ней (72 %) блок занимает ~2/3 высоты. */
        private const val AUTO_TEXT = 0.3f
    }
}

/** Баннер «Звонят в калитку» сверху экрана настроек или редактора: «Открыть» — окно звонка поверх, «Позже» — скрыть. */
@SuppressLint("ViewConstructor")
internal class DoorbellBanner private constructor(
    val host: Activity,
    val tileId: String,
    private val onOpen: (RectF) -> Unit,
    private val onLater: () -> Unit,
    private val onGone: (DoorbellBanner) -> Unit
) : LinearLayout(host) {
    private val density = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var gone = false

    init {
        tag = "door_banner"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = 180f
        val pad = (14 * density).toInt()
        setPadding(pad * 2, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.rgb(12, 22, 18)); cornerRadius = 22f * density
            setStroke((2 * density).toInt(), DoorbellFrame.RING)
        }
        addView(ImageView(host).apply { setImageResource(R.drawable.ic_doorbell); setColorFilter(DoorbellFrame.RING) },
            LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply { rightMargin = (12 * density).toInt() })
        addView(TextView(host).apply {
            text = DoorbellHub.RING_PHRASE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(button("Позже", false) { dismiss(); onLater() }.apply { tag = "door_banner_later" })
        addView(button("Открыть", true) {
            val location = IntArray(2)
            getLocationInWindow(location)
            val rect = RectF(location[0].toFloat(), location[1].toFloat(), location[0] + width.toFloat(), location[1] + height.toFloat())
            dismiss()
            onOpen(rect)
        }.apply { tag = "door_banner_open" })
    }

    private fun button(text: String, primary: Boolean, action: () -> Unit) = TextView(host).apply {
        this.text = text
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = (48 * density).toInt()
        minWidth = (110 * density).toInt()
        setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(if (primary) DoorbellFrame.RING else Color.argb(60, 255, 255, 255))
        }
        isClickable = true
        setOnClickListener { action() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * density).toInt() }
    }

    fun dismiss() {
        if (gone) return
        gone = true
        main.removeCallbacksAndMessages(null)
        animate().translationY(-height - 40f).alpha(0f).setDuration(200).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
        }.start()
        onGone(this)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        if (!gone) { gone = true; onGone(this) }
        super.onDetachedFromWindow()
    }

    companion object {
        fun show(activity: Activity, tileId: String, onOpen: (RectF) -> Unit, onLater: () -> Unit,
                 onGone: (DoorbellBanner) -> Unit): DoorbellBanner {
            val banner = DoorbellBanner(activity, tileId, onOpen, onLater, onGone)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val density = activity.resources.displayMetrics.density
            val params = FrameLayout.LayoutParams(minOf((720 * density).toInt(), root.width.takeIf { it > 0 }?.let { (it * 0.94f).toInt() } ?: Int.MAX_VALUE),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (16 * density).toInt() }
            root.addView(banner, params)
            banner.translationY = -200 * density
            banner.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            banner.main.postDelayed({ banner.dismiss() }, DoorbellRingWindow.RING_MS)
            return banner
        }
    }
}
