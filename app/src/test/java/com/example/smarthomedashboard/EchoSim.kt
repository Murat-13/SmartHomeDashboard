package com.example.smarthomedashboard

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Модель тракта для проверки эхоподавления без планшета (docs/talk-tz.md, «Одновременный разговор»): искусственная
 * речь (слоги с основным тоном и формантами, паузы между фразами), путь эха — задержка, отклик динамика и комнаты,
 * сжатие пиков и шум, зависящий от сигнала (у домофона эхо совпадает с отправленным примерно на 90 %).
 */
internal object EchoSim {
    const val RATE = 16_000

    /** Искусственная речь: [seconds] секунд, основной тон около [pitch] Гц, громкость слогов [rms]. */
    fun voice(seconds: Double, seed: Long, pitch: Double, rms: Double, pause: Double = 1.0): DoubleArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        val phase = DoubleArray(48)
        var t = (rnd.nextDouble() * 0.3 * RATE).toInt()
        while (t < n) {
            repeat(4 + rnd.nextInt(7)) {
                val dur = ((0.10 + 0.18 * rnd.nextDouble()) * RATE).toInt()
                val voiced = rnd.nextDouble() < 0.8
                val f0 = pitch * (0.85 + 0.3 * rnd.nextDouble())
                val formants = doubleArrayOf(300 + 500 * rnd.nextDouble(), 900 + 1_400 * rnd.nextDouble(), 2_400 + 800 * rnd.nextDouble())
                val widths = doubleArrayOf(80.0, 120.0, 160.0)
                val amp = 0.5 + 0.5 * rnd.nextDouble()
                val harmonics = minOf(phase.size, (3_800 / f0).toInt())
                val weights = DoubleArray(harmonics) { h ->
                    val f = (h + 1) * f0
                    var a = 0.0
                    for (j in 0..2) { val d = (f - formants[j]) / widths[j]; a += 1.0 / (1.0 + d * d) }
                    a / sqrt(h + 1.0)
                }
                var prevNoise = 0.0
                for (i in 0 until dur) {
                    if (t + i >= n) break
                    val env = sin(PI * i / dur).let { it * it }
                    var s = 0.0
                    if (voiced) {
                        val f = f0 * (1 + 0.03 * sin(2 * PI * 5 * i / RATE))
                        for (h in 0 until harmonics) {
                            phase[h] += 2 * PI * (h + 1) * f / RATE
                            s += weights[h] * sin(phase[h])
                        }
                    } else {
                        val w = rnd.nextGaussian()
                        s = 0.6 * (w - prevNoise)
                        prevNoise = w
                    }
                    out[t + i] = env * amp * s
                }
                t += dur + ((0.02 + 0.06 * rnd.nextDouble()) * RATE).toInt()
            }
            t += ((0.3 + 0.6 * rnd.nextDouble()) * pause * RATE).toInt()
        }
        var sum = 0.0; var count = 0
        for (v in out) if (v != 0.0) { sum += v * v; count++ }
        val scale = if (count == 0) 0.0 else rms / sqrt(sum / count)
        for (i in out.indices) out[i] *= scale
        return out
    }

    /** Отклик пути: главный отклик на [direct]-м отсчёте, после него затухающие отражения (все вместе — [reverbDb] к прямому звуку); общее усиление [gain]. */
    fun room(seed: Long, direct: Int, gain: Double, rt60Ms: Int, reverbDb: Double, length: Int): DoubleArray {
        val rnd = java.util.Random(seed)
        val h = DoubleArray(length)
        h[direct] = 1.0
        // Отражения все вместе на reverbDb слабее прямого звука (динамик и микрофон рядом — прямой звук главный).
        val decay = 6.9 / (rt60Ms * RATE / 1000.0)
        var tailEnergy = 0.0
        for (i in direct + 1 until length) { h[i] = rnd.nextGaussian() * exp(-decay * (i - direct)); tailEnergy += h[i] * h[i] }
        val tail = sqrt(Math.pow(10.0, reverbDb / 10) / tailEnergy)
        for (i in direct + 1 until length) h[i] *= tail
        var e = 0.0
        for (v in h) e += v * v
        val scale = gain / sqrt(e)
        for (i in h.indices) h[i] *= scale
        return h
    }

    fun convolve(x: DoubleArray, h: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        val taps = h.indices.filter { h[it] != 0.0 }.toIntArray()
        for (i in x.indices) {
            var acc = 0.0
            for (j in taps) { if (i - j < 0) break; acc += h[j] * x[i - j] }
            y[i] = acc
        }
        return y
    }

    fun highPass(x: DoubleArray, hz: Double): DoubleArray {
        val f = SpeechFilter.Biquad.highPass(RATE, hz)
        return DoubleArray(x.size) { f.step(x[it]) }
    }

    /** Сжатие пиков динамиком: до [knee] почти линейно. */
    fun squash(x: DoubleArray, knee: Double) = DoubleArray(x.size) { knee * tanh(x[it] / knee) }

    /** Шум, пропорциональный сигналу (кодек, искажения): доля [sigma] по амплитуде. */
    fun grit(x: DoubleArray, sigma: Double, seed: Long): DoubleArray {
        val rnd = java.util.Random(seed)
        return DoubleArray(x.size) { x[it] * (1 + sigma * rnd.nextGaussian()) }
    }

    /** Те же отсчёты по чуть более медленным часам (уход [ppm] миллионных): точная интерполяция sinc на 32 отсчёта. */
    fun drift(x: DoubleArray, ppm: Double): DoubleArray {
        val half = 16
        return DoubleArray(x.size) { i ->
            val at = i * (1 - ppm * 1e-6)
            val base = Math.floor(at).toInt()
            val frac = at - base
            var acc = 0.0; var sum = 0.0
            for (j in -half + 1..half) {
                val k = base + j
                if (k < 0 || k >= x.size) continue
                val d = j - frac
                val sinc = if (Math.abs(d) < 1e-9) 1.0 else sin(PI * d) / (PI * d)
                val w = 0.5 + 0.5 * Math.cos(PI * d / half)
                acc += x[k] * sinc * w; sum += sinc * w
            }
            if (sum != 0.0) acc / sum else 0.0
        }
    }

    fun delay(x: DoubleArray, samples: Int) = DoubleArray(x.size) { if (it >= samples) x[it - samples] else 0.0 }

    fun noise(n: Int, rms: Double, seed: Long): DoubleArray {
        val rnd = java.util.Random(seed)
        return DoubleArray(n) { rms * rnd.nextGaussian() }
    }

    fun mix(vararg parts: DoubleArray) = DoubleArray(parts[0].size) { i -> parts.sumOf { it[i] } }

    fun shorts(x: DoubleArray) = ShortArray(x.size) { x[it].coerceIn(-32_768.0, 32_767.0).toInt().toShort() }

    fun doubles(x: ShortArray) = DoubleArray(x.size) { x[it].toDouble() }

    fun power(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until minOf(to, x.size)) s += x[i] * x[i]
        return s / (to - from).coerceAtLeast(1)
    }

    fun db(ratio: Double) = 10 * log10(ratio.coerceAtLeast(1e-12))

    /** Доля сигнала [part] в [whole] на отрезке: коэффициент проекции (1 — прошёл целиком, 0 — нет). */
    fun share(whole: DoubleArray, part: DoubleArray, from: Int, to: Int): Double {
        var dot = 0.0; var e = 0.0
        for (i in from until to) { dot += whole[i] * part[i]; e += part[i] * part[i] }
        return if (e == 0.0) 0.0 else dot / e
    }

    /**
     * Прогон контура в «реальном времени»: опора пишется кусками по [refChunk] (как блоки отправки), вход чистится
     * кусками по [capChunk]. Возвращает выход, сдвинутый назад на задержку контура (совмещён со входом).
     */
    fun run(loop: EchoLoop, tap: RefTap, reference: DoubleArray, capture: DoubleArray, refChunk: Int = 1_024, capChunk: Int = 320,
            onChunk: ((Int) -> Unit)? = null): DoubleArray {
        val ref = shorts(reference)
        val cap = shorts(capture)
        val out = ShortArray(cap.size)
        var written = 0
        var t = 0
        val buf = ShortArray(capChunk)
        while (t + capChunk <= cap.size) {
            while (written + refChunk <= t + capChunk) {
                tap.write(ref.copyOfRange(written, written + refChunk), refChunk)
                written += refChunk
            }
            System.arraycopy(cap, t, buf, 0, capChunk)
            loop.process(buf, capChunk)
            System.arraycopy(buf, 0, out, t, capChunk)
            t += capChunk
            onChunk?.invoke(t)
        }
        val latency = 2 * EchoLoop.BLOCK
        return DoubleArray(out.size) { if (it + latency < out.size) out[it + latency].toDouble() else 0.0 }
    }

    /**
     * Путь «отправлено → вернулось с улицы» у домофона: слабые басы динамика, сжатие пиков, короткий отклик, шум
     * кодеков. Доля шума [grit] — по стенду 20.11.2026: вычитание убирает 15 дБ (остаётся ~0,18 по амплитуде).
     */
    fun farEcho(sent: DoubleArray, delaySamples: Int, seed: Long = 7, grit: Double = 0.18): DoubleArray {
        val speaker = squash(highPass(sent, 500.0), 14_000.0)
        val path = convolve(speaker, room(seed, direct = 12, gain = 0.3, rt60Ms = 150, reverbDb = -8.0, length = 1_600))
        return delay(grit(path, grit, seed + 1), delaySamples)
    }

    /** Путь «динамик планшета → микрофон планшета»: рядом, громко, с комнатой. */
    fun nearEcho(played: DoubleArray, delaySamples: Int, gain: Double = 0.8, seed: Long = 11): DoubleArray {
        val speaker = squash(highPass(played, 300.0), 20_000.0)
        val path = convolve(speaker, room(seed, direct = 30, gain = gain, rt60Ms = 400, reverbDb = -10.0, length = 4_800))
        return delay(grit(path, 0.1, seed + 1), delaySamples)
    }
}
