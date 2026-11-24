package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.io.File

/**
 * Панель остывает сама, когда аккумулятор слишком горячий (docs/setup-tz.md, часть 4, решение 5): яркость панели на четверть
 * ниже. Действует только на окно панели: звонок, запись, разговор и видео не меняются, настройки Android — тоже. Частота
 * экрана 60 Гц не делается: по замеру на планшете ничего не даёт. Здесь же — сдвиг картинки для экранов OLED (по выбору).
 */
internal object PanelCooling {
    private const val SHARE = 0.75f
    private const val LOWEST = 0.02f
    private const val MAX_BRIGHTNESS = 255f

    /** Яркость окна панели: как в Android или на четверть ниже. */
    fun brightness(context: Context, cooling: Boolean): Float {
        val full = debug(context, "full")
        if (!cooling) return if (full) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (full) return SHARE
        val system = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
            catch (_: Settings.SettingNotFoundException) { return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        return (system / MAX_BRIGHTNESS * SHARE).coerceIn(LOWEST, 1f)
    }

    /** Проверочные выключатели для замера: файлы `tablet_debug/dim` (на четверть ниже) и `full` (полная яркость) в папке приложения. */
    fun debug(context: Context, name: String) = try { File(context.getExternalFilesDir(null), "tablet_debug/$name").exists() }
        catch (_: RuntimeException) { false }

    // ---- OLED ----

    /** Места картинки по кругу, пиксели: не дальше [RADIUS] от обычного. */
    private val SHIFTS = listOf(0 to 0, 2 to 0, 4 to 2, 2 to 4, 0 to 2, -2 to 4, -4 to 2, -2 to 0, -4 to -2, -2 to -4, 0 to -2, 2 to -4, 4 to -2)
    internal const val RADIUS = 4
    const val SHIFT_MINUTES = 3

    fun shift(step: Int): Pair<Int, Int> = SHIFTS[Math.floorMod(step, SHIFTS.size)]

    /** Сдвинуть содержимое окна [activity]; [step] меньше нуля — на место. */
    fun applyShift(activity: Activity, step: Int) {
        val root = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) ?: return
        val (x, y) = if (step < 0) 0 to 0 else shift(step)
        if (root.translationX != x.toFloat()) root.translationX = x.toFloat()
        if (root.translationY != y.toFloat()) root.translationY = y.toFloat()
    }
}

/** Заставка Android, появившаяся поверх панели: повод для совета на экране «Планшет». */
internal object PanelDream {
    private const val JUST_MS = 3_000L
    private var leftAt = 0L

    /** Панель на экране. */
    var shown = false
        set(value) { if (field && !value) leftAt = android.os.SystemClock.elapsedRealtime(); field = value }

    fun register(app: android.app.Application) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (shown || android.os.SystemClock.elapsedRealtime() - leftAt < JUST_MS) TabletWatch.dreamOverPanel(context)
            }
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(app, receiver, android.content.IntentFilter(android.content.Intent.ACTION_DREAMING_STARTED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: RuntimeException) {}
    }
}
