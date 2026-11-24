package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Прямой снимок камеры (docs/cameras.md, «Камеры только со снимком»). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraSnapshotTest {
    private val server = MockWebServer()
    private val transport = Onvif.transport
    @After fun tearDown() { runCatching { server.shutdown() }; Onvif.transport = transport }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun jpeg(w: Int, h: Int) = ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

    private fun load(path: String, user: String = "admin", password: String = "secret"): android.graphics.Bitmap? {
        val result = AtomicReference<Array<android.graphics.Bitmap?>>()
        CameraSnapshot.load("http://127.0.0.1:${server.port}$path", user, password) { result.set(arrayOf(it)) }
        val end = System.currentTimeMillis() + 8_000
        while (result.get() == null) { shadowOf(Looper.getMainLooper()).idle(); assertTrue(System.currentTimeMillis() < end); Thread.sleep(10) }
        return result.get()[0]
    }

    private fun serve(body: ByteArray, digest: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (digest) {
                    val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(request.getHeader("Authorization").orEmpty()).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
                    if (p["response"] != md5("${md5("admin:cam:secret")}:n1:${md5("GET:${request.path}")}"))
                        return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"cam\"").addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"n1\"")
                }
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    @Test fun digestSnapshotIsLoadedAndScaled() {
        serve(jpeg(2560, 1920), digest = true)
        val bitmap = load("/snap.jpg?channel=1")
        assertNotNull(bitmap)
        assertTrue("${bitmap!!.width}", bitmap.width in 960..1280)
        // Пароль открытым текстом не ушёл: у камеры есть Digest.
        repeat(server.requestCount) { assertFalse(server.takeRequest().getHeader("Authorization").orEmpty().startsWith("Basic")) }
    }

    @Test fun wrongPasswordIsAskedOnce() {
        serve(jpeg(64, 64), digest = true)
        assertNull(load("/snap.jpg", password = "нет"))
        assertEquals("вопрос и один ответ", 2, server.requestCount)
    }

    @Test fun onlyHomeNetworkAndNoCredentialsInAddress() {
        assertNull(CameraSnapshot.usable("http://8.8.8.8/snap.jpg"))
        assertNull(CameraSnapshot.usable("https://camera.example.com/snap.jpg"))
        assertNull(CameraSnapshot.usable("ftp://192.168.1.5/snap.jpg"))
        assertNull(CameraSnapshot.usable("просто текст"))
        assertEquals("http://192.168.1.5/snap.jpg?x=1", CameraSnapshot.usable(" http://admin:secret@192.168.1.5/snap.jpg?x=1 "))
    }

    @Test fun tooBigOrNotPicture() {
        serve(ByteArray(9 * 1024 * 1024), digest = false)
        assertNull(load("/big.jpg"))
        assertNull(CameraSnapshot.decode("это не картинка".toByteArray()))
    }

    // ---- ONVIF и настройки ----

    private fun soap(body: String) = """<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
        xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>$body</s:Body></s:Envelope>"""

    private fun onvif(stream: String?, snapshot: String?) {
        Onvif.transport = { _, body ->
            when {
                "GetDeviceInformation" in body -> 200 to soap("<tds:GetDeviceInformationResponse><tds:Manufacturer>Old</tds:Manufacturer><tds:Model>Cam</tds:Model><tds:FirmwareVersion>1</tds:FirmwareVersion></tds:GetDeviceInformationResponse>")
                "GetCapabilities" in body -> 200 to soap("<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://192.0.2.10:80/onvif/media</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>")
                "GetProfiles" in body -> 200 to soap("<trt:GetProfilesResponse><trt:Profiles token=\"p0\"><tt:VideoEncoderConfiguration token=\"v0\"><tt:Encoding>JPEG</tt:Encoding>" +
                    "<tt:Resolution><tt:Width>640</tt:Width><tt:Height>480</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles></trt:GetProfilesResponse>")
                "GetStreamUri" in body -> if (stream == null) 500 to soap("") else 200 to soap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>$stream</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>")
                "GetSnapshotUri" in body -> if (snapshot == null) 500 to soap("") else 200 to soap("<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>$snapshot</tt:Uri></trt:MediaUri></trt:GetSnapshotUriResponse>")
                else -> 500 to soap("")
            }
        }
    }

    @Test fun onvifSnapshotAndUnreachableAddresses() {
        onvif("rtsp://0.0.0.0:554/stream1", "http://127.0.0.1:8080/snap.jpg")
        val info = Onvif.probe("192.168.1.50", 80, "admin", "secret")
        assertEquals("rtsp://192.168.1.50:554/stream1", info.main!!.uri)
        assertEquals("http://192.168.1.50:8080/snap.jpg", info.snapshot)
        onvif("rtsps://192.168.1.50:7441/abc?enableSrtp", null)
        assertEquals("rtsps://192.168.1.50:7441/abc?enableSrtp", Onvif.probe("192.168.1.50", 80, "admin", "secret").main!!.uri)
        // Снимок наружу не берём.
        onvif("rtsp://192.168.1.50:554/stream1", "http://cloud.example.com/snap.jpg")
        assertEquals("", Onvif.probe("192.168.1.50", 80, "admin", "secret").snapshot)
        assertEquals("rtsp://10.0.0.7:554/a", Onvif.reachable("rtsp://10.0.0.7:554/a", "192.168.1.50"))
    }

    @Test fun cameraWithSnapshotOnly() {
        onvif(null, "http://192.168.1.50/snap.jpg")
        val info = Onvif.probe("192.168.1.50", 80, "admin", "secret")
        assertNull(info.main)
        assertEquals("http://192.168.1.50/snap.jpg", info.snapshot)
        onvif(null, null)
        assertTrue(runCatching { Onvif.probe("192.168.1.50", 80, "admin", "secret") }.exceptionOrNull() is Onvif.OnvifException)
    }

    @Test fun snapshotAddressInSettings() {
        val config = DoorbellConfig(snapshotUrl = "http://192.168.1.50/snap.jpg")
        assertTrue(config.hasVideo)
        assertEquals("http://192.168.1.50/snap.jpg", DoorbellConfig.from(config.writeTo("{}")).snapshotUrl)
        assertFalse("у кого его нет, настройки прежние", JSONObject(DoorbellConfig().writeTo("{}")).toString().contains("snapshot_url"))
    }
}
