package com.example.smarthomedashboard

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.WritableByteChannel
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Запись файлов камер на носитель — в своём потоке, а не в потоке, который читает камеру (проверка аудита 23.11.2026,
 * пункт C; docs/recording.md, «Запись на носитель — вне потока камеры»).
 *
 * Раньше фрагменты, закрытие и переименование отрезков шли прямо в потоке чтения камеры. Он же раздаёт кадры живому
 * видео: пока носитель писал, поток стоял, кадры копились у камеры и приходили к плееру пачкой, а плеер на три минуты
 * поднимал запас (задержку). На SD-карте 23.11 поток стоял до 1,3 с (в памяти планшета — до 0,19 с). Теперь поток
 * камеры только кладёт готовые байты в очередь; здесь они пишутся на носитель по порядку, затем файл закрывается,
 * обрезается и переименовывается — тоже по порядку, в том же потоке.
 *
 * Очередь ограничена ([LIMIT_BYTES], ≈ 30 с HD): если носитель отстаёт сильнее, поток камеры ждёт, как раньше, — память
 * не растёт без предела.
 */
internal object RecIo {
    /** Сколько байт может ждать записи на носитель, пока поток камеры не начнёт ждать сам. */
    const val LIMIT_BYTES = 24L * 1024 * 1024
    private const val TAG = "Recording"
    private const val REPORT_MS = 60_000L
    private const val STORAGE = "/storage/"

