package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/** Карточка, которую раскрытие показывает поверх панели и освобождает при закрытии. */
internal interface ModernOverlayCard {
    fun dispose()
}

/**
 * Раскрытие одной Modern-плитки (АКБ, Электропитание) поверх панели. Карточка — отдельная View в [overlay];
 * компактная плитка остаётся на месте (скрыта) и после закрытия снова видна с прежними
 * размерами и координатами. Анимация — только transform (без relayout содержимого на каждом кадре).
 *
 * Таймер: [timeoutMs] после последнего взаимодействия пользователя ([touch]). События HA его
 * не трогают: отрисовка данных не вызывает методы этого класса. [pause] останавливает отсчёт.
 */
internal class BatteryModernExpansion(
    private val handler: Handler,
    private val overlay: () -> FrameLayout?,
    private val blurTarget: () -> View?,
    private val blurSupported: Boolean,
    private val timeoutMs: Long = TIMEOUT_MS
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
    private var blurred: View? = null
    private val timeout = Runnable {
        collapseAt = null
        collapse()
    }

    fun expand(id: String, from: View, expandedCard: View, target: Bounds) {
        clear()
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
        host.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        expandedCard.layoutParams = FrameLayout.LayoutParams(target.width, target.height)
        expandedCard.elevation = 24f
        expandedCard.pivotX = 0f
        expandedCard.pivotY = 0f
        host.addView(expandedCard)
        from.alpha = 0f

        val sx = start.width / target.width.toFloat().coerceAtLeast(1f)
        val sy = start.height / target.height.toFloat().coerceAtLeast(1f)
        run(OPEN_MS, onEnd = null) { t ->
            expandedCard.x = start.x + (target.x - start.x) * t
            expandedCard.y = start.y + (target.y - start.y) * t
            expandedCard.scaleX = sx + (1f - sx) * t
            expandedCard.scaleY = sy + (1f - sy) * t
            expandedCard.alpha = 0.6f + 0.4f * t
            dim.alpha = dimAlpha() * t
            blur(t)
        }
        scheduleTimer()
    }

    /** Плавно возвращает карточку к сохранённым размерам/координатам и удаляет overlay. */
    fun collapse() {
        val expandedCard = card ?: return
        if (closing) return
        val start = checkNotNull(original)
        closing = true
        stopTimer()
        val target = Bounds(expandedCard.x, expandedCard.y, expandedCard.layoutParams.width, expandedCard.layoutParams.height)
        val sx = start.width / target.width.toFloat().coerceAtLeast(1f)
        val sy = start.height / target.height.toFloat().coerceAtLeast(1f)
        val dim = scrim
        val dimStart = dim?.alpha ?: 0f
        run(CLOSE_MS, onEnd = ::clear) { t ->
            expandedCard.x = target.x + (start.x - target.x) * t
            expandedCard.y = target.y + (start.y - target.y) * t
            expandedCard.scaleX = 1f + (sx - 1f) * t
            expandedCard.scaleY = 1f + (sy - 1f) * t
            expandedCard.alpha = 1f - 0.4f * t
            dim?.alpha = dimStart * (1f - t)
            blur(1f - t)
        }
    }

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

    /** Немедленное освобождение: анимация, таймер, overlay, blur; компактная плитка снова видна. */
    fun clear() {
        stopTimer()
        animator?.let {
            animator = null
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        clearBlur()
        card?.let { view ->
            (view as? ModernOverlayCard)?.dispose()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        scrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        source?.alpha = 1f
        card = null
        scrim = null
        source = null
        original = null
        tileId = null
        closing = false
        paused = false
    }

    private fun scheduleTimer() {
        stopTimer()
        if (paused) return
        collapseAt = android.os.SystemClock.uptimeMillis() + timeoutMs
        handler.postDelayed(timeout, timeoutMs)
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

    private fun dimAlpha() = if (blurSupported) 0.35f else 0.6f

    private fun blur(fraction: Float) {
        if (!blurSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val target = blurTarget() ?: return
        blurred = target
        Blur.apply(target, BLUR_RADIUS * fraction)
    }

    private fun clearBlur() {
        val target = blurred ?: return
        blurred = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Blur.apply(target, 0f)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Blur {
        fun apply(view: View, radius: Float) {
            view.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val OPEN_MS = 300L
        const val CLOSE_MS = 250L
        const val BLUR_RADIUS = 28f

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
