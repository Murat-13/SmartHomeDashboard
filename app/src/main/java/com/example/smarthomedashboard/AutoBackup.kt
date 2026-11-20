package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smarthomedashboard.data.TileParser
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Автокопии того же открытого файла, что и ручной экспорт (без токена, ключа Яндекса и PIN).
 * Раз в сутки: если с прошлой копии ничего не изменилось — копия не создаётся. Хранятся [KEEP] последние;
 * старая удаляется только после того, как новая записана и прочитана обратно без ошибок.
 * Место — выбранная владельцем папка (переживает удаление приложения), иначе — внутри приложения.
 * Ручные файлы (другое имя) ротация не трогает.
 */
internal object AutoBackup {
    const val KEEP = 3
    private const val TAG = "AutoBackup"
    const val PREFIX = "smarthome_auto_"
    const val PRE_UPDATE_PREFIX = "smarthome_preupdate_"
    const val PRE_UPDATE_KEEP = 2
    const val PREF_TREE = "backup_tree_uri"
    const val PREF_LAST_FP = "backup_last_fingerprint"
    const val PREF_LAST_TIME = "backup_last_time"
    const val PREF_LAST_RESULT = "backup_last_result"
    const val PREF_LAST_CHECK = "backup_last_check"
    private const val WORK = "daily_auto_backup"
    private const val NIGHT_WORK = "night_auto_backup"

    /** DAMAGED — плитки повреждены: копия не создаётся, чтобы не вытеснить исправные. */
    enum class Outcome { CREATED, UNCHANGED, FAILED, DAMAGED }

    /** Место хранения копий: папка владельца (SAF) или каталог приложения. */
    internal interface Store {
        fun names(): List<String>
        fun write(name: String, text: String): Boolean
        fun read(name: String): String?
        fun delete(name: String): Boolean
        /** Потоковая запись/чтение больших файлов (история мощности); null — недоступно. */
        fun output(name: String): java.io.OutputStream? = null
        fun input(name: String): java.io.InputStream? = null
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
        override fun output(name: String) = runCatching { dir.mkdirs(); File(dir, name).outputStream() }.getOrNull()
        override fun input(name: String) = runCatching { File(dir, name).inputStream() }.getOrNull()
    }

