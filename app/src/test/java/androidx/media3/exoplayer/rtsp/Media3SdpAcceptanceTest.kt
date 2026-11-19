package androidx.media3.exoplayer.rtsp

import com.example.smarthomedashboard.Media3Sdp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверка тем же кодом Media3, что падал на планшете: `RtspMediaTrack.generatePayloadFormat` (пакет закрыт —
 * поэтому тест лежит в пакете Media3). Описание как у субпотока Reolink — до правки отказ, после — принято.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Media3SdpAcceptanceTest {
    private val reolinkSub = "v=0\r\no=- 1700000000 1 IN IP4 192.0.2.10\r\ns=Session streamed by \"preview\"\r\nt=0 0\r\n" +
        "a=tool:BC Streaming Media\r\na=type:broadcast\r\na=control:*\r\na=range:npt=now-\r\n" +
        "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:500\r\na=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n" +
        "a=control:trackID=1\r\n" +
        "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\nb=AS:256\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:trackID=2\r\n"

    private fun formats(sdp: String) =
        SessionDescriptionParser.parse(sdp).mediaDescriptionList.map { RtspMediaTrack.generatePayloadFormat(it) }

    @Test fun reolinkSubstreamRejectedAsIsAcceptedAfterFix() {
        val error = assertThrows(IllegalArgumentException::class.java) { formats(reolinkSub) }
        assertEquals("missing attribute fmtp", error.message)
        val fixed = formats(Media3Sdp.compatible(reolinkSub))
        assertEquals(listOf("video/avc"), fixed.map { it.format.sampleMimeType })
    }
}
