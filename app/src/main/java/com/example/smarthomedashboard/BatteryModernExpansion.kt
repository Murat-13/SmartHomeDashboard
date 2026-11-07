package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/** Карточка, которую раскрытие показывает поверх панели и освобождает при закрытии. */
internal interface ModernOverlayCard {
    fun dispose()
    /** Началось сворачивание (таймер, касание снаружи): закрыть свои слои поверх приложения. */
    fun collapsing() {}
}

/** Раскрытая карточка с областью собственных жестов (график); координаты — оконные. */
internal interface ModernInteractiveArea {
    fun interactiveAt(windowX: Float, windowY: Float): Boolean
}

/**
 * Компактная карточка, которую раскрытие скрывает прозрачностью исходной плитки: alpha родителя
 * не видна через isShown, поэтому о скрытии сообщается явно (остановить невидимые анимации).
 */
internal interface ModernHideAware {
    fun setHiddenBySource(hidden: Boolean)
}

/**
 * Раскрытие одной Modern-плитки (АКБ, Электропитание) поверх панели. Карточка — отдельная View в [overlay];
 * компактная плитка остаётся на месте (скрыта) и после закрытия снова видна с прежними
 * размерами и координатами. Анимация — только transform (без relayout содержимого на каждом кадре).
 *
 * Таймер: [timeoutMs] после последнего взаимодействия пользователя ([touch]). События HA его
 * не трогают: отрисовка данных не вызывает методы этого класса. [pause] останавливает отсчёт.
 *
 * Размытие фона (0.9.14): один снимок панели [backdrop] в начале раскрытия, размытый один раз, —
 * слой над панелью, у которого анимируется только прозрачность. Раньше RenderEffect с растущим радиусом
 * пересоздавался на всю панель на каждом кадре: на Huawei MatePad SE (Adreno 610) анимация шла ~30 кадров/с.
 * На время движения карточка рисуется аппаратным слоем, тень постоянная (гаснет вместе с прозрачностью).
 */