    internal class FolderStore(private val context: Context, private val tree: Uri) : Store {
        private val resolver get() = context.contentResolver
        private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        /**
         * Файлы, созданные этим экземпляром: проверка чтением и удаление идут по адресу, который вернул
         * createDocument, а не поиском по имени. Список папки у провайдера может отставать от записи (найдено
         * 20.11.2026 на SM-X216B: ночная копия записалась, но поиск по имени её не нашёл — «ошибка записи», и
         * лишний файл не удалился).
         */
        private val created = java.util.concurrent.ConcurrentHashMap<String, Uri>()
        private fun find(name: String): Uri? = created[name] ?: children()[name]
        private fun children(): Map<String, Uri> = runCatching {
            val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
            }.orEmpty()
        }.getOrDefault(emptyMap())
        override fun names() = children().keys.toList()
        override fun write(name: String, text: String) = runCatching {
            val doc = DocumentsContract.createDocument(resolver, root, "application/json", name)
                ?: return false.also { Log.w(TAG, "Папка копий: файл не создан") }
            created[name] = doc
            resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: return false.also { Log.w(TAG, "Папка копий: файл не открылся на запись") }
            true
        }.getOrElse { Log.w(TAG, "Папка копий: запись не удалась (${it.javaClass.simpleName})"); false }
        override fun read(name: String) = find(name)?.let { uri ->
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .onFailure { Log.w(TAG, "Папка копий: чтение не удалось (${it.javaClass.simpleName})") }.getOrNull()
        }
        override fun delete(name: String) = find(name)?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false).also { if (it) created.remove(name) }
        } ?: false
        override fun output(name: String) = runCatching {
            DocumentsContract.createDocument(resolver, root, "application/gzip", name)?.let { doc ->
                created[name] = doc
                resolver.openOutputStream(doc, "wt")
            }
        }.onFailure { Log.w(TAG, "Папка копий: запись не удалась (${it.javaClass.simpleName})") }.getOrNull()
        override fun input(name: String) = find(name)?.let { runCatching { resolver.openInputStream(it) }.getOrNull() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun store(context: Context): Store =
        prefs(context).getString(PREF_TREE, null)?.let { FolderStore(context, it.toUri()) } ?: InternalStore(context)

    /** Плитки, которые сейчас попали бы в копию, читаются без потерь. */
    private fun tilesIntact(context: Context): Boolean =
        TileParser.parse(context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).getString("tiles", "[]")).issue == null

    /** Копия, из которой можно восстановить настройки (ежедневная или «перед обновлением»). */
    class Copy(val store: Store, val name: String, val label: String)

    /**
     * Все копии, новые сверху: в текущем месте хранения и во внутренней папке приложения (там лежат копии,
     * сделанные до выбора папки, и при недоступной папке). Тот же формат, что у ручного экспорта.
     */
    fun restorableCopies(context: Context): List<Copy> {
        val stores = listOfNotNull(store(context), InternalStore(context).takeIf { prefs(context).getString(PREF_TREE, null) != null })
        val parse = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        val show = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru"))
        return stores.flatMapIndexed { index, store ->
            runCatching { store.names() }.getOrDefault(emptyList()).mapNotNull { name ->
                if (!name.endsWith(".json")) return@mapNotNull null
                val (prefix, kind) = when {
                    name.startsWith(PREFIX) -> PREFIX to "Автокопия"
                    name.startsWith(PRE_UPDATE_PREFIX) -> PRE_UPDATE_PREFIX to "Перед обновлением"
                    else -> return@mapNotNull null
                }
                val stamp = name.removePrefix(prefix).take(15)
                val time = runCatching { parse.parse(stamp)?.time }.getOrNull() ?: return@mapNotNull null
                val version = name.substringAfter("_to_", "").removeSuffix(".json").takeIf { it.isNotEmpty() }
                val place = if (stores.size > 1 && index == 1) " · внутри приложения" else ""
                time to Copy(store, name, kind + (version?.let { " до $it" } ?: "") + " · ${show.format(Date(time))}$place")
            }
        }.sortedByDescending { it.first }.map { it.second }
    }

    /** Одна проверка: копия создаётся только при изменении содержимого. [now] — для имени файла и статуса. */
    fun run(context: Context, store: Store = store(context), now: Long = System.currentTimeMillis()): Outcome {
        val prefs = prefs(context)
        prefs.edit { putLong(PREF_LAST_CHECK, now) }
        if (!tilesIntact(context)) {
            prefs.edit { putString(PREF_LAST_RESULT, "damaged") }
            return Outcome.DAMAGED
        }
        val text = ConfigBackup.build(context).toString(2)
        val fingerprint = ConfigBackup.fingerprint(text)
        if (fingerprint == prefs.getString(PREF_LAST_FP, null) && store.names().any { it.startsWith(PREFIX) }) {
            return Outcome.UNCHANGED
        }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + ".json"
        // Новая копия засчитывается, только если записалась и читается обратно тем же содержимым.
        val written = store.write(name, text)
        val back = if (written) store.read(name) else null
        val verified = back != null && runCatching { JSONObject(back) }.isSuccess && back == text
        if (!verified) {
            // Что именно не вышло — в журнал (без содержимого копии), иначе причину ночной ошибки не найти.
            Log.w(TAG, when {
                !written -> "Копия настроек не записалась"
                back == null -> "Копия настроек записалась, но не читается обратно"
                else -> "Копия настроек читается не тем содержимым (${back.length} из ${text.length} символов)"
            })
            store.delete(name)
            prefs.edit { putString(PREF_LAST_RESULT, "error") }
            return Outcome.FAILED
        }
        store.names().filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted().dropLast(KEEP).forEach { store.delete(it) }
        prefs.edit { putString(PREF_LAST_FP, fingerprint).putLong(PREF_LAST_TIME, now).putString(PREF_LAST_RESULT, "ok") }
        return Outcome.CREATED
    }

    /**
     * Копия «перед обновлением» на версию [versionName]: создаётся всегда (даже без изменений), засчитывается
     * только после проверки чтением. Хранятся [PRE_UPDATE_KEEP] последние; ежедневные копии не затрагиваются.
     * false — установка обновления должна встать на паузу.
     */
    fun beforeUpdate(context: Context, versionName: String, store: Store = store(context),
                     now: Long = System.currentTimeMillis()): Boolean = runCatching {
        // Повреждённые плитки не считаются копией: установка встаёт на паузу, как при ошибке записи.
        if (!tilesIntact(context)) return@runCatching false
        val text = ConfigBackup.build(context).toString(2)
        val safeVersion = versionName.replace(Regex("[^0-9A-Za-z.]"), "_")
        val name = PRE_UPDATE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + "_to_$safeVersion.json"
        val verified = store.write(name, text) && store.read(name) == text
        if (!verified) { store.delete(name); return@runCatching false }
        store.names().filter { it.startsWith(PRE_UPDATE_PREFIX) && it.endsWith(".json") }.sorted()
            .dropLast(PRE_UPDATE_KEEP).forEach { store.delete(it) }
        true
    }.getOrDefault(false)

    /**
     * Короткий статус для «Устройства» и боковой панели: когда ночная задача последний раз **проверяла** (а не только
     * когда создала копию — копия создаётся лишь при изменениях, и по её дате не отличить «ничего не менялось» от
     * «задача не запускается»). Проверки не было дольше [STALE_MS] — ошибка.
     */
    fun statusText(context: Context, now: Long = System.currentTimeMillis()): Pair<ModernUi.Status, String> {
        val prefs = prefs(context)
        val time = prefs.getLong(PREF_LAST_TIME, 0L)
        val check = prefs.getLong(PREF_LAST_CHECK, 0L)
        val copy = if (time > 0) " (копия от ${dayFormat.format(Date(time))})" else ""
        return when {
            prefs.getString(PREF_LAST_RESULT, null) == "damaged" ->
                ModernUi.Status.ERROR to "Бэкап: пропущен — плитки повреждены" + if (time > 0) " (прошлый — ${whenText(time, now)})" else ""
            prefs.getString(PREF_LAST_RESULT, null) == "error" ->
                ModernUi.Status.ERROR to "Бэкап: ошибка записи" + if (time > 0) " (прошлый — ${whenText(time, now)})" else ""
            check > 0 && now - check > STALE_MS ->
                ModernUi.Status.ERROR to "Бэкап: проверки не было с ${whenText(check, now)}$copy"
            check > 0 && time > 0 -> ModernUi.Status.OK to "Бэкап: проверен ${whenText(check, now)}$copy"
            time > 0 -> ModernUi.Status.OK to "Бэкап: ${whenText(time, now)}"
            else -> ModernUi.Status.UNKNOWN to "Бэкап: ещё не создавался"
        }
    }

    /** Ночная задача не запускалась дольше двух суток — что-то не так (Android её отложил, приложение не запускалось). */
    const val STALE_MS = 48 * 3_600_000L

    private val dayFormat get() = SimpleDateFormat("d MMM", Locale("ru"))

    /** «сегодня в 15:10», «вчера в 03:00», «7 нояб., 03:00». */
    fun whenText(at: Long, now: Long = System.currentTimeMillis()): String {
        val clock = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(at))
        val today = RecArchive.dayStart(now)
        return when {
            at >= today -> "сегодня в $clock"
            at >= RecArchive.dayStart(today - 1) -> "вчера в $clock"
            else -> SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(at))
        }
    }

    /** Каждую ночь в 03:00 ([NightlyWork]; Android может немного задержать). Повторный вызов расписание не дублирует. */
    fun schedule(context: Context, fromWorker: Boolean = false) =
        NightlyWork.schedule(context, NIGHT_WORK, WORK, Worker::class.java, 3, 0, fromWorker)

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            AutoBackup.run(applicationContext)
            // Сохранённые ролики (★) — в ту же папку копий (docs/recording.md, «Сохранность»).
            try { ArchiveCopy.run(applicationContext) } catch (e: Exception) {
                android.util.Log.w("ArchiveCopy", "Копия роликов не выполнена: ${e.javaClass.simpleName}")
            }
            // Следующая — следующей ночью в 03:00 (не через 24 ч от этого запуска).
            schedule(applicationContext, fromWorker = true)
            // Ошибка записи видна в статусе; повторять немедленно бессмысленно — следующая попытка через сутки.
            return Result.success()
        }
    }
}
