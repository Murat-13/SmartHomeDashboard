package com.example.smarthomedashboard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Автокопии того же открытого файла, что и ручной экспорт (без токена, ключа Яндекса и PIN).
 * Раз в сутки: если с прошлой копии ничего не изменилось — копия не создаётся. Хранятся [KEEP] последние;
 * старая удаляется только после того, как новая записана и прочитана обратно без ошибок.
 * Место — выбранная владельцем папка (переживает удаление приложения), иначе — внутри приложения.
 * Ручные файлы (другое имя) ротация не трогает.
 */
internal object AutoBackup {
    const val KEEP = 3
    const val PREFIX = "smarthome_auto_"
    const val PREF_TREE = "backup_tree_uri"
    const val PREF_LAST_FP = "backup_last_fingerprint"
    const val PREF_LAST_TIME = "backup_last_time"
    const val PREF_LAST_RESULT = "backup_last_result"
    const val PREF_LAST_CHECK = "backup_last_check"
    private const val WORK = "daily_auto_backup"

    enum class Outcome { CREATED, UNCHANGED, FAILED }

    /** Место хранения копий: папка владельца (SAF) или каталог приложения. */
    internal interface Store {
        fun names(): List<String>
        fun write(name: String, text: String): Boolean
        fun read(name: String): String?
        fun delete(name: String): Boolean
    }

    private class InternalStore(context: Context) : Store {
        private val dir = File(context.filesDir, "backups")
        override fun names() = dir.list()?.toList().orEmpty()
        override fun write(name: String, text: String) = runCatching {
            dir.mkdirs()
            File(dir, name).writeText(text, Charsets.UTF_8)
            true
        }.getOrDefault(false)
        override fun read(name: String) = runCatching { File(dir, name).readText(Charsets.UTF_8) }.getOrNull()
        override fun delete(name: String) = File(dir, name).delete()
    }

    private class FolderStore(private val context: Context, private val tree: Uri) : Store {
        private val resolver get() = context.contentResolver
        private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        private fun children(): Map<String, Uri> = runCatching {
            val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
            }.orEmpty()
        }.getOrDefault(emptyMap())
        override fun names() = children().keys.toList()
        override fun write(name: String, text: String) = runCatching {
            val doc = DocumentsContract.createDocument(resolver, root, "application/json", name) ?: return false
            resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return false
            true
        }.getOrDefault(false)
        override fun read(name: String) = children()[name]?.let { uri ->
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
        override fun delete(name: String) = children()[name]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false) } ?: false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun store(context: Context): Store =
        prefs(context).getString(PREF_TREE, null)?.let { FolderStore(context, Uri.parse(it)) } ?: InternalStore(context)

    /** Одна проверка: копия создаётся только при изменении содержимого. [now] — для имени файла и статуса. */
    fun run(context: Context, store: Store = store(context), now: Long = System.currentTimeMillis()): Outcome {
        val prefs = prefs(context)
        val text = ConfigBackup.build(context).toString(2)
        val fingerprint = ConfigBackup.fingerprint(text)
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        if (fingerprint == prefs.getString(PREF_LAST_FP, null) && store.names().any { it.startsWith(PREFIX) }) {
            return Outcome.UNCHANGED
        }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + ".json"
        // Новая копия засчитывается, только если записалась и читается обратно тем же содержимым.
        val verified = store.write(name, text) && store.read(name)?.let { runCatching { JSONObject(it) }.isSuccess && it == text } == true
        if (!verified) {
            store.delete(name)
            prefs.edit().putString(PREF_LAST_RESULT, "error").apply()
            return Outcome.FAILED
        }
        store.names().filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted().dropLast(KEEP).forEach { store.delete(it) }
        prefs.edit().putString(PREF_LAST_FP, fingerprint).putLong(PREF_LAST_TIME, now).putString(PREF_LAST_RESULT, "ok").apply()
        return Outcome.CREATED
    }

    /** Короткий статус для «Устройства» и боковой панели. */
    fun statusText(context: Context): Pair<ModernUi.Status, String> {
        val prefs = prefs(context)
        val time = prefs.getLong(PREF_LAST_TIME, 0L)
        val format = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        return when {
            prefs.getString(PREF_LAST_RESULT, null) == "error" ->
                ModernUi.Status.ERROR to "Бэкап: ошибка записи" + if (time > 0) " (прошлый — ${format.format(Date(time))})" else ""
            time > 0 -> ModernUi.Status.OK to "Бэкап: ${format.format(Date(time))}"
            else -> ModernUi.Status.UNKNOWN to "Бэкап: ещё не создавался"
        }
    }

    /** Ежедневная задача около 3:00 (Android может сдвинуть время). Повторный вызов расписание не дублирует. */
    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (_: IllegalStateException) {
            // WorkManager не инициализирован (тестовое окружение без манифеста): копии просто не планируются.
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            AutoBackup.run(applicationContext)
            // Ошибка записи видна в статусе; повторять немедленно бессмысленно — следующая попытка через сутки.
            return Result.success()
        }
    }
}
