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

    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "rec-io").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val lock = Object()
    /** Байты в очереди; под [lock]. */
    private var queued = 0L
    private var queuedMax = 0L
    /** Файлы, открытые через очередь и ещё не закрытые до конца: их не трогает восстановление после падения. */
    private val busy = ConcurrentHashMap.newKeySet<String>()

    // Итоги за минуту для журнала — только поток записи.
    private var reportSince = 0L
    private var writes = 0
    private var writtenBytes = 0L
    private var writeMaxNs = 0L
    private var closes = 0
    private var closeMaxNs = 0L

    /** Задача в потоке записи; ошибка задачи не останавливает очередь. */
    fun execute(task: () -> Unit) = executor.execute {
        try { task() } catch (e: Exception) { Log.w(TAG, "Запись на носитель: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * Выполнить [task] в потоке записи — после всего, что уже в очереди, — и дождаться результата не дольше [waitMs].
     * null — не дождались (задача всё равно выполнится) или она упала.
     */
    fun <T> call(waitMs: Long, task: () -> T): T? = try {
        executor.submit(Callable { task() }).get(waitMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "Запись на носитель: не дождались (${e.javaClass.simpleName})")
        null
    }

    /** Дождаться, пока записано и закрыто всё, что уже в очереди (не дольше [waitMs]). */
    fun flush(waitMs: Long): Boolean = call(waitMs) { true } == true

    /** Файлы, которые пишутся сейчас (живой набор). */
    fun busyFiles(): Set<String> = busy

    /** Байты в очереди сейчас (для тестов и журнала). */
    val queuedBytes: Long get() = synchronized(lock) { queued }

    internal fun opened(file: File) { busy += file.path }

    /** Поставить в очередь запись [bytes] байт; поток камеры ждёт, только если очередь полна. */
    internal fun write(bytes: Int, task: () -> Unit) {
        val started = System.nanoTime()
        synchronized(lock) {
            while (queued > 0 && queued + bytes > LIMIT_BYTES) {
                try { lock.wait(1_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Запись остановлена")
                }
            }
            queued += bytes
            if (queued > queuedMax) queuedMax = queued
        }
        RecBusy.queueWait(System.nanoTime() - started)
        executor.execute {
            val t = System.nanoTime()
            try { task() } finally {
                synchronized(lock) { queued -= bytes; lock.notifyAll() }
                writes++
                writtenBytes += bytes
                writeMaxNs = maxOf(writeMaxNs, System.nanoTime() - t)
                report()
            }
        }
    }

    /** Закрыть файл в потоке записи после всех его байтов; [then] — там же, сразу после закрытия. */
    internal fun close(file: File, closeStream: () -> Unit, then: (() -> Unit)?) = execute {
        val t = System.nanoTime()
        try {
            try { closeStream() } catch (e: IOException) { Log.w(TAG, "Файл записи не закрыт: ${e.message}") }
            then?.invoke()
        } finally {
            busy -= file.path
            closes++
            closeMaxNs = maxOf(closeMaxNs, System.nanoTime() - t)
            report()
        }
    }

    /** Раз в минуту: сколько записано и сколько занимала запись на носитель (не в потоке камеры). */
    private fun report() {
        val now = System.currentTimeMillis()
        if (reportSince == 0L) { reportSince = now; return }
        if (now - reportSince < REPORT_MS) return
        val peak = synchronized(lock) { queuedMax.also { queuedMax = queued } }
        Log.i(TAG, "Запись на носитель за минуту: ${writtenBytes / 1024} КБ, записей $writes — самая долгая " +
            "${writeMaxNs / 1_000_000} мс; закрытий файлов $closes — до ${closeMaxNs / 1_000_000} мс; " +
            "очередь до ${peak / 1024} КБ (в потоке камеры не ждут)")
        reportSince = now
        writes = 0; writtenBytes = 0; writeMaxNs = 0; closes = 0; closeMaxNs = 0
    }
}

/**
 * Файл, который пишется через [RecIo]: байты копируются и уходят в очередь, сам файл создаётся и пишется в потоке
 * записи. Ошибка носителя (нет места, карту вынули) приходит при следующей записи — как и раньше, обрывом сессии записи.
 */
internal class QueuedFileChannel(private val file: File) : WritableByteChannel {
    @Volatile private var failure: IOException? = null
    /** Только поток записи. */
    private var out: FileOutputStream? = null
    @Volatile private var open = true

    init {
        // Тот же файл ещё дописывается или закрывается (новый ролик с того же ключевого кадра): дождаться, иначе в один
        // файл писали бы два ролика.
        if (file.path in RecIo.busyFiles()) RecIo.flush(5_000)
        RecIo.opened(file)
    }

    override fun write(src: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        failure?.let { throw IOException(it.message, it) }
        val n = src.remaining()
        if (n == 0) return 0
        val copy = ByteArray(n).also { src.get(it) }
        RecIo.write(n) {
            if (failure == null) {
                try { (out ?: FileOutputStream(file).also { out = it }).write(copy) } catch (e: IOException) { failure = e }
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
        RecIo.close(file, { out?.close(); out = null }, then)
    }
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
