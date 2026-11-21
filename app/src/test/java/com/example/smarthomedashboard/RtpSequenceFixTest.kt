package com.example.smarthomedashboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Свои номера RTP-пакетов для Media3 ([RtpSequenceFix]): очередь Media3 теряет пакет № 0 после № 65535, а с ним кадр.
 * Очередь в тестах — настоящий класс Media3 (он закрыт, поэтому через отражение), вызовы — как в его `RtpExtractor`.
 */
class RtpSequenceFixTest {

    /** `RtpPacketReorderingQueue` из Media3: положить пакет и сразу взять один, как делает `RtpExtractor.read`. */
    private class Media3Queue {
        private val type = Class.forName("androidx.media3.exoplayer.rtsp.RtpPacketReorderingQueue")
        private val queue = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        private val offer = type.getDeclaredMethod("offer", androidx.media3.exoplayer.rtsp.RtpPacket::class.java,
            Long::class.javaPrimitiveType).apply { isAccessible = true }
        private val poll = type.getDeclaredMethod("poll", Long::class.javaPrimitiveType).apply { isAccessible = true }
        private var nowMs = 1_000L

        /** Номер пакета, который очередь отдала разборщику; null — ничего (пакет отброшен или придержан). */
        fun pass(sequence: Int): Int? {
            nowMs += 2
            val packet = androidx.media3.exoplayer.rtsp.RtpPacket.Builder()
                .setSequenceNumber(sequence).setTimestamp(0).setPayloadData(ByteArray(1)).build()
            offer.invoke(queue, packet, nowMs)
            return (poll.invoke(queue, nowMs - CUTOFF_MS) as androidx.media3.exoplayer.rtsp.RtpPacket?)?.sequenceNumber
        }

        companion object { const val CUTOFF_MS = 30L }
    }

    /**
     * Причина обхода. Если этот тест упал после обновления Media3 — ошибку там исправили: [RtpSequenceFix] больше не
     * нужен (достаточно `renumber = false` в `ConnectTimeoutSocketFactory`).
     */
    @Test fun media3LosesPacketZeroAfterWrap() {
        val queue = Media3Queue()
        for (sequence in 65_530..65_535) assertEquals(sequence, queue.pass(sequence))
        assertNull("пакет № 0 после № 65535 очередь Media3 отбрасывает", queue.pass(0))
        assertNull("а следующие придерживает: по номерам перед ними пропуск", queue.pass(1))
    }

    @Test fun renumberedStreamPassesMedia3QueueWithoutLossAcrossManyWraps() {
        val queue = Media3Queue()
        val restarts = AtomicInteger()
        val fix = RtpSequenceFix(restarts)
        var camera = 65_000                 // камера трижды переходит через ноль
        var previous = 0
        var previousMarker = false
        repeat(200_000) { index ->
            val marker = index % FRAME == FRAME - 1
            val out = fix.map(0, camera, marker)
            assertEquals("пакет $index (номер камеры $camera) дошёл до разборщика сразу", out, queue.pass(out))
            if (index > 0 && out != previous + 1) assertTrue("отсчёт начинается заново только на границе кадров", previousMarker)
            assertTrue("до перехода через ноль номера не доходят", out in 1..40_000)
            previous = out
            previousMarker = marker
            camera = (camera + 1) and 0xFFFF
        }
        assertEquals("отсчёт начат заново столько раз, сколько раз номера дошли до середины", 200_000 / 32_784, restarts.get())
    }

    @Test fun firstPacketStartsOwnCountAndCameraGapsStayVisible() {
        val fix = RtpSequenceFix()
        assertEquals(1, fix.map(0, 41_000, marker = false))
        assertEquals(2, fix.map(0, 41_001, marker = true))
        // Камера (или ретранслятор) потеряла три пакета: пропуск тот же — Media3 должна его увидеть.
        assertEquals(6, fix.map(0, 41_005, marker = false))
        // Звук на своём канале считается отдельно.
        assertEquals(1, fix.map(2, 7, marker = true))
        assertEquals(7, fix.map(0, 41_006, marker = false))
    }

    @Test fun streamWithoutFrameMarkersStillRestartsBeforeWrap() {
        val queue = Media3Queue()
        val restarts = AtomicInteger()
        val fix = RtpSequenceFix(restarts)
        var lost = 0
        repeat(140_000) { index ->
            val out = fix.map(0, index and 0xFFFF, marker = false)
            if (queue.pass(out) != out) lost++
            assertTrue(out in 1..64_000)
        }
        assertEquals("очередь Media3 принимает и такой скачок без потерь", 0, lost)
        assertEquals(2, restarts.get())
    }

