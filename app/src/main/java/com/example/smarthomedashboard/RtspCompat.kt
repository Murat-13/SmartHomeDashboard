package com.example.smarthomedashboard

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Совместимость описания потока (SDP) камеры с RTSP-плеером Media3.
 *
 * Media3 1.11.1 отвергает всё описание целиком, если у звука AAC нет `a=fmtp` с `config`
 * (`missing attribute fmtp` в `RtspMediaTrack`): не играет даже видео. Так отдаёт субпоток Reolink
 * (найдено 19.11.2026 на московском планшете). Такая звуковая дорожка из описания убирается — плеер
 * её не запрашивает, видео идёт. Плитка звук всё равно не играет; запись разбирает описание камеры
 * сама ([AacDepacketizer] без `config` пишет без звука) и этой правки не видит.
 */
internal object Media3Sdp {
    private const val TAG = "RtspCompat"

    /** Описание без дорожек, которые Media3 не примет. Последнюю дорожку не убираем — отказ плеера честнее пустого видео. */
    fun compatible(sdp: String): String {
        val lines = sdp.lines().map { it.trimEnd('\r') }
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }
        if (firstMedia < 0) return sdp
        val blocks = ArrayList<List<String>>()
        var i = firstMedia
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("m=")) j++
            blocks += lines.subList(i, j)
            i = j
        }
        val keep = blocks.filter { block ->
            val track = SdpParser.parse(block.joinToString("\r\n")).firstOrNull()
            track == null || !rejected(track)
        }
        if (keep.size == blocks.size || keep.isEmpty()) return sdp
        Log.i(TAG, "Убрана дорожка, которую плеер не примет (звук AAC без fmtp/config)")
        val out = lines.take(firstMedia) + keep.flatten()
        return out.filter { it.isNotEmpty() }.joinToString("\r\n", postfix = "\r\n")
    }

    /** Ветка AAC в `RtspMediaTrack.generatePayloadFormat`: без fmtp — отказ; mpeg4-generic ещё и без config. */
    internal fun rejected(track: SdpTrack) = when (track.encoding) {
        "MPEG4-GENERIC" -> track.fmtp.isEmpty() || track.fmtp["config"].isNullOrEmpty()
        "MP4A-LATM" -> track.fmtp.isEmpty()
        else -> false
    }
}

/**
 * Сокет RTSP-плеера: ответ на DESCRIBE проходит через [Media3Sdp]. Остальное — байт в байт; после описания
 * (и с первым пакетом `$` в любом случае) поток не разбирается вовсе.
 */
internal class SdpFixingSocket : java.net.Socket() {
    private var wrapped: InputStream? = null

    @Synchronized
    override fun getInputStream(): InputStream = wrapped ?: SdpFixingInputStream(super.getInputStream()).also { wrapped = it }
}

/** Читает ответы RTSP целиком, пока не встретит описание потока; дальше — прозрачная труба. */
internal class SdpFixingInputStream(private val source: InputStream) : InputStream() {
    private var pending: ByteArray? = null
    private var position = 0
    private var passThrough = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        pending?.let { buffer ->
            val n = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, n)
            position += n
            if (position >= buffer.size) { pending = null; position = 0 }
            return n
        }
        if (passThrough) return source.read(b, off, len)
        val message = nextMessage() ?: return -1
        pending = message
        position = 0
        return read(b, off, len)
    }

    override fun available(): Int = pending?.let { it.size - position } ?: if (passThrough) source.available() else 0

    override fun close() = source.close()

    /** Один ответ RTSP (заголовки + тело). null — конец потока. */
    private fun nextMessage(): ByteArray? {
        val first = source.read()
        if (first < 0) return null
        // Пакет RTP поверх RTSP: описание уже было или его не будет.
        if (first == '$'.code) { passThrough = true; return byteArrayOf(first.toByte()) }
        val head = ByteArrayOutputStream()
        head.write(first)
        // Последние четыре байта: конец заголовков — «\r\n\r\n».
        var tail = first
        while (tail != BLANK_LINE) {
            val next = source.read()
            if (next < 0) return head.toByteArray()
            head.write(next)
            tail = (tail shl 8) or next
            if (head.size() > MAX_HEAD) { passThrough = true; return head.toByteArray() }
        }
        val headText = head.toString(Charsets.UTF_8.name())
        val headerLines = headText.split("\r\n").filter { it.isNotEmpty() }
        val length = header(headerLines, "content-length")?.toIntOrNull() ?: 0
        if (length <= 0) return head.toByteArray()
        if (length > MAX_BODY) { passThrough = true; return head.toByteArray() + readExactly(length) }
        val body = readExactly(length)
        if (header(headerLines, "content-type")?.contains("application/sdp", ignoreCase = true) != true) {
            return head.toByteArray() + body
        }
        passThrough = true
        val original = String(body, Charsets.UTF_8)
        val fixed = Media3Sdp.compatible(original)
        if (fixed == original) return head.toByteArray() + body
        val fixedBytes = fixed.toByteArray(Charsets.UTF_8)
        val rebuilt = headerLines.map { line ->
            if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) "Content-Length: ${fixedBytes.size}" else line
        }
        return (rebuilt.joinToString("\r\n", postfix = "\r\n\r\n")).toByteArray(Charsets.UTF_8) + fixedBytes
    }

    private fun readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = source.read(out, read, length - read)
            if (n < 0) throw IOException("Ответ RTSP оборвался")
            read += n
        }
        return out
    }

    private fun header(lines: List<String>, name: String) = lines.drop(1)
        .firstOrNull { it.substringBefore(':').trim().lowercase(Locale.ROOT) == name }
        ?.substringAfter(':')?.trim()

    private companion object {
        const val BLANK_LINE = 0x0D0A0D0A
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 256 * 1024
    }
}
