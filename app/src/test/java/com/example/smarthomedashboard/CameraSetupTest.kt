package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Найденная камера → настройки плитки (docs/setup-tz.md, часть 3): ONVIF, Reolink без ONVIF, экран, редактор. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
class CameraSetupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val probe = CameraSetup.probe
    private val rtsp = CameraSetup.checkRtsp
    private val secretsFactory = CameraPasswords.secrets
    private val secrets by lazy { CameraSecrets(context, object : SecretKeyProvider {
        private var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }) }
    private val asked = CopyOnWriteArrayList<String>()
    private val controllers = mutableListOf<ActivityController<*>>()

    private val found = CameraFound("192.168.77.60", "", "Video Doorbell PoE", CameraBrand.REOLINK, 8000, setOf(CameraFound.Source.ONVIF), true)
    private val info = Onvif.Info("Reolink", "Video Doorbell PoE", "v3", 8000,
        Onvif.Stream("rtsp://192.168.77.60:554/h264Preview_01_main", 2560, 1920, "H264", "p0", fps = 20),
        Onvif.Stream("rtsp://192.168.77.60:554/h264Preview_01_sub", 640, 480, "H264", "p1", fps = 15), emptyList(), null)

    @Before fun setUp() {
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        context.getSharedPreferences(CameraSecrets.PREFS, 0).edit().clear().commit()
        CameraPasswords.clearCache()
        CameraPasswords.secrets = { secrets }
        CameraSetupActivity.last = null
        CameraSetup.probe = { host, port, user, password ->
            asked += "$host:$port $user"
            if (password != "Pa55-верно") throw Onvif.OnvifException("Камера не приняла логин или пароль (ONVIF)", auth = true)
            info
        }
        CameraSetup.checkRtsp = { _, _, _ -> error("RTSP не нужен") }
    }

    @After fun tearDown() {
        controllers.forEach { c -> runCatching { c.pause().stop().destroy() } }
        CameraSetup.probe = probe; CameraSetup.checkRtsp = rtsp; CameraPasswords.secrets = secretsFactory
        CameraSetupActivity.last = null
    }

    // ---- Логика ----

    @Test fun onvifGivesStreamsAndModel() {
        val ready = CameraSetup.connect(found, " ", "Pa55-верно")
        assertEquals(listOf("192.168.77.60:8000 admin"), asked.toList())
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_main", ready.config.rtspMain)
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_sub", ready.config.rtspSub)
        assertEquals("Reolink Video Doorbell PoE", ready.config.model)
        assertEquals("admin", ready.config.rtspUser)
        assertEquals(8000, ready.config.onvifPort)
        assertFalse("пароля в настройках нет", ready.config.toJson().toString().contains("Pa55-верно"))
    }

    @Test fun wrongPasswordIsOneAttemptWithoutFallback() {
        val e = assertThrows(CameraSetup.Failed::class.java) { CameraSetup.connect(found, "admin", "wrong") }
        assertTrue(e.auth)
        assertEquals("одна попытка: камеры блокируют вход", 1, asked.size)
    }

    @Test fun reolinkWithoutOnvifUsesItsUsualLinks() {
        CameraSetup.probe = { _, _, _, _ -> throw Onvif.OnvifException("Камера не ответила по ONVIF") }
        val checked = mutableListOf<String>()
        CameraSetup.checkRtsp = { url, user, _ -> checked += "$url $user" }
        val ready = CameraSetup.connect(found.copy(onvifPort = 0, model = "Двор"), "user", "Pa55-верно")
        assertEquals(listOf("rtsp://192.168.77.60:554/h264Preview_01_sub user"), checked)
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_main", ready.config.rtspMain)
        assertEquals("Reolink Двор", ready.config.model)
        assertTrue(CameraLight.looksReolink(ready.config))
        CameraSetup.checkRtsp = { _, _, _ -> throw RtspException("Неверный логин или пароль камеры", 401) }
        assertTrue(assertThrows(CameraSetup.Failed::class.java) { CameraSetup.connect(found, "admin", "x") }.auth)
        CameraSetup.checkRtsp = { _, _, _ -> throw java.net.ConnectException("refused") }
        val off = assertThrows(CameraSetup.Failed::class.java) { CameraSetup.connect(found, "admin", "x") }
        assertFalse(off.auth)
        assertTrue(off.message!!.contains("RTSP и ONVIF"))
    }

    @Test fun otherBrandWithoutOnvifIsExplained() {
        CameraSetup.probe = { _, _, _, _ -> throw java.net.SocketTimeoutException("timeout") }
        val e = assertThrows(CameraSetup.Failed::class.java) { CameraSetup.connect(CameraFound("192.168.77.70", model = "IPC"), "admin", "x") }
        assertTrue(e.message!!, e.message!!.contains("Включите ONVIF"))
        CameraSetup.probe = { _, _, _, _ -> info.copy(main = info.main!!.copy(uri = "")) }
        assertThrows(CameraSetup.Failed::class.java) { CameraSetup.connect(CameraFound("192.168.77.70", onvifPort = 80), "admin", "x") }
    }

    @Test fun hintsAreForBrandAndNeverEmpty() {
        CameraBrand.entries.forEach { assertTrue(it.name, CameraHints.of(it).contains("самой камеры")) }
        assertTrue(CameraHints.of(CameraBrand.TAPO).contains("Учётная запись камеры"))
        assertEquals("неподтверждённой марки подсказка общая", CameraHints.of(CameraBrand.OTHER), CameraHints.of(CameraBrand.UBIQUITI))
    }

    @Test fun decoderNoteSaysWhatThisTabletWillDo() {
        val samsung = DecoderFacts(mapOf(VideoDepacketizer.Codec.H264 to (1920 to 1080))) { _, w, h -> w * h <= 1920 * 1088 }
        assertTrue(CameraSetup.decoderNote(info, samsung).contains("больше предела аппаратного декодера этого планшета (1920×1080)"))
        val small = info.copy(main = info.main!!.copy(width = 1920, height = 1080))
        assertEquals("Основной поток 1920×1080 H.264, 20 к/с — планшет справится", CameraSetup.decoderNote(small, samsung))
        assertTrue(CameraSetup.decoderNote(info.copy(main = info.main!!.copy(encoding = "H265")), samsung).contains("субпоток"))
        assertTrue(CameraSetup.decoderNote(info.copy(main = info.main!!.copy(encoding = "JPEG")), samsung).contains("H.264 и H.265"))
        assertEquals("", CameraSetup.decoderNote(null, samsung))
    }

    /** Стенд камер (docs/camera-stand.md): CAM_STAND=127.0.0.1, ONVIF камеры D на порту 8000 — настоящие ONVIF и RTSP. */
    @Test fun standCameraIsSetUp() {
        val stand = System.getenv("CAM_STAND").orEmpty()
        org.junit.Assume.assumeTrue(stand.isNotEmpty())
        CameraSetup.probe = probe
        CameraSetup.checkRtsp = rtsp
        val ready = CameraSetup.connect(CameraFound(stand, model = "StandD", onvifPort = 8000), "admin", "любой")
        assertTrue(ready.config.rtspMain, ready.config.rtspMain.startsWith("rtsp://$stand:8557/"))
        assertTrue(ready.config.rtspSub.startsWith("rtsp://$stand:8557/") && ready.config.rtspSub != ready.config.rtspMain)
        assertEquals(2560 to 1920, ready.info!!.main!!.width to ready.info!!.main!!.height)
        CameraSetup.checkRtsp(ready.config.rtspSub, "admin", "любой")
        assertThrows(java.io.IOException::class.java) { CameraSetup.checkRtsp("rtsp://$stand:8557/нет-такого", "admin", "любой") }
    }

    // ---- Экран ----

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }
    private fun <T : PinProtectedActivity> allow(activity: T) = ReflectionHelpers.setField(activity, "pinGateAccess", PinAccess(object : PinAccess.Storage {
        override fun read() = PinAccess.State(null, null)
        override fun write(state: PinAccess.State) = true
    }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
        clock = object : PinAccess.Clock { override fun elapsed() = 1L; override fun boot() = "b" }, worker = {}))

    private fun open(item: CameraFound = found, otherDoorbell: Boolean = false): CameraSetupActivity {
        val c = Robolectric.buildActivity(CameraSetupActivity::class.java, CameraSetupActivity.intent(context, item, otherDoorbell))
        controllers += c
        allow(c.get())
        c.create().start().resume().visible()
        await("доступ") { ReflectionHelpers.getField<Boolean>(c.get(), "pinAuthorized") }
        return c.get()
    }
    private fun Activity.view(tag: String): View? = window.decorView.findViewWithTag(tag)
    private fun Activity.input(tag: String) = view(tag) as EditText
    private fun Activity.texts(): String {
        val out = StringBuilder()
        fun walk(v: View) { if (v is TextView && v.visibility == View.VISIBLE) out.append(v.text).append('\n'); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(window.decorView)
        return out.toString()
    }

    @Test fun cameraIsCheckedShownAndHandedOver() {
        val activity = open(found.copy(doorbell = false, name = "Двор"))
        activity.view("camera_check")!!.performClick()
        assertTrue(activity.texts().contains("Введите пароль камеры"))
        activity.input("camera_password").setText("Pa55-верно")
        activity.view("camera_check")!!.performClick()
        await("показан результат") { activity.view("camera_add") != null }
        assertNotNull(activity.view("camera_picture"))
        assertTrue(activity.texts().contains("2560×1920"))
        assertEquals("Двор", activity.input("camera_title").text.toString())
        activity.input("camera_title").setText("Двор, ворота")
        activity.view("camera_add")!!.performClick()
        await("передано") { activity.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val data = shadowOf(activity).resultIntent
        val id = data.getStringExtra(CameraSetupActivity.EXTRA_ID)!!
        assertEquals("Двор, ворота", data.getStringExtra(CameraSetupActivity.EXTRA_TITLE))
        assertFalse(data.getBooleanExtra(CameraSetupActivity.EXTRA_DOORBELL, true))
        assertFalse("пароль не передаётся", data.extras!!.keySet().any { data.extras!!.get(it).toString().contains("Pa55-верно") })
        assertEquals("пароль — в защищённом хранилище под номером плитки", "Pa55-верно", secrets.read(id))
        val config = DoorbellConfig.fromJson(JSONObject(data.getStringExtra(CameraSetupActivity.EXTRA_CONFIG)!!))
        assertEquals("192.168.77.60", config.host)
        assertFalse("разговор у новой камеры выключен", config.talk)
    }

    @Test fun doorbellKeepsItsUsualSettings() {
        val activity = open()
        activity.input("camera_password").setText("Pa55-верно")
        activity.view("camera_check")!!.performClick()
        await("результат") { activity.view("camera_add") != null }
        // Пароль домофона — в прежнем слоте ([SecretStore]); его запись в JVM не проверяется, поэтому — только настройки.
        ReflectionHelpers.setField(activity, "doorbell", false)
        activity.view("camera_type_doorbell")!!.performClick()
        assertTrue(ReflectionHelpers.getField(activity, "doorbell"))
        activity.view("camera_type_camera")!!.performClick()
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "doorbell"))
    }

    @Test fun wrongPasswordShowsHintAndLeavesNothing() {
        val activity = open()
        activity.input("camera_password").setText("wrong")
        activity.view("camera_check")!!.performClick()
        await("отказ") { activity.texts().contains("не приняла логин или пароль") }
        assertTrue("подсказка марки", (activity.view("camera_hint") as TextView).text.isNotBlank())
        assertNull(activity.view("camera_add"))
        assertNull("пароль не предлагается дальше", CameraSetupActivity.last)
        assertTrue(context.getSharedPreferences(CameraSecrets.PREFS, 0).all.isEmpty())
        assertFalse(activity.input("camera_password").isSaveEnabled)
    }

    @Test fun cancelledCameraLeavesNoPasswordAndNextCameraOffersSameLoginByButtonOnly() {
        val activity = open()
        activity.input("camera_user").setText("viewer")
        activity.input("camera_password").setText("Pa55-верно")
        activity.view("camera_check")!!.performClick()
        await("результат") { activity.view("camera_add") != null }
        assertEquals(1, context.getSharedPreferences(CameraSecrets.PREFS, 0).all.size)
        activity.finish()
        controllers.first().pause().stop().destroy()
        await("пароль убран") { context.getSharedPreferences(CameraSecrets.PREFS, 0).all.isEmpty() }
        asked.clear()
        val next = open(CameraFound("192.168.77.61", onvifPort = 80))
        assertEquals("сам пароль не подставляется", "", next.input("camera_password").text.toString())
        assertTrue(asked.isEmpty())
        next.view("camera_same")!!.performClick()
        await("проверена") { next.view("camera_add") != null }
        assertEquals(listOf("192.168.77.61:80 viewer"), asked.toList())
    }

    @Test fun passwordIsRemovedEvenIfLaterCheckFailed() {
        val activity = open()
        activity.input("camera_password").setText("Pa55-верно")
        activity.view("camera_check")!!.performClick()
        await("результат") { activity.view("camera_add") != null }
        activity.input("camera_password").setText("wrong")
        activity.view("camera_check")!!.performClick()
        await("отказ") { activity.texts().contains("не приняла логин или пароль") }
        activity.finish()
        controllers.first().pause().stop().destroy()
        await("пароль убран") { context.getSharedPreferences(CameraSecrets.PREFS, 0).all.isEmpty() }
    }

    @Test fun secondDoorbellDoesNotOverwriteFirstDoorbellsPassword() {
        // Пароль первого домофона уже в общем месте (в JVM — сразу в памяти DoorbellHub: хранилище Android недоступно).
        ReflectionHelpers.setField(DoorbellHub, "password", "Pa55-первый")
        ReflectionHelpers.setField(DoorbellHub, "passwordLoaded", true)
        try {
            val activity = open(otherDoorbell = true)
            activity.input("camera_password").setText("Pa55-верно")
            activity.view("camera_check")!!.performClick()
            await("результат") { activity.view("camera_add") != null }
            activity.view("camera_type_doorbell")!!.performClick()
            activity.view("camera_add")!!.performClick()
            await("объяснение") { activity.texts().contains("один общий пароль") }
            assertFalse(activity.isFinishing)
            activity.view("camera_type_camera")!!.performClick()
            activity.view("camera_add")!!.performClick()
            await("добавлена как камера") { activity.isFinishing }
        } finally {
            ReflectionHelpers.setField(DoorbellHub, "passwordLoaded", false)
            ReflectionHelpers.setField(DoorbellHub, "password", null)
        }
    }

    // ---- Редактор ----

    private fun editor(type: String): DoorbellEditorActivity {
        TileManager(context).saveTiles(listOf(TileEntity("tile", type, "grid", "Видео", 10, 20, 1, 1,
            config = DoorbellConfig.newTileConfig(camera = type == DoorbellConfig.CAMERA_TYPE))))
        val c = Robolectric.buildActivity(DoorbellEditorActivity::class.java, Intent(context, DoorbellEditorActivity::class.java).putExtra("tile_id", "tile"))
        controllers += c
        return c.create().get().also { idle() }
    }

    @Test fun editorFindsCameraAndFillsFieldsOnlyWithTypedPassword() {
        val e = editor(DoorbellConfig.CAMERA_TYPE)
        assertNotNull(e.view("door_find_network"))
        val result = e.view("door_onvif_result") as TextView
        e.useFound(found, result)
        await("просит пароль") { result.text.contains("Введите пароль камеры") }
        assertEquals("192.168.77.60", e.input("door_host").text.toString())
        assertTrue("без пароля камера не спрашивается", asked.isEmpty())
        e.input("door_password").setText("Pa55-верно")
        e.useFound(found, result)
        await("найдено") { result.text.startsWith("Найдено") }
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_main", e.input("door_rtsp_main").text.toString())
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_sub", e.input("door_rtsp_sub").text.toString())
        e.input("door_password").setText("wrong")
        e.useFound(found, result)
        await("отказ") { result.text.contains("не приняла логин или пароль") }
        assertEquals("прежнее не тронуто", "rtsp://192.168.77.60:554/h264Preview_01_main", e.input("door_rtsp_main").text.toString())
    }

    @Test fun doorbellEditorGetsOnlyLinksAndLogin() {
        val e = editor(DoorbellConfig.TYPE)
        e.input("door_password").setText("Pa55-верно")
        val result = e.view("door_find_result") as TextView
        e.useFound(found, result)
        await("найдено") { result.text.startsWith("Найдено") }
        val config = ReflectionHelpers.getField<DoorbellConfig>(e, "config")
        assertEquals("rtsp://192.168.77.60:554/h264Preview_01_main", config.rtspMain)
        assertEquals("прежний набор ключей домофона", "" to "", config.host to config.model)
    }
}