    // ---- Поток RTSP ----

    private fun rtp(channel: Int, sequence: Int, marker: Boolean, payload: Int = 40): ByteArray {
        val packet = ByteArray(12 + payload) { (it * 31 + sequence).toByte() }
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        packet[2] = (sequence ushr 8).toByte()
        packet[3] = sequence.toByte()
        return frame(channel, packet)
    }

    private fun frame(channel: Int, packet: ByteArray) =
        byteArrayOf('$'.code.toByte(), channel.toByte(), (packet.size ushr 8).toByte(), packet.size.toByte()) + packet

    private fun senderReport(channel: Int): ByteArray {
        val packet = ByteArray(28) { (0xF0 + it).toByte() }   // байты 2–3 (длина отчёта) не должны измениться
        packet[0] = 0x80.toByte()
        packet[1] = 200.toByte()
        return frame(channel, packet)
    }

    private fun reply(body: String = "") = ("RTSP/1.0 200 OK\r\nCSeq: 3\r\n" +
        (if (body.isEmpty()) "" else "content-LENGTH: ${body.toByteArray().size}\r\n") + "\r\n" + body).toByteArray()

    /** Читатель с буфером то в один байт, то в несколько: номер пакета попадает на край буфера всеми способами. */
    private fun readAll(input: InputStream, sizes: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(sizes.max())
        var turn = 0
        while (true) {
            val n = input.read(buffer, 0, sizes[turn++ % sizes.size])
            if (n < 0) break
            assertTrue("чтение не возвращает ноль байт", n > 0)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Источник, отдающий не больше [chunk] байт за раз, как сокет. */
    private class Trickle(data: ByteArray, private val chunk: Int) : ByteArrayInputStream(data) {
        override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, chunk))
    }

    @Test fun streamRenumbersOnlyRtpSequenceBytes() {
        // Описание потока с «$» внутри тела: это не начало пакета.
        val sdp = "v=0\r\ns=cost \$5 \$\$\r\nm=video 0 RTP/AVP 96\r\n"
        val source = ByteArrayOutputStream()
        val expected = ByteArrayOutputStream()
        fun both(bytes: ByteArray) { source.write(bytes); expected.write(bytes) }
        both(reply())
        both(reply(sdp))
        both(reply())
        var video = 65_533
        var audio = 9
        var videoOut = 1
        var audioOut = 1
        repeat(60) { index ->
            val marker = index % 5 == 4
            source.write(rtp(0, video, marker)); expected.write(rtp(0, videoOut, marker).also { patched ->
                // Содержимое пакета — от номера камеры, меняются только два байта номера.
                rtp(0, video, marker).copyInto(patched, 6, 6); patched[6] = (videoOut ushr 8).toByte(); patched[7] = videoOut.toByte()
            })
            video = (video + 1) and 0xFFFF; videoOut++
            if (index % 3 == 0) {
                source.write(rtp(2, audio, true, payload = 9)); expected.write(rtp(2, audio, true, payload = 9).also { patched ->
                    patched[6] = (audioOut ushr 8).toByte(); patched[7] = audioOut.toByte()
                })
                audio++; audioOut++
            }
            if (index % 10 == 0) both(senderReport(1))
            if (index == 30) both(reply("ok \$ ok"))            // ответ на служебный запрос посреди потока
            if (index == 40) both(frame(0, byteArrayOf(1, 2, 3)))  // не RTP: короче заголовка
            if (index == 41) both(frame(0, ByteArray(16) { 0x33 })) // не RTP: другая версия
        }
        val want = expected.toByteArray()
        for (sizes in listOf(intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(7), intArrayOf(4096), intArrayOf(1, 5, 2, 64, 3))) {
            for (chunk in listOf(1, 2, 3, 5, 4096)) {
                val got = readAll(RtpSequenceInputStream(Trickle(source.toByteArray(), chunk)), sizes)
                assertArrayEquals("буфер читателя ${sizes.toList()}, порции источника по $chunk", want, got)
            }
        }
    }

    @Test fun unreadableStreamPassesThroughUnchanged() {
        val garbage = ByteArray(40_000) { 'x'.code.toByte() } + rtp(0, 500, true)
        assertArrayEquals(garbage, readAll(RtpSequenceInputStream(ByteArrayInputStream(garbage)), intArrayOf(512)))
    }

    private companion object {
        /** Пакетов в кадре: не делитель 32 768, чтобы граница кадра не совпадала с порогом. */
        const val FRAME = 17
    }
}
