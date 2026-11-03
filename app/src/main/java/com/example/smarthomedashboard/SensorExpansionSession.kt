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
        animate(source, target, 300L, EXPANDED_ELEVATION)
        current = source
        // Закрывающий тап по-прежнему распознаёт Activity; дети получают прокрутку.
        source.setOnTouchListener(null)
        stopTimer()
        collapseTask = Runnable { onTimeout() }.also { handler.postDelayed(it, 30000L) }
    }

    fun collapse(onClosed: (View) -> Unit) {
        val source = current ?: return
        val target = checkNotNull(original)
        animate(source, target, 250L, COLLAPSED_ELEVATION, onClosed)
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

    /** Тень меняется вместе с размером (0.9.2, этап 5): раньше она переключалась скачком в начале движения. */
    private fun animate(source: View, target: Bounds, durationMs: Long, elevation: Float, onClosed: ((View) -> Unit)? = null) {
        animations.remove(source)?.let(::cancel)
        val start = Bounds.of(source)
        val startElevation = source.elevation
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                source.layoutParams.width = (start.width + (target.width - start.width) * fraction).toInt()
                source.layoutParams.height = (start.height + (target.height - start.height) * fraction).toInt()
                source.x = start.x + (target.x - start.x) * fraction
                source.y = start.y + (target.y - start.y) * fraction
                source.elevation = startElevation + (elevation - startElevation) * fraction
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

    companion object {
        const val EXPANDED_ELEVATION = 20f
        /** Обычная тень сенсорной карточки (SensorCardFactory). */
        const val COLLAPSED_ELEVATION = 8f
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
