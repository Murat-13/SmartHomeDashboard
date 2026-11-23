package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Мелодия домофона в его микрофоне ([ChimeDetector]). Синтетика повторяет то, что записано у домофона владельца
 * 23.11.2026: удар «динь» — сначала ≈ 0,3 с низкого призвука, затем высокий тон ≈ 2,55 кГц, затухающий за ≈ 1 с; пауза;
 * удар «дон» — так же, 0,9 с. Фон улицы — около −48 дБ; речь — гласные на низких частотах и шипящие (шум).
 */
class ChimeDetectorTest {
    private val rate = 16_000
    private val rnd = Random(7)

    private fun silence(ms: Int) = ShortArray(rate * ms / 1000) { (rnd.nextDouble(-1.0, 1.0) * 220).toInt().toShort() }

    private fun strike(ms: Int, tone: Double = 2_550.0): ShortArray = ShortArray(rate * ms / 1000) { i ->
        val t = i.toDouble() / rate
        val low = if (t < 0.3) 6_000 * sin(2 * PI * 1_150 * t) else 0.0
        val high = 7_000 * exp(-t * 1.9) * sin(2 * PI * tone * t)
        (low + high + rnd.nextDouble(-1.0, 1.0) * 220).toInt().coerceIn(-32_000, 32_000).toShort()
    }

    /** Речь: гласная (основной тон 140 Гц и гармоники) и шипящая (громкий шум) по очереди. */
    private fun speech(ms: Int): ShortArray = ShortArray(rate * ms / 1000) { i ->
        val t = i.toDouble() / rate
        val syllable = (t * 4).toInt()
        val v = if (syllable % 3 == 2) rnd.nextDouble(-1.0, 1.0) * 5_000
        else (1..8).sumOf { h -> 1_500.0 / h * sin(2 * PI * 140 * h * t) }
        (v + rnd.nextDouble(-1.0, 1.0) * 220).toInt().toShort()
    }