internal class BatteryModernExpansion(
    private val handler: Handler,
    private val overlay: () -> FrameLayout?,
    private val blurTarget: () -> View?,
    private val blurSupported: Boolean,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val backdrop: ExpansionBackdrop = PixelCopyBackdrop(handler)
) {
    data class Bounds(val x: Float, val y: Float, val width: Int, val height: Int)

    var tileId: String? = null
        private set
    var card: View? = null
        private set
    var source: View? = null
        private set
    var original: Bounds? = null
        private set
    var paused = false
        private set
    /** Время срабатывания таймера по часам Handler; null — таймер не запланирован. */
    var collapseAt: Long? = null
        private set
    val isOpen get() = card != null && !closing

    private var scrim: View? = null
    private var animator: ValueAnimator? = null
    private var closing = false
    /** Затемнение раскрытой карточки: слабее при размытии, сильнее без него (в т. ч. если снимок не удался). */
    private var dimTarget = DIM_ONLY
    /** Запрос снимка текущего раскрытия; ответ с другим токеном отбрасывается (и его bitmap освобождается). */
    private var backdropToken: Any? = null
    /** Размытый слой над панелью и его bitmap. */
    internal var backdropView: ImageView? = null
        private set
    private var backdropBitmap: Bitmap? = null
    /** Доля раскрытия, к которой снимок был готов: отсюда слой проявляется до 1 к концу раскрытия. */
    private var backdropFrom = 0f
    private var openProgress = 0f
    /** Копия кадра текущего раскрытия ещё идёт на фоновом потоке; дождаться до первого кадра. */
    private var pendingCopy: ExpansionBackdrop.Pending? = null

    /** Снимок, начатый до [expand] ([prefetch]): пока собирается карточка, кадр уже копируется. */
    private class Prefetch(val source: View, val panel: View, val token: Any) {
        var pending: ExpansionBackdrop.Pending = ExpansionBackdrop.DONE
        var delivered = false
        var result: Bitmap? = null
    }
    private var prefetch: Prefetch? = null
    private val timeout = Runnable {
        collapseAt = null
        collapse()
    }

    /** Таймер текущего раскрытия; null — карточка не сворачивается сама (решение владельца для домофона). */
    private var currentTimeout: Long? = timeoutMs

    /**
     * Начать снимок фона для скорого [expand] из [from] — до сборки раскрытой карточки (0.9.15): копия кадра
     * идёт параллельно с созданием и заполнением карточки, а не после них. Без [expand] снимок освобождается
     * следующим [clear] / [prefetch].
     */
    fun prefetch(from: View) {
        dropPrefetch()
        if (!blurOn() || card != null) return
        val panel = blurTarget() ?: return
        val next = Prefetch(from, panel, Any())
        prefetch = next
        next.pending = runCatching {
            backdrop.capture(panel, holeOf(from)) { bitmap -> onSnapshot(next.token, panel, bitmap) }
        }.getOrElse {
            next.delivered = true
            ExpansionBackdrop.DONE
        }
    }

    fun expand(id: String, from: View, expandedCard: View, target: Bounds, timeout: Long? = timeoutMs) {
        val early = prefetch?.takeIf { it.source === from }
        if (early != null) prefetch = null
        clear()
        currentTimeout = timeout
        val host = overlay() ?: return
        val start = Bounds(from.x, from.y, from.layoutParams.width, from.layoutParams.height)
        tileId = id
        source = from
        original = start
        card = expandedCard
        closing = false

        val dim = View(host.context).apply {
            tag = "bm_scrim"
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = false
        }
        scrim = dim
        // Снимок — до первого кадра раскрытия: в нём ещё нет карточки и затемнения.
        dimTarget = dimAlpha()
        requestBackdrop(start, early)
        host.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        expandedCard.layoutParams = FrameLayout.LayoutParams(target.width, target.height)
        expandedCard.elevation = ELEVATION
        expandedCard.pivotX = 0f
        expandedCard.pivotY = 0f
        host.addView(expandedCard)
        from.alpha = 0f
        notifyHidden(from, true)
        expandedCard.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val sx = start.width / target.width.toFloat().coerceAtLeast(1f)
        val sy = start.height / target.height.toFloat().coerceAtLeast(1f)
        run(OPEN_MS, onEnd = { expandedCard.setLayerType(View.LAYER_TYPE_NONE, null) }) { t ->
            expandedCard.x = start.x + (target.x - start.x) * t
            expandedCard.y = start.y + (target.y - start.y) * t
            expandedCard.scaleX = sx + (1f - sx) * t
            expandedCard.scaleY = sy + (1f - sy) * t
            expandedCard.alpha = 0.6f + 0.4f * t
            dim.alpha = dimTarget * t
            openProgress = t
            backdropView?.alpha = if (backdropFrom >= 1f) 1f else ((t - backdropFrom) / (1f - backdropFrom)).coerceIn(0f, 1f)
        }
        scheduleTimer()
        // Снимок, начатый заранее, уже готов — слой сразу (проявляется вместе с раскрытием).
        if (early != null && early.delivered && backdropToken === early.token) {
            val panel = early.panel
            onBackdrop(early.token, panel, early.result)
        }
        awaitCopy()
    }

    /**
     * До первого кадра раскрытия копия кадра должна закончиться: иначе в снимок попадёт сама карточка.
     * Обычно она уже готова (шла параллельно со сборкой карточки); не успела за [COPY_WAIT_MS] —
     * снимок отбрасывается, раскрытие идёт с одним затемнением.
     */
    private fun awaitCopy() {
        val pending = pendingCopy ?: return
        pendingCopy = null
        if (backdropToken == null) return
        android.os.Trace.beginSection("SHD await copy")
        val copied = try { pending.awaitCopied(COPY_WAIT_MS) } finally { android.os.Trace.endSection() }
        if (!copied) {
            backdropToken = null
            dimTarget = DIM_ONLY
        }
    }

    /**
     * Плавно возвращает карточку к сохранённым размерам/координатам и удаляет overlay (0.9.2, этап 5):
     * - движение начинается с текущего состояния карточки (закрытие во время раскрытия не прыгает к полному размеру);
     * - в последней части карточка уходит в прозрачность, а компактная плитка проявляется на своём месте, тень
     *   гаснет: в конце удаляется уже невидимая карточка (раньше — скачок 60% уменьшенной карточки с тенью
     *   к 100% компактной плитки за один кадр).
     * - тень (elevation) не меняется по кадрам (0.9.14): она гаснет вместе с прозрачностью карточки, без
     *   пересчёта тени на каждом кадре; карточка рисуется аппаратным слоем, размытый слой гаснет.
     */
    fun collapse() {
        val expandedCard = card ?: return
        if (closing) return
        val start = checkNotNull(original)
        closing = true
        stopTimer()
        (expandedCard as? ModernOverlayCard)?.collapsing()
        val from = Frame(expandedCard.x, expandedCard.y, expandedCard.scaleX, expandedCard.scaleY, expandedCard.alpha)
        val sx = start.width / expandedCard.layoutParams.width.toFloat().coerceAtLeast(1f)
        val sy = start.height / expandedCard.layoutParams.height.toFloat().coerceAtLeast(1f)
        val dim = scrim
        val dimStart = dim?.alpha ?: 0f
        val layer = backdropView
        val layerStart = layer?.alpha ?: 0f
        val compact = source
        expandedCard.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        run(CLOSE_MS, onEnd = ::clear) { t ->
            expandedCard.x = from.x + (start.x - from.x) * t
            expandedCard.y = from.y + (start.y - from.y) * t
            expandedCard.scaleX = from.scaleX + (sx - from.scaleX) * t
            expandedCard.scaleY = from.scaleY + (sy - from.scaleY) * t
            val fade = crossfade(t)
            expandedCard.alpha = from.alpha * (1f - fade)
            compact?.alpha = fade
            dim?.alpha = dimStart * (1f - t)
            layer?.alpha = layerStart * (1f - t)
        }
    }

    private data class Frame(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)

    /** Взаимодействие пользователя с открытой карточкой: перезапуск таймера. */
    fun touch() {
        if (!isOpen || paused) return
        scheduleTimer()
    }

    fun pause() {
        paused = true
        stopTimer()
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (isOpen) scheduleTimer()
    }

    /** Немедленное освобождение: анимация, таймер, overlay, размытый слой и его bitmap; компактная плитка снова видна. */
    fun clear() {
        stopTimer()
        animator?.let {
            animator = null
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        clearBackdrop()
        card?.let { view ->
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            (view as? ModernOverlayCard)?.dispose()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        scrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        source?.alpha = 1f
        source?.let { notifyHidden(it, false) }
        card = null
        scrim = null
        source = null
        original = null
        tileId = null
        closing = false
        paused = false
    }

    private fun notifyHidden(view: View, hidden: Boolean) {
        if (view is ModernHideAware) view.setHiddenBySource(hidden)
        if (view is ViewGroup) for (i in 0 until view.childCount) notifyHidden(view.getChildAt(i), hidden)
    }

    private fun scheduleTimer() {
        stopTimer()
        if (paused) return
        val delay = currentTimeout ?: return
        collapseAt = android.os.SystemClock.uptimeMillis() + delay
        handler.postDelayed(timeout, delay)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timeout)
        collapseAt = null
    }

    private fun run(duration: Long, onEnd: (() -> Unit)?, frame: (Float) -> Unit) {
        animator?.let {
            animator = null
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        val next = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { if (animator === it) frame(it.animatedValue as Float) }
        }
        animator = next
        next.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== next) return
                frame(1f)
                animator = null
                next.removeAllUpdateListeners()
                next.removeAllListeners()
                onEnd?.invoke()
            }
        })
        frame(0f)
        next.start()
    }

    private fun blurOn() = blurSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun dimAlpha() = if (blurOn()) DIM_WITH_BLUR else DIM_ONLY

    /** Запрос одного снимка панели; раскрытие его не ждёт. Место исходной плитки закрашивается фоном (она скрыта). */
    private fun requestBackdrop(tile: Bounds, early: Prefetch?) {
        backdropFrom = 0f
        openProgress = 0f
        if (!blurOn()) {
            early?.result?.recycle()
            return
        }
        if (early != null) {
            // Снимок уже идёт (или готов): тот же токен, без второй копии.
            backdropToken = early.token
            pendingCopy = early.pending
            return
        }
        val panel = blurTarget() ?: return
        val token = Any()
        backdropToken = token
        val hole = Rect(tile.x.toInt(), tile.y.toInt(), (tile.x + tile.width).toInt(), (tile.y + tile.height).toInt())
        val accepted = runCatching { backdrop.capture(panel, hole) { bitmap -> onSnapshot(token, panel, bitmap) } }
        accepted.onSuccess { pendingCopy = it }
        if (accepted.isFailure) onBackdrop(token, panel, null)
    }

    private fun holeOf(from: View) = Rect(from.x.toInt(), from.y.toInt(),
        (from.x + from.layoutParams.width).toInt(), (from.y + from.layoutParams.height).toInt())

    /** Ответ снимка: текущему раскрытию, в запас (раскрытие ещё не началось) или в освобождение. */
    private fun onSnapshot(token: Any, panel: View, bitmap: Bitmap?) {
        if (token === backdropToken) return onBackdrop(token, panel, bitmap)
        val waiting = prefetch
        if (waiting != null && waiting.token === token) {
            waiting.result = bitmap
            waiting.delivered = true
            return
        }
        bitmap?.recycle()
    }

    private fun dropPrefetch() {
        prefetch?.result?.recycle()
        prefetch = null
    }

    private fun onBackdrop(token: Any, panel: View, bitmap: Bitmap?) {
        if (token !== backdropToken || card == null || closing) {
            bitmap?.recycle()
            return
        }
        backdropToken = null
        val parent = panel.parent as? ViewGroup
        if (bitmap == null || parent == null || panel.width <= 0 || panel.height <= 0) {
            bitmap?.recycle()
            // Размытия не будет: затемнение как на устройствах без размытия.
            dimTarget = DIM_ONLY
            if (animator == null) scrim?.alpha = dimTarget
            return
        }
        val layer = ImageView(panel.context).apply {
            tag = BACKDROP_TAG
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            x = panel.left.toFloat()
            y = panel.top.toFloat()
        }
        parent.addView(layer, parent.indexOfChild(panel) + 1, ViewGroup.LayoutParams(panel.width, panel.height))
        backdropView = layer
        backdropBitmap = bitmap
        if (animator != null) {
            backdropFrom = openProgress
        } else {
            // Раскрытие уже закончилось: короткое проявление слоя.
            run(BACKDROP_FADE_MS, onEnd = null) { t -> layer.alpha = t }
        }
    }

    private fun clearBackdrop() {
        backdropToken = null
        pendingCopy = null
        dropPrefetch()
        backdropView?.let { layer ->
            (layer.parent as? ViewGroup)?.removeView(layer)
            layer.setImageDrawable(null)
        }
        backdropView = null
        backdropBitmap?.recycle()
        backdropBitmap = null
        backdropFrom = 0f
        openProgress = 0f
        dimTarget = DIM_ONLY
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val OPEN_MS = 300L
        const val CLOSE_MS = 250L
        /** Радиус размытия фона в пикселях экрана (как прежний RenderEffect). */
        const val BLUR_RADIUS = 28f
        const val ELEVATION = 24f
        const val DIM_WITH_BLUR = 0.35f
        const val DIM_ONLY = 0.6f
        const val BACKDROP_FADE_MS = 150L
        const val BACKDROP_TAG = "bm_backdrop"
        /** Сколько главный поток может ждать копию кадра перед первым кадром раскрытия (как раньше в худшем случае). */
        const val COPY_WAIT_MS = 50L
        /** Доля закрытия, с которой карточка и компактная плитка плавно меняются местами. */
        const val CROSSFADE_FROM = 0.6f

        /** 0 до [CROSSFADE_FROM], затем равномерно до 1 к концу анимации. */
        internal fun crossfade(t: Float) = ((t - CROSSFADE_FROM) / (1f - CROSSFADE_FROM)).coerceIn(0f, 1f)

        /**
         * RenderEffect (API 31+) — штатное размытие Android. На слабых устройствах (low-RAM)
         * и до API 31 используется только затемнение: без рывков и без блокировки UI.
         */
        fun blurAvailable(context: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            return manager?.isLowRamDevice != true
        }
    }
}
