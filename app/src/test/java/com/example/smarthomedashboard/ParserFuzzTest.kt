package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileParser
import org.json.JSONException
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Random

/**
 * Фаззинг разборщиков (второй аудит 25.11.2026, пакет 7): всё, что приходит от камер, из сети и из файлов, портится случайным образом —
 * обрезается, дублируется, получает чужие байты и невозможные числа. Разборщик обязан либо ответить, либо отказать «своим» исключением
 * (то, которое ждёт вызывающий); выход за границы массива, деление на ноль, отрицательный размер, переполнение стека — ошибка.
 * Число кругов — `-Pchaos.iterations` (как у остальных хаос-тестов), зерно — `-Pchaos.seed`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ParserFuzzTest {
    private val rounds = System.getProperty("chaos.iterations")?.toIntOrNull()?.times(10) ?: 1_500
    private val seed = System.getProperty("chaos.seed")?.toLongOrNull() ?: 20261126L

    private val numbers = listOf("0", "-1", "-8", "2147483647", "2147483648", "-2147483649", "99999999999999999999", "1e309", "NaN", "0x7fffffff", "")
    private val junk = listOf("<", ">", "</", "\"", "'", "\u0000", "\r\n", "\r\n\r\n", "=", ";", ",", ":", "{", "}", "[", "]", "&#x0;", "<!DOCTYPE a [<!ENTITY b \"c\">]>", "a=fmtp:", "sprop-parameter-sets=", "\uD83D", " ")

    private fun mutate(random: Random, sample: ByteArray): ByteArray {
        var b = sample
        repeat(1 + random.nextInt(4)) {
            if (b.isEmpty()) b = ByteArray(1 + random.nextInt(40)).also(random::nextBytes)
            val at = random.nextInt(b.size + 1)
            b = when (random.nextInt(8)) {
                0 -> b.copyOf(at) // обрезать
                1 -> b.copyOf().also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                2 -> { val to = (at + random.nextInt(64)).coerceAtMost(b.size); b.copyOfRange(0, to) + b.copyOfRange(at, b.size) } // повторить кусок
                3 -> b.copyOfRange(0, at) + numbers[random.nextInt(numbers.size)].toByteArray() + b.copyOfRange(at, b.size)
                4 -> b.copyOfRange(0, at) + junk[random.nextInt(junk.size)].toByteArray() + b.copyOfRange(at, b.size)
                5 -> { val to = (at + random.nextInt(32)).coerceAtMost(b.size); b.copyOfRange(0, at) + b.copyOfRange(to, b.size) } // вырезать кусок
                6 -> ByteArray(random.nextInt(200)).also(random::nextBytes) // чужие данные
                else -> { val digits = b.indices.filter { b[it] in '0'.code.toByte()..'9'.code.toByte() }
                    if (digits.isEmpty()) b else { val i = digits[random.nextInt(digits.size)]; b.copyOfRange(0, i) + numbers[random.nextInt(numbers.size)].toByteArray() + b.copyOfRange(i + 1, b.size) } }
            }
        }
        return b
    }

    /** [allowed] — исключения, которые вызывающий этого разборщика ловит сам. */
    private fun fuzz(name: String, samples: List<ByteArray>, allowed: (Throwable) -> Boolean = { false }, parse: (ByteArray) -> Unit) {
        val random = Random(seed + name.hashCode())
        repeat(rounds) { round ->
            val input = mutate(random, samples[random.nextInt(samples.size)])
            try { parse(input) } catch (e: Throwable) {
                if (!allowed(e)) fail("$name, круг $round (зерно $seed): ${e.javaClass.simpleName}: ${e.message}\nвход (${input.size} байт): " +
                    String(input, Charsets.ISO_8859_1).take(300).replace("\r", "\\r").replace("\n", "\\n"))
            }
        }
    }

    private fun text(vararg samples: String) = samples.map { it.toByteArray() }
    private fun str(b: ByteArray) = String(b, Charsets.UTF_8)

    private val sdp = "v=0\r\no=- 1 1 IN IP4 192.0.2.10\r\ns=Session\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:500\r\n" +
        "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\n" +
        "a=control:trackID=1\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\na=control:trackID=2\r\n" +
        "m=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\na=sendonly\r\na=control:trackID=3\r\n"
    private val h265 = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\na=fmtp:96 sprop-vps=QAEMAf//AWAAAAMAAAMAAAMAAAMAlqwJ;sprop-sps=QgEBAWAAAAMAAAMAAAMAAAMAlqADwIAQ5Y2uSTL8;sprop-pps=RAHA8vA8kA==\r\na=control:track1\r\n"

    @Test fun sdp() {
        fuzz("SdpParser", text(sdp, h265)) { SdpParser.parse(str(it)) }
        fuzz("Media3Sdp", text(sdp, h265)) { Media3Sdp.compatible(str(it)) { "Z2QAM6wVFKAoAPGQ,aO48sA==" } }
    }

    @Test fun onvif() {
        val events = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:wsnt=\"http://docs.oasis-open.org/wsn/b-2\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<s:Body><PullMessagesResponse><wsnt:NotificationMessage><wsnt:Topic Dialect=\"x\">tns1:RuleEngine/MyRuleDetector/PeopleDetect</wsnt:Topic>" +
            "<wsnt:Message><tt:Message UtcTime=\"2026-11-26T00:00:00Z\"><tt:Data><tt:SimpleItem Name=\"State\" Value=\"true\"/></tt:Data></tt:Message></wsnt:Message>" +
            "</wsnt:NotificationMessage></PullMessagesResponse></s:Body></s:Envelope>"
        val options = "<Envelope><Body><GetVideoEncoderConfigurationOptionsResponse><Options><H264><ResolutionsAvailable><Width>2560</Width><Height>1920</Height>" +
            "</ResolutionsAvailable><GovLengthRange><Min>1</Min><Max>100</Max></GovLengthRange><FrameRateRange><Min>2</Min><Max>25</Max></FrameRateRange></H264>" +
            "<Extension><H264><BitrateRange><Min>64</Min><Max>8192</Max></BitrateRange></H264></Extension></Options></GetVideoEncoderConfigurationOptionsResponse></Body></Envelope>"
        fuzz("Onvif", text(events, options), { it is IOException || it is org.xmlpull.v1.XmlPullParserException }) {
            val root = Onvif.parse(str(it))
            Onvif.events(root)
            Onvif.parseOptions(root)
        }
        fuzz("Onvif.typeOf", text("tns1:RuleEngine/MyRuleDetector/PeopleDetect", "tns1:VideoSource/MotionAlarm")) { Onvif.typeOf(str(it)) }
    }

    @Test fun discovery() {
        val probe = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">" +
            "<s:Body><d:ProbeMatches><d:ProbeMatch><d:Types>dn:NetworkVideoTransmitter</d:Types><d:Scopes>onvif://www.onvif.org/name/Cam onvif://www.onvif.org/hardware/X</d:Scopes>" +
            "<d:XAddrs>http://192.0.2.10:8000/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"
        fuzz("WsDiscovery", text(probe)) { WsDiscovery.parse(str(it), "192.0.2.10") }
        fuzz("ReolinkSearch", listOf(ByteArray(400) { (it * 7).toByte() }, ByteArray(60))) { ReolinkSearch.parse(it, "192.0.2.10") }
    }

    @Test fun media() {
        val rtp = byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2) + ByteArray(40) { it.toByte() }
        val extended = byteArrayOf(0xB3.toByte(), 0xE0.toByte(), 0, 1, 0, 0, 0, 1, 0, 0, 0, 2) + ByteArray(12) + byteArrayOf(0, 1, 0, 2) + ByteArray(30) { 3 }
        fuzz("RtpPacket", listOf(rtp, extended)) { RtpPacket.parse(it, it.size) }
        val sps = android.util.Base64.decode("Z2QAM6wVFKAoAPGQ", android.util.Base64.DEFAULT)
        fuzz("H264LowDelay.sps", listOf(sps)) { H264LowDelay.rewriteSps(it) }
        val unit = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte()) + byteArrayOf(0, 0, 1, 0x65) + ByteArray(40) { 9 }
        fuzz("H264LowDelay.unit", listOf(unit)) { H264LowDelay.rewriteAccessUnit(it) }
        fuzz("ImaAdpcm", listOf(ByteArray(516) { (it * 31).toByte() })) { ImaAdpcm.decode(it, ShortArray(1024)) }
        fuzz("Baichuan.tag", text("<TalkAbility><audioType>adpcm</audioType><sampleRate>16000</sampleRate><lengthPerEncoder>1024</lengthPerEncoder></TalkAbility>")) {
            BaichuanTalkSession.tag(str(it), "lengthPerEncoder"); BaichuanTalkSession.tag(str(it), "sampleRate")
        }
    }

    @Test fun network() {
        fuzz("DigestAuth", text("Digest realm=\"cam\", nonce=\"abc\", qop=\"auth\", algorithm=SHA-256, opaque=\"x\"", "Basic realm=\"cam\"",
            "Digest realm=\"a, b\", nonce=\"n\", stale=true")) { DigestAuth.choose(listOf(str(it)))?.header("admin", "secret", "GET", "/rpc") }
        fuzz("HaInboundMessage", text("""{"id":3,"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"light.a","new_state":{"state":"on","attributes":{"brightness":255}}}}}""",
            """{"id":1,"type":"result","success":true,"result":[{"entity_id":"sensor.t","state":"21.5","attributes":{"unit_of_measurement":"°C"}}]}""",
            """{"type":"auth_ok"}""")) { HaInboundMessage.parse(str(it)) }
        fuzz("AppReleases", text("""[{"tag_name":"v0.9.72","prerelease":true,"draft":false,"body":"x","assets":[{"name":"app-release.apk","size":16000000,"browser_download_url":"https://github.com/a/b/releases/download/v0.9.72/app-release.apk","digest":"sha256:00"}]}]""")) {
            AppReleases.parse(str(it))
        }
        fuzz("ReolinkEvents", text("""{"alarm":{"channel":0,"type":"VISITOR","deviceModel":"x"}}""")) { ReolinkDriver("192.0.2.10", "admin", "x", okhttp3.OkHttpClient()).parse(str(it)) }
    }

    @Test fun files() {
        val tiles = """[{"id":"t","type":"button","container":"grid","title":"T","x":0,"y":0,"width":1,"height":1,"page":1,"config":"{\"entity_id\":\"light.a\"}"}]"""
        fuzz("TileParser", text(tiles)) { TileParser.parse(str(it)) }
        fuzz("ConfigImport", text("""{"app_theme":"dark","page_count":2,"screen_timeout_minutes":5,"tiles_json":"[]"}"""),
            { it is JSONException || it is IllegalArgumentException }) { ConfigImport.parse(str(it)) }
        fuzz("CopyPayload", text("""{"v":1,"config":"{}","screen":{"w":1920,"h":1200,"density":2.0},"name":"Планшет","app":"0.9.72"}"""), { it is JSONException }) { CopyPayload.parse(str(it)) }
        fuzz("CopyCode", text("ABCD-EFGH-JK", "абвг 1234")) { CopyCode.parse(str(it)) }
        fuzz("GateStore", text("""[{"id":"g","name":"Калитка","kind":"relay","open":"switch.a","confirm":15,"direct":{"host":"192.0.2.5","protocol":"shelly","pulse":4}}]""")) { GateStore.parse(str(it)) }
        fuzz("ArchiveCopy.refs", text("""{"Домофон 2026-11-26 00-00-00.mp4":"content://x/1"}""")) { ArchiveCopy.loadRefs(str(it)) }
    }
}
