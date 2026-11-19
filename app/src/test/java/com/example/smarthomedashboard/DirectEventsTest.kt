package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket

/**
 * Прямые события с камер (docs/direct-events-tz.md, этап 1): драйвер Reolink против поддельной камеры (API как у
 * Video Doorbell PoE владельца, 19.11.2026), приёмник вебхуков и весь путь «включить → вебхук → звонок → выключить».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectEventsTest {
    /** Поддельная камера Reolink: вход, 4 слота вебхука, состояние кнопки, истечение сессии. */
    class FakeReolink(var password: String = "pass") : Dispatcher() {
        val slots = MutableList(4) { JSONObject().put("index", it).put("hookUrl", "").put("hookBody", "").put("bCustom", 0).put("indexEnable", -1) }
        @Volatile var token: String? = null
        @Volatile var logins = 0
        @Volatile var expireNext = false
        @Volatile var visitor = 0
        @Volatile var polls = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val cmd = request.requestUrl?.queryParameter("cmd").orEmpty()
            val param = JSONArray(request.body.readUtf8()).getJSONObject(0).optJSONObject("param") ?: JSONObject()
            if (cmd == "Login") {
                val user = param.getJSONObject("User")
                return if (user.optString("userName") == "admin" && user.optString("password") == password) {
                    token = "t${++logins}"
                    ok(cmd, JSONObject().put("Token", JSONObject().put("leaseTime", 3600).put("name", token)))
                } else error(cmd, -7, "login failed")
            }
            if (expireNext || request.requestUrl?.queryParameter("token") != token) { expireNext = false; return error(cmd, -6, "please login first") }
            return when (cmd) {
                "GetDevInfo" -> ok(cmd, JSONObject().put("DevInfo", JSONObject().put("model", "Reolink Video Doorbell PoE").put("firmVer", "v3.0.0.6460")))
                "GetWebHook" -> ok(cmd, JSONObject().put("WebHook", JSONArray(synchronized(slots) { slots.map { JSONObject(it.toString()) } })).put("channel", 0))
                "SetWebHook" -> {
                    val hook = param.getJSONObject("WebHook")
                    synchronized(slots) {
                        val i = hook.getInt("index")
                        slots[i] = JSONObject().put("index", i).put("hookUrl", hook.optString("hookUrl")).put("hookBody", hook.optString("hookBody"))
                            .put("bCustom", hook.optInt("bCustom")).put("indexEnable", hook.optInt("indexEnable"))
                    }
                    ok(cmd, JSONObject().put("rspCode", 200))
                }
                "GetEvents" -> polls++.let { ok(cmd, JSONObject().put("visitor", JSONObject().put("alarm_state", visitor).put("support", 1))) }
                "Logout" -> { token = null; ok(cmd, JSONObject().put("rspCode", 200)) }
                else -> error(cmd, -9, "not support")
            }
        }

        private fun ok(cmd: String, value: JSONObject) =
            MockResponse().setBody(JSONArray().put(JSONObject().put("cmd", cmd).put("code", 0).put("value", value)).toString())

        private fun error(cmd: String, code: Int, detail: String) = MockResponse().setBody(JSONArray().put(JSONObject().put("cmd", cmd)
            .put("code", 1).put("error", JSONObject().put("detail", detail).put("rspCode", code))).toString())

        fun slot(i: Int) = synchronized(slots) { JSONObject(slots[i].toString()) }
    }

    private val camera = FakeReolink()
    private val api = MockWebServer().apply { dispatcher = camera; start() }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rings = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val recordingEvents = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
    private val base get() = api.url("/").toString().trimEnd('/')
    private val http = OkHttpClient()

    private fun driver(password: String = "pass") = ReolinkDriver(base, "admin", password, http)

    @Before fun setUp() {
        context.getSharedPreferences("camera_events", Context.MODE_PRIVATE).edit().clear()
            .putInt("port", ServerSocket(0).use { it.localPort }).commit()
        DirectEvents.driverFactory = { _, user, password, _, _ -> ReolinkDriver(base, user, password, http) }
        DirectEvents.passwordSource = { _, result -> result(camera.password) }
        DirectEvents.onRing = { id, source -> rings += id to source }
        DirectEvents.onRecordingEvents = { id, keys -> recordingEvents += id to keys }
        DirectEvents.localAddress = { "127.0.0.1" }
    }

    @After fun tearDown() {
        DirectEvents.detach()
        DirectEvents.drain()
        shadowOf(Looper.getMainLooper()).idle()
        api.shutdown()
        RecordingEvents.clear()
        RecordingEvents.clock = { System.currentTimeMillis() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.currentTimeMillis() > until) fail("Не дождались: $what")
            Thread.sleep(20)
        }
    }

    private fun post(url: String, body: String): Int = http.newCall(Request.Builder().url(url)
        .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { it.code }

    private val visitorHook = """{"alarm":{"alarmTime":"2026-11-19T15:12:35.000+0000","channel":0,"channelName":"Reolink Video Doorbell PoE",""" +
        """"device":"Reolink Video Doorbell PoE","message":"A Visitor is Ringing","name":"Visitor Alert","title":"Visitor message","type":"VISITOR"}}"""

    // ---- Драйвер ----

    @Test fun driverInstallsIntoFreeSlotReusesOwnAndLeavesForeignAlone() {
        camera.slots[0] = JSONObject(camera.slot(0).toString()).put("hookUrl", "http://192.0.2.50/other").put("indexEnable", 1)
        val d = driver()
        assertEquals("Reolink Video Doorbell PoE", d.detect().model)
        assertEquals("чужой слот 0 занят — берётся 1", 1, d.install("http://192.0.2.10:8765/cam/aaaabbbbccccdddd", "aaaabbbbccccdddd"))
        assertEquals("http://192.0.2.50/other", camera.slot(0).getString("hookUrl"))
        assertEquals(CameraEventDriver.Health.OK, d.verify("http://192.0.2.10:8765/cam/aaaabbbbccccdddd", "aaaabbbbccccdddd"))
        assertEquals("адрес планшета сменился", CameraEventDriver.Health.MOVED, d.verify("http://192.0.2.11:8765/cam/aaaabbbbccccdddd", "aaaabbbbccccdddd"))
        assertEquals("свой слот находится повторно", 1, d.install("http://192.0.2.11:8765/cam/aaaabbbbccccdddd", "aaaabbbbccccdddd"))
        assertEquals("http://192.0.2.11:8765/cam/aaaabbbbccccdddd", camera.slot(1).getString("hookUrl"))
        assertEquals(CameraEventDriver.Health.MISSING, d.verify("http://192.0.2.11:8765/cam/eeee", "eeeeffff00001111"))
        d.uninstall("aaaabbbbccccdddd")
        assertEquals("", camera.slot(1).getString("hookUrl"))
        assertEquals(-1, camera.slot(1).getInt("indexEnable"))
        assertEquals("чужой не тронут", "http://192.0.2.50/other", camera.slot(0).getString("hookUrl"))
        d.close()
        assertNull("сессия закрыта", camera.token)
    }

    @Test fun driverReportsFullSlotsAuthAndRelogsInAfterExpiry() {
        repeat(4) { i -> camera.slots[i] = JSONObject(camera.slot(i).toString()).put("hookUrl", "http://192.0.2.5$i/x").put("indexEnable", 1) }
        val full = assertThrows(CameraException::class.java) { driver().install("http://192.0.2.10:8765/cam/s", "aaaabbbbccccdddd") }
        assertTrue(full.message!!.contains("заняты"))
        val auth = assertThrows(CameraException::class.java) { driver("wrong").detect() }
        assertTrue(auth.auth)
        val d = driver()
        d.detect()
        val before = camera.logins
        camera.expireNext = true
        assertEquals("сессия истекла — повторный вход", "Reolink Video Doorbell PoE", d.detect().model)
        assertEquals(before + 1, camera.logins)
    }

    @Test fun driverParsesWebhookTypesAndPollsButton() {
        val d = driver()
        assertEquals(listOf(CameraEventType.RING), d.parse(visitorHook))
        assertEquals(listOf(CameraEventType.PERSON), d.parse("""{"alarm":{"type":"PEOPLE"}}"""))
        assertTrue(d.parse("""{"alarm":{"type":"SOMETHING_NEW"}}""").isEmpty())
        assertTrue(d.parse("мусор").isEmpty())
        assertFalse(d.ringActive())
        camera.visitor = 1
        assertTrue(d.ringActive())
    }

    // ---- Приёмник ----

    @Test fun hookServerAcceptsOnlyRoutedPostsWithinLimits() {
        val port = ServerSocket(0).use { it.localPort }
        val seen = mutableListOf<String>()
        val server = CameraHookServer(port) { path, _, body -> if (path == "/cam/ok") { seen += body; true } else false }
        server.start()
        try {
            assertEquals(200, post("http://127.0.0.1:$port/cam/ok", visitorHook))
            assertEquals(listOf(visitorHook), seen)
            assertEquals(404, post("http://127.0.0.1:$port/cam/unknown", visitorHook))
            assertEquals(404, http.newCall(Request.Builder().url("http://127.0.0.1:$port/cam/ok").build()).execute().use { it.code })
            assertEquals("тело больше 16 КБ", 404, post("http://127.0.0.1:$port/cam/ok", "x".repeat(20_000)))
            // Поток запросов с одного адреса: лишние за секунду закрываются без ответа.
            val answered = (1..30).count {
                try { Socket("127.0.0.1", port).use { s ->
                    s.getOutputStream().write("POST /cam/ok HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    s.soTimeout = 2_000
                    s.getInputStream().read() >= 0
                } } catch (_: Exception) { false }
            }
            assertTrue("ответов $answered из 30", answered in 1..20)
        } finally {
            server.stop()
        }
    }

    // ---- Весь путь ----

    private val config = DoorbellConfig(rtspMain = "rtsp://127.0.0.1:554/h264Preview_01_main", rtspUser = "admin")

    @Test fun enableWebhookRingsAndDisableCleansCamera() {
        DirectEvents.attach(context)
        DirectEvents.setEnabled(context, "door", config, true)
        waitFor("статус «Подключено»") { DirectEvents.status(context, "door").level == DirectEvents.Level.OK }
        assertTrue(DirectEvents.status(context, "door").text.contains("Reolink Video Doorbell PoE"))
        val url = camera.slot(0).getString("hookUrl")
        val port = DirectEvents.port(context)
        assertTrue(url, url.matches(Regex("http://127\\.0\\.0\\.1:$port/cam/[0-9a-f]{32}")))
        assertNull("сессия API закрыта после проверки", camera.token)

        assertEquals(200, post(url, visitorHook))
        waitFor("звонок") { rings.isNotEmpty() }
        assertEquals("door" to "вебхук", rings.single())
        waitFor("последнее событие в статусе") { DirectEvents.status(context, "door").text.contains("звонок") }
        assertEquals("неверный секрет", 404, post(url.dropLast(4) + "0000", visitorHook))
        // Этап 2: события камеры — в запись (звонок тоже событие записи).
        assertEquals(200, post(url, """{"alarm":{"type":"PEOPLE","name":"Person Detected"}}"""))
        waitFor("событие «человек» для записи") { recordingEvents.any { it.second == listOf("person") } }
        assertEquals("значок «человек» на плитке", setOf(DoorbellConfig.Detection.PERSON), DirectEvents.activeDetections("door"))
        assertTrue(recordingEvents.contains("door" to listOf("visitor")))
        assertEquals("человек — не звонок", 1, rings.size)

        // Повторная проверка: адрес на месте — слот тот же, второй не занимается.
        DirectEvents.check("door")
        waitFor("повторная проверка") { DirectEvents.status(context, "door").level == DirectEvents.Level.OK }
        assertEquals("", camera.slot(1).getString("hookUrl"))

        DirectEvents.setEnabled(context, "door", config, false)
        waitFor("вебхук убран из камеры") { camera.slot(0).getString("hookUrl").isEmpty() }
        assertEquals(-1, camera.slot(0).getInt("indexEnable"))
        assertEquals(DirectEvents.Level.OFF, DirectEvents.status(context, "door").level)
        assertFalse(DirectEvents.enabled(context, "door"))
        ServerSocket(port).close() // приёмник закрыт — порт свободен
    }

    @Test fun webhookFromAnotherAddressIsRejected() {
        DirectEvents.attach(context)
        // Камера по настройкам — 127.0.0.2, а запрос приходит с 127.0.0.1.
        DirectEvents.setEnabled(context, "door", config.copy(rtspMain = "rtsp://127.0.0.2:554/h264Preview_01_main"), true)
        waitFor("подключено") { DirectEvents.status(context, "door").level == DirectEvents.Level.OK }
        assertEquals(404, post(camera.slot(0).getString("hookUrl"), visitorHook))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(rings.isEmpty())
    }

    @Test fun noFreeSlotFallsBackToPollingAndWrongPasswordStops() {
        repeat(4) { i -> camera.slots[i] = JSONObject(camera.slot(i).toString()).put("hookUrl", "http://192.0.2.5$i/x").put("indexEnable", 1) }
        DirectEvents.attach(context)
        DirectEvents.setEnabled(context, "door", config, true)
        waitFor("предупреждение") { DirectEvents.status(context, "door").level == DirectEvents.Level.WARN }
        assertTrue(DirectEvents.status(context, "door").text.contains("заняты"))
        // Опрос сначала видит «не нажато» (запуск во время звонка не звонит), затем нажатие.
        waitFor("первый опрос") { camera.polls > 0 }
        camera.visitor = 1
        waitFor("звонок опросом") { rings.isNotEmpty() }
        assertEquals("door" to "опрос", rings.first())

        DirectEvents.setEnabled(context, "door", config, false)
        camera.password = "changed"
        DirectEvents.passwordSource = { _, result -> result("pass") }
        DirectEvents.setEnabled(context, "door", config, true)
        waitFor("ошибка входа") { DirectEvents.status(context, "door").level == DirectEvents.Level.ERROR }
        assertTrue(DirectEvents.status(context, "door").text.contains("логин или пароль"))
    }

    /** Этап 2: событие с камеры запускает HD-запись и без HA; держится [RecordingEvents.DIRECT_HOLD_MS]. */
    @Test fun directCameraEventsDriveRecordingWithAndWithoutHa() {
        var now = 1_000_000L
        RecordingEvents.clock = { now }
        val triggers = RecordingConfig.DEFAULT_TRIGGERS // без «движения»
        assertNull("ни HA, ни событий — неизвестно", RecordingEvents.of("door"))
        RecordingEvents.direct("door", listOf("person", "motion"), triggers)
        assertEquals("без HA — по камере; движение не в настройке записи", setOf("person"), RecordingEvents.of("door"))
        now += RecordingEvents.DIRECT_HOLD_MS - 1
        assertEquals(setOf("person"), RecordingEvents.of("door"))
        now += 2
        assertNull("время события вышло, HA нет — снова неизвестно", RecordingEvents.of("door"))

        // С HA: объединение. Датчик «машина» в HA включён, камера сообщает звонок.
        val config = DoorbellConfig(rtspMain = "rtsp://192.0.2.10/main", rtspUser = "admin", vehicle = "binary_sensor.door_vehicle")
        val tile = com.example.smarthomedashboard.data.TileEntity(id = "door", type = DoorbellConfig.TYPE, container = "main",
            title = "Домофон", x = 0, y = 0, width = 400, height = 300, config = config.writeTo("{}"))
        val values = mapOf("binary_sensor.door_vehicle" to "on")
        RecordingEvents.update(listOf(tile), BatteryReadings(HaSession(true, true), values::get, { false }, { null }))
        assertEquals(setOf("vehicle"), RecordingEvents.of("door"))
        RecordingEvents.direct("door", listOf("visitor"), triggers)
        assertEquals(setOf("vehicle", "visitor"), RecordingEvents.of("door"))
        now += RecordingEvents.DIRECT_HOLD_MS
        assertEquals("событие камеры закончилось, HA остаётся", setOf("vehicle"), RecordingEvents.of("door"))
    }

    /**
     * Раскрытая карточка ловит касания как «свернуть»; «Архив» и «Говорить» объявлены своей областью (найдено на
     * планшете 19.11.2026: «Архив» в раскрытой плитке не открывался). Значки событий — в шапке.
     */
    @Test fun expandedCardButtonsAreInteractiveAndIconsAreOnTop() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val card = DoorbellCard(activity, expanded = true).apply { tileId = "door"; onArchive = {} }
        activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(card, android.view.ViewGroup.LayoutParams(1200, 900))
        val config = DoorbellConfig(rtspSub = "rtsp://192.0.2.10/sub", rtspUser = "admin", person = "binary_sensor.door_person")
        val readings = BatteryReadings(HaSession(true, true), mapOf("binary_sensor.door_person" to "on")::get, { false }, { null })
        card.bind("Домофон", config, DoorbellModel.snapshot(config, readings), false, 0, null, System.currentTimeMillis(), false)
        shadowOf(Looper.getMainLooper()).idle()
        fun center(tag: String): Pair<Float, Float> {
            val v = card.findViewWithTag<android.view.View>(tag)
            assertTrue("$tag видна", v.isShown)
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            return loc[0] + v.width / 2f to loc[1] + v.height / 2f
        }
        val (ax, ay) = center("door_archive")
        assertTrue("«Архив» — своя область", card.interactiveAt(ax, ay))
        val (tx, ty) = center("door_talk")
        assertTrue("«Говорить» — своя область", card.interactiveAt(tx, ty))
        assertFalse("видео — касание сворачивает", card.interactiveAt(600f, 450f))
        val (_, iconY) = center("door_icon_person")
        assertTrue("значок вверху (y=$iconY)", iconY < 450)
        card.dispose()
    }

    @Test fun missingAddressOrLoginIsExplained() {
        DirectEvents.attach(context)
        DirectEvents.setEnabled(context, "door", DoorbellConfig(), true)
        assertEquals(DirectEvents.Level.ERROR, DirectEvents.status(context, "door").level)
        assertFalse(DirectEvents.enabled(context, "door"))
    }
}
