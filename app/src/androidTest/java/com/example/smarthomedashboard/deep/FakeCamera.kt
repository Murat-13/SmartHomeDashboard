package com.example.smarthomedashboard.deep

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Декодер эмулятора Android 10 и старше образец не показывает (DECODING_FAILED): видео там не проверить. */
internal const val OLD_EMULATOR = "Эмулятор Android 10 и старше не декодирует образец"

/**
 * Поддельная камера RTSP в процессе теста: настоящий H.264 из образца ffmpeg (160×120, 10 кадров/с, ключевой раз в секунду), по
 * кругу, без сети и камер владельца. Со странностями других марок (docs/cameras.md): [sha256] — вход только Digest SHA-256,
 * [wrongHost] — в описании чужой адрес, [asks] — камера сама спрашивает внутри потока и без ответа рвёт сессию.
 */
internal class FakeCamera(private val sha256: Boolean = false, private val wrongHost: Boolean = false, private val asks: Boolean = false, path: String = "cam") : Closeable {
    private val server = ServerSocket(0)
    val connections = AtomicInteger()
    val url = "rtsp://127.0.0.1:${server.localPort}/$path"
    internal val units: List<List<ByteArray>>
    private val sdp: String

    init {
        val sample = instrumentation.context.assets.open("recording/sample.h264").use { it.readBytes() }
        units = accessUnits(nals(sample))
        val sps = units[0].first { (it[0].toInt() and 0x1F) == 7 }
        val pps = units[0].first { (it[0].toInt() and 0x1F) == 8 }
        val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
        val control = if (wrongHost) "rtsp://192.0.2.77:554/cam/trackID=1" else "trackID=1"
        sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=fake\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=control:$control\r\n" +
            "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=${b64(sps)},${b64(pps)}\r\n"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                connections.incrementAndGet()
                thread(isDaemon = true) { try { serve(socket) } catch (_: Exception) {} finally { socket.close() } }
            }
        }
    }

    override fun close() = server.close()

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun allowed(header: String?, method: String, uri: String): Boolean {
        if (!sha256) return true
        val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(header.orEmpty()).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
        return p["algorithm"] == "SHA-256" && p["uri"] == uri && p["response"] ==
            hash("${hash("$USER:cam:$PASSWORD")}:n1:${p["nc"]}:${p["cnonce"]}:auth:${hash("$method:$uri")}")
    }

    private fun line(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.append(b.toChar())
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val answered = AtomicInteger()
        fun reply(cseq: String, headers: String = "", body: String = "", status: String = "200 OK") = synchronized(out) {
            val b = body.toByteArray()
            out.write(("RTSP/1.0 $status\r\nCSeq: $cseq\r\n$headers" + (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
            out.flush()
        }
        while (true) {
            val first = line(input) ?: return
            if (first.isEmpty()) continue
            val h = mutableMapOf<String, String>()
            while (true) { val l = line(input) ?: return; if (l.isEmpty()) break; h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            if (first.startsWith("RTSP/")) { answered.incrementAndGet(); continue }
            val cseq = h["cseq"].orEmpty()
            val (method, uri) = first.split(' ')
            if (!allowed(h["authorization"], method, uri)) {
                reply(cseq, "WWW-Authenticate: Digest realm=\"cam\", nonce=\"n1\", algorithm=SHA-256, qop=\"auth\"\r\n", status = "401 Unauthorized")
                continue
            }
            when (method) {
                "OPTIONS" -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: ${if (wrongHost) "rtsp://0.0.0.0:554/cam" else url}/\r\n", sdp)
                "SETUP" -> reply(cseq, "Session: 77;timeout=60\r\nTransport: ${h["transport"]}\r\n")
                "PLAY" -> {
                    reply(cseq, "Session: 77\r\n")
                    thread(isDaemon = true) { try { play(s, out, answered) } catch (_: Exception) {} }
                }
                "TEARDOWN" -> { reply(cseq); return }
                else -> reply(cseq)
            }
        }
    }

    private fun play(s: Socket, out: java.io.OutputStream, answered: AtomicInteger) {
        var seq = 0
        var frame = 0
        while (!s.isClosed) {
            val au = units[frame % units.size]
            for (p in packetize(au, frame * 9_000L, seq)) {
                synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p) }
                seq = (seq + 1) and 0xFFFF
            }
            synchronized(out) { out.flush() }
            frame++
            if (asks && frame % 20 == 0) {
                val before = answered.get()
                synchronized(out) { out.write("OPTIONS $url RTSP/1.0\r\nCSeq: ${9_000 + frame}\r\n\r\n".toByteArray()); out.flush() }
                Thread.sleep(1_500)
                if (answered.get() == before) { s.close(); return }
            }
            Thread.sleep(100)
        }
    }

    companion object { const val USER = "admin"; const val PASSWORD = "deep-secret" }

    // ---- H.264 → RTP (как в RecordingTestSupport JVM-тестов) ----

    private fun nals(annexB: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 <= annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                if (annexB[i + 2].toInt() == 1) { starts += (i + 3) to 3; i += 3; continue }
                if (i + 4 <= annexB.size && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) { starts += (i + 4) to 4; i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { index, (pos, _) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first - starts[index + 1].second else annexB.size
            annexB.copyOfRange(pos, end)
        }.filter { it.isNotEmpty() }
    }

    private fun accessUnits(nals: List<ByteArray>): List<List<ByteArray>> {
        val out = mutableListOf<MutableList<ByteArray>>()
        for (n in nals) {
            val aud = (n[0].toInt() and 0x1F) == 9
            if (aud || out.isEmpty()) out += mutableListOf<ByteArray>()
            if (!aud) out.last() += n
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun packetize(au: List<ByteArray>, ts: Long, seqStart: Int, mtu: Int = 1_200): List<ByteArray> {
        val payloads = mutableListOf<ByteArray>()
        for (nal in au) {
            if (nal.size <= mtu) { payloads += nal; continue }
            var pos = 1
            var first = true
            while (pos < nal.size) {
                val chunk = minOf(mtu, nal.size - pos)
                val last = pos + chunk >= nal.size
                val type = nal[0].toInt() and 0x1F
                payloads += byteArrayOf(((nal[0].toInt() and 0xE0) or 28).toByte(),
                    ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()) + nal.copyOfRange(pos, pos + chunk)
                pos += chunk
                first = false
            }
        }
        return payloads.mapIndexed { i, p ->
            val seq = (seqStart + i) and 0xFFFF
            val h = ByteArray(12)
            h[0] = 0x80.toByte()
            h[1] = ((if (i == payloads.lastIndex) 0x80 else 0) or 96).toByte()
            h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
            h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
            h[8] = 0x12; h[9] = 0x34; h[10] = 0x56; h[11] = 0x78
            h + p
        }
    }
}
