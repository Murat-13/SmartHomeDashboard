package com.example.smarthomedashboard

import android.util.Log
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Приход каждого кадра видео от камеры — для разбора неровности прихода (docs/doorbell.md, «Нижняя граница»).
 * Пишется только по выключателю: файл `video_debug/arrival_trace` в папке приложения на общей памяти; читается при
 * каждом подключении к камере. Файлы — в `video_debug/trace/`, по одному на сессию потока. Поведение видео и записи не
 * меняется: поток камеры только дописывает строку в память, на носитель пишет свой поток.
 *
 * Строка кадра: `f,<пришёл последний пакет, мкс>,<метка RTP>,<от первого пакета до последнего, мкс>,<байт>,<пакетов>,
 * <ключевой 0/1>,<наше время на потоке чтения за кадр, мкс>`. Строка отчёта отправителя RTCP:
 * `sr,<пришёл, мкс>,<метка RTP>,<часы камеры, мс>`. У больших кадров (от [PACKETS_LISTED] пакетов) — ещё строка
 * `p,<метка RTP>,<приход каждого пакета от первого, в десятках мкс через «;»>`: видно, ровным ли темпом камера
 * отдаёт кадр или пачками. Время — монотонные часы планшета ([System.nanoTime]).
 */
internal class ArrivalTrace(private val file: File) : Closeable {
    private var text = StringBuilder(CHUNK)
    private var lines = 0
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "arrival-trace").apply { isDaemon = true } }
    @Volatile private var failed = false

    private var frameTs = Long.MIN_VALUE
    private var firstNs = 0L
    private var bytes = 0
    private var packets = 0
    private var busyNs = 0L
    private val offsets = IntArray(MAX_LISTED)

    /** Пакет видео с меткой [rtpTs] и [length] байт прочитан в [nowNs]. */
    fun packet(rtpTs: Long, length: Int, nowNs: Long) {
        if (rtpTs != frameTs) { frameTs = rtpTs; firstNs = nowNs; bytes = 0; packets = 0; busyNs = 0 }
        if (packets < MAX_LISTED) offsets[packets] = ((nowNs - firstNs) / 10_000).toInt()
        bytes += length
        packets++
    }

    /** Наша обработка пакета (ретранслятор, разбор, записи) заняла поток чтения на [ns]. */
    fun busy(ns: Long) { busyNs += ns }

    /** Последний пакет кадра ([rtpTs]) прочитан в [lastNs]; [key] — ключевой кадр. */
    fun frame(rtpTs: Long, lastNs: Long, key: Boolean) {
        if (failed) return
        text.append("f,").append(lastNs / 1000).append(',').append(rtpTs).append(',')
            .append(if (rtpTs == frameTs) (lastNs - firstNs) / 1000 else -1).append(',')
            .append(bytes).append(',').append(packets).append(',').append(if (key) 1 else 0).append(',')
            .append(busyNs / 1000).append('\n')
        if (rtpTs == frameTs && packets >= PACKETS_LISTED) {
            text.append("p,").append(rtpTs).append(',')
            for (i in 0 until minOf(packets, MAX_LISTED)) { if (i > 0) text.append(';'); text.append(offsets[i]) }
            text.append('\n')
        }
        frameTs = Long.MIN_VALUE
        if (++lines >= LINES) flush()
    }

    fun senderReport(nowNs: Long, rtpTs: Long, wallMs: Long) {
        if (failed) return
        text.append("sr,").append(nowNs / 1000).append(',').append(rtpTs).append(',').append(wallMs).append('\n')
        if (++lines >= LINES) flush()
    }

    private fun flush() {
        if (text.isEmpty()) return
        val chunk = text.toString()
        text = StringBuilder(CHUNK)
        lines = 0
        io.execute {
            try { file.appendText(chunk) } catch (e: Exception) {
                failed = true
                Log.w(TAG, "Запись прихода кадров остановлена: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        flush()
        io.shutdown()
    }

    companion object {
        private const val TAG = "ArrivalTrace"
        private const val LINES = 500
        private const val CHUNK = 32 * 1024
        const val PACKETS_LISTED = 12
        private const val MAX_LISTED = 1024

        /** Выключатель стоит — новый файл для потока [url]; иначе null. [dir] — папка `video_debug`. */
        fun open(dir: File?, url: String): ArrivalTrace? {
            if (dir == null || !File(dir, "arrival_trace").exists()) return null
            return try {
                val out = File(dir, "trace").apply { mkdirs() }
                val stream = when {
                    url.contains("main", ignoreCase = true) -> "main"
                    url.contains("sub", ignoreCase = true) -> "sub"
                    else -> "s" + (url.hashCode() and 0xFFFF).toString(16)
                }
                val name = "$stream-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"
                Log.i(TAG, "Пишу приход кадров: $name")
                ArrivalTrace(File(out, name))
            } catch (e: Exception) {
                Log.w(TAG, "Приход кадров не пишется: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}

/**
 * Запас плеера живого видео 20 раз в секунду — для разбора, как запас ходит между ключевыми кадрами (тот же выключатель
 * `video_debug/arrival_trace`, файлы `video_debug/trace/player-…`). Строка: `b,<мкс, [System.nanoTime]>,<запас, мс>,
 * <скорость ×100>,<состояние плеера>,<показано кадров>,<выброшено кадров>`. Поведение плеера не меняется.
 */
internal class PlayerTrace(private val file: File) : Closeable {
    private var text = StringBuilder(CHUNK)
    private var lines = 0
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "player-trace").apply { isDaemon = true } }
    @Volatile private var failed = false

    fun sample(nowNs: Long, bufferedMs: Long, speed: Float, state: Int, rendered: Int, dropped: Int, control: String = "") {
        if (failed) return
        text.append("b,").append(nowNs / 1000).append(',').append(bufferedMs).append(',').append((speed * 100).toInt())
            .append(',').append(state).append(',').append(rendered).append(',').append(dropped)
        if (control.isNotEmpty()) text.append(',').append(control)
        text.append('\n')
        if (++lines >= LINES) flush()
    }

    private fun flush() {
        if (text.isEmpty()) return
        val chunk = text.toString()
        text = StringBuilder(CHUNK)
        lines = 0
        io.execute { try { file.appendText(chunk) } catch (_: Exception) { failed = true } }
    }

    override fun close() {
        flush()
        io.shutdown()
    }

    companion object {
        private const val LINES = 200
        private const val CHUNK = 16 * 1024
        const val PERIOD_MS = 50L

        fun open(dir: File?, url: String): PlayerTrace? {
            if (dir == null || !File(dir, "arrival_trace").exists()) return null
            return try {
                val stream = if (url.contains("main", ignoreCase = true)) "main" else if (url.contains("sub", ignoreCase = true)) "sub" else "s"
                PlayerTrace(File(File(dir, "trace").apply { mkdirs() },
                    "player-$stream-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"))
            } catch (_: Exception) { null }
        }
    }
}
