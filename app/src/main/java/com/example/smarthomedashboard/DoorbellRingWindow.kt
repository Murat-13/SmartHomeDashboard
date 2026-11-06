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
 * остальное приглушается. Видео со звуком (после мелодии и голоса), кнопки: «Открыть калитку» (удержание 1 с,
 * без PIN; ожидание → факт HA), «Свет у входа», «Ответить» (готовые фразы домофона), «Закрыть».
 * Закрывается само через [RING_MS]; повторный звонок продлевает. Калитку открыли (здесь или на другом планшете) —
 * 2,5 с «Калитку открыли» и закрытие. В тихие часы яркость окна снижается до заданной.
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
    val openButton = HoldButton(host, "Открыть калитку", R.drawable.ic_door_unlock).apply { tag = "door_open" }
    private val lightButton = pill("Свет у входа", R.drawable.ic_door_light).apply { tag = "door_light" }
    private val replyButton = pill("Ответить", R.drawable.ic_door_reply).apply { tag = "door_reply" }
    private val closeButton = pill("Закрыть", R.drawable.ic_close).apply { tag = "door_close" }
    private val replies = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; visibility = GONE; tag = "door_replies" }
    private var animator: ValueAnimator? = null
    private var closing = false
    var isShowing = false
        private set
    private var openedAt = 0L
    private var savedBrightness: Float? = null
    private var gate = Gate.IDLE
    private var gateDeadline: Runnable? = null
    private var interacted = false

    /** UNCONFIRMED — HA принял команду, но факта открытия панель не увидела: повтор не предлагаем (импульсное реле). */
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
        buttons.addView(openButton)
        buttons.addView(lightButton)
        buttons.addView(replyButton)
        buttons.addView(closeButton)
        controls.addView(buttons)
        controls.addView(replies)
        panel.addView(card)
        panel.addView(controls)
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        scrim.setOnClickListener { touched() }

        openButton.onComplete = { openGate() }
        openButton.onTouchStart = { touched() }
        lightButton.setOnClickListener { touched(); toggleLight() }
        replyButton.setOnClickListener { touched(); showReplies(replies.visibility != VISIBLE) }
        closeButton.setOnClickListener { touched(); close() }
        card.setOnClickListener { touched() }
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
                height = if (buttons.getChildAt(i) === openButton) (base * 1.4f).toInt() else base
                topMargin = (12 * unit * density / 1.5f).toInt()
            }
        }
    }

    private fun applyText(unit: Float) {
        val k = unit * density / 1.5f
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * k)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * k)
        listOf(lightButton, replyButton, closeButton).forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k) }
        openButton.textPx = 23f * k
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
        // Звонят снова после «Калитку открыли»: окно остаётся, кнопка снова доступна.
        main.removeCallbacks(closeAfterOpened)
        if (gate == Gate.OPENED || gate == Gate.UNCONFIRMED || gate == Gate.FAILED) gate = Gate.IDLE
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
        closing = true
        main.removeCallbacksAndMessages(null)
        animator?.let { animator = null; it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        restoreBrightness()
        // Экраны настроек и редакторов сами по себе экран не держат: флаг окна снимается.
        if (host !is MainActivity) host.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        card.bind(tile.title, config, snapshot.copy(ringing = snapshot.ringing || gate != Gate.OPENED),
            DoorbellHub.offline(tileId), 0, DoorbellHub.lastRing(tileId), now)
        openButton.visibility = if (config.lock.isNotBlank() && DoorbellModel.openCommand(config.lock) != null) VISIBLE else GONE
        lightButton.visibility = if (config.light.isNotBlank()) VISIBLE else GONE
        val lightOn = snapshot.lightOn == true
        lightButton.text = if (lightOn) "Свет у входа · вкл" else "Свет у входа"
        style(lightButton, if (lightOn) DoorbellFrame.PERSON else null)
        replyButton.visibility = if (snapshot.quickReplies.isNotEmpty()) VISIBLE else GONE
        if (replies.visibility == VISIBLE) fillReplies(config, snapshot.quickReplies)
        sub.text = when (gate) {
            Gate.SENDING -> "Отправляю команду…"
            Gate.WAITING -> "Открываю калитку…"
            Gate.OPENED -> "Калитку открыли"
            Gate.FAILED -> "Не удалось открыть — попробуйте ещё раз"
            Gate.UNCONFIRMED -> "Команда принята, подтверждения нет — проверьте калитку"
            Gate.IDLE -> DoorbellHub.lastRing(tileId)?.let { "Звонок " + DoorbellModel.ago(it, now) } ?: ""
        }
        sub.setTextColor(when (gate) {
            Gate.OPENED -> DoorbellFrame.RING
            Gate.FAILED -> DoorbellFrame.OFFLINE
            Gate.UNCONFIRMED -> DoorbellFrame.PERSON
            else -> DoorbellFrame.SECONDARY
        })
        openButton.state = gate
    }

    private fun touched() {
        if (!interacted) { interacted = true; DoorbellHub.acknowledge(tileId) }
        // Человек смотрит: мелодию и голос можно прервать, окно не закрывается раньше, чем через минуту после касания.
        PanelAlerts.cancel(DoorbellHub.ringTag(tileId))
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, RING_MS)
    }

    private fun openGate() {
        val config = DoorbellHub.config(tileId) ?: return
        val command = DoorbellModel.openCommand(config.lock) ?: return
        if (gate == Gate.SENDING || gate == Gate.WAITING || gate == Gate.OPENED) return
        // После «подтверждения нет» повтор — только осознанный: второе удержание (первое лишь снимает предупреждение).
        if (gate == Gate.UNCONFIRMED) { gate = Gate.IDLE; render(); return }
        val actions = DoorbellHub.actions
        gate = Gate.SENDING
        render()
        val sent = actions?.call(command.domain, command.service, config.lock, emptyMap()) { result ->
            if (gate != Gate.SENDING && gate != Gate.WAITING) return@call
            if (result == ServiceCommandResult.ACKNOWLEDGED) { if (gate == Gate.SENDING) gate = Gate.WAITING }
            else gate = Gate.FAILED
            render()
        } == true
        if (!sent) { gate = Gate.FAILED; render(); return }
        // Факт открытия — событие HA (DoorbellMonitor.GateOpened). Нет факта — честно «не удалось».
        val deadline = Runnable {
            when (gate) {
                Gate.SENDING -> gate = Gate.FAILED
                Gate.WAITING -> gate = Gate.UNCONFIRMED
                else -> return@Runnable
            }
            render()
        }
        gateDeadline = deadline
        main.postDelayed(deadline, GATE_TIMEOUT_MS)
    }

    /** Калитку открыли (здесь или на другом планшете): короткое подтверждение и закрытие. */
    fun gateOpened() {
        if (!isShowing || closing) return
        gateDeadline?.let(main::removeCallbacks)
        gate = Gate.OPENED
        DoorbellHub.acknowledge(tileId)
        PanelAlerts.cancel(DoorbellHub.ringTag(tileId))
        render()
        card.applyAccent(DoorbellFrame.RING, pulse = false, animate = true)
        main.removeCallbacks(autoClose)
        main.postDelayed(closeAfterOpened, OPENED_SHOW_MS)
    }

    private val closeAfterOpened = Runnable { close() }

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
        replyButton.text = if (show) "Скрыть фразы" else "Ответить"
        if (show) fillReplies(config, DoorbellModel.snapshot(config, readings).quickReplies)
    }

    private var shownReplies: List<String> = emptyList()

    private fun fillReplies(config: DoorbellConfig, options: List<String>) {
        if (options == shownReplies && replies.childCount > 0) return
        shownReplies = options
        replies.removeAllViews()
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
        const val OPENED_SHOW_MS = 2_500L
        const val GATE_TIMEOUT_MS = 15_000L
        private const val KEEP_AWAKE_MS = 20_000L

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
internal class HoldButton(context: Context, private val label: String, icon: Int) : View(context) {
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
    var textPx = 23f * density / 1.5f
        set(value) { field = value; invalidate() }
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
        else -> DoorbellFrame.RING
    }

    private val caption get() = when (state) {
        DoorbellRingWindow.Gate.SENDING, DoorbellRingWindow.Gate.WAITING -> "Открываю…"
        DoorbellRingWindow.Gate.OPENED -> "Калитка открыта"
        else -> label
    }

    private val hint get() = when (state) {
        DoorbellRingWindow.Gate.IDLE, DoorbellRingWindow.Gate.FAILED -> if (progress > 0f) "держите…" else "удерживайте 1 секунду"
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
        if (!isEnabled) return false
        val busy = state == DoorbellRingWindow.Gate.SENDING || state == DoorbellRingWindow.Gate.WAITING || state == DoorbellRingWindow.Gate.OPENED
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
                if (!holding) return true
                holding = false
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
        textPaint.textSize = textPx
        val text = caption
        val small = hint
        val textW = textPaint.measureText(text)
        val iconSide = (textPx * 1.3f).toInt()
        val total = textW + iconSide + 10 * density
        val left = ((w - total) / 2f).coerceAtLeast(12 * density)
        val lineH = textPaint.descent() - textPaint.ascent()
        val smallSize = textPx * 0.72f
        val blockH = if (small != null) lineH + smallSize * 1.25f else lineH
        val top = (h - blockH) / 2f
        iconDrawable?.setBounds(left.toInt(), (top + (lineH - iconSide) / 2).toInt(), left.toInt() + iconSide,
            (top + (lineH + iconSide) / 2).toInt())
        iconDrawable?.draw(canvas)
        canvas.drawText(text, left + iconSide + 10 * density + textW / 2, top - textPaint.ascent(), textPaint)
        if (small != null) {
            val size = textPaint.textSize
            textPaint.textSize = smallSize
            textPaint.alpha = 190
            canvas.drawText(small, w / 2f, top + lineH + smallSize * 1.1f, textPaint)
            textPaint.alpha = 255
            textPaint.textSize = size
        }
    }

    override fun onDetachedFromWindow() {
        holding = false
        removeCallbacks(complete)
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    internal val holdProgress get() = progress

    companion object {
        const val HOLD_MS = 1_000L
        private const val RELEASE_MS = 220L
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
