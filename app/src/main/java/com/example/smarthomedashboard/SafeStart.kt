package com.example.smarthomedashboard

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * Безопасный запуск (docs/reliability.md). Три сбоя подряд сразу после запуска — панель открывается без видео, записи, размытия и
 * голоса ([Mode.SAFE]): планшет на стене не мигает сбоями, а показывает плитки. Падает и так — экран с выходом: обновление,
 * настройки, копия ([Mode.RESCUE], [SafeStartActivity]). Прожила два минуты — сбои забыты; час без сбоев в безопасном режиме —
 * следующий запуск обычный. Откатиться на прежнюю версию Android не даёт, поэтому выход — починка и быстрое обновление.
 */
internal object SafeStart {
    enum class Mode { NORMAL, SAFE, RESCUE }

    @Volatile var mode = Mode.NORMAL
        private set
    /** Видео, запись, голос и размытие выключены. */
    val active get() = mode != Mode.NORMAL

    internal const val PREFS = "safe_start"
    private const val KEY_SAFE = "safe"
    private const val KEY_QUICK = "quick"
    private const val KEY_START = "start"
    private const val KEY_OK = "ok"
    private const val KEY_COUNTED = "counted"

    /** Сбой раньше этого — «сразу после запуска». */
    const val SURVIVE_MS = 2 * 60_000L
    internal const val WINDOW_MS = 15 * 60_000L
    internal const val CRASHES = 3
    internal const val SAFE_CRASHES = 2
    internal const val RECOVER_MS = 60 * 60_000L
    private const val RESTART_MS = 3_000L
    private const val RESTARTS = 5

    internal var now: () -> Long = System::currentTimeMillis
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var survived = false
    private var startedAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun quick(context: Context): List<Long> = prefs(context).getString(KEY_QUICK, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        .filter { now() - it in 0..WINDOW_MS }

    /** Начало процесса: решить, каким будет запуск. До видео, записи и экранов. */
    fun processStarted(context: Context) {
        val p = prefs(context)
        val t = now()
        var crashes = quick(context)
        // Прошлый запуск не прожил двух минут, а обработчик сбоя ничего не записал: сбой вне Java или «не отвечает».
        val last = p.getLong(KEY_START, 0)
        if (last > 0 && !p.getBoolean(KEY_OK, true) && !p.getBoolean(KEY_COUNTED, false))
            unseenCrash(context, last)?.takeIf { t - it in 0..WINDOW_MS }?.let { crashes = crashes + it }
        val safe = p.getBoolean(KEY_SAFE, false)
        mode = when {
            safe && crashes.size >= SAFE_CRASHES -> Mode.RESCUE
            safe -> Mode.SAFE
            crashes.size >= CRASHES -> Mode.SAFE
            else -> Mode.NORMAL
        }
        if (mode == Mode.SAFE && !safe) crashes = emptyList()
        survived = false
        startedAt = t
        p.edit {
            putBoolean(KEY_SAFE, mode != Mode.NORMAL)
            putString(KEY_QUICK, crashes.joinToString(","))
            putLong(KEY_START, t); putBoolean(KEY_OK, false); putBoolean(KEY_COUNTED, false)
        }
        if (mode != Mode.NORMAL) Log.w(TAG, "Безопасный запуск: $mode")
        main.postDelayed({ survived(context) }, SURVIVE_MS)
    }

    /** Прожили [SURVIVE_MS]: запуск удался. */
    internal fun survived(context: Context) {
        survived = true
        prefs(context).edit { putBoolean(KEY_OK, true); putString(KEY_QUICK, "") }
        if (mode == Mode.SAFE) main.postDelayed({ if (mode == Mode.SAFE) prefs(context).edit { putBoolean(KEY_SAFE, false) } }, RECOVER_MS - SURVIVE_MS)
    }

    /** Из обработчика сбоя: процесс сейчас умрёт — запись сразу на диск. */
    fun crashed(context: Context) {
        if (survived || startedAt == 0L || now() - startedAt > SURVIVE_MS) return
        prefs(context).edit(commit = true) {
            putString(KEY_QUICK, (quick(context) + now()).takeLast(RESTARTS * 2).joinToString(","))
            putBoolean(KEY_COUNTED, true)
        }
    }

    /** «Обычный запуск»: забыть сбои; видео и запись — сразу. */
    fun leave(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_SAFE, false); putString(KEY_QUICK, "") }
        mode = Mode.NORMAL
        LightMode.refresh(context)
        RecorderService.sync(context)
        Log.i(TAG, "Обычный запуск по кнопке")
    }

    /** Экран с выходом → безопасная панель. */
    fun enterSafe() { if (mode == Mode.RESCUE) mode = Mode.SAFE }

    private fun unseenCrash(context: Context, since: Long): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exits(context).firstOrNull { (at, _) -> at >= since }?.first else null

    /** Сбои по данным Android (в тестах — подмена). */
    internal var exits: (Context) -> List<Pair<Long, TabletCheck.Exit>> = { c -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readExits(c) else emptyList() }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExits(context: Context): List<Pair<Long, TabletCheck.Exit>> = try {
        context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(context.packageName, 0, EXITS).orEmpty()
            .filter { it.processName == context.packageName }
            .mapNotNull { i -> TabletCheck.exitKind(i.reason, i.status, i.importance)?.takeIf { it == TabletCheck.Exit.CRASH }?.let { i.timestamp to it } }
    } catch (_: RuntimeException) { emptyList() }

    /**
     * После сбоя вернуть панель (главный экран Android вернёт и сам). Открывать окна из фона Android даёт только с разрешением
     * «поверх других окон», которое человек дал для возврата после включения ([BootReturn]); без него — ничего.
     */
    fun restartLater(context: Context) {
        if (quick(context).size >= RESTARTS || !Settings.canDrawOverlays(context) || !BootReturn.overlayEnabled(context)) return
        val intent = Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) null else
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, now() + RESTART_MS, pending)
    }

    internal fun resetForTests(context: Context) {
        main.removeCallbacksAndMessages(null)
        prefs(context).edit(commit = true) { clear() }
        mode = Mode.NORMAL; survived = false; startedAt = 0L
        now = System::currentTimeMillis
        exits = { emptyList() }
    }

    private const val EXITS = 5
    private const val TAG = "SafeStart"
}
