package androidx.media3.exoplayer.rtsp

import com.example.smarthomedashboard.Media3Sdp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверка тем же кодом Media3, что падал на планшете: `RtspMediaTrack.generatePayloadFormat` (пакет закрыт —
 * поэтому тест лежит в пакете Media3). Описание — настоящий субпоток Reolink (адрес заменён): видео H.264 без fmtp.
 * До правки — отказ, после (SPS/PPS из потока) — принято, размер кадра — из SPS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Media3SdpAcceptanceTest {
    private val reolinkSub = "v=0\r\no=- 1795093375741656 1 IN IP4 192.0.2.10\r\ns=Session streamed by \"preview\"\r\n" +
        "i=reolink rtsp stream\r\nt=0 0\r\na=tool:Reolink Streaming Media 2024.12.11\r\na=type:broadcast\r\n" +
        "a=control:*\r\na=range:npt=now-\r\na=x-qt-text-nam:Session streamed by \"preview\"\r\n" +
        "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n" +
        "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\n" +
        "a=recvonly\r\na=control:track2\r\n"
    private val params = "packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA=="

    private fun formats(sdp: String) =
        SessionDescriptionParser.parse(sdp).mediaDescriptionList.map { RtspMediaTrack.generatePayloadFormat(it) }

    @Test fun reolinkSubstreamRejectedAsIsAcceptedWithSpsPps() {
        val error = assertThrows(IllegalArgumentException::class.java) { formats(reolinkSub) }
        assertEquals("missing attribute fmtp", error.message)
        val fixed = formats(Media3Sdp.compatible(reolinkSub) { params })
        assertEquals(listOf("video/avc", "audio/mp4a-latm"), fixed.map { it.format.sampleMimeType })
        assertTrue("размер кадра взят из SPS", fixed[0].format.width > 0 && fixed[0].format.height > 0)
    }
}
