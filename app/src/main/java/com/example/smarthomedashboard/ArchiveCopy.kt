package com.example.smarthomedashboard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ночная копия сохранённых роликов (★) в папку копий владельца (docs/recording.md, «Сохранность»): та же ночная задача,
 * что и копия настроек ([AutoBackup.Worker]). Ролики со звёздочкой копируются в подпапку [FOLDER]; уже скопированные
 * (то же имя и размер) пропускаются; в папке владельца ничего не удаляется (сняли звёздочку — копия остаётся).
 * Без выбранной папки (SAF) копия не делается: копия внутри того же планшета от его поломки не спасает.
 * Блокирующее — фоновый поток.
 */
internal object ArchiveCopy {
    const val FOLDER = "Домофон — сохранённые ролики"
    private const val PREF_LAST = "clips_copy_last"
    private const val PREF_RESULT = "clips_copy_result"
    private const val PREF_REFS = "clips_copy_refs"
    private const val PREF_DIR = "clips_copy_dir"
    private const val TAG = "ArchiveCopy"

    /** Папка с файлами: список «имя → размер», запись нового файла. Подменяется в тестах. */
    interface Folder {
        fun sizes(): Map<String, Long>
        fun create(name: String): OutputStream?
        fun delete(name: String)
        /** Адрес только что созданного файла — по нему копия потом находится без списка папки. */
        val lastRef: String? get() = null
        /** Размер файла по его адресу; null — файла нет или узнать нельзя. */
        fun sizeAt(ref: String): Long? = null
        fun deleteAt(ref: String) {}
    }

    data class Result(val copied: Int, val skipped: Int, val failed: Int, val bytes: Long)

    /** Имя копии: «Домофон 2026-11-19 20-05-53.mp4» — читается человеком, уникально до секунды. */
    fun nameFor(title: String, file: RecFile): String {
        val safe = title.ifBlank { DoorbellConfig.DEFAULT_TITLE }.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        return "$safe ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date(file.startMs))}.mp4"
    }

    /** Скопировать [files] (со звёздочкой) в [folder]; [open] — чтение исходника. */
    /**
     * [refs] — своя память «имя → адрес копии»: список папки на планшете бывает пустым или неполным (ночи 20 и 21.11.2026), и по одному
     * списку все ролики копировались бы заново каждую ночь под именами «… (1)» (второй аудит 25.11.2026, NEW-DATA-1).
     */
    fun copy(files: List<Pair<String, RecFile>>, folder: Folder, refs: MutableMap<String, String> = HashMap(),
             open: (RecFile) -> InputStream? = { it.path.inputStream() }): Result {
        val existing = folder.sizes()
        var copied = 0; var skipped = 0; var failed = 0; var bytes = 0L
        for ((title, file) in files) {
            val name = nameFor(title, file)
            val size = file.path.length()
            if (existing[name] == size || refs[name]?.let(folder::sizeAt) == size) { skipped++; continue }
            if (name in existing) folder.delete(name) // недописанная прошлой ночью копия
            // Она же, когда список папки пуст: по запомненному адресу — иначе рядом легла бы «… (1).mp4», а оборванная осталась бы.
            else refs[name]?.let { if (folder.sizeAt(it) != null) folder.deleteAt(it) }
            val ok = try {
                val input = synchronized(Mp4Finalize.LOCK) { open(file) } ?: throw java.io.IOException("нет файла")
                input.use { src -> (folder.create(name) ?: throw java.io.IOException("папка не принимает файл")).use { src.copyTo(it, 1 shl 16) } }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Ролик не скопирован: ${e.javaClass.simpleName}")
                folder.delete(name)
                false
            }
            if (ok) { copied++; bytes += size; folder.lastRef?.let { refs[name] = it } } else { failed++; refs.remove(name) }
        }
        refs.keys.retainAll(files.map { (title, file) -> nameFor(title, file) }.toSet())
        return Result(copied, skipped, failed, bytes)
    }

