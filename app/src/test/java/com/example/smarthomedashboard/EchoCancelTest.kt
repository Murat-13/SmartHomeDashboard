package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Эхоподавление одновременного разговора (docs/talk-tz.md, «Одновременный разговор») на модели тракта [EchoSim]:
 * задержки, уровни и доля искажений — как намерено стендом у домофона Reolink 20.11.2026 (эхо на 12 дБ тише
 * отправленного, совпадает с ним примерно на 90 %, возвращается через 0,7 с). Цифры в проверках — с запасом к тому,
 * что получается на модели; настоящий тракт меряется стендом TalkProbe.
 */
class EchoCancelTest {
    private val r = EchoSim.RATE

    /** Подавление эха на отрезке [from]…[to] секунд, дБ: громкость эха на входе к громкости выхода. */
    private fun quieter(echo: DoubleArray, out: DoubleArray, from: Double, to: Double) =
        EchoSim.db(EchoSim.power(echo, (from * r).toInt(), (to * r).toInt()) / EchoSim.power(out, (from * r).toInt(), (to * r).toInt()))

    private fun kept(out: DoubleArray, voice: DoubleArray, from: Double, to: Double) =
        EchoSim.share(out, voice, (from * r).toInt(), (to * r).toInt())

    private fun only(x: DoubleArray, from: Int, to: Int) = DoubleArray(x.size) { if (it in from * r until to * r) x[it] else 0.0 }

    @Test fun fftMatchesDirectTransformAndRoundTrips() {
        val n = 64
        val x = DoubleArray(n) { sin(2 * PI * 5 * it / n) + 0.5 * cos(2 * PI * 12 * it / n) + 0.1 * it }
        val re = x.copyOf(); val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        for (k in 0 until n) {
            var dr = 0.0; var di = 0.0
            for (i in 0 until n) { dr += x[i] * cos(2 * PI * k * i / n); di -= x[i] * sin(2 * PI * k * i / n) }
            assertEquals("Re[$k]", dr, re[k], 1e-9)
            assertEquals("Im[$k]", di, im[k], 1e-9)
        }
        fft.inverse(re, im)
        for (i in 0 until n) { assertEquals(x[i], re[i], 1e-9); assertEquals(0.0, im[i], 1e-9) }
    }

    @Test fun referenceTapKeepsAbsoluteNumberingAndWriteTimes() {
        val tap = RefTap(1_000, seconds = 2)
        var now = 100L
        tap.clock = { now }
        tap.write(ShortArray(500) { it.toShort() }, 500)
        now = 200L
        tap.write(ShortArray(500) { (500 + it).toShort() }, 500)
        assertEquals(1_000L, tap.total)
        val out = DoubleArray(6)
        tap.read(498, out, 0, 6)
        assertEquals(listOf(498.0, 499.0, 500.0, 501.0, 502.0, 503.0), out.toList())
        tap.read(998, out, 0, 6)
        assertEquals("чего ещё нет — нули", listOf(998.0, 999.0, 0.0, 0.0, 0.0, 0.0), out.toList())
        assertEquals(100L, tap.timeOf(10))
        assertEquals(200L, tap.timeOf(700))
        // Кольцо на 2 с: после третьей секунды первая затёрта.
        now = 300L
        repeat(4) { tap.write(ShortArray(500), 500) }
        tap.read(0, out, 0, 6)
        assertTrue("затёртое — нули", out.all { it == 0.0 })
    }

    /** Наш голос, вернувшийся из домофона через 0,7 с: сдвиг находится на первых словах, эхо тише не меньше чем на 25 дБ. */
    @Test fun ownVoiceReturningFromDoorbellIsSuppressed() {
        val own = EchoSim.voice(16.0, 1, 120.0, 4_000.0)
        val echo = EchoSim.farEcho(own, (0.7 * r).toInt() + 37)
        val duplex = DuplexEcho()
        var lockAt = -1
        val out = EchoSim.run(duplex.far, duplex.sent, own, EchoSim.mix(echo, EchoSim.noise(own.size, 15.0, 3))) { t ->
            if (lockAt < 0 && duplex.far.locked) lockAt = t
        }
        assertTrue("сдвиг найден за ${lockAt * 1000L / r} мс", lockAt in 1 until (2.5 * r).toInt())
        assertTrue("пока сдвиг ищется — приглушено: ${quieter(echo, out, 0.3, 2.0)} дБ", quieter(echo, out, 0.3, 2.0) > 20)
        var t = 2.0
        while (t < 16.0) {
            val db = quieter(echo, out, t, t + 2)
            assertTrue("эхо на $t–${t + 2} с тише на $db дБ", db > 25)
            t += 2
        }
        assertEquals("без перезахватов", 0, duplex.far.relocks)
        assertTrue("сводка: ${duplex.far.summary()}", duplex.far.suppressionDb > 25)
    }

