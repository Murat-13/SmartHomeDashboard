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
 * обычный режим включается сам, когда панелью никто не пользуется ([recovered]), а следующий запуск обычный в любом случае. Откатиться
 * на прежнюю версию Android не даёт, поэтому выход — починка и быстрое обновление.
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
    private const val KEY_AUTO = "auto_at"
    private const val KEY_TRIES = "auto_tries"
    private const val KEY_NOTIFIED = "phone_notified"
    internal const val PHONE_TEXT = "Безопасный запуск после сбоев: видео, запись и голос выключены. Коснитесь строки на панели — обычный запуск"

    /** Сбой раньше этого — «сразу после запуска». */
    const val SURVIVE_MS = 2 * 60_000L
    internal const val WINDOW_MS = 15 * 60_000L
    internal const val CRASHES = 3
    internal const val SAFE_CRASHES = 2
    internal const val RECOVER_MS = 60 * 60_000L
    /** Обычный режим, включённый сам, снова привёл к сбоям раньше этого — следующая попытка вдвое позже (до 16 часов). */
    private const val AUTO_FAILED_MS = 30 * 60_000L
    private const val MAX_TRIES = 4
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
        if (mode == Mode.SAFE && !safe) {
            crashes = emptyList()
            val again = t - p.getLong(KEY_AUTO, 0) in 0..AUTO_FAILED_MS
            p.edit { putInt(KEY_TRIES, if (again) (p.getInt(KEY_TRIES, 0) + 1).coerceAtMost(MAX_TRIES) else 0) }
        }
        survived = false
        startedAt = t
        p.edit {
            putBoolean(KEY_SAFE, mode != Mode.NORMAL)
            putString(KEY_QUICK, crashes.joinToString(","))
            putLong(KEY_START, t); putBoolean(KEY_OK, false); putBoolean(KEY_COUNTED, false)
            if (mode == Mode.NORMAL) remove(KEY_NOTIFIED)
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

    /** Безопасный режим час прожил без сбоев — обычный можно включить самому (аудит 25.11.2026, STAB-04). */
    fun recovered(context: Context): Boolean =
        mode == Mode.SAFE && now() - startedAt >= RECOVER_MS shl prefs(context).getInt(KEY_TRIES, 0)

    /** «Обычный запуск»: забыть сбои; видео и запись — сразу. [auto] — панель вернулась сама. */
    fun leave(context: Context, auto: Boolean = false) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_SAFE, false); putString(KEY_QUICK, "")
            if (auto) putLong(KEY_AUTO, now()) else { remove(KEY_AUTO); remove(KEY_TRIES) }
        }
        mode = Mode.NORMAL
        LightMode.refresh(context)
        RecorderService.sync(context)
        Log.i(TAG, if (auto) "Час без сбоев — обычный режим" else "Обычный запуск по кнопке")
    }

    /**
     * Одно уведомление на телефон, выбранный у домофона или камеры, — через Home Assistant (решение владельца 25.11.2026; второй аудит,
     * AUD-17): панель, которая сама в обычный режим не вернётся, иначе молчит о том, что видео, запись и голос выключены. Главный поток,
     * после подключения к Home Assistant; повторяется только после следующего входа в безопасный запуск.
     */
    fun notifyPhone(context: Context, tiles: List<com.example.smarthomedashboard.data.TileEntity>) {
        val p = prefs(context)
        if (mode != Mode.SAFE || p.getBoolean(KEY_NOTIFIED, false)) return
        val service = tiles.asSequence().filter { it.type in CameraSource.TYPES }
            .map { DoorbellAway.settings(context, it.id).notifyService }.firstOrNull { it.isNotBlank() } ?: return
        val message = mapOf<String, Any>("title" to "Панель умного дома", "message" to PHONE_TEXT,
            "data" to org.json.JSONObject().put("tag", "panel_safe_start"))
        DoorbellAway.sender(service.substringBefore('.'), service.substringAfter('.'), message) { ok ->
            if (ok) p.edit { putBoolean(KEY_NOTIFIED, true) }
            Log.i(TAG, if (ok) "Уведомление о безопасном запуске отправлено" else "Уведомление о безопасном запуске не отправлено")
        }
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
        if (quick(context).size >= RESTARTS || !restartable(context)) return
        val intent = Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) null else
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, now() + RESTART_MS, pending)
    }

    private fun restartable(context: Context) = Settings.canDrawOverlays(context) && BootReturn.overlayEnabled(context)

    /** Вернётся ли панель после сбоя сама — Android ([HomeScreenMode]) или [restartLater]. В тестах — подмена. */
    internal var returns: (Context) -> Boolean = { HomeScreenMode.isDefaultHome(it) || restartable(it) }

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