    private fun join(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** Подать звук порциями по 64 мс (как приходит AAC); что решил детектор и на какой миллисекунде звука. */
    private fun run(d: ChimeDetector, pcm: ShortArray, until: Set<ChimeDetector.Verdict>): Pair<ChimeDetector.Verdict, Long> {
        var at = 0
        while (at < pcm.size) {
            val n = minOf(1024, pcm.size - at)
            val v = d.push(pcm.copyOfRange(at, at + n), n)
            at += n
            if (v in until) return v to at * 1000L / rate
        }
        return d.verdict to at * 1000L / rate
    }

    private val done = setOf(ChimeDetector.Verdict.NONE, ChimeDetector.Verdict.ENDED)
    private val dingDong get() = join(silence(450), strike(1_300), silence(1_200), strike(1_200))

    /** Слушать начали раньше обычного (за 1,2 с до удара): «мелодии нет» не решается, пока не прошло [noneAt] от звонка. */
    @Test fun earlyListeningStillCatchesChime() {
        val d = ChimeDetector(rate, noneAtMs = 1_300)
        val (v, _) = run(d, join(silence(900), strike(1_300), silence(1_200), strike(1_200), silence(4_000)), done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        assertTrue(d.heardWhole)
    }

    @Test fun chimeEndsAfterPauseLongerThanBetweenStrikes() {
        val d = ChimeDetector(rate)
        val (v, at) = run(d, join(dingDong, silence(4_000)), done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        val lastStrikeEnd = 450 + 1_300 + 1_200 + 1_200
        assertTrue("стихла через $at мс (удары кончились к $lastStrikeEnd)", at in lastStrikeEnd..lastStrikeEnd + 2_300)
        assertTrue("мелодия от начала: ${d.onsetMs}", d.heardWhole)
        assertTrue("длина ${d.chimeMs}", d.chimeMs in 2_800L..4_000L)
    }

    /** Со второго звонка длина известна — конец виден сразу, без ожидания паузы между ударами. */
    @Test fun learnedLengthEndsRightAfterLastStrike() {
        val first = ChimeDetector(rate)
        run(first, join(dingDong, silence(4_000)), done)
        val second = ChimeDetector(rate, first.chimeMs)
        val (v, at) = run(second, join(dingDong, silence(4_000)), done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        val lastStrikeEnd = 450 + 1_300 + 1_200 + 1_200
        assertTrue("стихла через $at мс (удары кончились к $lastStrikeEnd)", at <= lastStrikeEnd + 400)
    }

    /** Мелодии нет (выключена или уже доиграла) — решено за секунду; речь и шипящие мелодией не считаются. */
    @Test fun noChimeIsDecidedInASecondEvenWithSpeech() {
        val (v, at) = run(ChimeDetector(rate), join(speech(3_000), silence(2_000)), done)
        assertEquals(ChimeDetector.Verdict.NONE, v)
        assertTrue("решено через $at мс", at <= 2_600)
        val quiet = run(ChimeDetector(rate), silence(3_000), done)
        assertEquals(ChimeDetector.Verdict.NONE, quiet.first)
        assertTrue("в тишине решено через ${quiet.second} мс", quiet.second <= 1_100)
    }

    /** Гость заговорил сразу после «дон»: его речь не продлевает мелодию. */
    @Test fun speechRightAfterChimeDoesNotProlongIt() {
        val (_, plain) = run(ChimeDetector(rate), join(dingDong, silence(4_000)), done)
        val (v, talking) = run(ChimeDetector(rate), join(dingDong, speech(4_000)), done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        assertTrue("с речью стихла через $talking мс, без неё — через $plain", talking <= plain + 100)
    }

    /** Сколько бы ни звучало похожее, дольше [ChimeDetector.MAX_MS] не слушаем. */
    @Test fun endlessToneStopsListeningAtLimit() {
        val tone = ShortArray(rate * 12) { i -> (3_000 * sin(2 * PI * 2_550 * i / rate)).toInt().toShort() }
        val (v, at) = run(ChimeDetector(rate), tone, done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        assertTrue("$at", at in ChimeDetector.MAX_MS..ChimeDetector.MAX_MS + 100)
    }

    /**
     * Звонок 23.11 в 13:07: тон первого удара забит другим звуком уже через 0,5 с, потом пауза и второй удар. Раньше
     * «мелодии нет» решалось в паузе — канал открывался перед вторым ударом, и голос опять копился в домофоне.
     */
    @Test fun maskedFirstStrikeStillWaitsForSecond() {
        val masked = strike(1_300).also { s ->
            for (i in rate * 500 / 1000 until s.size) s[i] = (s[i] / 6 + 9_000 * sin(2 * PI * 180 * i / rate)).toInt().toShort()
        }
        val d = ChimeDetector(rate, learnedMs = 3_400, noneAtMs = 1_000)
        val (v, at) = run(d, join(silence(300), masked, silence(1_000), strike(1_200), silence(3_000)), done)
        assertEquals(ChimeDetector.Verdict.ENDED, v)
        val secondEnd = 300 + 1_300 + 1_000 + 1_200
        assertTrue("стихла через $at мс — не раньше конца второго удара ($secondEnd)", at >= secondEnd - 300)
    }

    /**
     * Настоящий звук домофона (WAV 16 кГц, вне репозитория: там голоса): `CHIME_REPLAY=<папка>`. В папке `rings.txt` —
     * строки «файл, секунда прихода звонка, секунда конца мелодии или none»; файлы `street_*.wav` — запись улицы без
     * звонков. Слушать начинаем через 0,3 с после звонка, как на планшете.
     */
    @Test fun replayRecordedDoorbellAudio() {
        val path = System.getenv("CHIME_REPLAY")
        assumeTrue("нет папки с записями (CHIME_REPLAY)", !path.isNullOrBlank() && File(path).isDirectory)
        val dir = File(path!!)
        File(dir, "rings.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.forEach { line ->
            val (name, ringS, endS) = line.trim().split(Regex("\\s+"))
            val pcm = wav(File(dir, name))
            val start = ((ringS.toDouble() + 0.3) * rate).toInt()
            val d = ChimeDetector(rate, DoorChime.DEFAULT_CHIME_MS, DoorChime.NONE_AFTER_RING_MS - 300)
            val (v, at) = run(d, pcm.copyOfRange(start, pcm.size), done)
            val afterRing = at + 300
            println("CHIME $line: $v через $afterRing мс после звонка, тон с ${d.onsetMs + 300} мс, длина ${d.chimeMs}")
            if (endS == "none") {
                assertTrue("$line: решено через $afterRing мс", v !in done || afterRing <= 5_000)
            } else {
                val end = ((endS.toDouble() - ringS.toDouble()) * 1000).toLong()
                assertEquals(line, ChimeDetector.Verdict.ENDED, v)
                assertTrue("$line: стихла по записи через $end мс, решено через $afterRing", afterRing in end..end + 1_000)
            }
        }
        var tries = 0; var waited = 0L
        dir.listFiles { f -> f.name.startsWith("street") && f.name.endsWith(".wav") }!!.sorted().forEach { f ->
            val pcm = wav(f)
            var start = 0
            // Каждые 2 с — как будто позвонили: сколько ждали бы зря (мелодии в записи улицы нет).
            while (start + rate * 6 < pcm.size) {
                val (v, at) = run(ChimeDetector(rate, DoorChime.DEFAULT_CHIME_MS, 1_000), pcm.copyOfRange(start, start + rate * 6), done)
                assertTrue("${f.name} с ${start / rate} с: $v", v in done)
                tries++; waited += at - 1_000
                start += rate * 2
            }
        }
        if (tries > 0) println("CHIME улица: $tries проб, лишнее ожидание в среднем ${waited / tries} мс")
    }

    private fun wav(f: File): ShortArray {
        val b = f.readBytes()
        var k = 12
        while (k + 8 <= b.size) {
            val id = String(b, k, 4, Charsets.US_ASCII)
            val len = (b[k + 4].toInt() and 0xFF) or ((b[k + 5].toInt() and 0xFF) shl 8) or
                ((b[k + 6].toInt() and 0xFF) shl 16) or ((b[k + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val n = minOf(len, b.size - k - 8) / 2
                return ShortArray(n) { i -> ((b[k + 8 + 2 * i].toInt() and 0xFF) or (b[k + 9 + 2 * i].toInt() shl 8)).toShort() }
            }
            k += 8 + len + (len and 1)
        }
        error("в ${f.name} нет звука")
    }
}
