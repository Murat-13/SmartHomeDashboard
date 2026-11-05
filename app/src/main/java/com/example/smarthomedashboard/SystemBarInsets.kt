package com.example.smarthomedashboard

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Android 15+ (targetSdk 35, 0.9.7): окна принудительно рисуются под системными полосами (edge-to-edge),
 * а `Configuration`/`displayMetrics` больше не вычитают полосы. Чтобы вид остался прежним:
 * - содержимое каждого экрана (`android.R.id.content`) получает отступы системных полос, выреза камеры
 *   и клавиатуры — так же, как раньше это делало окно само;
 * - под полосами рисуются их прежние цвета из темы (`statusBarColor`/`navigationBarColor`), которые
 *   Android 15 сам больше не применяет;
 * - раскладка панели считает размер окна по-старому ([legacyWindowSize]: без полосы навигации и выреза).
 * До Android 15 ничего не меняется: окно по-прежнему само не заходит под полосы.
 */
internal object SystemBarInsets {
    /** Регистрируется один раз в [SmartHomeApp]: срабатывает после onCreate каждого экрана. */
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) install(activity)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Отступы как у окна до edge-to-edge: полосы, вырез и клавиатура (снизу — что больше). */
    fun install(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        // Фон полос — слоем поверх фона окна (через окно: decorView сам переставляет свой фон);
        // виден и когда экран PIN прячет содержимое.
        val bars = BarsBackground(activity)
        val window = activity.window
        window.setBackgroundDrawable(LayerDrawable(listOfNotNull(window.decorView.background, bars).toTypedArray()))
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, max(system.bottom, ime.bottom))
            bars.update(system.left, system.top, system.right, system.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    /** Полосы прежних цветов темы: сверху — строка состояния, по остальным краям — навигация. */
    private class BarsBackground(activity: Activity) : Drawable() {
        private val statusPaint = Paint()
        private val navigationPaint = Paint()
        private var left = 0
        private var top = 0
        private var right = 0
        private var bottom = 0

        init {
            // Прозрачный цвет темы (так платформа задаёт полосы для Android 15) до 15 система
            // показывала чёрным — так и рисуем.
            statusPaint.color = themeColor(activity, android.R.attr.statusBarColor)
            navigationPaint.color = themeColor(activity, android.R.attr.navigationBarColor)
        }

        private fun themeColor(activity: Activity, attr: Int): Int {
            val value = TypedValue()
            val color = if (activity.theme.resolveAttribute(attr, value, true) &&
                value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) value.data else 0
            return if (Color.alpha(color) == 0) Color.BLACK else color
        }

        fun update(left: Int, top: Int, right: Int, bottom: Int) {
            if (left == this.left && top == this.top && right == this.right && bottom == this.bottom) return
            this.left = left; this.top = top; this.right = right; this.bottom = bottom
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (top > 0) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + top).toFloat(), statusPaint)
            if (bottom > 0) canvas.drawRect(b.left.toFloat(), (b.bottom - bottom).toFloat(), b.right.toFloat(), b.bottom.toFloat(), navigationPaint)
            if (left > 0) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + left).toFloat(), b.bottom.toFloat(), navigationPaint)
            if (right > 0) canvas.drawRect((b.right - right).toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), navigationPaint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Размер окна, как его давал `displayMetrics` до Android 15: без полосы навигации и выреза
     * (строка состояния у панели скрыта и в расчёт не входила). Нужен раскладке плиток и раскрытиям.
     */
    fun legacyWindowSize(activity: Activity): Pair<Int, Int> {
        val metrics = activity.resources.displayMetrics
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return metrics.widthPixels to metrics.heightPixels
        val window = activity.windowManager.currentWindowMetrics
        val inset = window.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.displayCutout())
        return (window.bounds.width() - inset.left - inset.right) to (window.bounds.height() - inset.top - inset.bottom)
    }
}

/** Класс приложения: только подключение [SystemBarInsets]; прочая логика остаётся в экранах. */
class SmartHomeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(SystemBarInsets.callbacks)
    }
}
