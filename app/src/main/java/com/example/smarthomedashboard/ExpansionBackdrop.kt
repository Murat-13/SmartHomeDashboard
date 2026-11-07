package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Снимок фона под раскрытой карточкой (0.9.14): один раз за раскрытие, уже размытый.
 * [capture] не блокирует: результат приходит в главный поток через [done]; null — снимка не будет
 * (тогда раскрытие идёт с одним затемнением). Получатель владеет bitmap и освобождает его сам.
 */
internal fun interface ExpansionBackdrop {
    /** [hole] — прямоугольник исходной плитки в координатах [panel]: на снимке закрашивается фоном (плитка скрыта). */
    fun capture(panel: View, hole: Rect, done: (Bitmap?) -> Unit)
}

/**
 * PixelCopy окна (API 26+) — копия последнего показанного кадра, в том числе TextureView с видео домофона
 * (View.draw() видео не рисует). Область панели копируется сразу в уменьшенный bitmap ([SCALE]), размывается
 * на фоновом потоке по радиусу, эквивалентному прежнему RenderEffect ([BatteryModernExpansion.BLUR_RADIUS]).
 */
internal class PixelCopyBackdrop(private val main: Handler) : ExpansionBackdrop {
    override fun capture(panel: View, hole: Rect, done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return done(null)
        Api26.capture(panel, hole, main, done)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun capture(panel: View, hole: Rect, main: Handler, done: (Bitmap?) -> Unit) {
            val window = panel.context.findActivity()?.window ?: return done(null)
            val w = panel.width
            val h = panel.height
            if (w <= 0 || h <= 0 || !panel.isAttachedToWindow) return done(null)
            val location = IntArray(2)
            panel.getLocationInWindow(location)
            val source = Rect(location[0], location[1], location[0] + w, location[1] + h)
            val bitmap = try {
                Bitmap.createBitmap(max(1, w / SCALE), max(1, h / SCALE), Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return done(null)
            }
            val background = backgroundColor(panel)
            val scale = bitmap.width / w.toFloat()
            val scaledHole = RectF(hole.left * scale, hole.top * scale, hole.right * scale, hole.bottom * scale)
            try {
                PixelCopy.request(window, source, bitmap, { result ->
                    if (result != PixelCopy.SUCCESS) {
                        bitmap.recycle()
                        done(null)
                        return@request
                    }
                    WORKER.execute {
                        val ok = runCatching {
                            Canvas(bitmap).drawRect(scaledHole, Paint().apply { color = background })
                            BoxBlur.blur(bitmap, BoxBlur.boxRadiusFor(BatteryModernExpansion.BLUR_RADIUS, scale))
                        }.isSuccess
                        main.post {
                            if (ok) done(bitmap) else {
                                bitmap.recycle()
                                done(null)
                            }
                        }
                    }
                }, main)
            } catch (_: IllegalArgumentException) {
                // Окно без поверхности (экран гаснет, Activity закрывается) — без размытия.
                bitmap.recycle()
                done(null)
            }
        }
    }

    companion object {
        /** Снимок в 1/4 размера: размытый фон не теряет деталей, а копия и размытие дёшевы (2000×1200 → 500×300). */
        const val SCALE = 4
        private val WORKER: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "expansion-backdrop").apply { isDaemon = true }
        }

        /** Цвет под панелью: первый сплошной фон панели или её родителей (у главного экрана — фон корня). */
        fun backgroundColor(view: View): Int {
            var current: View? = view
            while (current != null) {
                (current.background as? ColorDrawable)?.let { if (Color.alpha(it.color) == 255) return it.color }
                current = current.parent as? View
            }
            return Color.BLACK
        }

        private tailrec fun Context.findActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}

/**
 * Быстрое размытие уменьшенного bitmap: три прохода скользящего box-фильтра по строкам и столбцам —
 * приближение гауссова размытия (дисперсия трёх проходов радиуса r — r(r+1)). Края — повтор крайнего пикселя,
 * как Shader.TileMode.CLAMP у прежнего RenderEffect.
 */
internal object BoxBlur {
    private const val PASSES = 3

    /**
     * Радиус box-фильтра для радиуса RenderEffect [renderRadius] (пиксели экрана) на bitmap масштаба [scale]:
     * RenderEffect переводит радиус в сигму как 0,57735·r + 0,5; три прохода дают дисперсию r(r+1) — берётся ближайший r.
     * Для 28 на 1/4: сигма 16,7 → 4,2 → r = 4.
     */
    fun boxRadiusFor(renderRadius: Float, scale: Float): Int {
        if (renderRadius <= 0f || scale <= 0f) return 0
        val sigma = (0.57735f * renderRadius + 0.5f) * scale
        val variance = sigma * sigma
        var best = 1
        for (r in 1..64) if (abs(r * (r + 1) - variance) < abs(best * (best + 1) - variance)) best = r
        return best
    }

    fun blur(bitmap: Bitmap, radius: Int) {
        if (radius <= 0) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        val buffer = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(PASSES) {
            pass(pixels, buffer, lines = h, length = w, along = 1, across = w, radius = radius)
            pass(buffer, pixels, lines = w, length = h, along = w, across = 1, radius = radius)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** Один проход вдоль линий: [along] — шаг внутри линии, [across] — шаг между линиями. */
    private fun pass(src: IntArray, dst: IntArray, lines: Int, length: Int, along: Int, across: Int, radius: Int) {
        val div = 2 * radius + 1
        val last = length - 1
        for (line in 0 until lines) {
            val base = line * across
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[base + i.coerceIn(0, last) * along]
                a += p ushr 24
                r += (p shr 16) and 0xff
                g += (p shr 8) and 0xff
                b += p and 0xff
            }
            for (i in 0..last) {
                dst[base + i * along] = ((a.toFloat() / div).roundToInt() shl 24) or
                    ((r.toFloat() / div).roundToInt() shl 16) or
                    ((g.toFloat() / div).roundToInt() shl 8) or
                    (b.toFloat() / div).roundToInt()
                val out = src[base + (i - radius).coerceIn(0, last) * along]
                val enter = src[base + (i + radius + 1).coerceIn(0, last) * along]
                a += (enter ushr 24) - (out ushr 24)
                r += ((enter shr 16) and 0xff) - ((out shr 16) and 0xff)
                g += ((enter shr 8) and 0xff) - ((out shr 8) and 0xff)
                b += (enter and 0xff) - (out and 0xff)
            }
        }
    }
}
