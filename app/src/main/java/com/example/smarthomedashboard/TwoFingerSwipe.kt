package com.example.smarthomedashboard

import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs

/**
 * Единый распознаватель навигационного жеста двумя пальцами по горизонтали (боковая панель, страницы).
 * Пока жест не распознан, касания идут плиткам как обычно. После распознавания вызывается
 * [Listener.onClaim]: владелец отменяет касания плиток (ACTION_CANCEL), чтобы нажатие кнопки
 * не превратилось в команду дому. Остаток жеста до отпускания всех пальцев тоже забирается.
 */
internal class TwoFingerSwipe(
    private val slop: Int,
    private val enabled: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** Направление: +1 — слева направо, -1 — справа налево. false — жест этому направлению не нужен. */
        fun onStart(direction: Int): Boolean
        fun onClaim()
        fun onDrag(dx: Float)
        fun onEnd(dx: Float, velocityX: Float)
        fun onCancel()
    }

    private enum class Phase { IDLE, TRACKING, CLAIMED, FINISHED, REJECTED }

    private var phase = Phase.IDLE
    private var startX = 0f
    private var startY = 0f
    private var velocity: VelocityTracker? = null

    /** Жест распознан и ещё не закончен: все события — ему. */
    val active get() = phase == Phase.CLAIMED || phase == Phase.FINISHED

    /** true — событие забрал жест, плиткам его не передавать. */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> reset()
            MotionEvent.ACTION_POINTER_DOWN -> when (phase) {
                Phase.IDLE -> if (event.pointerCount == 2 && enabled()) {
                    phase = Phase.TRACKING
                    startX = centerX(event)
                    startY = centerY(event)
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                Phase.TRACKING -> phase = Phase.REJECTED // третий палец — не навигация
                else -> Unit
            }
            MotionEvent.ACTION_MOVE -> when (phase) {
                Phase.TRACKING -> {
                    velocity?.addMovement(event)
                    val dx = centerX(event) - startX
                    val dy = centerY(event) - startY
                    if (abs(dy) > slop * 2 && abs(dy) > abs(dx)) phase = Phase.REJECTED
                    else if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        if (!listener.onStart(if (dx > 0) 1 else -1)) phase = Phase.REJECTED
                        else {
                            phase = Phase.CLAIMED
                            listener.onClaim()
                            listener.onDrag(dx)
                        }
                    }
                }
                Phase.CLAIMED -> {
                    velocity?.addMovement(event)
                    listener.onDrag(centerX(event) - startX)
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> when (phase) {
                Phase.CLAIMED -> finish(event)
                Phase.TRACKING -> phase = Phase.REJECTED
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                val consumed = phase == Phase.CLAIMED || phase == Phase.FINISHED
                if (phase == Phase.CLAIMED) finish(event)
                reset()
                return consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = phase == Phase.CLAIMED || phase == Phase.FINISHED
                if (phase == Phase.CLAIMED) listener.onCancel()
                reset()
                return consumed
            }
        }
        return phase == Phase.CLAIMED || phase == Phase.FINISHED
    }

    /** Первый поднятый палец завершает жест: доведение не ждёт второго. */
    private fun finish(event: MotionEvent) {
        val tracker = velocity
        tracker?.addMovement(event)
        tracker?.computeCurrentVelocity(1000)
        phase = Phase.FINISHED
        listener.onEnd(centerX(event) - startX, tracker?.xVelocity ?: 0f)
        tracker?.recycle()
        velocity = null
    }

    private fun reset() {
        phase = Phase.IDLE
        velocity?.recycle()
        velocity = null
    }

    private fun centerX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun centerY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }
}
