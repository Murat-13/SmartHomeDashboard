package com.example.smarthomedashboard

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** Перехват жеста раскрытой карточки. Один экземпляр на дерево панели. */
internal class SensorWindowTouchHandler(
    private val expanded: () -> View?,
    private val attached: (View) -> Boolean,
    private val touch: () -> Unit,
    private val clearExpansion: () -> Unit,
    private val collapse: () -> Unit,
    private val sensorTouchSlop: Int
) {
    private var sensorWindowGesture: SensorWindowGesture? = null
    private var consumeSensorWindowGesture = false

    private class SensorWindowGesture(
        val source: View,
        val down: MotionEvent,
        val startX: Float,
        val startY: Float,
        val inside: Boolean
    ) {
        var moved = false
        var forwarded = false
    }

    /** null передаёт событие штатному диспетчеру окна. */
    fun dispatch(event: MotionEvent): Boolean? {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancelSensorWindowGesture()
            consumeSensorWindowGesture = false
            val source = expanded() ?: return null
            val location = IntArray(2)
            source.getLocationInWindow(location)
            val inside = event.x >= location[0] && event.x < location[0] + source.width &&
                event.y >= location[1] && event.y < location[1] + source.height
            sensorWindowGesture = SensorWindowGesture(source, sensorLocalEvent(event, source), event.x, event.y, inside)
            consumeSensorWindowGesture = true
            if (inside) touch()
            return true
        }
        if (!consumeSensorWindowGesture) return null

        val gesture = sensorWindowGesture
        if (gesture != null) {
            val current = expanded() === gesture.source && attached(gesture.source)
            if (!current || action == MotionEvent.ACTION_CANCEL || event.pointerCount > 1 ||
                action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                // Остаток жеста остаётся поглощённым даже после удаления/закрытия исходной View.
                cancelSensorWindowGesture()
                if (!current && expanded() === gesture.source) clearExpansion()
            } else {
                fun record(x: Float, y: Float) {
                    val dx = x - gesture.startX
                    val dy = y - gesture.startY
                    if (dx * dx + dy * dy > sensorTouchSlop * sensorTouchSlop) gesture.moved = true
                }
                for (i in 0 until event.historySize) record(event.getHistoricalX(i), event.getHistoricalY(i))
                record(event.x, event.y)
                if (gesture.inside) touch()
                if (action == MotionEvent.ACTION_MOVE && gesture.inside && gesture.moved) {
                    // Не передаём DOWN дочерним click/long-click до распознавания движения.
                    // При прокрутке обычная диспетчеризация дерева сама выбирает вложенного получателя.
                    if (!gesture.forwarded) {
                        gesture.forwarded = true
                        gesture.source.dispatchTouchEvent(gesture.down)
                        clearSensorChildPresses(gesture.source)
                    }
                    val local = sensorLocalEvent(event, gesture.source)
                    try { gesture.source.dispatchTouchEvent(local) } finally { local.recycle() }
                    clearSensorChildPresses(gesture.source)
                }
                if (action == MotionEvent.ACTION_UP) {
                    if (gesture.forwarded) {
                        // UP нужен прокрутке для штатного завершения и fling, но не дочернему click.
                        clearSensorChildPresses(gesture.source)
                        val local = sensorLocalEvent(event, gesture.source)
                        try { gesture.source.dispatchTouchEvent(local) } finally { local.recycle() }
                        gesture.forwarded = false
                        clearSensorChildPresses(gesture.source)
                    }
                    val tap = !gesture.moved &&
                        event.eventTime - gesture.down.downTime < android.view.ViewConfiguration.getLongPressTimeout()
                    cancelSensorWindowGesture()
                    if (tap) collapse()
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelSensorWindowGesture()
            consumeSensorWindowGesture = false
        }
        return true
    }

    private fun sensorLocalEvent(event: MotionEvent, source: View): MotionEvent {
        val location = IntArray(2)
        source.getLocationInWindow(location)
        return MotionEvent.obtain(event).apply { offsetLocation(-location[0].toFloat(), -location[1].toFloat()) }
    }

    private fun clearSensorChildPresses(view: View) {
        view.cancelLongPress()
        view.isPressed = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearSensorChildPresses(view.getChildAt(i))
        } else {
            view.cancelPendingInputEvents()
        }
    }

    private fun cancelSensorWindowGesture() {
        val gesture = sensorWindowGesture ?: return
        sensorWindowGesture = null
        if (gesture.forwarded) {
            val cancel = MotionEvent.obtain(gesture.down).apply { action = MotionEvent.ACTION_CANCEL }
            try { gesture.source.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            clearSensorChildPresses(gesture.source)
        }
        gesture.down.recycle()
    }

    fun clear() = cancelSensorWindowGesture()
}
