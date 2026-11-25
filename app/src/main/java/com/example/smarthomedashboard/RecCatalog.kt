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
    /** Момент самой поздней правки, которую история уже не помнит: чтение, начатое раньше, сверять нельзя. */
    private var forgotten = Long.MIN_VALUE
    /**
     * Правка из архива или очистки (★, удаление): служба записи ведёт свой список файлов и узнаёт о ней отсюда (второй аудит
     * 25.11.2026, NEW-REC-1). Зовётся под замком каталога — только передать дальше.
     */
    @Volatile var onEdit: ((old: RecFile, new: RecFile?) -> Unit)? = null

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
        if (root.path in complete || since <= forgotten) return@synchronized
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
    fun reconcile(roots: List<File>, camera: String, listed: List<RecFile>, since: Long, now: Long = System.currentTimeMillis()): Boolean {
        var removed = false
        val found = synchronized(lock) {
            // История правок с начала чтения неполна (за это время удалено очень много) — прочитанному верить нельзя: удалённое
            // вернулось бы в список (второй аудит 25.11.2026, AUD-03). Следующая сверка возьмёт свежее чтение.
            if (since <= forgotten) return false
            val known = camera(roots, camera) ?: return false
            val border = now - SETTLE_MS
            val fresh = roots.flatMap { root -> upToDate(root.path, camera, listed.filter { rootOf(it) == root.path }, since) }
            val present = fresh.mapTo(HashSet()) { it.path.path }
            val paths = known.mapTo(HashSet()) { it.path.path }
            val gone = known.filter { it.path.path !in present && it.endMs < border }
            gone.forEach { f -> rootOf(f)?.let { this.roots[it]?.get(f.camera)?.remove(f.path.path) } }
            removed = gone.isNotEmpty()
            fresh.filter { it.path.path !in paths && !it.open && it.endMs < border }
        }
        // Размеры — вне замка: носитель может отвечать медленно, а каталог нужен потоку службы записи (AUD-04).
        val sized = found.map { it.copy(bytes = it.path.length()) }
        if (sized.isNotEmpty()) synchronized(lock) { sized.forEach { f -> if (edits.none { it.at >= since && it.old.path == f.path }) add(f) } }
        return removed || sized.isNotEmpty()
    }

    private fun known(root: String, camera: String) = root in complete || (root to camera) in listed

    private fun rootOf(file: RecFile): String? = file.path.parentFile?.parentFile?.parentFile?.parentFile?.path

    private fun edit(old: RecFile, new: RecFile?) {
        val at = System.nanoTime()
        edits.addLast(Edit(at, old, new))
        // История — по времени (чтение носителя длится секунды), с запасным пределом по числу; что забыто — помнит [forgotten].
        while (edits.size > EDITS || edits.first().at < at - EDITS_NS) forgotten = edits.removeFirst().at
        onEdit?.invoke(old, new)
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
    private const val EDITS = 20_000
    private const val EDITS_NS = 10 * 60_000_000_000L

    internal fun clear() = synchronized(lock) { roots.clear(); complete.clear(); sized.clear(); listed.clear(); edits.clear(); forgotten = Long.MIN_VALUE }
}
