package com.example.smarthomedashboard

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Разбор проверочных записей со стенда (docs/talk-tz.md, «Одновременный разговор»): записи разговора или измерения
 * [TalkProbe], скопированные с планшета в папку, прогоняются через то же эхоподавление, что в приложении, и
 * печатаются цифры — насколько эхо стало тише, какой сдвиг и уход часов. Так настройки проверяются на настоящем
 * звуке без планшета. Запуск: `TALK_REPLAY=<папка> ./gradlew testDebugUnitTest --tests "*EchoReplayTest*" -i`;
 * без переменной тест пропускается. `TALK_GUEST=<файл.pcm>` (16 кГц) — подмешать во вход голос собеседника разной
 * громкости и проверить, проходит ли он. Записи в репозиторий не попадают.
 */
class EchoReplayTest {
    private class Track(val pcm: ShortArray, val rate: Int, val startNs: Long)

    private fun pcm(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
    }

    private fun track(dir: File, name: String): Track? {
        val file = File(dir, "$name.pcm")
        if (!file.exists()) return null
        val rate = File(dir, "$name.rate").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 16_000
        val start = File(dir, "$name.t0").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        return Track(pcm(file), rate, start)
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from.coerceAtLeast(0) until minOf(to, x.size)) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    /**
     * Мощность той части [signal], что повторяет опору [ref] (совпадает с ней по спектрам, усреднённым за отрезок):
     * так эхо отделяется от фона и чужого голоса. [shift] — на сколько отсчётов сигнал отстаёт от опоры.
     */
    private fun coherent(ref: ShortArray, signal: ShortArray, shift: Int, from: Int, to: Int, rate: Int): Double {
        val n = 4_096
        val fft = Fft(n)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val sxx = DoubleArray(n / 2); val sre = DoubleArray(n / 2); val sim = DoubleArray(n / 2)
        val xr = DoubleArray(n); val xi = DoubleArray(n); val dr = DoubleArray(n); val di = DoubleArray(n)
        var t = from
        while (t + n <= minOf(to, signal.size)) {
            val r0 = t - shift
            if (r0 >= 0 && r0 + n <= ref.size) {
                for (i in 0 until n) { xr[i] = ref[r0 + i] * window[i]; xi[i] = 0.0; dr[i] = signal[t + i] * window[i]; di[i] = 0.0 }
                fft.forward(xr, xi); fft.forward(dr, di)
                for (k in 0 until n / 2) {
                    sxx[k] += xr[k] * xr[k] + xi[k] * xi[k]
                    sre[k] += dr[k] * xr[k] + di[k] * xi[k]
                    sim[k] += di[k] * xr[k] - dr[k] * xi[k]
                }
            }
            t += n / 2
        }
        var sum = 0.0
        for (k in 300 * n / rate until 4_000 * n / rate) if (sxx[k] > 0) sum += (sre[k] * sre[k] + sim[k] * sim[k]) / sxx[k]
        return sum
    }

    private class Result(val out: ShortArray, val loop: EchoLoop)

    /** Прогон контура: опора и вход подаются в том же порядке по времени, что на планшете (по началу дорожек). */
    private fun run(loop: EchoLoop, tap: RefTap, ref: Track, cap: ShortArray, lead: Int, rate: Int, log: Boolean): Result {
        var at = 0
        if (log) loop.log = { println("REPLAY   %.1f с: $it".format(at.toDouble() / rate)) }
        val out = ShortArray(cap.size)
        val chunk = rate / 50
        var written = 0
        val buf = ShortArray(chunk)
        while (at + chunk <= cap.size) {
            // К этому мгновению в опоре записано всё до (время входа + lead).
            val refUntil = minOf(ref.pcm.size, at + chunk + lead)
            while (written + 1_024 <= refUntil) { tap.write(ref.pcm.copyOfRange(written, written + 1_024), 1_024); written += 1_024 }
            System.arraycopy(cap, at, buf, 0, chunk)
            loop.process(buf, chunk)
            System.arraycopy(buf, 0, out, at, chunk)
            at += chunk
            if (System.getenv("TALK_TRACE") != null && at % (4 * rate) == 0) println("REPLAY     ${at / rate} с: ${loop.debug()}")
        }
        // Выход совмещается со входом: задержка контура — 2 блока.
        val latency = 2 * EchoLoop.BLOCK
        return Result(ShortArray(out.size) { if (it + latency < out.size) out[it + latency] else 0 }, loop)
    }

