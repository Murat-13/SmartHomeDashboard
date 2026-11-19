package com.example.smarthomedashboard

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Увеличение видео пальцами (раскрытый «Домофон» и архив): масштаб 1–[MAX]× и сдвиг картинки. Чистая математика:
 * видео размером [width]×[height] рисуется с масштабом [scale] от левого верхнего угла и сдвигом ([x], [y]);
 * края увеличенной картинки не уходят внутрь окна (чёрных полос от сдвига не бывает).
 */
internal data class VideoZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed get() = scale > 1.01f

    /** Два пальца: точка между пальцами остаётся под пальцами; [dx]/[dy] — сдвиг этой точки. */
    fun pinch(focusX: Float, focusY: Float, factor: Float, dx: Float, dy: Float, width: Float, height: Float): VideoZoom {
        val next = (scale * factor).coerceIn(1f, MAX)
        val k = next / scale
        return VideoZoom(next, focusX - (focusX - x) * k + dx, focusY - (focusY - y) * k + dy).clamp(width, height)
    }

    fun pan(dx: Float, dy: Float, width: Float, height: Float) = copy(x = x + dx, y = y + dy).clamp(width, height)

    /** Двойное касание: увеличить место касания до [DOUBLE_TAP]× или вернуть обычный вид. */
    fun toggle(atX: Float, atY: Float, width: Float, height: Float): VideoZoom =
        if (zoomed) VideoZoom() else VideoZoom().pinch(atX, atY, DOUBLE_TAP, 0f, 0f, width, height)

    fun clamp(width: Float, height: Float): VideoZoom {
        if (scale <= 1.001f || width <= 0f || height <= 0f) return VideoZoom()
        return VideoZoom(scale, x.coerceIn(width * (1 - scale), 0f), y.coerceIn(height * (1 - scale), 0f))
    }

    /** Подпись для владельца: «×2,5». */
    val title: String get() = "×" + String.format(java.util.Locale("ru"), "%.1f", scale).removeSuffix(",0")

    companion object {
        const val MAX = 5f
        const val DOUBLE_TAP = 2.5f
    }
}

/**
 * Жесты увеличения для обычной View: два пальца — масштаб, один палец по увеличенной картинке — сдвиг, двойное
 * касание — увеличить/вернуть, одиночное касание по неувеличенной — [onTap] (раскрытый домофон сворачивается).
 * Результат применяется к [target] (масштаб от левого верхнего угла); обрезку даёт родитель.
 */
internal class ZoomGestures(context: Context, private val target: View, private val onTap: () -> Unit,
                            private val onChange: (VideoZoom) -> Unit = {}) {
    var zoom = VideoZoom()
        private set
    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastX = 0f
        private var lastY = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true; lastX = d.focusX; lastY = d.focusY; return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            set(zoom.pinch(d.focusX, d.focusY, d.scaleFactor, d.focusX - lastX, d.focusY - lastY, w(), h()))
            lastX = d.focusX; lastY = d.focusY
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling || e2.pointerCount > 1 || !zoom.zoomed) return false
            set(zoom.pan(-distanceX, -distanceY, w(), h()))
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!zoom.zoomed) onTap()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            set(zoom.toggle(e.x, e.y, w(), h()))
            return true
        }
    })

    private fun w() = target.width.toFloat()
    private fun h() = target.height.toFloat()

    /** Координаты события — в системе [target] без увеличения (обычно родитель того же размера). */
    fun onTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return true
    }

    fun reset() = set(VideoZoom())

    private fun set(next: VideoZoom) {
        if (next == zoom) return
        zoom = next
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = next.scale
        target.scaleY = next.scale
        target.translationX = next.x
        target.translationY = next.y
        onChange(next)
    }
}