    /** Ночной запуск: все ролики со ★ всех камер этого планшета. */
    @Synchronized fun run(context: Context, now: Long = System.currentTimeMillis()): Result? {
        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        val tree = prefs.getString(AutoBackup.PREF_TREE, null)?.toUri()
        val starred = RecArchive.defaultRoots(context).flatMap { RecLayout.scan(it) }.filter { it.starred && !it.open }
        if (starred.isEmpty()) return null
        if (tree == null) {
            prefs.edit { putString(PREF_RESULT, "nofolder").putLong(PREF_LAST, now) }
            return null
        }
        val titles = com.example.smarthomedashboard.data.TileManager(context).loadTiles()
            .associate { RecLayout.cameraKey(it.id) to it.title }
        val folder = try { SafFolder(context, tree, prefs) } catch (e: Exception) {
            Log.w(TAG, "Папка копий недоступна: ${e.javaClass.simpleName}")
            prefs.edit { putString(PREF_RESULT, "error").putLong(PREF_LAST, now) }
            return null
        }
        val refs = loadRefs(prefs.getString(PREF_REFS, null))
        val result = copy(starred.map { (titles[it.camera] ?: DoorbellConfig.DEFAULT_TITLE) to it }, folder, refs)
        prefs.edit { putString(PREF_REFS, org.json.JSONObject(refs as Map<*, *>).toString()) }
        Log.i(TAG, "Сохранённые ролики: скопировано ${result.copied}, уже были ${result.skipped}, ошибок ${result.failed}")
        prefs.edit { putString(PREF_RESULT, if (result.failed > 0) "error" else "ok").putLong(PREF_LAST, now) }
        return result
    }

    internal fun loadRefs(json: String?): MutableMap<String, String> = try {
        val o = org.json.JSONObject(json ?: "{}")
        o.keys().asSequence().associateWithTo(HashMap()) { o.getString(it) }
    } catch (_: org.json.JSONException) { HashMap() }

    /** Строка для архива: как копируются ролики со ★. */
    fun statusText(context: Context): String {
        val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST, 0L)
        val time = if (last > 0) SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(last)) else null
        return when {
            prefs.getString(AutoBackup.PREF_TREE, null) == null -> "★ ролики не копируются: выберите папку копий в «Настройки → Устройство»"
            prefs.getString(PREF_RESULT, null) == "error" -> "★ ролики: ошибка копирования" + (time?.let { " ($it)" } ?: "")
            time != null -> "★ ролики копируются ночью в папку копий · последний раз $time"
            else -> "★ ролики копируются ночью в папку копий"
        }
    }

    /** Подпапка [FOLDER] в выбранной владельцем папке копий (SAF). */
    private class SafFolder(private val context: Context, private val tree: Uri, prefs: android.content.SharedPreferences) : Folder {
        private val resolver get() = context.contentResolver
        private val dir: Uri
        override var lastRef: String? = null

        init {
            // Подпапка — по запомненному адресу (пока она есть в той же папке копий); по списку папки — только в первый раз.
            val known = prefs.getString(PREF_DIR, null)?.split('\n')?.takeIf { it.size == 2 && it[0] == tree.toString() }?.get(1)?.toUri()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            dir = known?.takeIf { sizeAt(it.toString()) != null } ?: children(rootId)[FOLDER]?.first ?: DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, FOLDER)
                ?: throw java.io.IOException("подпапка не создана")
            if (dir != known) prefs.edit { putString(PREF_DIR, "$tree\n$dir") }
        }

        override fun deleteAt(ref: String) { runCatching { DocumentsContract.deleteDocument(resolver, ref.toUri()) } }

        override fun sizeAt(ref: String): Long? = try {
            resolver.query(ref.toUri(), arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (_: Exception) { null }

        private fun children(parentId: String): Map<String, Pair<Uri, Long>> {
            val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            return resolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to c.getLong(2)) }
            }.orEmpty()
        }

        private fun files() = children(DocumentsContract.getDocumentId(dir))

        override fun sizes() = files().mapValues { it.value.second }
        override fun create(name: String): OutputStream? =
            DocumentsContract.createDocument(resolver, dir, "video/mp4", name)?.let { lastRef = it.toString(); resolver.openOutputStream(it, "wt") }
        override fun delete(name: String) {
            files()[name]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it.first) } }
        }
    }
}