    /** Одновременная речь: голос человека у домофона проходит, пока мы говорим; до и после — эхо подавлено. */
    @Test fun guestIsHeardWhileWeTalk() {
        val own = EchoSim.voice(24.0, 1, 120.0, 4_000.0)
        val echo = EchoSim.farEcho(own, (0.7 * r).toInt() + 37)
        val guest = only(EchoSim.voice(24.0, 2, 200.0, 1_500.0), 10, 18)
        val duplex = DuplexEcho()
        val out = EchoSim.run(duplex.far, duplex.sent, own, EchoSim.mix(echo, guest, EchoSim.noise(own.size, 15.0, 3)))
        assertTrue("до гостя: ${quieter(echo, out, 4.0, 10.0)} дБ", quieter(echo, out, 4.0, 10.0) > 25)
        val whole = kept(out, guest, 10.0, 18.0)
        assertTrue("голос гостя прошёл на ${whole * 100} %", whole > 0.75)
        var t = 10.0
        while (t < 18.0) {
            val part = kept(out, guest, t, t + 2)
            assertTrue("гость на $t–${t + 2} с: ${part * 100} %", part > 0.5)
            t += 2
        }
        // Эхо под голосом гостя: то, что осталось в выходе помимо гостя, почти не похоже на эхо.
        val from = 10 * r; val to = 18 * r
        val rest = DoubleArray(out.size) { if (it in from until to) out[it] - whole * guest[it] else 0.0 }
        val leaked = EchoSim.share(rest, echo, from, to)
        assertTrue("доля эха под голосом гостя $leaked", abs(leaked) < 0.25)
        assertTrue("после гостя: ${quieter(echo, out, 19.0, 24.0)} дБ", quieter(echo, out, 19.0, 24.0) > 25)
        assertEquals(0, duplex.far.relocks)
    }

    /** Ближний контур: улица из динамика планшета в его микрофоне, наш голос на 11 дБ тише этого эха. */
    @Test fun streetFromTabletSpeakerIsRemovedFromMicrophone() {
        val street = EchoSim.voice(24.0, 5, 180.0, 2_500.0)
        val echo = EchoSim.nearEcho(street, (0.18 * r).toInt() + 11)
        val own = only(EchoSim.voice(24.0, 6, 110.0, 400.0), 10, 18)
        val duplex = DuplexEcho()
        val out = EchoSim.run(duplex.near, duplex.played, street, EchoSim.mix(echo, own, EchoSim.noise(street.size, 20.0, 8)), refChunk = 320)
        assertTrue(duplex.near.locked)
        assertTrue("улица в микрофоне тише на ${quieter(echo, out, 4.0, 10.0)} дБ", quieter(echo, out, 4.0, 10.0) > 25)
        // Трудный случай: наш голос на 11 дБ тише эха (динамик рядом с микрофоном). Он проходит, но приглушённым —
        // подавитель выбирает «лучше тише, чем с эхом».
        assertTrue("наш голос прошёл на ${kept(out, own, 10.0, 18.0) * 100} %", kept(out, own, 10.0, 18.0) > 0.45)
        assertTrue("после: ${quieter(echo, out, 19.0, 24.0)} дБ", quieter(echo, out, 19.0, 24.0) > 22)
        assertTrue("вычитанием: ${duplex.near.linearDb} дБ", duplex.near.linearDb > 10)
    }

    /** Часы домофона и планшета расходятся на 60 миллионных (отсчёт в секунду): контур находит уход и держится за эхо. */
    @Test fun clockDriftIsFollowed() {
        val own = EchoSim.voice(30.0, 1, 120.0, 4_000.0)
        val echo = EchoSim.drift(EchoSim.farEcho(own, (0.7 * r).toInt() + 37), 60.0)
        val duplex = DuplexEcho()
        val out = EchoSim.run(duplex.far, duplex.sent, own, EchoSim.mix(echo, EchoSim.noise(own.size, 15.0, 3)))
        assertEquals("уход часов, млн⁻¹", 60.0, duplex.far.drift * 1e6, 12.0)
        assertTrue("эхо тише на ${quieter(echo, out, 4.0, 30.0)} дБ", quieter(echo, out, 4.0, 30.0) > 25)
        assertTrue("вычитание работает: ${duplex.far.linearDb} дБ", duplex.far.linearDb > 4)
        assertEquals(0, duplex.far.relocks)
        // Уход запоминается для этой камеры: следующий разговор начинается уже с подстройкой.
        val first = DuplexEcho(key = "camera-a").apply { far.drift = 55e-6 }
        EchoSim.run(first.far, first.sent, own.copyOf(8 * r), EchoSim.farEcho(own, (0.7 * r).toInt()).copyOf(8 * r))
        first.remember()
        assertEquals(first.far.drift, DuplexEcho(key = "camera-a").far.drift, 1e-12)
        assertEquals(0.0, DuplexEcho(key = "camera-b").far.drift, 0.0)
    }

