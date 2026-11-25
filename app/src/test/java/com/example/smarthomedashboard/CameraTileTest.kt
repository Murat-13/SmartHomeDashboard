package com.example.smarthomedashboard

import android.view.View
import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Плитка «Камера» (docs/camera-tz.md, часть 1): ONVIF, свой пароль у камеры, плитка без звонка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraTileTest {
    private val context = RuntimeEnvironment.getApplication()

    // ---- ONVIF ----

    private fun soap(body: String) = """<?xml version="1.0" encoding="UTF-8"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
        xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
        xmlns:tt="http://www.onvif.org/ver10/schema"><SOAP-ENV:Body>$body</SOAP-ENV:Body></SOAP-ENV:Envelope>"""

    /** Поддельная камера ONVIF (как Reolink на порту 8000): запоминает запросы, проверяет подпись. */
    private class FakeCamera(val port: Int, val password: String = "secret") {
        val requests = mutableListOf<String>()
    }

    private fun camera(fake: FakeCamera, wrongAuth: Boolean = false): (String, String) -> Pair<Int, String> = { url, body ->
        fake.requests += body
        if (!url.contains(":${fake.port}/")) throw java.net.ConnectException("нет")
        when {
            body.contains("GetSystemDateAndTime") -> 200 to soap("<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime>" +
                "<tt:Date><tt:Year>2026</tt:Year><tt:Month>11</tt:Month><tt:Day>20</tt:Day></tt:Date>" +
                "<tt:Time><tt:Hour>1</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>0</tt:Second></tt:Time></tt:UTCDateTime>" +
                "</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>")
            wrongAuth -> 400 to soap("<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value>" +
                "</SOAP-ENV:Subcode></SOAP-ENV:Code></SOAP-ENV:Fault>")
            body.contains("GetDeviceInformation") -> 200 to soap("<tds:GetDeviceInformationResponse><tds:Manufacturer>Reolink</tds:Manufacturer>" +
                "<tds:Model>Reolink Video Doorbell PoE</tds:Model><tds:FirmwareVersion>v3.0.0</tds:FirmwareVersion></tds:GetDeviceInformationResponse>")
            body.contains("GetCapabilities") -> 200 to soap("<tds:GetCapabilitiesResponse><tds:Capabilities>" +
                "<tt:Events><tt:XAddr>http://0.0.0.0:${fake.port}/onvif/event_service</tt:XAddr></tt:Events>" +
                "<tt:Media><tt:XAddr>http://192.168.99.99:${fake.port}/onvif/media_service</tt:XAddr></tt:Media>" +
                "</tds:Capabilities></tds:GetCapabilitiesResponse>")
            body.contains("GetProfiles") -> 200 to soap("<trt:GetProfilesResponse>" +
                "<trt:Profiles token=\"000\" fixed=\"true\"><tt:Name>mainStream</tt:Name><tt:VideoEncoderConfiguration token=\"v0\">" +
                "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1920</tt:Height></tt:Resolution>" +
                "</tt:VideoEncoderConfiguration></trt:Profiles>" +
                "<trt:Profiles token=\"001\" fixed=\"true\"><tt:Name>subStream</tt:Name><tt:VideoEncoderConfiguration token=\"v1\">" +
                "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>640</tt:Width><tt:Height>480</tt:Height></tt:Resolution>" +
                "</tt:VideoEncoderConfiguration></trt:Profiles></trt:GetProfilesResponse>")
            body.contains("GetStreamUri") && url.contains("media_service") -> {
                val token = Regex("<ProfileToken>([^<]+)</ProfileToken>").find(body)!!.groupValues[1]
                val path = if (token == "000") "h264Preview_01_main" else "h264Preview_01_sub"
                200 to soap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://admin:pw@192.168.31.14:554/$path</tt:Uri>" +
                    "</trt:MediaUri></trt:GetStreamUriResponse>")
            }
            else -> 500 to soap("<SOAP-ENV:Fault/>")
        }
    }

    @Test fun probeFindsModelAndMainAndSubStreamsTryingPorts() {
        val fake = FakeCamera(8000)
        Onvif.transport = camera(fake)
        val info = Onvif.probe("192.168.31.14", 0, "admin", "secret")
        assertEquals("Reolink Reolink Video Doorbell PoE", info.title)
        assertEquals("порт найден перебором (80 не ответил)", 8000, info.port)
        assertEquals("rtsp://192.168.31.14:554/h264Preview_01_main", info.main!!.uri)
        assertEquals(2560, info.main!!.width)
        assertEquals("логин и пароль из ссылки убраны", "rtsp://192.168.31.14:554/h264Preview_01_sub", info.sub!!.uri)
        assertEquals("адрес службы событий — по тому адресу, где нашли камеру", "http://192.168.31.14:8000/onvif/event_service", info.eventsAddress)
        val signed = fake.requests.last { it.contains("GetDeviceInformation") }
        assertTrue(signed.contains("PasswordDigest"))
        assertFalse("пароль не уходит открытым текстом", signed.contains("secret"))
        assertTrue(signed.contains("<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">2026-11-20T01:00:"))
    }

    @Test fun wrongPasswordAndNoOnvifAreExplained() {
        Onvif.transport = camera(FakeCamera(8000), wrongAuth = true)
        try { Onvif.probe("192.168.31.14", 8000, "admin", "bad"); fail() } catch (e: Onvif.OnvifException) {
            assertTrue(e.auth)
            assertTrue(e.message!!.contains("логин или пароль"))
        }
        Onvif.transport = { _, _ -> throw java.net.SocketTimeoutException() }
        try { Onvif.probe("192.168.31.14", 0, "admin", "x"); fail() } catch (e: Onvif.OnvifException) {
            assertTrue(e.message!!, e.message!!.contains("Включите ONVIF"))
        }
    }

    @Test fun digestAndStreamChoice() {
        // Пример из стандарта WS-Security UsernameToken: Base64(SHA-1(nonce + created + password)).
        val nonce = android.util.Base64.decode("LKqI6G/AikKCQrN0zqZFlg==", android.util.Base64.NO_WRAP)
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", Onvif.digest(nonce, "2010-09-16T07:50:45Z", "userpassword"))
        val big = Onvif.Stream("rtsp://a/main", 1920, 1080, "H264", "0")
        val small = Onvif.Stream("rtsp://a/sub", 640, 360, "H264", "1")
        assertEquals(big to small, Onvif.pick(listOf(small, big)))
        assertEquals("один поток — и основной, и субпоток", big to big, Onvif.pick(listOf(big)))
        assertEquals("rtsp://h:554/x", Onvif.withoutCredentials("rtsp://u:p@h:554/x"))
        // SEC-02 (аудит 25.11.2026): rtsps, другой регистр и «@» в пароле — тоже без логина и пароля.
        assertEquals("rtsps://h:322/x", Onvif.withoutCredentials("rtsps://u:p@h:322/x"))
        assertEquals("RTSP://h/x", Onvif.withoutCredentials("RTSP://u:p@ss@h/x"))
        assertEquals("rtsp://h/x?a=b@c", Onvif.withoutCredentials("rtsp://h/x?a=b@c"))
        assertEquals("rtsp://h?a=b@c", Onvif.withoutCredentials("rtsp://h?a=b@c"))
    }

    // ---- Пароль камеры ----

    private class SoftKeys : SecretKeyProvider {
        var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    /** REC-04 (аудит 25.11.2026): хранилище не ответило — это не «пароля нет»: не запоминается, прочитается снова. */
    @Test fun storageFailureIsNotRememberedAsNoPassword() {
        val keys = SoftKeys()
        assertTrue(CameraSecrets(context, keys).write("cam_fail", "pass-one"))
        val key = keys.key
        val previous = CameraPasswords.secrets
        CameraPasswords.secrets = { CameraSecrets(it, keys) }
        CameraPasswords.clearCache()
        fun ask(secretKey: String): String? {
            var answered = false
            var value: String? = null
            CameraPasswords.read(context, secretKey) { value = it; answered = true }
            val until = System.nanoTime() + 5_000_000_000
            while (!answered && System.nanoTime() < until) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertTrue(answered)
            return value
        }
        try {
            keys.key = null
            assertNull("ключ недоступен", ask("cam_fail"))
            assertFalse(CameraPasswords.load(context, "cam_fail").sure)
            assertTrue("пароля точно нет", CameraPasswords.load(context, "cam_none").sure)
            keys.key = key
            assertEquals("сбой прошёл — пароль есть", "pass-one", ask("cam_fail"))
            // Служба записи: сбой посреди работы — прежний пароль, а не запись без пароля.
            val service = org.robolectric.Robolectric.buildService(RecorderService::class.java).get()
            fun recorder(k: String) = org.robolectric.util.ReflectionHelpers.callInstanceMethod<String?>(service, "readPassword",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, k))
            assertEquals("pass-one", recorder("cam_fail"))
            keys.key = null
            assertEquals("сбой — идёт с прежним", "pass-one", recorder("cam_fail"))
            keys.key = key
            assertTrue(CameraSecrets(context, keys).write("cam_fail", ""))
            assertNull("пароль удалили — это не сбой", recorder("cam_fail"))
        } finally {
            CameraPasswords.secrets = previous
            CameraPasswords.clearCache()
        }
    }

    @Test fun doorbellPasswordIsReadAgainAfterStorageFailure() {
        val previous = DoorbellHub.passwordStore
        var reads = 0
        var answer = SecretStore.ReadResult(SecretStore.Status.KEY_UNAVAILABLE)
        DoorbellHub.passwordStore = { reads++; answer }
        org.robolectric.util.ReflectionHelpers.setField(DoorbellHub, "passwordLoaded", false)
        fun ask(): String? {
            var answered = false
            var value: String? = null
            DoorbellHub.cameraPassword(context) { value = it; answered = true }
            val until = System.nanoTime() + 5_000_000_000
            while (!answered && System.nanoTime() < until) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertTrue(answered)
            return value
        }
        try {
            assertNull(ask())
            answer = SecretStore.ReadResult(SecretStore.Status.SUCCESS, "secret")
            assertEquals("после сбоя читается снова", "secret", ask())
            assertEquals("secret", ask())
            assertEquals("успех запоминается", 2, reads)
            org.robolectric.util.ReflectionHelpers.setField(DoorbellHub, "passwordLoaded", false)
            answer = SecretStore.ReadResult(SecretStore.Status.ABSENT)
            assertNull(ask()); assertNull(ask())
            assertEquals("«пароля нет» запоминается", 3, reads)
        } finally {
            DoorbellHub.passwordStore = previous
            org.robolectric.util.ReflectionHelpers.setField(DoorbellHub, "passwordLoaded", false)
            org.robolectric.util.ReflectionHelpers.setField(DoorbellHub, "password", null)
        }
    }

    @Test fun eachCameraHasOwnPasswordBoundToItsTile() {
        val keys = SoftKeys()
        val secrets = CameraSecrets(context, keys)
        assertTrue(secrets.write("cam1", "pass-one"))
        assertTrue(secrets.write("cam2", "pass-two"))
        assertEquals("pass-one", secrets.read("cam1"))
        assertEquals("pass-two", secrets.read("cam2"))
        // Подмена: шифр одной камеры подложили другой — не расшифруется (AAD с номером плитки).
        val prefs = context.getSharedPreferences(CameraSecrets.PREFS, 0)
        prefs.edit().putString("cam2", prefs.getString("cam1", null)).commit()
        assertNull(secrets.read("cam2"))
        assertFalse("в файле нет пароля открытым текстом", prefs.all.values.any { it.toString().contains("pass-one") })
        assertTrue(secrets.write("cam1", ""))
        assertNull(secrets.read("cam1"))
        // Источник записи: у камеры — свой ключ пароля, у домофона — прежний слот.
        val cam = TileEntity("cam9", DoorbellConfig.CAMERA_TYPE, "grid", "Улица", 0, 0, 1, 1, config = DoorbellConfig.newTileConfig())
        assertEquals("cam9", CameraSource.of(cam)!!.secretKey)
        val door = TileEntity("door9", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1, config = DoorbellConfig.newTileConfig())
        assertEquals("", CameraSource.of(door)!!.secretKey)
    }

    // ---- Прожектор (часть 3) ----

    private class Floodlight(val supported: Boolean) : okhttp3.mockwebserver.Dispatcher() {
        var state = 0
        val commands = mutableListOf<String>()
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
            val cmd = request.requestUrl?.queryParameter("cmd").orEmpty()
            commands += cmd
            val param = org.json.JSONArray(request.body.readUtf8()).getJSONObject(0).optJSONObject("param") ?: org.json.JSONObject()
            fun ok(value: org.json.JSONObject) = okhttp3.mockwebserver.MockResponse().setBody(
                org.json.JSONArray().put(org.json.JSONObject().put("cmd", cmd).put("code", 0).put("value", value)).toString())
            fun fail() = okhttp3.mockwebserver.MockResponse().setBody(org.json.JSONArray().put(org.json.JSONObject().put("cmd", cmd)
                .put("code", 1).put("error", org.json.JSONObject().put("rspCode", -9).put("detail", "not support"))).toString())
            return when (cmd) {
                "Login" -> ok(org.json.JSONObject().put("Token", org.json.JSONObject().put("name", "t1")))
                "GetWhiteLed" -> if (supported) ok(org.json.JSONObject().put("WhiteLed", org.json.JSONObject().put("state", state))) else fail()
                "SetWhiteLed" -> if (supported) { state = param.getJSONObject("WhiteLed").getInt("state"); ok(org.json.JSONObject()) } else fail()
                "Logout" -> ok(org.json.JSONObject())
                else -> fail()
            }
        }
    }

    @Test fun reolinkFloodlightOnOffAndModelWithoutIt() {
        val camera = Floodlight(supported = true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { dispatcher = camera; start() }
        val driver = ReolinkDriver(server.url("/").toString().trimEnd('/'), "admin", "pw", okhttp3.OkHttpClient())
        assertEquals(false, driver.floodlight())
        driver.setFloodlight(true)
        assertEquals(1, camera.state)
        assertEquals(true, driver.floodlight())
        driver.close()
        server.shutdown()
        val plain = Floodlight(supported = false)
        val server2 = okhttp3.mockwebserver.MockWebServer().apply { dispatcher = plain; start() }
        val d2 = ReolinkDriver(server2.url("/").toString().trimEnd('/'), "admin", "pw", okhttp3.OkHttpClient())
        assertNull("у модели нет прожектора — кнопки нет", d2.floodlight())
        d2.close(); server2.shutdown()
        assertTrue(CameraLight.looksReolink(DoorbellConfig(model = "Reolink RLC-811A")))
        assertTrue(CameraLight.looksReolink(DoorbellConfig(rtspSub = "rtsp://x/h264Preview_01_sub")))
        assertFalse(CameraLight.looksReolink(DoorbellConfig(rtspSub = "rtsp://x/Streaming/Channels/102", model = "Hikvision DS-2CD")))
        // Кнопка: только у раскрытой камеры, состояние — подписью.
        val card = DoorbellCard(context, expanded = true, camera = true)
        val button = card.findViewWithTag<android.widget.TextView>("door_floodlight")
        var asked: Boolean? = null
        card.onFloodlight = { asked = it }
        card.floodlight(null)
        assertEquals(View.GONE, button.visibility)
        card.floodlight(false)
        assertEquals("Прожектор: выкл", button.text.toString())
        button.performClick()
        assertEquals(true, asked)
        val door = DoorbellCard(context, expanded = true)
        door.floodlight(true)
        assertEquals("у домофона кнопки прожектора нет", View.GONE, door.findViewWithTag<View>("door_floodlight").visibility)
        card.dispose(); door.dispose()
    }

    // ---- Плитка ----

    @Test fun cameraCardHasNoDoorbellParts() {
        val card = DoorbellCard(context, expanded = true, camera = true).apply { tileId = "cam1" }
        card.onArchive = {}
        val config = DoorbellConfig(rtspSub = "rtsp://192.0.2.10/sub", host = "192.0.2.10", model = "Reolink RLC-811A", talk = false)
        val readings = BatteryReadings(HaSession(true, true), { null }, { false }, { null })
        card.bind("", config, DoorbellModel.snapshot(config, readings), false, 3, 1L, System.currentTimeMillis(), false)
        assertEquals(DoorbellConfig.CAMERA_TITLE, card.findViewWithTag<android.widget.TextView>("door_title").text.toString())
        assertEquals("нет «Звонок: …»", View.GONE, card.findViewWithTag<View>("door_last").visibility)
        assertEquals("нет пропущенных", View.GONE, card.findViewWithTag<View>("door_missed").visibility)
        assertEquals("разговор у камеры выключен — кнопки нет", View.GONE, card.findViewWithTag<View>("door_talk").visibility)
        assertFalse("у новой камеры разговор выключен",
            DoorbellConfig.from(DoorbellConfig.newTileConfig(camera = true)).talk)
        assertTrue(DoorbellConfig.from(DoorbellConfig.newTileConfig()).talk)
        assertEquals(View.VISIBLE, card.findViewWithTag<View>("door_archive").visibility)
        assertEquals("В сети", card.findViewWithTag<android.widget.TextView>("door_status").text.toString())
        assertTrue(DoorbellConfig.isVideo(DoorbellConfig.CAMERA_TYPE))
        // Новые поля — только у камер; у домофона формат прежний.
        val json = DoorbellConfig(host = "192.0.2.10", onvifPort = 8000, model = "X").toJson()
        assertEquals(8000, DoorbellConfig.fromJson(json).onvifPort)
        assertFalse(DoorbellConfig().toJson().has("host"))
        assertNotNull(DoorbellCard.createTile(context, "cam1", 400, 300, false, camera = true).first.findViewWithTag<DoorbellCard>("door_compact"))
        card.dispose()
    }
}
