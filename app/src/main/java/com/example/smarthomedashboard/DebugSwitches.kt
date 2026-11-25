package com.example.smarthomedashboard

import android.content.Context
import androidx.core.content.edit

/**
 * Проверочные выключатели — файлы в папках `talk_debug`, `video_debug`, `tablet_debug` на общей памяти (замеры и разбор звука через
 * adb). Положить такой файл может не только владелец: adb, USB, а на Android 7–10 — чужое приложение с доступом к памяти; по нему панель
 * записала бы разговор или проиграла звук в домофон (аудит 25.11.2026, SEC-08). Поэтому файлы действуют, только пока человек сам
 * включил проверочный режим (Настройки → «О приложении» → «Сбои и отчёт», под PIN); через сутки он гаснет.
 */
internal object DebugSwitches {
    private const val PREFS = "debug_switches"
    private const val KEY_UNTIL = "until"
    const val HOURS = 24L
    @Volatile private var until = Long.MIN_VALUE
    internal var now: () -> Long = System::currentTimeMillis

    private fun load(context: Context): Long {
        if (until == Long.MIN_VALUE) until = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UNTIL, 0)
        return until
    }

    /** Проверочный режим включён (и часы не уходили назад дальше суток). */
    fun allowed(context: Context): Boolean = (load(context) - now()).let { it > 0 && it <= HOURS * 3_600_000 }

    fun set(context: Context, on: Boolean) {
        until = if (on) now() + HOURS * 3_600_000 else 0
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_UNTIL, until) }
    }

    internal fun resetForTests() { until = Long.MIN_VALUE; now = System::currentTimeMillis }
}
