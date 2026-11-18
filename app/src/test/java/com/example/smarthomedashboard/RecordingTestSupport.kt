package com.example.smarthomedashboard

/**
 * Запись с камер: общие помощники тестов. Образцы `recording/sample.h264|h265` — 3 с тестовой картинки 160×120,
 * 10 кадров/с, ключевой кадр раз в секунду, разделители кадров (AUD) — сгенерированы ffmpeg (x264/x265).
 */
internal object RecordingTestSupport {
    fun resource(name: String): ByteArray =
        RecordingTestSupport::class.java.classLoader!!.getResourceAsStream("recording/$name")!!.use { it.readBytes() }

    /** NAL-блоки Annex-B без стартовых кодов. */
    fun nals(annexB: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // позиция NAL, длина стартового кода
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

    /** Кадры по разделителям AUD (H.264 — тип 9, H.265 — 35); сами AUD отбрасываются. */
    fun accessUnits(nals: List<ByteArray>, hevc: Boolean): List<List<ByteArray>> {
        val out = mutableListOf<MutableList<ByteArray>>()
        for (n in nals) {
            val aud = if (hevc) ((n[0].toInt() shr 1) and 0x3F) == 35 else (n[0].toInt() and 0x1F) == 9
            if (aud || out.isEmpty()) out += mutableListOf<ByteArray>()
            if (!aud) out.last() += n
        }
        return out.filter { it.isNotEmpty() }
    }

    /** RTP-пакеты кадра: одиночные NAL или FU-A/FU (RFC 6184/7798) по [mtu]; маркер — на последнем. */
    fun packetize(au: List<ByteArray>, hevc: Boolean, ts: Long, seqStart: Int, pt: Int = 96, mtu: Int = 600): List<ByteArray> {
        val payloads = mutableListOf<ByteArray>()
        for (nal in au) {
            if (nal.size <= mtu) { payloads += nal; continue }
            val headerLen = if (hevc) 2 else 1
            var pos = headerLen
            var first = true
            while (pos < nal.size) {
                val chunk = minOf(mtu, nal.size - pos)
                val last = pos + chunk >= nal.size
                val fu = if (hevc) {
                    val type = (nal[0].toInt() shr 1) and 0x3F
                    byteArrayOf(((nal[0].toInt() and 0x81) or (49 shl 1)).toByte(), nal[1],
                        ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte())
                } else {
                    val type = nal[0].toInt() and 0x1F
                    byteArrayOf(((nal[0].toInt() and 0xE0) or 28).toByte(),
                        ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte())
                }
                payloads += fu + nal.copyOfRange(pos, pos + chunk)
                pos += chunk
                first = false
            }
        }
        return payloads.mapIndexed { i, p -> rtp(pt, i == payloads.lastIndex, (seqStart + i) and 0xFFFF, ts, p) }
    }

    fun rtp(pt: Int, marker: Boolean, seq: Int, ts: Long, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = ((if (marker) 0x80 else 0) or pt).toByte()
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
        h[8] = 0x12; h[9] = 0x34; h[10] = 0x56; h[11] = 0x78
        return h + payload
    }

    /** Верхнеуровневые боксы MP4: тип → количество. */
    fun boxes(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var pos = 0
        while (pos + 8 <= data.size) {
            var size = ((data[pos].toLong() and 0xFF) shl 24) or ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                size = 0
                for (i in 8 until 16) size = (size shl 8) or (data[pos + i].toLong() and 0xFF)
            }
            if (size < 8 || pos + size > data.size) { out += "!$type"; break }
            out += type
            pos += size.toInt()
        }
        return out
    }
}
