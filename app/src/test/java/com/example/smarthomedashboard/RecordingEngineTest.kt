package com.example.smarthomedashboard

import com.example.smarthomedashboard.RecordingTestSupport.accessUnits
import com.example.smarthomedashboard.RecordingTestSupport.boxes
import com.example.smarthomedashboard.RecordingTestSupport.nals
import com.example.smarthomedashboard.RecordingTestSupport.packetize
import com.example.smarthomedashboard.RecordingTestSupport.resource
import com.example.smarthomedashboard.RecordingTestSupport.rtp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Запись с камер, этап 1 (docs/recording.md): SDP, RTP → кадры, отрезки fMP4, восстановление после падения.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val reolinkSdp = """
        v=0
        o=- 1109162014219182 0 IN IP4 0.0.0.0
        s=Session streamed by "preview"
        t=0 0
        a=control:*
        m=video 0 RTP/AVP 96
        a=control:trackID=1
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==
        m=audio 0 RTP/AVP 97
        a=control:trackID=2
        a=rtpmap:97 MPEG4-GENERIC/16000
        a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408
    """.trimIndent().replace("\n", "\r\n")

    @Test fun sdpParsesReolinkVideoAndAac() {
        val tracks = SdpParser.parse(reolinkSdp)
        assertEquals(2, tracks.size)
        val v = tracks[0]
        assertTrue(v.isVideo); assertEquals("H264", v.encoding); assertEquals(90_000, v.clockRate); assertEquals("trackID=1", v.control)
        assertEquals("Z2QAM6wVFKAoAPGQ,aO48sA==", v.fmtp["sprop-parameter-sets"])
        val a = tracks[1]
        assertTrue(a.isAudio); assertEquals("MPEG4-GENERIC", a.encoding); assertEquals(16_000, a.clockRate)
        val aac = AacDepacketizer.create(a)
        assertNotNull(aac); assertArrayEquals(byteArrayOf(0x14, 0x08), aac!!.config)
        assertEquals("rtsp://cam/x/trackID=1", SdpParser.resolve("rtsp://cam/x/", "trackID=1"))
        assertEquals("rtsp://cam/x/trackID=1", SdpParser.resolve("rtsp://cam/x", "trackID=1"))
        assertEquals("rtsp://other/t", SdpParser.resolve("rtsp://cam/x", "rtsp://other/t"))
        assertEquals("rtsp://cam/x", SdpParser.resolve("rtsp://cam/x", "*"))
        // Параметры из SDP засеваются в разборщик до первого кадра.
        val d = VideoDepacketizer.create(v)!!
        assertTrue(d.hasParameters)
    }

    @Test fun g711AudioIsNotRecordedButVideoStays() {
        val sdp = "m=audio 0 RTP/AVP 8\r\na=control:trackID=2\r\n"
        val t = SdpParser.parse(sdp).single()
        assertEquals("PCMA", t.encoding); assertEquals(8000, t.clockRate)
        assertNull(AacDepacketizer.create(t))
    }

    private fun roundTrip(name: String, hevc: Boolean): Pair<List<VideoFrame>, VideoDepacketizer> {
        val units = accessUnits(nals(resource(name)), hevc)
        assertEquals(30, units.size)
        val d: VideoDepacketizer = if (hevc) H265Depacketizer() else H264Depacketizer()
        val frames = mutableListOf<VideoFrame>()
        var seq = 65_530 // переход порядкового номера через 0
        units.forEachIndexed { i, au ->
            for (p in packetize(au, hevc, 4_294_960_000L + i * 9_000L and 0xFFFFFFFFL, seq)) {
                frames += d.push(RtpPacket.parse(p, p.size)!!)
                seq = (seq + 1) and 0xFFFF
            }
        }
        return frames to d
    }

    @Test fun h264FuAndSingleNalsReassembleEveryFrame() {
        val (frames, d) = roundTrip("sample.h264", hevc = false)
        assertEquals(30, frames.size)
        assertEquals(listOf(0, 10, 20), frames.withIndex().filter { it.value.key }.map { it.index })
        // Метка времени не прыгает назад при переполнении 32 бит.
        assertTrue(frames.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp == 9_000L })
        // Кадры совпадают с исходными NAL побайтно.
        val units = accessUnits(nals(resource("sample.h264")), false)
        units.zip(frames).forEach { (u, f) -> assertEquals(u.size, f.nals.size); u.zip(f.nals).forEach { (a, b) -> assertArrayEquals(a, b) } }
        assertTrue(d.hasParameters)
        assertEquals(0, d.droppedFrames)
    }

    @Test fun h265FuReassemblesAndKeepsVpsSpsPps() {
        val (frames, d) = roundTrip("sample.h265", hevc = true)
        assertEquals(30, frames.size)
        assertEquals(listOf(0, 10, 20), frames.withIndex().filter { it.value.key }.map { it.index })
        assertNotNull(d.vps); assertNotNull(d.sps); assertNotNull(d.pps)
    }

    @Test fun lostPacketDropsFramesUntilNextKeyframe() {
        val units = accessUnits(nals(resource("sample.h264")), false)
        val d = H264Depacketizer()
        val frames = mutableListOf<VideoFrame>()
        var seq = 100
        units.forEachIndexed { i, au ->
            val packets = packetize(au, false, i * 9_000L, seq)
            packets.forEachIndexed { k, p ->
                // Кадр 3: теряется первый пакет.
                if (!(i == 3 && k == 0)) frames += d.push(RtpPacket.parse(p, p.size)!!)
            }
            seq += packets.size
        }
        val kept = frames.map { (it.timestamp / 9_000).toInt() }
        // Кадры 3–9 отброшены (испорчен 3-й, дальше нет ключевого), с 10-го — снова.
        assertEquals((0..2).toList() + (10..29).toList(), kept)
        assertTrue(d.droppedFrames >= 7)
    }

    @Test fun streamStartsOnlyFromKeyframe() {
        val units = accessUnits(nals(resource("sample.h264")), false)
        val d = H264Depacketizer()
        val frames = mutableListOf<VideoFrame>()
        var seq = 0
        // Подключение посреди группы кадров: первые 4 кадра без ключевого.
        units.drop(6).forEachIndexed { i, au ->
            packetize(au, false, (i + 6) * 9_000L, seq).forEach { p -> frames += d.push(RtpPacket.parse(p, p.size)!!); seq++ }
        }
        assertTrue(frames.first().key)
        assertEquals(10L, frames.first().timestamp / 9_000)
    }

    @Test fun aacHbrSplitsSeveralFramesWithTimestamps() {
        val track = SdpParser.parse(reolinkSdp)[1]
        val aac = AacDepacketizer.create(track)!!
        val f1 = ByteArray(100) { 1 }
        val f2 = ByteArray(5000) { 2 } // больше 4095 не бывает у AAC, но 13 бит размера это допускают
        val headers = byteArrayOf(0x00, 0x20, (100 shr 5).toByte(), ((100 and 0x1F) shl 3).toByte(),
            (5000 shr 5).toByte(), ((5000 and 0x1F) shl 3).toByte())
        val packet = rtp(97, true, 1, 1000, headers + f1 + f2)
        val frames = aac.push(RtpPacket.parse(packet, packet.size)!!)
        assertEquals(2, frames.size)
        assertEquals(1000L, frames[0].timestamp); assertEquals(2024L, frames[1].timestamp)
        assertArrayEquals(f1, frames[0].data); assertArrayEquals(f2, frames[1].data)
    }

    @Test fun rtpParserHandlesPaddingExtensionAndGarbage() {
        val payload = byteArrayOf(9, 9, 9)
        val withExt = byteArrayOf(0xB0.toByte(), 96.toByte(), 0, 1, 0, 0, 0, 5, 1, 2, 3, 4,
            0, 0, 0, 1, 7, 7, 7, 7) + payload + byteArrayOf(0, 2)
        val p = RtpPacket.parse(withExt, withExt.size)!!
        assertEquals(3, p.length); assertEquals(9, p.payload[p.offset].toInt())
        assertNull(RtpPacket.parse(ByteArray(5), 5))
        assertNull(RtpPacket.parse(ByteArray(20), 20)) // версия 0
    }

    private fun writeSegment(file: File, name: String, hevc: Boolean, frames: Int = 30): VideoParams {
        val units = accessUnits(nals(resource(name)), hevc)
        val d: VideoDepacketizer = if (hevc) H265Depacketizer() else H264Depacketizer()
        val decoded = mutableListOf<VideoFrame>()
        var seq = 0
        units.forEachIndexed { i, au ->
            packetize(au, hevc, i * 9_000L, seq).forEach { p -> decoded += d.push(RtpPacket.parse(p, p.size)!!); seq++ }
        }
        val params = VideoParams(d.codec, d.vps, d.sps!!, d.pps!!)
        Mp4SegmentWriter(file, params, AudioParams(byteArrayOf(0x14, 0x08), 16_000, 1)).use { w ->
            decoded.take(frames).forEach { w.writeVideo(it, it.timestamp * 1_000_000 / 90_000) }
            for (k in 0 until 40) w.writeAudio(AudioFrame(k * 1024L, ByteArray(20) { 3 }), k * 64_000L)
        }
        return params
    }

    @Test fun h264SegmentIsFragmentedMp4WithSizeFromSps() {
        val f = tmp.newFile("c.mp4")
        val params = writeSegment(f, "sample.h264", hevc = false)
        assertEquals(160, params.width); assertEquals(120, params.height)
        val top = boxes(f.readBytes())
        assertEquals("ftyp", top[0]); assertEquals("moov", top[1])
        // Фрагмент на каждую секунду (группа кадров): 3 пары moof+mdat.
        assertEquals(3, top.count { it == "moof" }); assertEquals(3, top.count { it == "mdat" })
        assertFalse(top.any { it.startsWith("!") })
    }

    @Test fun h265SegmentWritesHvc() {
        val f = tmp.newFile("h.mp4")
        val params = writeSegment(f, "sample.h265", hevc = true)
        assertEquals(160, params.width); assertEquals(120, params.height)
        val bytes = f.readBytes()
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("hvcC"))
        assertTrue(boxes(bytes).count { it == "moof" } >= 2)
    }

    @Test fun crashedSegmentIsCutToLastWholeFragmentAndRenamed() {
        val root = tmp.newFolder("root")
        val start = 1_763_490_000_000L
        val dir = RecLayout.dayDir(root, "door", start).apply { mkdirs() }
        val part = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
        writeSegment(part, "sample.h264", hevc = false)
        val whole = part.length()
        // Падение посреди записи: хвост последнего фрагмента не дописан.
        java.io.RandomAccessFile(part, "rw").use { it.setLength(whole - 700) }
        part.setLastModified(start + 2_500)
        assertEquals(1, RecLayout.recover(root))
        val files = RecLayout.scan(root)
        assertEquals(1, files.size)
        val f = files.single()
        assertFalse(f.open); assertEquals(start, f.startMs); assertEquals(2_500L, f.durationMs)
        val top = boxes(f.path.readBytes())
        assertEquals(2, top.count { it == "moof" }); assertEquals(2, top.count { it == "mdat" })
        assertFalse(top.any { it.startsWith("!") })
    }

    @Test fun unusableCrashedSegmentIsDeleted() {
        val root = tmp.newFolder("root2")
        val start = 1_763_490_000_000L
        val dir = RecLayout.dayDir(root, "door", start).apply { mkdirs() }
        val part = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
        part.writeBytes(ByteArray(10))
        assertEquals(0, RecLayout.recover(root))
        assertFalse(part.exists())
    }

    @Test fun base64MatchesJdkForAllLengths() {
        val rnd = java.util.Random(7)
        for (n in 0..40) {
            val data = ByteArray(n).also(rnd::nextBytes)
            val jdk = java.util.Base64.getEncoder().encodeToString(data)
            assertEquals(jdk, RecBase64.encode(data))
            assertArrayEquals(data, RecBase64.decode(jdk))
        }
        assertNull(RecBase64.decode("a*b"))
    }

    @Test fun parametersChangeIsDetected() {
        val a = VideoParams(VideoDepacketizer.Codec.H264, null, byteArrayOf(0x67, 1), byteArrayOf(0x68, 1))
        val b = a.copy(sps = byteArrayOf(0x67, 2))
        assertTrue(a.sameAs(a.copy())); assertFalse(a.sameAs(b))
    }
}
