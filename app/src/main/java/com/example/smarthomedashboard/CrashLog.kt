package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Сбои на этом планшете (docs/reliability.md): что упало, где и что было перед этим. Хранится только здесь — [KEEP] последних, в копии
 * и в облако не попадает, никуда не отправляется (решение владельца 25.11.2026: «Сервис сбора позже»). Адреса, токены, названия из
 * текста убираются сразу при записи ([sanitize]).
 */
internal object CrashLog {
    enum class Kind(val title: String) {
        JAVA("Сбой приложения"), NATIVE("Сбой внутри Android или библиотеки"), ANR("Приложение перестало отвечать"),
        MEMORY("Android закрыл приложение: мало памяти"), KILLED("Android закрыл приложение")
    }

    class Entry(val kind: Kind, val atMs: Long, val text: String, val file: File) {
        /** Одной строкой: тип ошибки без пакета, для Java-сбоя. */
        val summary: String get() = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.substringBefore(':')?.substringAfterLast('.')
            ?.trim().orEmpty()
    }

    const val KEEP = 10
    private const val MAX_TEXT = 24_000
    private const val MAX_MESSAGE = 240
    private const val CRUMBS = 24
    private const val DIR = "crashes"
    private const val PREFS = "crash_log"
    private const val KEY_EXITS = "exits_seen"
    private const val EXITS_DELAY_MS = 20_000L
    private const val SAME_MS = 15_000L

