package com.example.smarthomedashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Состояние раскрытия одного экрана; без данных HA, построения содержимого и жестов окна. */
internal class SensorExpansionSession(private val handler: Handler) {
    data class Bounds(val width: Int, val height: Int, val x: Float, val y: Float) {
        companion object {
            fun of(view: View) = Bounds(view.layoutParams.width, view.layoutParams.height, view.x, view.y)
        }
    }

    var current: View? = null
        private set
    var original: Bounds? = null
        private set
    private val animations = mutableMapOf<View, ValueAnimator>()
    private var collapseTask: Runnable? = null

    fun expand(source: View, target: Bounds, onTimeout: () -> Unit) {
        original = Bounds.of(source)
        source.bringToFront()
        source.elevation = 20f
        animate(source, target, 300L)
        current = source
        // Закрывающий тап по-прежнему распознаёт Activity; дети получают прокрутку.
        source.setOnTouchListener(null)
        stopTimer()
        collapseTask = Runnable { onTimeout() }.also { handler.postDelayed(it, 30000L) }
    }

    fun collapse(onClosed: (View) -> Unit) {
        val source = current ?: return
        val target = checkNotNull(original)
        source.elevation = 8f
        animate(source, target, 250L, onClosed)
        // Следующая карточка может открыться до конца анимации. Target уже принадлежит переходу.
        current = null
        original = null
        stopTimer()
    }

    fun touch() {
        collapseTask?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, 30000L)
        }
    }

    fun clear() {
        stopTimer()
        // cancel вызывает onAnimationEnd: сначала обезвреживаем все callbacks старых Views.
        animations.values.toList().forEach(::cancel)
        animations.clear()
        current?.setOnTouchListener(null)
        current = null
        original = null
    }

    private fun animate(source: View, target: Bounds, durationMs: Long, onClosed: ((View) -> Unit)? = null) {
        animations.remove(source)?.let(::cancel)
        val start = Bounds.of(source)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                source.layoutParams.width = (start.width + (target.width - start.width) * fraction).toInt()
                source.layoutParams.height = (start.height + (target.height - start.height) * fraction).toInt()
                source.x = start.x + (target.x - start.x) * fraction
                source.y = start.y + (target.y - start.y) * fraction
                source.requestLayout()
            }
        }
        animations[source] = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animations[source] !== animator) return
                onClosed?.invoke(source)
                if (animations[source] === animator) animations.remove(source)
                animator.removeAllUpdateListeners()
                animator.removeAllListeners()
            }
        })
        animator.start()
    }

    private fun stopTimer() {
        collapseTask?.let { handler.removeCallbacks(it) }
        collapseTask = null
    }

    private fun cancel(animator: ValueAnimator) {
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()
    }
}
