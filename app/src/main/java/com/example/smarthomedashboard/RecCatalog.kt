package com.example.smarthomedashboard

import java.io.File

/**
 * Какие записи есть на носителях — в памяти процесса (docs/recording.md, «Архив открывается сразу»). Служба записи и так обходит
 * носители после запуска и раз в 6 часов и знает о каждом новом, удалённом и отмеченном ★ файле. Архив берёт список готовым:
 * 25.11.2026 на планшете было 7,5 тысячи файлов, и обход при каждом входе занимал 5–10 с.
 *
 * Только имена и то, что из них следует; сами файлы здесь не открываются. Любой поток.
 */
internal object RecCatalog {
    private val lock = Any()
    /** Носитель → камера → путь → файл. */
    private val roots = HashMap<String, HashMap<String, LinkedHashMap<String, RecFile>>>()
    /** Носители, обойдённые службой записи целиком; из них с размерами файлов — [sized]. */
    private val complete = HashSet<String>()
    private val sized = HashSet<String>()
    /** Камеры носителя, прочитанные архивом, пока служба записи его не обошла (или её нет). */
    private val listed = HashSet<Pair<String, String>>()

    /** Удаления и ★ последнего времени: обход, начатый раньше, о них не знает ([upToDate]). */
    private class Edit(val at: Long, val old: RecFile, val new: RecFile?)
    private val edits = ArrayDeque<Edit>()

    /** Отметка начала чтения носителя — для [publish], [publishCamera] и [reconcile]. */
    fun mark(): Long = System.nanoTime()

    /** Полный обход носителя, начатый в [since]. [withSizes] — с размерами файлов. */
    fun publish(root: File, files: Collection<RecFile>, since: Long, withSizes: Boolean = true) = synchronized(lock) {
        val cameras = HashMap<String, LinkedHashMap<String, RecFile>>()
        for (f in upToDate(root.path, null, files, since)) if (!f.open) cameras.getOrPut(f.camera) { LinkedHashMap() }[f.path.path] = f
        roots[root.path] = cameras
        complete += root.path
        if (withSizes) sized += root.path else sized -= root.path
        listed.removeAll { it.first == root.path }
    }

    /** Записи одной камеры, прочитанные архивом с [since]. Обход службы не хуже — поверх него не пишутся. */
    fun publishCamera(root: File, camera: String, files: Collection<RecFile>, since: Long) = synchronized(lock) {
        if (root.path in complete) return@synchronized
        roots.getOrPut(root.path) { HashMap() }[camera] =
            upToDate(root.path, camera, files, since).filterNot { it.open }.associateByTo(LinkedHashMap()) { it.path.path }
        listed += root.path to camera
    }

    /** Новый закрытый файл. Носитель ещё не обойдён — обход найдёт его сам. */
    fun add(file: RecFile) = synchronized(lock) {
        val root = rootOf(file) ?: return@synchronized
        if (known(root, file.camera)) roots.getOrPut(root) { HashMap() }.getOrPut(file.camera) { LinkedHashMap() }[file.path.path] = file
    }

    fun remove(files: Collection<RecFile>) = synchronized(lock) { files.forEach { edit(it, null) } }

    /** Переименование (★ — в имени файла). */
    fun replace(old: RecFile, new: RecFile) = synchronized(lock) { edit(old, new) }

    /** Все записи носителя с размерами; null — служба его ещё не обошла. */
    fun files(root: File): List<RecFile>? = synchronized(lock) {
        if (root.path !in sized) null else roots[root.path]?.values?.flatMap { it.values }
    }

    /** Записи камеры на всех [roots]; null — какой-то носитель ещё не обойдён. */
    fun camera(roots: List<File>, camera: String): List<RecFile>? = synchronized(lock) {
        val out = ArrayList<RecFile>()
        for (root in roots) {
            if (!known(root.path, camera)) return@synchronized null
            this.roots[root.path]?.get(camera)?.values?.let(out::addAll)
        }
        out
    }

    /**
     * Сверка с прочитанным сейчас ([listed], чтение начато в [since]): чего на носителе нет — убрать, чего нет здесь — добавить.
     * Файлы последних минут не трогаются: они могли закрыться, пока шло чтение. true — что-то изменилось.
     */
    fun reconcile(roots: List<File>, camera: String, listed: List<RecFile>, since: Long, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val known = camera(roots, camera) ?: return@synchronized false
            val border = now - SETTLE_MS
            val fresh = roots.flatMap { root -> upToDate(root.path, camera, listed.filter { rootOf(it) == root.path }, since) }
            val present = fresh.mapTo(HashSet()) { it.path.path }
            val paths = known.mapTo(HashSet()) { it.path.path }
            val gone = known.filter { it.path.path !in present && it.endMs < border }
            val found = fresh.filter { it.path.path !in paths && !it.open && it.endMs < border }
            gone.forEach { f -> rootOf(f)?.let { this.roots[it]?.get(f.camera)?.remove(f.path.path) } }
            found.forEach { add(it.copy(bytes = it.path.length())) }
            gone.isNotEmpty() || found.isNotEmpty()
        }

    private fun known(root: String, camera: String) = root in complete || (root to camera) in listed

    private fun rootOf(file: RecFile): String? = file.path.parentFile?.parentFile?.parentFile?.parentFile?.path

    private fun edit(old: RecFile, new: RecFile?) {
        edits.addLast(Edit(System.nanoTime(), old, new))
        while (edits.size > EDITS) edits.removeFirst()
        rootOf(old)?.let { roots[it]?.get(old.camera)?.remove(old.path.path) }
        if (new != null) add(new)
    }

    /** [files] носителя, прочитанные с [since], — с учётом удалений и ★, сделанных за время чтения. */
    private fun upToDate(root: String, camera: String?, files: Collection<RecFile>, since: Long): Collection<RecFile> {
        val later = edits.filter { it.at >= since && rootOf(it.old) == root && (camera == null || it.old.camera == camera) }
        if (later.isEmpty()) return files
        val map = files.associateByTo(LinkedHashMap()) { it.path.path }
        for (e in later) {
            map.remove(e.old.path.path)
            if (e.new != null) map[e.new.path.path] = e.new
        }
        return map.values
    }

    private const val SETTLE_MS = 2 * 60_000L
    private const val EDITS = 500

    internal fun clear() = synchronized(lock) { roots.clear(); complete.clear(); sized.clear(); listed.clear(); edits.clear() }
}
