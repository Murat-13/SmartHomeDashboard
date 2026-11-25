package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Экраны поверх панели (архив, настройки, редакторы) ведут себя как панель (решение владельца 23.11.2026: «во время
 * настроек и просмотра архива [должен] работать тот же режим, что и во время штатной работы. Если нет касания в течение
 * 5 мин, то программа возвращается в исходное положение»):
 * - экран не гаснет по таймеру Android (раньше архив быстро темнел) — как у панели, `FLAG_KEEP_SCREEN_ON`;
 * - нет касаний столько же, сколько панель ждёт до затемнения (`screen_timeout_minutes`, по умолчанию 5 мин), —
 *   возврат на панель; дальше всё как обычно (затемнение по датчику движения).
 * Экран может попросить подождать ([Busy]) — архив, пока играет видео. Заставка и первая настройка — не трогаются.
 *
 * Всплывающие окна экрана (диалоги) — свои окна, экран касаний в них не видит: пять минут правки только в них закрывали редактор с
 * потерей черновика (аудит 25.11.2026, STAB-06). Окно открылось — у экрана пропал фокус; тогда в него ставится [Touches].
 */
internal object PanelIdle {
    /** Экран занят и без касаний (идёт видео) — на панель пока не возвращаться. */
    interface Busy { val idleBusy: Boolean }

    internal var timeoutMs: (Context) -> Long = { c ->
        c.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).getInt("screen_timeout_minutes", 5)
            .coerceAtLeast(1) * 60_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    /** Экран, на котором идёт отсчёт; слабая ссылка — экран не держится в памяти дольше своей жизни. */
    private var current: java.lang.ref.WeakReference<Activity>? = null
    private val back = Runnable { timeUp() }

    /** Экраны, которые ведут себя как панель: все, кроме самой панели, заставки и первой настройки. */
    fun managed(activity: Activity) = activity !is MainActivity && activity !is SplashActivity && activity !is SetupActivity && activity !is SafeStartActivity

    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (!managed(activity)) return
            current = java.lang.ref.WeakReference(activity)
            activity.window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (w.callback !is Watch) w.callback?.let { w.callback = Watch(it) }
            }
            touched()
        }
        override fun onActivityPaused(activity: Activity) {
            if (current?.get() !== activity) return
            handler.removeCallbacks(back)
            current = null
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Касание или кнопка — отсчёт заново. */
    fun touched() {
        val activity = current?.get() ?: return
        handler.removeCallbacks(back)
        handler.postDelayed(back, timeoutMs(activity))
    }

    /** Экран потерял фокус ввода: поверх него могло открыться своё окно (Android 10+; раньше окна приложения не перечислить). */
    private fun coveredByWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) handler.post { current?.get()?.let { watchWindows(it) } }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun watchWindows(activity: Activity) {
        val own = activity.window?.decorView
        for (root in WindowInspector.getGlobalWindowViews()) {
            // Только окна-диалоги: их корень — FrameLayout системы; выпадающие списки и прочее не трогаем.
            if (root === own || root !is FrameLayout || root.javaClass.simpleName != "DecorView" || activityOf(root) !== activity) continue
            if (root.findViewWithTag<View>(TOUCHES_TAG) == null) root.addView(Touches(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        touched()
    }

    private fun activityOf(view: View): Activity? {
        var context: Context? = view.context
        while (context is android.content.ContextWrapper) { if (context is Activity) return context; context = context.baseContext }
        return null
    }

    /** Прозрачный слой поверх содержимого окна: отмечает касание и отдаёт его дальше. Места в окне не занимает. */
    @SuppressLint("ViewConstructor")
    private class Touches(context: Context) : View(context) {
        init { tag = TOUCHES_TAG; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(exact(widthMeasureSpec), exact(heightMeasureSpec))
        private fun exact(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touched()
            return false
        }
    }

    private fun timeUp() {
        val activity = current?.get() ?: return
        if ((activity as? Busy)?.idleBusy == true) { touched(); return }
        Log.i(TAG, "Нет касаний ${timeoutMs(activity) / 60_000} мин — возврат на панель")
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Окно экрана: всё как было, касания и кнопки отмечаются. */
    private class Watch(private val inner: Window.Callback) : Window.Callback by inner {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touched()
            return inner.dispatchTouchEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) touched()
            return inner.dispatchKeyEvent(event)
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            inner.onWindowFocusChanged(hasFocus)
            if (!hasFocus) coveredByWindow()
        }
    }

    private const val TAG = "PanelIdle"
    internal const val TOUCHES_TAG = "panel_idle_touches"
}