    private fun replay(title: String, make: () -> Pair<EchoLoop, RefTap>, ref: Track, cap: Track, onDevice: Track?, guest: ShortArray?) {
        val rate = cap.rate
        val lead = if (ref.startNs != 0L && cap.startNs != 0L) ((cap.startNs - ref.startNs) * rate / 1_000_000_000L).toInt() else 0
        println("REPLAY $title: опора ${ref.pcm.size / rate} с, вход ${cap.pcm.size / rate} с, вход начался на ${lead * 1000L / rate} мс позже опоры")
        val (loop, tap) = make()
        val result = run(loop, tap, ref, cap.pcm, lead, rate, log = true)
        val out = result.out
        val seconds = cap.pcm.size / rate
        println("REPLAY   итог: ${loop.summary()}")
        if (!loop.locked) return
        val shift = loop.alignment.toInt()
        // По 2 с: громкость опоры (там, откуда сейчас приходит эхо), входа, выхода при разборе и выхода на планшете.
        val line = StringBuilder("REPLAY   по 2 с — опора | вход → выход" + (if (onDevice != null) " / на планшете" else "") + ":")
        var s = 0
        while (s + 2 <= seconds) {
            val a = s * rate; val b = (s + 2) * rate
            line.append("\nREPLAY     %2d с: %5.0f | %5.0f → %5.0f".format(s, rms(ref.pcm, a - shift, b - shift), rms(cap.pcm, a, b), rms(out, a, b)))
            if (onDevice != null) line.append(" / %5.0f".format(rms(onDevice.pcm, a + 2 * EchoLoop.BLOCK, b + 2 * EchoLoop.BLOCK)))
            s += 2
        }
        println(line)
        // Участки (секунды «от-до» через запятую в TALK_SEGMENTS): эхо — то, что повторяет опору; остальное — голос
        // собеседника и фон. Сколько осталось того и другого после очистки.
        System.getenv("TALK_SEGMENTS")?.split(',')?.forEach { range ->
            val (a, b) = range.trim().split('-').map { it.trim().toInt() * rate }
            fun parts(x: ShortArray, extra: Int): Pair<Double, Double> {
                val coh = coherent(ref.pcm, x, shift + extra, a + extra, b + extra, rate)
                return coh to (coherent(x, x, 0, a + extra, b + extra, rate) - coh).coerceAtLeast(1.0)
            }
            val (echoIn, otherIn) = parts(cap.pcm, 0)
            val (echoOut, otherOut) = parts(out, 0)
            val text = StringBuilder("REPLAY   участок $range с: эхо тише на %.1f дБ, остальное (голос, фон) изменилось на %+.1f дБ".format(
                10 * log10(echoIn / echoOut), 10 * log10(otherOut / otherIn)))
            if (onDevice != null) {
                val (echoDev, otherDev) = parts(onDevice.pcm, 2 * EchoLoop.BLOCK)
                text.append("; на планшете: эхо тише на %.1f дБ, остальное %+.1f дБ".format(10 * log10(echoIn / echoDev), 10 * log10(otherDev / otherIn)))
            }
            println(text)
        }
        val from = minOf(12, seconds / 3) * rate
        val to = cap.pcm.size
        val before = coherent(ref.pcm, cap.pcm, shift, from, to, rate)
        println("REPLAY   эхо (то, что повторяет опору) с %d-й секунды: тише на %.1f дБ".format(from / rate, 10 * log10(before / coherent(ref.pcm, out, shift, from, to, rate))))
        if (onDevice != null) println("REPLAY   то же на планшете: тише на %.1f дБ".format(
            10 * log10(before / coherent(ref.pcm, onDevice.pcm, shift + 2 * EchoLoop.BLOCK, from, to, rate))))
        File(System.getenv("TALK_REPLAY"), "replay_${title.substringBefore(' ')}.pcm").writeBytes(
            ByteArray(out.size * 2) { i -> if (i % 2 == 0) out[i / 2].toByte() else (out[i / 2].toInt() shr 8).toByte() })
        if (guest == null) return
        // Одновременная речь на настоящем эхе: во вход подмешан голос собеседника разной громкости (к громкости эха).
        val echoRms = rms(cap.pcm, from, to)
        val guestRms = rms(guest, 0, guest.size)
        for (db in listOf(-6, 0, 6, 12)) {
            val gain = echoRms * Math.pow(10.0, db / 20.0) / guestRms
            val added = ShortArray(cap.pcm.size) { if (it >= from) (guest[(it - from) % guest.size] * gain).toInt().coerceIn(-32_768, 32_767).toShort() else 0 }
            val mixed = ShortArray(cap.pcm.size) { (cap.pcm[it] + added[it]).coerceIn(-32_768, 32_767).toShort() }
            val (l2, t2) = make()
            val r2 = run(l2, t2, ref, mixed, lead, rate, log = false)
            var dot = 0.0; var energy = 0.0
            for (i in from until to) { dot += r2.out[i].toDouble() * added[i]; energy += added[i].toDouble() * added[i] }
            println("REPLAY   собеседник %+d дБ к эху: проходит на %.0f %%, эхо под ним тише на %.1f дБ; %s".format(db, 100 * dot / energy,
                10 * log10(before / coherent(ref.pcm, r2.out, shift, from, to, rate)), l2.summary().substringAfter(": ")))
        }
    }

    @Test fun replayRecordedTracks() {
        val path = System.getenv("TALK_REPLAY")
        assumeTrue("нет папки с записями (TALK_REPLAY)", !path.isNullOrBlank() && File(path).isDirectory)
        val dir = File(path!!)
        val guest = System.getenv("TALK_GUEST")?.let { File(it) }?.takeIf { it.isFile }?.let { pcm(it) }
        val sent = track(dir, "sent_ref")
        val street = track(dir, "street_rx")
        if (sent != null && street != null && street.rate == DuplexEcho.RATE) {
            replay("дальний контур (свой голос в звуке улицы)", { DuplexEcho().let { it.far to it.sent } }, sent, street, track(dir, "street_aec"), guest)
        }
        val played = track(dir, "street")
        val mic = track(dir, "mic")
        if (played != null && mic != null && mic.rate == DuplexEcho.RATE && played.rate == DuplexEcho.RATE) {
            replay("ближний контур (улица в микрофоне)", { DuplexEcho().let { it.near to it.played } }, played, mic, track(dir, "mic_aec"), guest)
        }
    }
}