    /**
     * У каждого носителя — своя очередь и свой поток (второй аудит 25.11.2026, AUD-05): зависшая карта памяти держит только свою
     * очередь, и запись, перешедшая в память планшета, идёт. Порядок внутри носителя прежний.
     */
    private class Lane(name: String) {
        val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor {
            Thread(it, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        val lock = Object()
        /** Байты в очереди; под [lock]. */
        var queued = 0L
        /** Когда носитель последний раз что-то принял (или очередь была пуста); под [lock]. */
        var progressNs = System.nanoTime()
        var queuedMax = 0L
    }
    private val lanes = ConcurrentHashMap<String, Lane>()
    /** Носитель файла: том общей памяти (`/storage/<том>`) или «всё остальное». В тестах — подмена. */
    @Volatile internal var volumeOf: (File) -> String = { f ->
        val p = f.path
        if (p.startsWith(STORAGE)) STORAGE + p.substring(STORAGE.length).substringBefore('/') else ""
    }
    private fun lane(file: File?): Lane = (if (file == null) "" else volumeOf(file)).let { key -> lanes.computeIfAbsent(key) { Lane("rec-io" + if (lanes.isEmpty()) "" else "-${lanes.size + 1}") } }
    /** Носитель не принимает ничего дольше этого, а очередь полна — ошибка записи: поток камеры и живое видео его не ждут. */
    @Volatile internal var stuckMs = 10_000L
    /** Файлы, открытые через очередь и ещё не закрытые до конца: их не трогает восстановление после падения. */
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val channels = ConcurrentHashMap.newKeySet<QueuedFileChannel>()

    // Итоги за минуту для журнала — под замком [stats].
    private val stats = Any()
    private var reportSince = 0L
    private var writes = 0
    private var writtenBytes = 0L
    private var writeMaxNs = 0L
    private var closes = 0
    private var closeMaxNs = 0L

    /** Задача в потоке записи носителя [on] (null — память приложения); ошибка задачи не останавливает очередь. */
    fun execute(on: File? = null, task: () -> Unit) = lane(on).executor.execute {
        try { task() } catch (e: Exception) { Log.w(TAG, "Запись на носитель: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * Выполнить [task] в потоке записи носителя [on] — после всего, что уже в его очереди, — и дождаться результата не дольше
     * [waitMs]. null — не дождались (задача всё равно выполнится) или она упала.
     */
    fun <T> call(waitMs: Long, on: File? = null, task: () -> T): T? = try {
        lane(on).executor.submit(Callable { task() }).get(waitMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        // Поток остановили (запись выключают) — признак прерывания остаётся для того, кто его ждёт.
        Thread.currentThread().interrupt()
        null
    } catch (e: Exception) {
        Log.w(TAG, "Запись на носитель: не дождались (${e.javaClass.simpleName})")
        null
    }

    /** Дождаться, пока записано и закрыто всё, что уже в очередях всех носителей (не дольше [waitMs] на все). */
    fun flush(waitMs: Long): Boolean {
        val until = System.nanoTime() + waitMs * 1_000_000
        val waits = (lanes.values.toList().ifEmpty { listOf(lane(null)) }).map { it.executor.submit(Callable { true }) }
        return waits.all { f ->
            try { f.get(maxOf(0, until - System.nanoTime()), TimeUnit.NANOSECONDS) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); false
            } catch (e: Exception) {
                Log.w(TAG, "Запись на носитель: не дождались (${e.javaClass.simpleName})"); false
            }
        }
    }

    /** Файлы, которые пишутся сейчас (живой набор). */
    fun busyFiles(): Set<String> = busy

    /** Байты в очередях сейчас (для тестов и журнала). */
    val queuedBytes: Long get() = lanes.values.sumOf { synchronized(it.lock) { it.queued } }

    internal fun opened(file: File, channel: QueuedFileChannel): Boolean = busy.add(file.path).also { if (it) channels += channel }

    /** Носитель извлекают: закрыть всё, что на нём открыто, прямо сейчас, не дожидаясь очереди. */
    internal fun abandon() {
        // Чей файл сейчас открывается на медленном носителе — последним: остальные его не ждут.
        channels.filterNot { it.abandonIfEjected(wait = false) }.forEach { it.abandonIfEjected(wait = true) }
    }

    /** Поставить в очередь запись [bytes] байт в [file]; поток камеры ждёт, только если очередь его носителя полна. */
    internal fun write(file: File, bytes: Int, task: () -> Unit) {
        val started = System.nanoTime()
        val lane = lane(file)
        synchronized(lane.lock) {
            if (lane.queued == 0L) lane.progressNs = System.nanoTime()
            while (lane.queued > 0 && lane.queued + bytes > LIMIT_BYTES) {
                if (System.nanoTime() - lane.progressNs > stuckMs * 1_000_000) throw IOException("Носитель не отвечает")
                try { lane.lock.wait(1_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Запись остановлена")
                }
            }
            lane.queued += bytes
            if (lane.queued > lane.queuedMax) lane.queuedMax = lane.queued
        }
        RecBusy.queueWait(System.nanoTime() - started)
        lane.executor.execute {
            val t = System.nanoTime()
            try { task() } finally {
                synchronized(lane.lock) { lane.queued -= bytes; lane.progressNs = System.nanoTime(); lane.lock.notifyAll() }
                synchronized(stats) {
                    writes++
                    writtenBytes += bytes
                    writeMaxNs = maxOf(writeMaxNs, System.nanoTime() - t)
                    report()
                }
            }
        }
    }

    /** Закрыть файл в потоке записи после всех его байтов; [then] — там же, сразу после закрытия. */
    internal fun close(file: File, channel: QueuedFileChannel, closeStream: () -> Unit, then: (() -> Unit)?) = execute(file) {
        val t = System.nanoTime()
        try {
            try { closeStream() } catch (e: IOException) { Log.w(TAG, "Файл записи не закрыт: ${e.message}") }
            then?.invoke()
        } finally {
            lane(file).let { synchronized(it.lock) { it.progressNs = System.nanoTime() } }
            busy -= file.path
            channels -= channel
            synchronized(stats) {
                closes++
                closeMaxNs = maxOf(closeMaxNs, System.nanoTime() - t)
                report()
            }
        }
    }

    /** Раз в минуту: сколько записано и сколько занимала запись на носитель (не в потоке камеры). */
    private fun report() {
        val now = System.currentTimeMillis()
        if (reportSince == 0L) { reportSince = now; return }
        if (now - reportSince < REPORT_MS) return
        val peak = lanes.values.sumOf { l -> synchronized(l.lock) { l.queuedMax.also { l.queuedMax = l.queued } } }
        Log.i(TAG, "Запись на носитель за минуту: ${writtenBytes / 1024} КБ, записей $writes — самая долгая " +
            "${writeMaxNs / 1_000_000} мс; закрытий файлов $closes — до ${closeMaxNs / 1_000_000} мс; " +
            "очередь до ${peak / 1024} КБ (в потоке камеры не ждут)")
        reportSince = now
        writes = 0; writtenBytes = 0; writeMaxNs = 0; closes = 0; closeMaxNs = 0
    }
}

/**
 * Файл, который пишется через [RecIo]: байты копируются и уходят в очередь, сам файл создаётся и пишется в потоке
 * записи. Ошибка носителя (нет места, карту вынули, она не отвечает) приходит при следующей записи — [CameraRecorder] закрывает файл.
 */
internal class QueuedFileChannel(private val file: File) : WritableByteChannel {
    @Volatile private var failure: IOException? = null
    /** Поток записи; закрыть раньше может [abandonIfEjected] — под [lock]. */
    private var out: FileOutputStream? = null
    private val lock = ReentrantLock()
    @Volatile private var open = true

    init {
        // Имя нового файла выбирается свободным ([RecLayout.freeStart]); если всё же занято — два ролика в одном файле
        // недопустимы: ошибка записи (сессия переподключится), а не ожидание в потоке камеры (ревью 24.11.2026).
        // «Свободно?» и «занять» — одним действием: иначе два потока могли занять одно имя (второй аудит 25.11.2026, AUD-06).
        if (RecEject.on(file)) throw IOException(RecEject.MESSAGE)
        if (!RecIo.opened(file, this)) throw IOException("Файл записи уже пишется: ${file.name}")
    }

    /** Файл на извлекаемом носителе — закрыть немедленно; недописанное восстановится, когда носитель вернут. false — занят (открывается). */
    fun abandonIfEjected(wait: Boolean): Boolean {
        if (!RecEject.on(file)) return true
        failure = IOException(RecEject.MESSAGE)
        if (wait) lock.lock() else if (!lock.tryLock()) return false
        try { out?.close() } catch (_: IOException) {} finally { lock.unlock() }
        return true
    }

    private fun stream(): FileOutputStream? = lock.withLock {
        if (failure == null && RecEject.on(file)) failure = IOException(RecEject.MESSAGE)
        if (failure != null) return@withLock null
        val s = out ?: FileOutputStream(file).also { out = it }
        if (failure == null) s else { s.close(); null }
    }

    override fun write(src: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        failure?.let { throw IOException(it.message, it) }
        val n = src.remaining()
        if (n == 0) return 0
        val copy = ByteArray(n).also { src.get(it) }
        RecIo.write(file, n) {
            if (failure == null) {
                try { stream()?.write(copy) } catch (e: Exception) {
                    // Любая ошибка носителя — как раньше, ошибка записи; поток записи не падает (ревью 24.11.2026).
                    if (failure == null) failure = e as? IOException ?: IOException(e)
                }
            }
        }
        return n
    }

    override fun isOpen() = open

    override fun close() = close(null)

    /** Закрыть после всех поставленных байтов; [then] — в потоке записи сразу после закрытия файла. */
    fun close(then: (() -> Unit)?) {
        if (!open) return
        open = false
        RecIo.close(file, this, { lock.withLock { out?.close(); out = null } }, then)
    }
}

/**
 * Носители, которые извлекают. Android закрывает приложение, у которого на извлекаемой карте открыт файл, примерно через 0,1 с после
 * сообщения об извлечении (эмулятор, 25.11.2026): панель исчезла бы со стены. Поэтому файлы записи закрываются сразу, а новых на
 * этом носителе не открывается, пока его не вернут.
 */
internal object RecEject {
    const val MESSAGE = "Носитель извлекают"
    private val volumes = ConcurrentHashMap.newKeySet<String>()

    fun on(file: File): Boolean = volumes.any { file.path == it || file.path.startsWith("$it/") }

    /** Извлекают [volume] (`/storage/…`). */
    fun start(volume: String) { volumes += volume; RecIo.abandon() }

    /** Носитель папки [dir] снова на месте (вернули или извлечь не вышло). */
    fun returned(dir: File) { volumes.removeIf { dir.path.startsWith("$it/") || dir.path == it } }
}

/**
 * Где поток чтения камеры ждёт запись (для строки журнала [SharedStream] раз в минуту). Каждый поток камеры копит своё.
 */
internal object RecBusy {
    private class Stats { var openMaxNs = 0L; var waitMaxNs = 0L }
    private val local = ThreadLocal.withInitial { Stats() }

    /** Открытие нового файла (носитель, место, папка дня). */
    fun open(ns: Long) { local.get()!!.let { it.openMaxNs = maxOf(it.openMaxNs, ns) } }

    /** Ожидание места в очереди записи (носитель отстал больше чем на [RecIo.LIMIT_BYTES]). */
    fun queueWait(ns: Long) { if (ns > 1_000_000) local.get()!!.let { it.waitMaxNs = maxOf(it.waitMaxNs, ns) } }

    /** Итог за минуту в этом потоке и сброс: «новый файл до N мс[, ожидание очереди до M мс]». */
    fun take(): String {
        val s = local.get()!!
        val text = "новый файл до ${s.openMaxNs / 1_000_000} мс" +
            if (s.waitMaxNs > 0) ", ожидание очереди записи до ${s.waitMaxNs / 1_000_000} мс" else ""
        s.openMaxNs = 0; s.waitMaxNs = 0
        return text
    }
}
