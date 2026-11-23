package com.example.smarthomedashboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager

/**
 * Экраны поверх панели (архив, настройки, редакторы) ведут себя как панель (решение владельца 23.11.2026: «во время
 * настроек и просмотра архива [должен] работать тот же режим, что и во время штатной работы. Если нет касания в течение
 * 5 мин, то программа возвращается в исходное положение»):
 * - экран не гаснет по таймеру Android (раньше архив быстро темнел) — как у панели, `FLAG_KEEP_SCREEN_ON`;
 * - нет касаний столько же, сколько панель ждёт до затемнения (`screen_timeout_minutes`, по умолчанию 5 мин), —
 *   возврат на панель; дальше всё как обычно (затемнение по датчику движения).
 * Экран может попросить подождать ([Busy]) — архив, пока играет видео. Заставка и первая настройка — не трогаются.
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
    fun managed(activity: Activity) = activity !is MainActivity && activity !is SplashActivity && activity !is SetupActivity

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
    }

    private const val TAG = "PanelIdle"
}
