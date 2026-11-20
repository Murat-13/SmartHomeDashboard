package com.example.smarthomedashboard

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Разбор проверочных записей со стенда (docs/talk-tz.md, «Одновременный разговор»): записи разговора или измерения
 * [TalkProbe], скопированные с планшета в папку, прогоняются через то же эхоподавление, что в приложении, и
 * печатаются цифры — насколько эхо стало тише, какой сдвиг и уход часов. Так настройки проверяются на настоящем
 * звуке без планшета. Запуск: `TALK_REPLAY=<папка> ./gradlew testDebugUnitTest --tests "*EchoReplayTest*" -i`;
 * без переменной тест пропускается. Записи в репозиторий не попадают.
 */
class EchoReplayTest {
    private class Track(val pcm: ShortArray, val rate: Int, val startNs: Long)

    private fun track(dir: File, name: String): Track? {
        val file = File(dir, "$name.pcm")
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val pcm = ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        val rate = File(dir, "$name.rate").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 16_000
        val start = File(dir, "$name.t0").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        return Track(pcm, rate, start)
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until minOf(to, x.size)) s += x[i].toDouble() * x[i]
        return Math.sqrt(s / (to - from).coerceAtLeast(1))
    }

    /** Прогон контура: опора и вход подаются в том же порядке по времени, что на планшете (по началу дорожек). */
    private fun replay(title: String, loop: EchoLoop, tap: RefTap, ref: Track, cap: Track, onDevice: Track?) {
        val rate = cap.rate
        val lead = if (ref.startNs != 0L && cap.startNs != 0L) ((cap.startNs - ref.startNs) * rate / 1_000_000_000L).toInt() else 0
        println("REPLAY $title: опора ${ref.pcm.size / rate} с, вход ${cap.pcm.size / rate} с, вход начался на ${lead * 1000L / rate} мс позже опоры")
        loop.log = { println("REPLAY   $it") }
        val out = ShortArray(cap.pcm.size)
        val chunk = rate / 50
        var written = 0
        val buf = ShortArray(chunk)
        var at = 0
        while (at + chunk <= cap.pcm.size) {
            // К этому мгновению в опоре записано всё до (время входа + lead).
            val refUntil = minOf(ref.pcm.size, at + chunk + lead)
            while (written + 1_024 <= refUntil) { tap.write(ref.pcm.copyOfRange(written, written + 1_024), 1_024); written += 1_024 }
            System.arraycopy(cap.pcm, at, buf, 0, chunk)
            loop.process(buf, chunk)
            System.arraycopy(buf, 0, out, at, chunk)
            at += chunk
        }
        val latency = 2 * EchoLoop.BLOCK
        val seconds = cap.pcm.size / rate
        val line = StringBuilder("REPLAY   по 2 с (вход → выход, дБ тише")
        if (onDevice != null) line.append("; на планшете")
        line.append("):")
        var s = 0
        while (s + 2 <= seconds) {
            val a = s * rate; val b = (s + 2) * rate
            val inRms = rms(cap.pcm, a, b)
            val outRms = rms(out, a + latency, b + latency)
            line.append(" %.0f→%.0f (%.0f".format(inRms, outRms, 20 * Math.log10((inRms + 1) / (outRms + 1))))
            if (onDevice != null) line.append("; %.0f".format(20 * Math.log10((inRms + 1) / (rms(onDevice.pcm, a + latency, b + latency) + 1))))
            line.append(")")
            s += 2
        }
        println(line)
        println("REPLAY   итог: ${loop.summary()}")
        File(System.getenv("TALK_REPLAY"), "replay_${title.substringBefore(' ')}.pcm").writeBytes(
            ByteArray(out.size * 2) { i -> if (i % 2 == 0) out[i / 2].toByte() else (out[i / 2].toInt() shr 8).toByte() })
    }

    @Test fun replayRecordedTracks() {
        val path = System.getenv("TALK_REPLAY")
        assumeTrue("нет папки с записями (TALK_REPLAY)", !path.isNullOrBlank() && File(path).isDirectory)
        val dir = File(path!!)
        val sent = track(dir, "sent_ref")
        val street = track(dir, "street_rx")
        if (sent != null && street != null && street.rate == DuplexEcho.RATE) {
            val duplex = DuplexEcho()
            replay("дальний контур (свой голос в звуке улицы)", duplex.far, duplex.sent, sent, street, track(dir, "street_aec"))
        }
        val played = track(dir, "street")
        val mic = track(dir, "mic")
        if (played != null && mic != null && mic.rate == DuplexEcho.RATE && played.rate == DuplexEcho.RATE) {
            val duplex = DuplexEcho()
            replay("ближний контур (улица в микрофоне)", duplex.near, duplex.played, played, mic, track(dir, "mic_aec"))
        }
    }
}
