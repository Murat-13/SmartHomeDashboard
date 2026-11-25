package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Живое видео по протоколу Reolink (docs/doorbell.md): разбор потока, превращение в RTP и поддельная камера с настоящим H.264. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReolinkVideoTest {
    private val cameras = CopyOnWriteArrayList<Camera>()
    private val sessions = CopyOnWriteArrayList<ReolinkVideoSession>()
    @After fun tearDown() { sessions.forEach { runCatching { it.close() } }; cameras.forEach { runCatching { it.close() } }; ReolinkVideo.forget() }

    private val units: List<List<ByteArray>> by lazy { RecordingTestSupport.accessUnits(RecordingTestSupport.nals(RecordingTestSupport.resource("sample.h264")), hevc = false) }
    private fun annexB(nals: List<ByteArray>) = nals.fold(ByteArray(0)) { a, n -> a + byteArrayOf(0, 0, 0, 1) + n }

    // ---- Куски BcMedia ----

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun padded(head: ByteArray, data: ByteArray) = head + data + ByteArray((8 - data.size % 8) % 8)
    private fun info() = le(32).putInt(0x32303031).putInt(32).putInt(2560).putInt(1920).array()
    private fun video(data: ByteArray, key: Boolean, us: Long, codec: String = "H264", extra: Int = if (key) 4 else 0) =
        padded(le(24 + extra).putInt(if (key) 0x63643030 else 0x63643130).put(codec.toByteArray()).putInt(data.size).putInt(extra).putInt(us.toInt()).putInt(0).array(), data)
    private fun sound(n: Int) = padded(le(8).putInt(0x62773530).putShort(n.toShort()).putShort(n.toShort()).array(), ByteArray(n) { it.toByte() })

    private fun parse(chunks: List<ByteArray>): List<BcMediaParser.Frame> { val out = mutableListOf<BcMediaParser.Frame>(); val p = BcMediaParser(); chunks.forEach { p.push(it, out::add) }; return out }

    @Test fun framesAreCollectedFromAnyPieces() {
        val stream = info() + video(ByteArray(1_001) { 1 }, true, 10) + sound(333) + video(ByteArray(7) { 2 }, false, 20) + video(ByteArray(64_000) { 3 }, false, 30, "H265") + sound(8) + video(ByteArray(8) { 4 }, false, 40)
        val whole = parse(listOf(stream))
        assertEquals(listOf(1_001, 7, 64_000, 8), whole.map { it.data.size })
        assertEquals(listOf(10L, 20L, 30L, 40L), whole.map { it.stampUs })
        assertEquals(listOf(true, false, false, false), whole.map { it.key })
        assertEquals(listOf(false, false, true, false), whole.map { it.h265 })
        for (step in listOf(1, 3, 17, 1_000, 40_000)) {
            val pieces = parse(stream.toList().chunked(step).map { it.toByteArray() })
            assertEquals("кусками по $step", whole.map { it.data.toList().hashCode() }, pieces.map { it.data.toList().hashCode() })
        }
    }

    @Test fun garbageIsNotPlayed() {
        assertTrue(runCatching { parse(listOf(info() + ByteArray(64) { 9 })) }.exceptionOrNull() is IOException)
        val huge = le(24).putInt(0x63643030).put("H264".toByteArray()).putInt(100 shl 20).putInt(0).putInt(0).putInt(0).array()
        assertTrue(runCatching { parse(listOf(huge)) }.exceptionOrNull() is IOException)
    }

    @Test fun manyLargeFramesDoNotGrowBuffer() {
        var got = 0
        val p = BcMediaParser()
        val frame = video(ByteArray(400_000) { 5 }, true, 1)
        repeat(40) { for (piece in frame.toList().chunked(33_000)) p.push(piece.toByteArray()) { got++ } }
        assertEquals(40, got)
    }

    @Test fun cameraClockOverflowIsNotJumpBack() {
        val near = 0xFFFFFFFFL - 10
        assertEquals(listOf(near, near + 60_000), parse(listOf(video(ByteArray(8), true, near) + video(ByteArray(8), false, (near + 60_000) and 0xFFFFFFFFL))).map { it.stampUs })
    }

    // ---- Поддельная камера ----

    /** [mode] — какое шифрование предлагает: 0x00, 0x01, 0x02 или 0x12 (и начало кусков видео); [password] — верный. */
    private inner class Camera(val mode: Int = 0x12, val password: String = "secret", val refuse: Boolean = false, val noKey: Boolean = false, val dropAfter: Int = Int.MAX_VALUE) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val commands = CopyOnWriteArrayList<String>()
        val pings = AtomicInteger()
        val connections = AtomicInteger()
        private val nonce = "ABCDEF0123"
        init {
            cameras += this
            thread(isDaemon = true) { while (!server.isClosed) { val s = try { server.accept() } catch (_: Exception) { break }; connections.incrementAndGet(); thread(isDaemon = true) { try { serve(s) } catch (_: Exception) {} finally { runCatching { s.close() } } } } }
        }
        override fun close() = server.close()

        private fun write(s: Socket, msgId: Int, num: Int, code: Int, ext: ByteArray, payload: ByteArray, modern: Boolean = true) = synchronized(s) {
            val h = le(if (modern) 24 else 20).putInt(0x0ABCDEF0).putInt(msgId).putInt(ext.size + payload.size).put(0).put(0).putShort(num.toShort()).putShort(code.toShort()).putShort((if (modern) 0x6414 else 0x6514).toShort())
            if (modern) h.putInt(ext.size)
            s.getOutputStream().write(h.array() + ext + payload); s.getOutputStream().flush()
        }

        private fun serve(s: Socket) {
            val input = DataInputStream(s.getInputStream().buffered())
            var key: ByteArray? = null
            fun hide(b: ByteArray) = when { b.isEmpty() -> b; key != null -> BcCrypto.aesCfb(key!!, b, true); mode == 0 -> b; else -> BcCrypto.xor(b, 0) }
            fun show(b: ByteArray, msgId: Int) = when { b.isEmpty() -> b; key != null && msgId != 1 -> BcCrypto.aesCfb(key!!, b, false); mode == 0 -> b; else -> BcCrypto.xor(b, 0) }
            while (true) {
                val h = ByteArray(20); input.readFully(h)
                val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN); b.int
                val msgId = b.int; val len = b.int; b.get(); val stream = b.get().toInt(); val num = b.short.toInt() and 0xFFFF; b.short; val cls = b.short.toInt() and 0xFFFF
                val off = if (cls == 0x6414) { val o = ByteArray(4); input.readFully(o); ByteBuffer.wrap(o).order(ByteOrder.LITTLE_ENDIAN).int } else 0
                val body = ByteArray(len); input.readFully(body)
                val xml = String(show(body.copyOfRange(off, len), msgId))
                when (msgId) {
                    1 -> if (cls == 0x6514) {
                        val reply = "<body><Encryption version=\"1.1\"><type>md5</type><nonce>$nonce</nonce></Encryption></body>".toByteArray()
                        write(s, 1, 0, 0xDD00 or mode, ByteArray(0), if (mode == 0) reply else BcCrypto.xor(reply, 0), modern = false)
                    } else {
                        val ok = xml.contains(BcCrypto.md5("admin$nonce")) && xml.contains(BcCrypto.md5(password + nonce))
                        write(s, 1, num, if (ok) 200 else 401, ByteArray(0), ByteArray(0))
                        if (mode and 0xF == 2) key = BcCrypto.aesKey(nonce, password)
                    }
                    3 -> { commands += "start $stream " + (BaichuanTalkSession.tag(xml, "streamType") ?: "?"); if (refuse) write(s, 3, num, 400, ByteArray(0), ByteArray(0)) else thread(isDaemon = true) { try { play(s, num, ::hide, key) } catch (_: Exception) {} } }
                    4 -> commands += "stop $stream"
                    93 -> { pings.incrementAndGet(); write(s, 93, num, 200, ByteArray(0), ByteArray(0)) }
                    234 -> commands += "keepalive ответ"
                }
            }
        }

        private fun play(s: Socket, num: Int, hide: (ByteArray) -> ByteArray, key: ByteArray?) {
            val plainExt = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n<binaryData>1</binaryData>\n</Extension>\n"
            write(s, 3, num, 200, hide(plainExt.toByteArray()), info())
            write(s, 234, 7, 0, ByteArray(0), ByteArray(0))
            var us = 1_000_000L
            for (i in 0 until 100_000) {
                if (i >= dropAfter) { s.close(); return }
                val unit = if (noKey) units[1] else units[i % units.size]
                val keyFrame = unit.any { (it[0].toInt() and 0x1F) == 5 }
                val media = video(annexB(unit), keyFrame, us) + sound(200)
                for (piece in media.toList().chunked(5_000)) {
                    val bytes = piece.toByteArray()
                    val n = if (mode == 0x12 && key != null) minOf(1_024, bytes.size) else 0
                    val ext = if (n > 0) plainExt.replace("</Extension>", "<encryptLen>$n</encryptLen>\n</Extension>") else plainExt
                    write(s, 3, num, 0, hide(ext.toByteArray()), if (n > 0) BcCrypto.aesCfb(key!!, bytes.copyOfRange(0, n), true) + bytes.copyOfRange(n, bytes.size) else bytes)
                }
                us += 50_000
                Thread.sleep(10)
            }
        }
    }

    private fun session(camera: Camera, password: String = "secret") = ReolinkVideoSession("127.0.0.1", "admin", password, camera.server.localPort, readTimeoutMs = 2_000).also { sessions += it }

    /** Прочитать [count] кадров через тот же разборщик, что у записи. */
    private fun frames(session: ReolinkVideoSession, count: Int): Triple<List<VideoFrame>, List<Long>, Int> {
        val setup = session.open { it.isVideo }.single()
        val depacketizer = VideoDepacketizer.create(setup.track)!!
        val out = mutableListOf<VideoFrame>()
        val reports = mutableListOf<Long>()
        var packets = 0
        runCatching {
            session.read(object : RtspSession.Sink {
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                    packets++
                    assertTrue("пакет не больше обычного: $length", length <= 1_400)
                    out += depacketizer.push(RtpPacket.parse(packet.copyOf(length), length)!!)
                    if (out.size >= count) session.close()
                }
                override fun onRtcp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { reports += ByteBuffer.wrap(packet, 16, 4).int.toLong() and 0xFFFFFFFFL }
            })
        }
        return Triple(out, reports, packets)
    }

    @Test fun realVideoComesThroughEveryEncryption() {
        for (mode in listOf(0x00, 0x01, 0x02, 0x12)) {
            val camera = Camera(mode)
            val session = session(camera)
            val (got, reports, _) = frames(session, 25)
            assertEquals("режим $mode", 25, got.size)
            assertTrue("первый — ключевой, сразу", got.first().key)
            for (i in 0 until 25) assertArrayEquals("кадр $i", annexB(units[i % units.size]), got[i].annexB())
            // Метки — по часам камеры: 50 мс между кадрами.
            assertTrue(got.zipWithNext { a, b -> b.timestamp - a.timestamp }.all { it == 4_500L })
            assertEquals("отчёт RTCP с меткой первого кадра", 90_000L, reports.first())
            assertEquals("start 0 mainStream", camera.commands.first())
            assertTrue("описание с параметрами", session.sdp.contains("sprop-parameter-sets=") && session.sdp.contains("profile-level-id="))
            assertFalse("плеер примет", SdpParser.parse(session.sdp).any(Media3Sdp::lacksH264Params))
        }
    }

    @Test fun goodbyeAndLinkChecks() {
        val camera = Camera()
        val (got, _, _) = frames(session(camera), 5)
        assertEquals(5, got.size)
        val end = System.currentTimeMillis() + 2_000
        while ("stop 0" !in camera.commands && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(camera.commands.toString(), "stop 0" in camera.commands)
        assertTrue("камере отвечают на проверку связи", "keepalive ответ" in camera.commands)
        assertTrue("и сами спрашивают", camera.pings.get() >= 1)
    }

    @Test fun refusalsAndSilence() {
        val wrong = runCatching { session(Camera(), password = "нет").open { true } }.exceptionOrNull()
        assertEquals("неверный пароль", 401, (wrong as RtspException).status)
        assertTrue(runCatching { session(Camera(refuse = true)).open { true } }.exceptionOrNull() is RtspException)
        val broken = session(Camera(dropAfter = 3))
        broken.open { true }
        assertTrue("обрыв замечен", runCatching { broken.read(object : RtspSession.Sink { override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {} }) }.isFailure)
    }

    @Test fun onlyReolinkAndRestAfterRefusal() {
        assertEquals("baichuan://192.0.2.5/main", ReolinkVideo.urlFor("rtsp://192.0.2.5:554/h264Preview_01_main"))
        assertNull("субпоток плитки — как раньше", ReolinkVideo.urlFor("rtsp://192.0.2.5:554/Preview_01_sub"))
        assertNull(ReolinkVideo.urlFor("rtsp://192.0.2.5:554/Streaming/Channels/101"))
        assertNull(ReolinkVideo.urlFor("rtsp:///h264Preview_01_main"))
        var t = 0L
        ReolinkVideo.clock = { t }
        val url = "baichuan://192.0.2.5/main"
        assertFalse(ReolinkVideo.held(url))
        ReolinkVideo.refused(url); assertTrue(ReolinkVideo.held(url))
        // Одна заминка камеры — 15 секунд запасным путём, а не минута (разбор 26.11.2026); подводит снова — паузы растут.
        t += 16_000; assertFalse("через 15 секунд — снова", ReolinkVideo.held(url))
        ReolinkVideo.refused(url); t += 16_000; assertTrue("второй отказ подряд — минута", ReolinkVideo.held(url))
        t += 45_000; assertFalse(ReolinkVideo.held(url))
        ReolinkVideo.refused(url); t += 61_000; assertTrue("третий — 10 минут", ReolinkVideo.held(url))
        ReolinkVideo.worked(url); assertFalse(ReolinkVideo.held(url))
        assertTrue(ReolinkVideo.feed(url, "a", "b") is ReolinkVideoSession)
        assertTrue(ReolinkVideo.feed("rtsp://192.0.2.5/x", "a", "b") is RtspSession)
    }

    /** Общий поток и ретранслятор не знают, что это Reolink: плеер получает обычный RTSP с первого ключевого кадра. */
    @Test fun playerSeesOrdinaryRtspThroughTablet() {
        val camera = Camera()
        val key = CameraHub.Key("baichuan://127.0.0.1/main", "admin", "secret")
        val hold = CameraHub.hold(key, { _, u, p -> ReolinkVideoSession("127.0.0.1", u, p.orEmpty(), camera.server.localPort) })
        try {
            val end = System.currentTimeMillis() + 5_000
            while (!CameraHub.ready(key.url, "admin") && System.currentTimeMillis() < end) Thread.sleep(20)
            val relay = CameraHub.relayUrl(key.url, "admin")
            assertNotNull(relay)
            val viewer = RtspSession(relay!!, "", null, readTimeoutMs = 3_000)
            val setup = viewer.open { it.isVideo }.single()
            assertEquals("H264", setup.track.encoding)
            val depacketizer = VideoDepacketizer.create(setup.track)!!
            val got = mutableListOf<VideoFrame>()
            runCatching { viewer.read(object : RtspSession.Sink { override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                got += depacketizer.push(RtpPacket.parse(packet.copyOf(length), length)!!); if (got.size >= 12) viewer.close() } }) }
            assertTrue("первый кадр — ключевой", got.first().key)
            assertEquals(1, camera.connections.get())
        } finally { hold.release() }
    }
}