    internal var now: () -> Long = System::currentTimeMillis
    private val startedAt = SystemClock.elapsedRealtime()
    private val crumbs = ArrayDeque<String>()

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Что происходило: «экран …», «мало памяти». Только свои слова — без адресов и названий. */
    fun mark(text: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(now())) + " " + text
        synchronized(crumbs) { crumbs.addLast(line); while (crumbs.size > CRUMBS) crumbs.removeFirst() }
    }

    /** Один раз на процесс, до всего остального. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> handle(app, thread, e, previous) }
        app.registerActivityLifecycleCallbacks(screens)
        app.registerComponentCallbacks(memory)
        // Закрытия, которых приложение само не видит (Android 11+): не в главном потоке и не на старте.
        Thread({
            try { Thread.sleep(EXITS_DELAY_MS); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) collectExits(app) }
            catch (e: Exception) { Log.w(TAG, "Причины закрытия не прочитаны: ${e.javaClass.simpleName}") }
        }, "crash-exits").apply { isDaemon = true; start() }
    }

    /** Записать сбой и отдать его Android, как без нас. Сам не падает ни на чём — второй сбой скрыл бы первый. */
    internal fun handle(context: Context, thread: Thread, e: Throwable, previous: Thread.UncaughtExceptionHandler?) {
        try {
            SafeStart.crashed(context)
            write(context, Kind.JAVA, now(), "Поток: ${thread.name}\n\n" + trace(e))
            SafeStart.restartLater(context)
        } catch (_: Throwable) { /* важнее не помешать Android */ }
        if (previous != null) previous.uncaughtException(thread, e)
        else { Process.killProcess(Process.myPid()); exitProcess(CRASH_STATUS) }
    }

    /** Стек с обезличенными сообщениями; причины — тоже. */
    internal fun trace(e: Throwable): String {
        val out = StringWriter()
        e.printStackTrace(PrintWriter(out))
        return out.toString().lineSequence().joinToString("\n") { line -> if (line.trimStart().startsWith("at ")) line else sanitize(line.take(MAX_MESSAGE)) }
    }

    @Synchronized
    internal fun write(context: Context, kind: Kind, atMs: Long, details: String) {
        val d = dir(context).apply { mkdirs() }
        val before = synchronized(crumbs) { crumbs.joinToString("\n") { "  $it" } }
        val text = "${kind.title}\n${DATE.get()!!.format(Date(atMs))}\n${about(context)}\n" + (if (before.isEmpty()) "" else "Перед этим:\n$before\n") + "\n" + details
        File(d, "$atMs-${kind.name}.txt").writeText(text.take(MAX_TEXT))
        d.listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    private fun about(context: Context): String {
        val info = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val minutes = (SystemClock.elapsedRealtime() - startedAt) / 60_000
        val heap = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
        return "Версия ${info?.versionName.orEmpty()}, работало ${if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"}\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), память приложения $heap МБ" +
            (if (SafeStart.mode == SafeStart.Mode.NORMAL) "" else "\nБезопасный запуск")
    }

    // ---- Закрытия, о которых знает только Android ----

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun collectExits(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXITS, 0)
        val known = entries(context)
        val infos = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0).filter { it.processName == context.packageName && it.timestamp > seen }
        for (info in infos.sortedBy { it.timestamp }) {
            val kind = kindOf(info.reason, info.status, info.importance) ?: continue
            // Сбой Java уже записан обработчиком: вторая запись о нём не нужна.
            if (kind == Kind.JAVA && known.any { it.kind == Kind.JAVA && kotlin.math.abs(it.atMs - info.timestamp) < SAME_MS }) continue
            val stack = if (kind == Kind.ANR) runCatching { info.traceInputStream?.use { mainThread(it.bufferedReader().readLines()) } }.getOrNull().orEmpty() else ""
            write(context, kind, info.timestamp, "Android: ${sanitize(info.description.orEmpty().take(MAX_MESSAGE))}\n$stack")
        }
        prefs.edit { putLong(KEY_EXITS, maxOf(seen, infos.maxOfOrNull { it.timestamp } ?: 0)) }
    }

    @SuppressLint("InlinedApi") // константы встраиваются при сборке; зовут только с Android 11
    internal fun kindOf(reason: Int, status: Int, importance: Int): Kind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> Kind.JAVA
        ApplicationExitInfo.REASON_CRASH_NATIVE -> Kind.NATIVE
        ApplicationExitInfo.REASON_ANR -> Kind.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY -> Kind.MEMORY.takeIf { TabletCheck.exitKind(reason, status, importance) != null }
        else -> Kind.KILLED.takeIf { TabletCheck.exitKind(reason, status, importance) == TabletCheck.Exit.KILLED }
    }

    /** Из снимка потоков — только главный: остальное длинно и не нужно. */
    internal fun mainThread(lines: List<String>): String {
        val from = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (from < 0) return ""
        return lines.drop(from).takeWhile { it.isNotBlank() }.take(ANR_LINES).joinToString("\n") { sanitize(it.take(MAX_MESSAGE)) }
    }

    // ---- Обезличивание ----

    private val URL = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"')]+")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
    private val IPV4 = Regex("(?<![\\d.])\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(?![\\d.])")
    private val IPV6 = Regex("(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){4,}[0-9a-f:]*|(?<![\\w:])[0-9a-f]{0,4}::[0-9a-f:]+")
    private val HOST = Regex("\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|ru|su|io|dev|me|home|lan|local|arpa|cloud|app|xyz|pro|info)\\b(?!\\.)")
    private val QUOTED = Regex("\"[^\"]*\"")
    private val BRACES = Regex("\\{[^{}]*\\}")
    private val TOKEN = Regex("\\b(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{24,}\\b")
    private val ENTITY = Regex("\\b(?:sensor|binary_sensor|light|switch|camera|lock|cover|climate|weather|select|button|input_\\w+|media_player|person|device_tracker)\\.[a-z0-9_]+")

    /** Убрать из текста всё, что может указывать на дом: адреса, узлы, почту, токены, имена сущностей, значения в кавычках и скобках. */
    internal fun sanitize(text: String): String = text.replace(URL) { it.value.substringBefore("://") + "://…" }.replace(EMAIL, "…").replace(QUOTED, "\"…\"").replace(BRACES, "{…}")
        .replace(IPV4, "<адрес>").replace(IPV6, "<адрес>").replace(ENTITY) { it.value.substringBefore('.') + ".…" }.replace(HOST, "<узел>").replace(TOKEN, "…")

    // ---- Показ ----

    fun entries(context: Context): List<Entry> = dir(context).listFiles().orEmpty().mapNotNull { f ->
        val at = f.name.substringBefore('-').toLongOrNull() ?: return@mapNotNull null
        val kind = Kind.entries.firstOrNull { it.name == f.name.substringAfter('-').removeSuffix(".txt") } ?: return@mapNotNull null
        Entry(kind, at, try { f.readText() } catch (_: Exception) { "" }, f)
    }.sortedByDescending { it.atMs }

    fun clear(context: Context) { dir(context).listFiles().orEmpty().forEach { it.delete() } }

    private val screens = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = mark("экран ${activity.javaClass.simpleName}")
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION") // новых уровней «мало памяти, пока на экране» нет
            if (level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) mark("мало памяти ($level)")
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    private val DATE = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale("ru")) }
    private const val ANR_LINES = 60
    private const val CRASH_STATUS = 10
    private const val TAG = "CrashLog"
}
