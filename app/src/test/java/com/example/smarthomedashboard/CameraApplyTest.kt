package com.example.smarthomedashboard

import android.content.Context
import com.example.smarthomedashboard.CameraDiagnostics.Change
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** «Применить» и «Вернуть как было» (docs/diagnostics-tz.md, этап 4) — на поддельных камерах ONVIF и Reolink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraApplyTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = DoorbellConfig(host = "192.0.2.10", onvifPort = 8000, rtspUser = "admin",
        rtspMain = "rtsp://192.0.2.10:554/stream1", rtspSub = "rtsp://192.0.2.10:554/stream2")
    private val fps25 = Change(true, Change.Field.FPS, "20", "25", "частота основного потока: 20 → 25 к/с")
    private val transport = Onvif.transport

    @Before fun setUp() {
        context.getSharedPreferences("camera_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tablet_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() { Onvif.transport = transport }

    // ---- ONVIF ----

    private fun soap(body: String) = """<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
        xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
        xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>$body</s:Body></s:Envelope>"""

    /** Камера ONVIF: основной поток 20 к/с; [mode] — "ok", "refuse" (ошибка), "ignore" (ответ «да» без изменения). */
    private inner class FakeOnvif(var mode: String = "ok") {
        var fps = 20
        val sets = mutableListOf<String>()
        fun config() = "<trt:Configuration token=\"v0\" GuaranteedFrameRate=\"false\"><tt:Name>main</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1920</tt:Height></tt:Resolution>" +
            "<tt:Quality>4</tt:Quality><tt:RateControl><tt:FrameRateLimit>$fps</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval>" +
            "<tt:BitrateLimit>6144</tt:BitrateLimit></tt:RateControl><tt:H264><tt:GovLength>20</tt:GovLength><tt:H264Profile>High</tt:H264Profile>" +
            "</tt:H264><tt:SessionTimeout>PT60S</tt:SessionTimeout></trt:Configuration>"
        val handler: (String, String) -> Pair<Int, String> = { url, body ->
            if (!url.contains(":8000/")) throw java.net.ConnectException("нет")
            when {
                body.contains("GetSystemDateAndTime") -> 500 to soap("")
                body.contains("GetDeviceInformation") -> 200 to soap("<tds:GetDeviceInformationResponse><tds:Manufacturer>Cam</tds:Manufacturer>" +
                    "<tds:Model>X</tds:Model><tds:FirmwareVersion>1</tds:FirmwareVersion></tds:GetDeviceInformationResponse>")
                body.contains("GetCapabilities") -> 200 to soap("<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media>" +
                    "<tt:XAddr>http://192.0.2.10:8000/onvif/media</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>")
                body.contains("GetProfiles") -> 200 to soap("<trt:GetProfilesResponse><trt:Profiles token=\"p0\">" +
                    config().replace("trt:Configuration", "tt:VideoEncoderConfiguration") + "</trt:Profiles><trt:Profiles token=\"p1\">" +
                    "<tt:VideoEncoderConfiguration token=\"v1\"><tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>640</tt:Width>" +
                    "<tt:Height>480</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles></trt:GetProfilesResponse>")
                body.contains("GetStreamUri") -> {
                    val sub = body.contains(">p1<")
                    200 to soap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://192.0.2.10:554/stream${if (sub) 2 else 1}</tt:Uri>" +
                        "</trt:MediaUri></trt:GetStreamUriResponse>")
                }
                body.contains("GetVideoEncoderConfigurationOptions") -> 500 to soap("")
                body.contains("GetVideoEncoderConfiguration>") || body.contains("<GetVideoEncoderConfiguration ") ->
                    200 to soap("<trt:GetVideoEncoderConfigurationResponse>${config()}</trt:GetVideoEncoderConfigurationResponse>")
                body.contains("SetVideoEncoderConfiguration") -> {
                    sets += body
                    when (mode) {
                        "refuse" -> 400 to soap("<s:Fault><s:Reason><s:Text>ConfigModify</s:Text></s:Reason></s:Fault>")
                        else -> {
                            if (mode == "ok") fps = Regex("<FrameRateLimit[^>]*>(\\d+)<").find(body)!!.groupValues[1].toInt()
                            200 to soap("<trt:SetVideoEncoderConfigurationResponse/>")
                        }
                    }
                }
                else -> 500 to soap("")
            }
        }
    }

    @Test fun applyIsOffByDefault() {
        assertFalse(CameraApply.enabled(context))
        CameraApply.setEnabled(context, true)
        assertTrue(CameraApply.enabled(context))
    }

    @Test fun onvifApplyWritesWholeConfigAndRollsBack() {
        val camera = FakeOnvif()
        Onvif.transport = camera.handler
        val done = CameraApply.apply(context, "cam", config, "secret", fps25)
        assertTrue(done.text, done.ok)
        assertEquals(25, camera.fps)
        val set = camera.sets.single()
        assertTrue("сохраняется в камере", set.contains("<ForcePersistence>true</ForcePersistence>"))
        assertTrue("настройки — в пространстве имён схемы ONVIF", set.contains("<Name xmlns=\"http://www.onvif.org/ver10/schema\">main</Name>"))
        assertTrue("атрибуты сохранены", set.contains("token=\"v0\"") && set.contains("GuaranteedFrameRate=\"false\""))
        assertTrue("остальное — как было", set.contains("<BitrateLimit>6144</BitrateLimit>") && set.contains("<GovLength>20</GovLength>"))
        assertFalse("пароль не уходит открытым текстом", set.contains("secret"))
        // Можно вернуть: прежние значения запомнены, без адреса камеры.
        val saved = CameraApply.saved(context, config).single()
        assertEquals(mapOf("RateControl/FrameRateLimit" to "20"), saved.before)
        assertFalse(context.getSharedPreferences("camera_diagnostics", Context.MODE_PRIVATE).all.toString().contains("192.0.2.10"))
        assertTrue(CameraApply.rollback(context, "cam", config, "secret").ok)
        assertEquals(20, camera.fps)
        assertTrue(CameraApply.saved(context, config).isEmpty())
    }

    @Test fun refusedOrIgnoredChangeIsReportedAndNothingElseTouched() {
        val refusing = FakeOnvif("refuse")
        Onvif.transport = refusing.handler
        val refused = CameraApply.apply(context, "cam", config, "secret", fps25)
        assertFalse(refused.ok)
        assertTrue(refused.text, refused.text.startsWith("Камера не приняла изменение"))
        assertTrue(CameraApply.saved(context, config).isEmpty())
        val ignoring = FakeOnvif("ignore")
        Onvif.transport = ignoring.handler
        val ignored = CameraApply.apply(context, "cam", config, "secret", fps25)
        assertFalse(ignored.ok)
        assertTrue(ignored.text, ignored.text.contains("не сменила") && ignored.text.contains("Больше ничего не трогаю"))
        assertEquals("одна попытка", 1, ignoring.sets.size)
        assertTrue(CameraApply.saved(context, config).isEmpty())
    }

    @Test fun notDuringRingTalkOrEvent() {
        val camera = FakeOnvif()
        Onvif.transport = camera.handler
        RecordingEvents.direct("busy", listOf("person"), setOf("person"))
        val outcome = CameraApply.apply(context, "busy", config, "secret", fps25)
        assertFalse(outcome.ok)
        assertTrue(outcome.text, outcome.text.startsWith("Сейчас идёт звонок, разговор или запись события"))
        assertTrue("камеру не трогали", camera.sets.isEmpty())
    }

    // ---- Reolink ----

    @Test fun reolinkChangesKeyFrameThroughItsApi() {
        // Reolink: `gop` — множитель частоты (2 — раз в 2 с); «раз в 1 с» — 1.
        var enc = JSONObject().put("channel", 0).put("mainStream", JSONObject().put("frameRate", 20).put("gop", 2).put("size", "2560*1920")
            .put("bitRate", 6144).put("vType", "h264")).put("subStream", JSONObject().put("frameRate", 15).put("gop", 1))
        val sets = mutableListOf<JSONObject>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val cmd = request.requestUrl!!.queryParameter("cmd")
                    val body = JSONArray(request.body.readUtf8()).getJSONObject(0)
                    val value = when (cmd) {
                        "Login" -> JSONObject().put("Token", JSONObject().put("name", "t1"))
                        "GetEnc" -> JSONObject().put("Enc", JSONObject(enc.toString()))
                        "SetEnc" -> { sets += body.getJSONObject("param"); enc = body.getJSONObject("param").getJSONObject("Enc"); JSONObject().put("rspCode", 200) }
                        else -> JSONObject()
                    }
                    return MockResponse().setBody(JSONArray().put(JSONObject().put("cmd", cmd).put("code", 0).put("value", value)).toString())
                }
            }
            start()
        }
        val previous = CameraLight.driver
        try {
            CameraLight.driver = { _, _, _, password -> ReolinkDriver(server.url("").toString().trimEnd('/'), "admin", password, OkHttpClient()) }
            val reolink = config.copy(rtspMain = "rtsp://192.0.2.10:554/h264Preview_01_main", rtspSub = "rtsp://192.0.2.10:554/h264Preview_01_sub")
            val gop = Change(true, Change.Field.GOP, "40", "20", "ключевой кадр основного потока: раз в 2 с → раз в 1 с")
            val done = CameraApply.apply(context, "door", reolink, "secret", gop)
            assertTrue(done.text, done.ok)
            assertEquals(1, enc.getJSONObject("mainStream").getInt("gop"))
            assertEquals("остальное — как было", 20, sets.single().getJSONObject("Enc").getJSONObject("mainStream").getInt("frameRate"))
            assertTrue(CameraApply.rollback(context, "door", reolink, "secret").ok)
            assertEquals(2, enc.getJSONObject("mainStream").getInt("gop"))
        } finally {
            CameraLight.driver = previous
            server.shutdown()
        }
    }
}
