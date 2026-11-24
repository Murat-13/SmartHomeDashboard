package com.example.smarthomedashboard

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

/**
 * Возвращается ли панель сама после включения планшета (docs/setup-tz.md, часть 4). Первый показ панели после каждого
 * включения запоминается: открыл её Android (главный экран, запасной способ) или человек, и через сколько секунд.
 * «Проверить перезагрузкой» — то же самое, только итог ещё и показывается на панели.
 */
internal object BootReturn {
    /** [HOME] — Android открыл главный экран, [OVERLAY] — запасной способ, [HAND] — открыл человек. */
    enum class How { HOME, OVERLAY, HAND }

    /** [seconds] — от включения планшета до показа панели; [tested] — это была «Проверить перезагрузкой». */
    data class Result(val how: How, val seconds: Int, val at: Long, val tested: Boolean)

    private const val KEY_BOOT = "boot_seen"
    private const val KEY_HOW = "boot_how"
    private const val KEY_SECONDS = "boot_seconds"
    private const val KEY_AT = "boot_at"
    private const val KEY_TESTED = "boot_tested"
    private const val KEY_TEST = "boot_test_at"
    private const val TEST_MS = 24 * 3_600_000L
    const val EXTRA_BOOT = "boot_open"

    internal var bootCount: (Context) -> Int = { c ->
        try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: RuntimeException) { -1 }
    }
    internal var sinceBootMs: () -> Long = SystemClock::elapsedRealtime

    @Volatile private var how: How? = null

    /** Чем открыли приложение (заставка). Первое открытие после запуска процесса — оно и вернуло панель. */
    fun launched(intent: Intent?) {
        if (how != null) return
        how = when {
            HomeScreenMode.isHomeLaunch(intent) -> How.HOME
            intent?.getBooleanExtra(EXTRA_BOOT, false) == true -> How.OVERLAY
            else -> How.HAND
        }
    }

    /** Панель на экране. Итог — если это первый показ после включения планшета; иначе null. */
    fun panelShown(context: Context): Result? {
        val boot = bootCount(context)
        if (boot < 0) return null
        val prefs = TabletWatch.prefs(context)
        val seen = prefs.getInt(KEY_BOOT, -1)
        if (seen == boot) return null
        if (seen < 0) { prefs.edit { putInt(KEY_BOOT, boot) }; return null }
        val result = Result(how ?: How.HAND, (sinceBootMs() / 1000).toInt(), TabletWatch.now(), pending(context))
        prefs.edit {
            putInt(KEY_BOOT, boot)
            putString(KEY_HOW, result.how.name)
            putInt(KEY_SECONDS, result.seconds)
            putLong(KEY_AT, result.at)
            putBoolean(KEY_TESTED, result.tested)
            remove(KEY_TEST)
        }
        Log.i(TAG, "Панель после включения планшета: ${result.how}, через ${result.seconds} с")
        return result
    }

    fun last(context: Context): Result? {
        val prefs = TabletWatch.prefs(context)
        val how = How.entries.firstOrNull { it.name == prefs.getString(KEY_HOW, null) } ?: return null
        return Result(how, prefs.getInt(KEY_SECONDS, 0), prefs.getLong(KEY_AT, 0), prefs.getBoolean(KEY_TESTED, false))
    }

    /** Ждём перезагрузку, о которой попросил человек (не дольше суток). */
    fun pending(context: Context): Boolean {
        val at = TabletWatch.prefs(context).getLong(KEY_TEST, 0)
        return at > 0 && TabletWatch.now() - at in 0..TEST_MS
    }

    fun startTest(context: Context) = TabletWatch.prefs(context).edit {
        putLong(KEY_TEST, TabletWatch.now())
        if (!TabletWatch.prefs(context).contains(KEY_BOOT)) putInt(KEY_BOOT, bootCount(context))
    }

    fun cancelTest(context: Context) = TabletWatch.prefs(context).edit { remove(KEY_TEST) }

    /** Итог для панели: «открылась сама за 47 с» или «сама не открылась». */
    fun text(r: Result) = if (r.how == How.HAND) "Проверка перезагрузкой: панель сама не открылась" else
        "Проверка перезагрузкой: панель открылась сама за ${TabletCheck.duration(r.seconds)}"

    // ---- Запасной способ ----

    private fun receiver(context: Context) = ComponentName(context, BootReceiver::class.java)

    fun overlayEnabled(context: Context) = context.packageManager.getComponentEnabledSetting(receiver(context)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setOverlay(context: Context, on: Boolean) = context.packageManager.setComponentEnabledSetting(receiver(context),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)

    /** Включение планшета: открыть панель, если её не открыл главный экран. Выключен, пока человек не попросил. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED || MainActivity.isAlive || !Settings.canDrawOverlays(context)) return
            try {
                context.startActivity(Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_BOOT, true))
            } catch (e: RuntimeException) { Log.w(TAG, "Панель не открылась после включения: ${e.javaClass.simpleName}") }
        }
    }

    internal fun resetForTests() {
        how = null
        sinceBootMs = SystemClock::elapsedRealtime
        bootCount = { c -> try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: RuntimeException) { -1 } }
    }

    private const val TAG = "BootReturn"
}
