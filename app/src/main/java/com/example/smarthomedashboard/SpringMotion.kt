package com.example.smarthomedashboard

import android.animation.ValueAnimator
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Пружинное доведение для жестов «за пальцем» (боковая панель, страницы) без отдельной библиотеки.
 * Почти критическое затухание: мягкая остановка с едва заметным перелётом, без раскачки.
 */
internal object SpringMotion {
    const val STIFFNESS = 600f
    const val DAMPING_RATIO = 0.9f

    data class State(val position: Float, val velocity: Float)

    /** Один шаг полуявного метода Эйлера; dt — секунды. */
    fun step(state: State, target: Float, dt: Float, stiffness: Float = STIFFNESS, dampingRatio: Float = DAMPING_RATIO): State {
        val damping = 2f * dampingRatio * sqrt(stiffness)
        val acceleration = -stiffness * (state.position - target) - damping * state.velocity
        val velocity = state.velocity + acceleration * dt
        return State(state.position + velocity * dt, velocity)
    }

    fun settled(state: State, target: Float) = abs(state.position - target) < 0.5f && abs(state.velocity) < 5f

    /**
     * Анимация по кадрам дисплея. Если системные анимации отключены (масштаб 0), значение ставится сразу.
     */
    class Runner(private val apply: (Float) -> Unit, private val done: () -> Unit = {}) : Choreographer.FrameCallback {
        private var state = State(0f, 0f)
        private var target = 0f
        private var lastFrame = 0L
        var running = false
            private set

        fun start(from: Float, to: Float, velocity: Float) {
            cancel()
            target = to
            if (!animationsEnabled()) {
                apply(to)
                done()
                return
            }
            state = State(from, velocity)
            lastFrame = 0L
            running = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun cancel() {
            if (running) Choreographer.getInstance().removeFrameCallback(this)
            running = false
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrame = frameTimeNanos
            state = step(state, target, dt)
            if (settled(state, target)) {
                running = false
                apply(target)
                done()
                return
            }
            apply(state.position)
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun animationsEnabled() =
            android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    }
}
