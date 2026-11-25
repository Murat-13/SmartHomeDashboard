package com.example.smarthomedashboard

import android.app.Activity
import androidx.media3.common.PlaybackException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Reolink на московском планшете (19.11.2026): у видео субпотока нет fmtp (SPS/PPS) — Media3 отвергал всё описание
 * («missing attribute fmtp»); основной поток 2560×1920 — больше предела декодера Samsung (1920×1088).
 * Звук AAC без config — защита на будущее (у этой камеры он с config).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RtspCompatTest {
    @After fun forgetUndecodable() {
        DoorbellVideoView.undecodable.clear()
        DoorbellVideoView.hardwareRejected.clear()
    }

    private val video = "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:500\r\na=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n" +
        "a=control:trackID=1\r\n"
    private val head = "v=0\r\no=- 1700000000 1 IN IP4 192.0.2.10\r\ns=Session streamed by \"preview\"\r\nt=0 0\r\n" +
        "a=tool:BC Streaming Media\r\na=type:broadcast\r\na=control:*\r\na=range:npt=now-\r\n"
    /** Как субпоток Reolink: звук AAC объявлен, а fmtp нет. */
    private val subSdp = head + video +
        "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\nb=AS:256\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:trackID=2\r\n"
    private val mainSdp = head + video +
        "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\n" +
        "a=control:trackID=2\r\n"

    @Test fun aacWithoutFmtpIsDroppedVideoStays() {
        val out = Media3Sdp.compatible(subSdp)
        val tracks = SdpParser.parse(out)
        assertEquals(listOf("H264"), tracks.map { it.encoding })
        assertEquals("trackID=1", tracks[0].control)
        assertEquals("Z2QAM6wVFKAoAPGQ,aO48sA==", tracks[0].fmtp["sprop-parameter-sets"])
        assertTrue("заголовок сессии сохранён", out.startsWith("v=0\r\no=- 1700000000"))
        assertTrue(out.contains("a=control:*\r\n"))
        assertFalse(out.contains("m=audio"))
    }

    @Test fun completeDescriptionIsUntouched() {
        assertSame(mainSdp, Media3Sdp.compatible(mainSdp))
        val latm = head + video + "m=audio 0 RTP/AVP 98\r\na=rtpmap:98 MP4A-LATM/16000\r\na=fmtp:98 cpresent=0;config=40002810\r\n"
        assertSame(latm, Media3Sdp.compatible(latm))
        val g711 = head + video + "m=audio 0 RTP/AVP 8\r\na=control:trackID=2\r\n"
        assertSame("G.711 без fmtp Media3 принимает", g711, Media3Sdp.compatible(g711))
    }

    @Test fun aacWithFmtpButNoConfigIsDropped() {
        val sdp = head + video + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=fmtp:97 mode=AAC-hbr;sizelength=13\r\n"
        assertEquals(listOf("H264"), SdpParser.parse(Media3Sdp.compatible(sdp)).map { it.encoding })
    }

    @Test fun lastTrackIsNeverDropped() {
        val onlyAudio = head + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n"
        assertSame(onlyAudio, Media3Sdp.compatible(onlyAudio))
        assertSame("без дорожек — как есть", head, Media3Sdp.compatible(head))
    }

    /** Ответы камеры, как их читает плеер: описание исправлено, длина пересчитана, остальное — байт в байт. */
    /** Второй аудит 25.11.2026, NEW-CAM-5: огромная длина ответа (её называет камера) не выделяется в памяти — ответ идёт дальше как есть. */
    @Test fun hugeAnswerIsPassedThroughWithoutBuffering() {
        val bytes = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 2000000000\r\n\r\n0123456789".toByteArray()
        val input = SdpFixingInputStream(ByteArrayInputStream(bytes))
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64)
        while (true) { val n = input.read(chunk, 0, chunk.size); if (n < 0) break; out.write(chunk, 0, n) }
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun socketStreamFixesOnlyDescribeAnswer() {
        val sdpBytes = subSdp.toByteArray()
        val before = ("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS, DESCRIBE\r\n\r\n" +
            "RTSP/1.0 401 Unauthorized\r\nCSeq: 2\r\nWWW-Authenticate: Digest realm=\"BC Streaming Media\", nonce=\"x\"\r\n\r\n" +
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Type: application/sdp\r\nContent-Base: rtsp://192.0.2.10:554/h264Preview_01_sub/\r\n" +
            "Content-Length: ${sdpBytes.size}\r\n\r\n").toByteArray() + sdpBytes
        // После описания: SETUP, PLAY и пакеты RTP (в них могут встретиться и «\r\n\r\n», и «Content-Length»).
        val after = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\nSession: 1\r\n\r\n".toByteArray() +
            byteArrayOf('$'.code.toByte(), 0, 0, 20) + "\r\n\r\nContent-Length: 9".toByteArray() + ByteArray(2) { 0x7F }
        val input = SdpFixingInputStream(ByteArrayInputStream(before + after))
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(7)
        while (true) { val n = input.read(chunk, 0, chunk.size); if (n < 0) break; out.write(chunk, 0, n) }
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)

        assertTrue(text.startsWith("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS, DESCRIBE\r\n\r\nRTSP/1.0 401 Unauthorized"))
        val describe = text.indexOf("CSeq: 3")
        val bodyStart = text.indexOf("\r\n\r\n", describe) + 4
        val length = Regex("Content-Length: (\\d+)").find(text.substring(describe))!!.groupValues[1].toInt()
        val body = text.substring(bodyStart, bodyStart + length)
        assertEquals(Media3Sdp.compatible(subSdp), body)
        assertTrue("Content-Base сохранён", text.contains("Content-Base: rtsp://192.0.2.10:554/h264Preview_01_sub/"))
        assertArrayEquals("после описания — без изменений", after, out.toByteArray().copyOfRange(bodyStart + length, out.size()))
    }

    @Test fun relayDoesNotOfferAacWithoutFmtp() {
        val tracks = SdpParser.parse(subSdp)
        val out = RtspRelay.sdpFor(subSdp, listOf(RtspSession.Setup(tracks[0], 0), RtspSession.Setup(tracks[1], 2)))
        val parsed = SdpParser.parse(out)
        assertEquals(listOf("H264"), parsed.map { it.encoding })
        assertEquals(listOf("track0"), parsed.map { it.control })
    }

    // ---- Настоящий субпоток Reolink: видео H.264 без fmtp (описание снято с камеры владельца 19.11.2026) ----

    private val reolinkHead = "v=0\r\no=- 1795093375741656 1 IN IP4 192.0.2.10\r\ns=Session streamed by \"preview\"\r\n" +
        "i=reolink rtsp stream\r\nt=0 0\r\na=tool:Reolink Streaming Media 2024.12.11\r\na=type:broadcast\r\n" +
        "a=control:*\r\na=range:npt=now-\r\na=x-qt-text-nam:Session streamed by \"preview\"\r\n"
    private val reolinkAudio = "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\n" +
        "a=recvonly\r\na=control:track2\r\n"
    private val reolinkSub = reolinkHead +
        "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n" + reolinkAudio
    private val sps = RecBase64.decode("Z2QAM6wVFKAoAPGQ")!!
    private val pps = RecBase64.decode("aO48sA==")!!
    private val params = "packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA=="

    @Test fun reolinkSubstreamGetsSpsPpsFromStream() {
        assertSame("без параметров из потока — как есть", reolinkSub, Media3Sdp.compatible(reolinkSub))
        assertTrue(Media3Sdp.lacksH264Params(SdpParser.parse(reolinkSub)[0]))
        var asked = 0
        val out = Media3Sdp.compatible(reolinkSub) { asked++; params }
        val tracks = SdpParser.parse(out)
        assertEquals(listOf("H264", "MPEG4-GENERIC"), tracks.map { it.encoding })
        assertEquals("Z2QAM6wVFKAoAPGQ,aO48sA==", tracks[0].fmtp["sprop-parameter-sets"])
        assertEquals("1", tracks[0].fmtp["packetization-mode"])
        assertEquals("track1", tracks[0].control)
        assertEquals("звук не тронут", "1408", tracks[1].fmtp["config"])
        assertTrue("fmtp сразу после rtpmap", out.contains("a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;"))
        assertEquals(1, asked)
        assertSame("SPS/PPS уже есть — поток не спрашиваем", out, Media3Sdp.compatible(out) { fail("лишний запрос"); null })
    }

    @Test fun collectorFindsSpsPpsInSingleAndAggregatedPackets() {
        val single = H264Params.Collector()
        single.push(byteArrayOf(0x41, 1, 2), 0, 3)
        assertNull(single.params)
        single.push(sps, 0, sps.size)
        assertNull("ещё нет PPS", single.params)
        single.push(byteArrayOf(9, 9) + pps, 2, pps.size)
        assertEquals(params, single.params)

        val stapA = byteArrayOf(24) + byteArrayOf(0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps
        val aggregated = H264Params.Collector()
        aggregated.push(stapA, 0, stapA.size)
        assertEquals(params, aggregated.params)
    }

    /** Отдельная короткая сессия к камере: ждёт SPS/PPS в потоке, закрывается сама (TEARDOWN). */
    @Test fun probeReadsSpsPpsFromCameraStreamAndHangsUp() {
        java.net.ServerSocket(0).use { server ->
            val teardown = java.util.concurrent.CountDownLatch(1)
            kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val out = s.getOutputStream()
                    val base = "rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub"
                    fun reply(cseq: String, headers: String = "", body: String = "") {
                        val b = body.toByteArray()
                        out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" +
                            (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
                        out.flush()
                    }
                    fun packet(seq: Int, payload: ByteArray) {
                        val p = RecordingTestSupport.rtp(96, true, seq, seq * 3000L, payload)
                        out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p)
                        out.flush()
                    }
                    try {
                        while (true) {
                            val first = reader.readLine() ?: break
                            if (first.isEmpty()) continue
                            val headers = mutableMapOf<String, String>()
                            while (true) { val l = reader.readLine() ?: return@use; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                            val cseq = headers["cseq"].orEmpty()
                            when (first.substringBefore(' ')) {
                                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $base/\r\n", reolinkSub)
                                "SETUP" -> reply(cseq, "Session: 7;timeout=60\r\nTransport: ${headers["transport"]}\r\n")
                                "PLAY" -> {
                                    reply(cseq, "Session: 7\r\n")
                                    packet(1, byteArrayOf(0x41, 1, 2, 3))
                                    packet(2, sps)
                                    packet(3, pps)
                                    packet(4, byteArrayOf(0x65, 1, 2, 3))
                                }
                                "TEARDOWN" -> { teardown.countDown(); reply(cseq); break }
                                else -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            val url = "rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub"
            assertEquals(params, H264Params.probe(url, "", null, timeoutMs = 5_000))
            assertTrue("сессия закрыта камерой TEARDOWN", teardown.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun relayAddsRememberedSpsPps() {
        val tracks = SdpParser.parse(reolinkSub)
        val relay = RtspRelay.sdpFor(reolinkSub, listOf(RtspSession.Setup(tracks[0], 0), RtspSession.Setup(tracks[1], 2)))
        val url = "rtsp://192.0.2.10:554/h264Preview_01_sub"
        H264Params.remember(url, params)
        try {
            val out = Media3Sdp.compatible(relay) { H264Params.cached(url) }
            assertEquals("Z2QAM6wVFKAoAPGQ,aO48sA==", SdpParser.parse(out)[0].fmtp["sprop-parameter-sets"])
            assertEquals(listOf("track0", "track1"), SdpParser.parse(out).map { it.control })
        } finally {
            H264Params.forget(url)
        }
    }

    // ---- Основной поток не по силам декодеру ----

    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val main = "rtsp://192.0.2.10:554/h264Preview_01_main"
    private val sub = "rtsp://192.0.2.10:554/h264Preview_01_sub"
    private fun error(code: Int) = PlaybackException("тест", null, code)

    private fun view(fallback: String) = DoorbellVideoView(activity).apply {
        bind(DoorbellVideoView.Source(main, "admin", "", snapshotOnly = false, fallbackRtsp = fallback), null)
        playingRtsp = main
    }

    @Test fun decoderRefusalSwitchesExpandedViewToSubstream() {
        val v = view(sub)
        assertEquals(main, v.rtspFor(DoorbellVideoView.Source(main, "admin", "", false, sub)))
        assertTrue(v.fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse("это не неудача связи", v.unreachable)
        assertEquals(DoorbellVideoView.Status.CONNECTING, v.status)
        assertEquals("дальше — сразу субпоток, и в других карточках", sub,
            view(sub).rtspFor(DoorbellVideoView.Source(main, "admin", "", false, sub)))
        v.playingRtsp = sub
        assertFalse("субпоток не декодируется — обычная ошибка", v.fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
    }

    @Test fun softwareDecodersAreRecognised() {
        assertTrue(DoorbellVideoView.isSoftwareDecoder("c2.android.avc.decoder"))
        assertTrue(DoorbellVideoView.isSoftwareDecoder("OMX.google.h264.decoder"))
        assertFalse(DoorbellVideoView.isSoftwareDecoder("c2.qti.avc.decoder"))
        assertFalse(DoorbellVideoView.isSoftwareDecoder("c2.qti.avc.decoder.low_latency"))
    }

    @Test fun otherErrorsAndCompactTileKeepUsualPath() {
        assertFalse(view(sub).fallBackFromDecoder(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse("плитке некуда переходить", view("").fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse(view(main).fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)))
        assertTrue(DoorbellVideoView.undecodable.isEmpty())
    }

    private val cleanSps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 1, 2, 3)
    private val cleanPps = byteArrayOf(0x68, 0x11, 0x22)

    /** Стартовые коды и лишние наборы в sprop: плеер получает ровно SPS и PPS, запись — чистые параметры. */
    @Test fun dirtyParametersInDescription() {
        val code = byteArrayOf(0, 0, 0, 1)
        fun sdp(sprop: String) = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=$sprop\r\na=control:t1\r\n"
        val clean = RecBase64.encode(cleanSps) + "," + RecBase64.encode(cleanPps)
        assertTrue(Media3Sdp.compatible(sdp(RecBase64.encode(code + cleanSps) + "," + RecBase64.encode(code + cleanPps))).contains("sprop-parameter-sets=$clean"))
        assertTrue(Media3Sdp.compatible(sdp("$clean," + RecBase64.encode(byteArrayOf(0x6D, 1)))).contains("sprop-parameter-sets=$clean\r\n"))
        assertEquals("чистое — как есть", sdp(clean), Media3Sdp.compatible(sdp(clean)))
        assertTrue("без PPS — взять из потока", Media3Sdp.lacksH264Params(SdpParser.parse(sdp(RecBase64.encode(cleanSps))).single()))
        val d = VideoDepacketizer.create(SdpParser.parse(sdp(RecBase64.encode(code + cleanSps) + "," + RecBase64.encode(code + cleanPps))).single())!!
        assertTrue(d.sps!!.contentEquals(cleanSps) && d.pps!!.contentEquals(cleanPps))
    }

}
