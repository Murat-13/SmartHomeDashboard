package com.example.smarthomedashboard

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Только жест редактирования одной плитки; обычные нажатия остаются у её владельца. */
internal class TileEditDragHandler(
    view: View,
    private val handler: Handler,
    private val screenW: Int,
    private val screenH: Int,
    onTap: () -> Unit,
    onDrop: (Int, Int) -> Unit
) {
    private var view: View? = view
    private var onTap: (() -> Unit)? = onTap
    private var onDrop: ((Int, Int) -> Unit)? = onDrop
    private var pending: Runnable? = null
    private var active = false
    private var dragging = false
    private var moved = false
    private var startX = 0f
    private var startY = 0f
    private var originalX = 0f
    private var originalY = 0f
    private var originalAlpha = 1f
    private var originalElevation = 0f

    fun onTouch(event: MotionEvent): Boolean {
        val target = view ?: return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                active = true
                startX = event.rawX
                startY = event.rawY
                originalX = target.x
                originalY = target.y
                originalAlpha = target.alpha
                originalElevation = target.elevation
                pending = Runnable {
                    if (active) {
                        dragging = true
                        target.alpha = 0.6f
                        target.elevation = 20f
                    }
                }.also { handler.postDelayed(it, 500L) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                if (abs(event.rawX - startX) > 10 || abs(event.rawY - startY) > 10) {
                    moved = true
                    stopPending()
                }
                if (dragging) {
                    val (x, y) = TilePlacement.position(
                        originalX + event.rawX - startX, originalY + event.rawY - startY,
                        target.width, target.height, screenW, screenH
                    )
                    target.x = x
                    target.y = y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!active) return true
                active = false
                target.performClick()
                stopPending()
                if (dragging && moved) {
                    target.alpha = 1f
                    target.elevation = 8f
                    onDrop?.invoke(target.x.toInt(), target.y.toInt())
                } else if (!moved) onTap?.invoke()
                dragging = false
                moved = false
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    private fun stopPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    /** Отмена не записывает координаты и возвращает фактический вид начала жеста. */
    private fun cancel() {
        stopPending()
        if (active) view?.let {
            it.x = originalX
            it.y = originalY
            it.alpha = originalAlpha
            it.elevation = originalElevation
        }
        active = false
        dragging = false
        moved = false
    }

    /** Старый listener после замены дерева больше не может сохранить положение или открыть экран. */
    fun dispose() {
        cancel()
        view = null
        onTap = null
        onDrop = null
    }
}
