package com.example.smarthomedashboard.deep

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.smarthomedashboard.CameraHub
import com.example.smarthomedashboard.CameraPasswords
import com.example.smarthomedashboard.DoorbellCard
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellVideoView
import com.example.smarthomedashboard.MainActivity
import com.example.smarthomedashboard.data.TileEntity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Камеры других марок на настоящей плитке (docs/cameras.md): камера, которую встроенный плеер не понимает, показывается своим
 * клиентом; камера без потока — снимком. Камеры поддельные, в процессе теста — идёт на GitHub при каждом изменении.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CameraBrandsDeepTest {
    private lateinit var ha: FakeHomeAssistant
    private var scenario: ActivityScenario<MainActivity>? = null
    private var camera: FakeCamera? = null
    private var web: MockWebServer? = null

    @After fun stop() {
        resumedActivity()?.let { a ->
            onMain { a.finish() }
            runCatching { waitFor(10_000, "экран закрыт") { onMain { a.isDestroyed } } }
        }
        scenario?.close()
        if (::ha.isInitialized) ha.close()
        camera?.close()
        web?.shutdown()
    }

    private fun open(config: DoorbellConfig) {
        assumeTrue("Только эмулятор: тест заменяет данные приложения", isEmulator())
        ha = FakeHomeAssistant()
        val size = JSONObject().put("button_size", 480).put("widget_height", 360).toString()
        val tiles = listOf(TileEntity(TILE, DoorbellConfig.CAMERA_TYPE, "grid", "Камера", 40, 120, 1, 1,
            config = config.writeTo(DoorbellConfig.newTileConfig(size, camera = true))))
        prepareApp(ha, tiles)
        val saved = CountDownLatch(1)
        onMain { CameraPasswords.save(appContext, TILE, FakeCamera.PASSWORD) { saved.countDown() } }
        assertTrue("пароль камеры записан", saved.await(10, TimeUnit.SECONDS))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(30_000, "подключение к фальшивому HA") { ha.connected() >= 1 }
    }

    private fun card(): DoorbellCard? = (resumedActivity() as? MainActivity)?.let { a ->
        onMain { a.window.decorView.findViewWithTag<View>(TILE)?.findViewWithTag<DoorbellCard>("door_compact") }
    }

    private fun status() = card()?.let { c -> onMain { c.video.status } }

    private fun playing(what: String) {
        waitFor(60_000, "$what: картинка на плитке (статус ${status()})") { status() == DoorbellVideoView.Status.PLAYING }
        val before = card()!!.let { c -> onMain { c.video.framesShown } }
        waitFor(20_000, "$what: кадры идут") { card()?.let { c -> onMain { c.video.framesShown } } ?: 0 > before + 10 }
    }

    /** Только SHA-256: встроенный плеер входить так не умеет. */
    @Test fun sha256CameraIsShownByOwnClient() {
        val cam = FakeCamera(sha256 = true).also { camera = it }
        open(DoorbellConfig(rtspMain = cam.url, rtspSub = cam.url, rtspUser = FakeCamera.USER, talk = false))
        playing("SHA-256")
        assertTrue("поток держит показ, записи нет", CameraHub.has(cam.url, FakeCamera.USER))
        log("Камера только с SHA-256: показывает свой клиент, подключений ${cam.connections.get()}")
        // Панель закрыли — камера отпущена.
        resumedActivity()?.let { a -> onMain { a.finish() } }
        waitFor(30_000, "поток закрыт") { !CameraHub.has(cam.url, FakeCamera.USER) }
    }

    /** Чужие адреса в описании и вопросы камеры внутри потока. */
    @Test fun wrongAddressesAndQuestionsInsideStream() {
        val cam = FakeCamera(sha256 = true, wrongHost = true, asks = true).also { camera = it }
        open(DoorbellConfig(rtspMain = cam.url, rtspSub = cam.url, rtspUser = FakeCamera.USER, talk = false))
        playing("чужие адреса")
        val connections = cam.connections.get()
        Thread.sleep(8_000)
        assertEquals("сессия не рвётся: камере отвечают", connections, cam.connections.get())
        assertEquals(DoorbellVideoView.Status.PLAYING, status())
    }

    /**
     * Reolink (docs/doorbell.md, «Видео по протоколу Reolink»): поток RTSP уже идёт через планшет (как при записи) — картинка приходит по
     * протоколу Reolink; он пропал — показ продолжается через планшет сам; его нет вовсе — показ начинается как раньше.
     */
    @Test fun reolinkProtocolWithInstantFallback() {
        val cam = FakeCamera(path = "h264Preview_01_main").also { camera = it }
        val reolink = FakeReolink(cam.units)
        val hold = CameraHub.hold(CameraHub.Key(cam.url, FakeCamera.USER, FakeCamera.PASSWORD))
        try {
            waitFor(10_000, "поток RTSP через планшет") { CameraHub.relayUrl(cam.url, FakeCamera.USER) != null }
            open(DoorbellConfig(rtspMain = cam.url, rtspSub = cam.url, rtspUser = FakeCamera.USER, talk = false))
            playing("по протоколу Reolink")
            assertTrue("картинка — по протоколу Reolink", onMainFast())
            assertEquals(listOf("main"), reolink.streams.toList())
            // Камера перестала отвечать на порту 9000: показ продолжается через планшет, без «Нет сигнала».
            reolink.close()
            waitFor(20_000, "показ через планшет") { status() == DoorbellVideoView.Status.PLAYING && !onMainFast() }
            playing("после пропажи протокола")
            assertEquals("к камере по RTSP — одно подключение", 1, cam.connections.get())
            log("Reolink: пропал протокол — показ через планшет, подключений по RTSP ${cam.connections.get()}")
        } finally { hold.release(); reolink.close() }
    }

    @Test fun reolinkWithoutItsPortStartsAsBefore() {
        val cam = FakeCamera(path = "h264Preview_01_main").also { camera = it }
        val hold = CameraHub.hold(CameraHub.Key(cam.url, FakeCamera.USER, FakeCamera.PASSWORD))
        try {
            waitFor(10_000, "поток RTSP через планшет") { CameraHub.relayUrl(cam.url, FakeCamera.USER) != null }
            open(DoorbellConfig(rtspMain = cam.url, rtspSub = cam.url, rtspUser = FakeCamera.USER, talk = false))
            playing("без порта 9000")
            assertTrue("обычным путём", !onMainFast())
        } finally { hold.release() }
    }

    private fun onMainFast() = card()?.let { c -> onMain { c.video.viaFast } } == true

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Камера без потока: снимок по HTTP с Digest, раз в несколько секунд. */
    @Test fun snapshotOnlyCamera() {
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { Canvas(this).drawColor(Color.rgb(20, 140, 60)) }
                .compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        val served = AtomicInteger()
        val server = MockWebServer().also { web = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(request.getHeader("Authorization").orEmpty())
                    .associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
                val ok = p["response"] == md5("${md5("${FakeCamera.USER}:cam:${FakeCamera.PASSWORD}")}:n1:${md5("GET:/snap.jpg?channel=1")}")
                if (!ok) return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"cam\", nonce=\"n1\"")
                served.incrementAndGet()
                return MockResponse().addHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
            }
        }
        server.start()
        open(DoorbellConfig(snapshotUrl = "http://127.0.0.1:${server.port}/snap.jpg?channel=1", rtspUser = FakeCamera.USER, talk = false))
        waitFor(30_000, "снимок на плитке (статус ${status()})") { status() == DoorbellVideoView.Status.SNAPSHOT }
        val first = served.get()
        waitFor(20_000, "снимок обновляется") { served.get() >= first + 2 }
        val video = card()!!.video
        val color = onMain { video.let { v ->
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)); b.getPixel(v.width / 2, v.height / 2) } }
        assertTrue("на плитке — снимок камеры: ${Integer.toHexString(color)}", Color.green(color) > 100 && Color.red(color) < 80)
    }

    private companion object { const val TILE = "deep_cam" }
}