    /** Очередь домофона опустела и набралась заново: эхо скачком стало приходить на 0,4 с позже. */
    @Test fun delayJumpIsReacquired() {
        val own = EchoSim.voice(24.0, 1, 120.0, 4_000.0)
        val before = EchoSim.farEcho(own, (0.7 * r).toInt() + 37)
        val after = EchoSim.farEcho(own, (1.1 * r).toInt() + 5)
        val echo = DoubleArray(own.size) { if (it < 10 * r) before[it] else after[it] }
        val guest = only(EchoSim.voice(24.0, 2, 200.0, 1_500.0), 16, 22)
        val duplex = DuplexEcho()
        val out = EchoSim.run(duplex.far, duplex.sent, own, EchoSim.mix(echo, guest, EchoSim.noise(own.size, 15.0, 3)))
        assertEquals("один перезахват", 1, duplex.far.relocks)
        assertTrue("до скачка: ${quieter(echo, out, 4.0, 10.0)} дБ", quieter(echo, out, 4.0, 10.0) > 25)
        assertTrue("пока сдвиг ищется заново, эхо приглушено: ${quieter(echo, out, 10.0, 12.0)} дБ", quieter(echo, out, 10.0, 12.0) > 6)
        assertTrue("после перезахвата: ${quieter(echo, out, 12.5, 16.0)} дБ", quieter(echo, out, 12.5, 16.0) > 25)
        assertTrue("гость после перезахвата: ${kept(out, guest, 16.0, 22.0) * 100} %", kept(out, guest, 16.0, 22.0) > 0.75)
    }

    /** Эха нет вовсе (динамик домофона выключен): после первых фраз линия открыта, гостя слышно, пока мы говорим. */
    @Test fun noEchoMeansOpenLine() {
        val own = EchoSim.voice(14.0, 1, 120.0, 4_000.0)
        val guest = EchoSim.voice(14.0, 2, 200.0, 1_500.0)
        val duplex = DuplexEcho()
        val out = EchoSim.run(duplex.far, duplex.sent, own, EchoSim.mix(guest, EchoSim.noise(own.size, 15.0, 3)))
        assertFalse(duplex.far.locked)
        assertTrue("гость: ${kept(out, guest, 7.0, 14.0) * 100} %", kept(out, guest, 7.0, 14.0) > 0.95)
        assertTrue(duplex.far.summary(), duplex.far.summary().contains("эха не было"))
    }

    /** Без звука в опоре (мы молчим; между словами — тихий фон комнаты) вход проходит без изменений. */
    @Test fun withoutReferenceTheInputPassesUnchanged() {
        val guest = EchoSim.voice(8.0, 2, 200.0, 1_500.0)
        for (hum in listOf(0.0, 40.0)) {
            val duplex = DuplexEcho()
            val out = EchoSim.run(duplex.far, duplex.sent, EchoSim.noise(guest.size, hum, 9), guest)
            assertEquals("фон опоры $hum", 1.0, kept(out, guest, 1.0, 7.0), 0.01)
            val diff = DoubleArray(out.size) { out[it] - guest[it] }
            assertTrue("искажение", EchoSim.db(EchoSim.power(guest, r, 7 * r) / EchoSim.power(diff, r, 7 * r)) > 30)
            assertFalse(duplex.far.locked)
        }
    }

    /** Задержка контура постоянна (2 блока), а результат не зависит от того, какими кусками приходит звук. */
    @Test fun outputDoesNotDependOnChunkSize() {
        val own = EchoSim.voice(6.0, 1, 120.0, 4_000.0)
        val echo = EchoSim.farEcho(own, (0.5 * r).toInt())
        val capture = EchoSim.mix(echo, EchoSim.noise(own.size, 15.0, 3))
        val outs = listOf(160, 320, 1_024).map { chunk ->
            val duplex = DuplexEcho()
            EchoSim.run(duplex.far, duplex.sent, own, capture, refChunk = 1_024, capChunk = chunk)
        }
        // Опора пишется в одном темпе, вход режется по-разному: первые 3 с (общие для всех размеров) совпадают.
        val n = 3 * r
        for (i in 0 until n) {
            assertEquals("отсчёт $i, куски 160 и 320", outs[0][i], outs[1][i], 1.0)
        }
        assertTrue("куски 1024: ${quieter(echo, outs[2], 3.0, 5.5)} дБ", quieter(echo, outs[2], 3.0, 5.5) > 25)
    }
}
