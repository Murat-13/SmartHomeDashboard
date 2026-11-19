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
 * Reolink на московском планшете (19.11.2026): субпоток без fmtp у звука AAC — Media3 отвергал всё описание
 * («missing attribute fmtp»), основной поток 2560×1920 — больше предела декодера Samsung (1920×1088).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RtspCompatTest {
    @After fun forgetUndecodable() = DoorbellVideoView.undecodable.clear()

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

    @Test fun otherErrorsAndCompactTileKeepUsualPath() {
        assertFalse(view(sub).fallBackFromDecoder(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse("плитке некуда переходить", view("").fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse(view(main).fallBackFromDecoder(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)))
        assertTrue(DoorbellVideoView.undecodable.isEmpty())
    }
}
