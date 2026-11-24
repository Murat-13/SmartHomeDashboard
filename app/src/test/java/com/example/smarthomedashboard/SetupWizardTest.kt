package com.example.smarthomedashboard

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.TimeUnit

/** Мастер первой настройки (docs/setup-tz.md, этап 1): шаги, пропуск, восстановление из файла, продолжение. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
class SetupWizardTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val tiles = context.getSharedPreferences("tiles_prefs", 0)
    private var controller: ActivityController<SetupActivity>? = null
    private var onChange: ((List<HaFound>) -> Unit)? = null

    private class SilentBrowser : HaBrowser {
        override fun start(onService: (HaService) -> Unit) {}
        override fun stop() {}
    }

    @Before fun setUp() { prefs.edit().clear().commit(); tiles.edit().clear().commit() }
    @After fun tearDown() { controller?.pause()?.stop()?.destroy() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    private fun open(state: (String) -> HaServer.State = { HaServer.State.READY }): SetupActivity {
        val c = Robolectric.buildActivity(SetupActivity::class.java)
        controller = c
        c.get().newDiscovery = { context, change -> onChange = change; HaDiscovery(context, change, SilentBrowser(), names = emptyList(), network = { null }) }
        c.get().checkServer = { address -> HaServer.Check(state(address), HaServer.origin(address), "проверено: ${state(address)}") }
        return c.setup().get().also { idle() }
    }

    private fun SetupActivity.view(tag: String): View = window.decorView.findViewWithTag(tag) ?: error("нет $tag")
    private fun SetupActivity.click(tag: String) { view(tag).performClick(); idle() }
    private fun SetupActivity.status() = findViewById<LinearLayout>(R.id.setupServerStatus).let { (it.getChildAt(1) as TextView).text.toString() }
    private fun SetupActivity.texts(): String {
        val out = StringBuilder()
        fun walk(v: View) { if (v is TextView && v.visibility == View.VISIBLE) out.append(v.text).append('\n'); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(window.decorView)
        return out.toString()
    }

    @Test fun foundServerOpensLoginWithItsAddress() {
        val activity = open()
        assertEquals(SetupActivity.Step.START, activity.step)
        assertTrue(activity.texts().contains("Восстановить из файла копии"))
        activity.click("setup_new")
        assertTrue(activity.texts().contains("Шаг 2 из 6"))
        onChange!!(listOf(HaFound("http://192.168.77.10:8123", "Мой дом", "2026.11.3", "u1", "https://home.example.com"),
            HaFound("http://192.168.77.11:8123", "Дача", "2025.1.0", "u2")))
        idle()
        assertTrue(activity.texts().contains("Мой дом"))
        assertTrue("первый выбран сам", activity.view("setup_found_0").isSelected)
        activity.click("setup_found_1")
        activity.click("setup_next")
        await("открыт вход") { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(HaLoginActivity::class.java.name, intent.component!!.className)
        assertEquals("http://192.168.77.11:8123", intent.getStringExtra(HaLoginActivity.EXTRA_LOCAL))
        assertEquals("u2", intent.getStringExtra(HaLoginActivity.EXTRA_UUID))
        assertEquals("Дача", intent.getStringExtra(HaLoginActivity.EXTRA_NAME))
        // Адрес до входа не сохраняется.
        assertFalse(prefs.contains("ha_local_host"))
        assertFalse(prefs.getBoolean("setup_completed", false))
    }

    @Test fun typedAddressIsCheckedAndUnsuitableStaysOnStep() {
        var state = HaServer.State.NOT_HA
        val activity = open { state }
        activity.click("setup_new")
        activity.click("setup_next")
        assertEquals("Выберите найденный Home Assistant или введите адрес", activity.status())
        // Внешний адрес без шифрования — отказ до подключения.
        (activity.view("setup_address") as EditText).setText("http://home.example.com:8123")
        activity.click("setup_next")
        assertEquals(HomeAssistantAddress.INSECURE_REMOTE_REFUSED, activity.status())
        (activity.view("setup_address") as EditText).setText("home.example.com")
        activity.click("setup_next")
        await("ответ показан") { activity.status().startsWith("проверено") }
        assertNull(shadowOf(activity).peekNextStartedActivityForResult())
        state = HaServer.State.READY
        activity.click("setup_next")
        await("открыт вход") { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        // Не домашняя сеть — адрес становится внешним.
        assertEquals("", intent.getStringExtra(HaLoginActivity.EXTRA_LOCAL))
        assertEquals("home.example.com", intent.getStringExtra(HaLoginActivity.EXTRA_EXTERNAL))
    }

    @Test fun homeAddressIsLocalAndUnencryptedExternalIsRefused() {
        val activity = open()
        activity.click("setup_new")
        (activity.view("setup_address") as EditText).setText("http://192.168.77.10:8123")
        (activity.view("setup_external") as EditText).setText("http://home.example.com")
        activity.click("setup_next")
        assertEquals(HomeAssistantAddress.INSECURE_REMOTE_REFUSED, activity.status())
        (activity.view("setup_external") as EditText).setText("https://home.example.com")
        activity.click("setup_next")
        await("открыт вход") { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals("http://192.168.77.10:8123", intent.getStringExtra(HaLoginActivity.EXTRA_LOCAL))
        assertEquals("https://home.example.com", intent.getStringExtra(HaLoginActivity.EXTRA_EXTERNAL))
    }

    @Test fun afterLoginProtectThenDoneThenPanel() {
        val activity = open()
        activity.click("setup_new")
        (activity.view("setup_address") as EditText).setText("http://192.168.77.10:8123")
        activity.click("setup_next")
        await("открыт вход") { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val started = shadowOf(activity).nextStartedActivityForResult
        prefs.edit().putBoolean("setup_completed", true).commit() // как HaConnector после входа
        activity.readHome = { throw HaLogin.LoginException("Home Assistant не отвечает") }
        activity.newCameraSearch = search()
        shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_OK,
            Intent().putExtra(HaLoginActivity.EXTRA_NAME, "Мой дом"))
        idle()
        assertEquals(SetupActivity.Step.CAMERAS, activity.step)
        assertEquals("прерванный мастер продолжится здесь", SetupActivity.Step.CAMERAS, SetupActivity.resumeStep(context))
        activity.click("setup_next")
        assertEquals(SetupActivity.Step.ROOMS, activity.step)
        await("комнаты не прочитались") { activity.texts().contains("Home Assistant не отвечает") }
        assertFalse("создавать нечего", activity.view("setup_rooms_next").isEnabled)
        activity.click("setup_rooms_skip")
        assertEquals(SetupActivity.Step.PROTECT, activity.step)
        activity.click("setup_next")
        assertEquals(SetupActivity.Step.DONE, activity.step)
        assertTrue(activity.texts().contains("Home Assistant: подключён · Мой дом"))
        activity.click("setup_finish")
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
        assertNull("мастер закончен — больше не продолжается", SetupActivity.resumeStep(context))
    }

    // ---- Камеры ----

    /** Поиск камер без сети: то, что задано, сразу. */
    private fun search(vararg items: CameraFound, homes: MutableList<Boolean> = mutableListOf()): (android.content.Context, (List<CameraFound>) -> Unit, () -> Unit, (() -> HaHome)?) -> CameraDiscovery =
        { context, change, done, home ->
            homes += home != null
            CameraDiscovery(context, change, done, home, onvif = { found, _ -> items.forEach(found) }, reolink = { _, _ -> })
        }

    private val door = CameraFound("192.168.77.60", "", "Video Doorbell", CameraBrand.REOLINK, 8000, setOf(CameraFound.Source.ONVIF), true)
    private val yard = CameraFound("192.168.77.61", "", "IPC", CameraBrand.OTHER, 80, setOf(CameraFound.Source.ONVIF))

    private fun cameras(connected: Boolean, vararg items: CameraFound, homes: MutableList<Boolean> = mutableListOf()): SetupActivity {
        prefs.edit().putBoolean("setup_completed", connected).apply {
            if (connected) putString("setup_step", "CAMERAS").putLong("setup_step_at", System.currentTimeMillis())
        }.commit()
        val c = Robolectric.buildActivity(SetupActivity::class.java)
        controller = c
        c.get().readHome = { home() }
        c.get().newCameraSearch = search(*items, homes = homes)
        return c.setup().get()
    }

    private fun SetupActivity.added(item: CameraFound, id: String, title: String, doorbell: Boolean) {
        click("setup_camera_${item.host}")
        val started = shadowOf(this).nextStartedActivityForResult
        assertEquals(CameraSetupActivity::class.java.name, started.intent.component!!.className)
        assertEquals(item.host, started.intent.getStringExtra(CameraSetupActivity.EXTRA_HOST))
        assertEquals(item.onvifPort, started.intent.getIntExtra(CameraSetupActivity.EXTRA_PORT, -1))
        shadowOf(this).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().putExtra(CameraSetupActivity.EXTRA_ID, id)
            .putExtra(CameraSetupActivity.EXTRA_HOST, item.host).putExtra(CameraSetupActivity.EXTRA_TITLE, title).putExtra(CameraSetupActivity.EXTRA_DOORBELL, doorbell)
            .putExtra(CameraSetupActivity.EXTRA_CONFIG, DoorbellConfig(rtspMain = "rtsp://${item.host}/main", rtspUser = "admin").toJson().toString()))
        idle()
    }
    private fun tilesNow() = com.example.smarthomedashboard.data.TileManager(context).loadTiles()

    @Test fun foundCamerasAreAddedAndPlacedOnMainWithTheirIds() {
        val homes = mutableListOf<Boolean>()
        val activity = cameras(true, yard, door, homes = homes)
        await("найдены") { activity.texts().contains("Найдено: 2") }
        assertEquals("поиск спрашивает и Home Assistant", listOf(true), homes)
        activity.added(door, "door-tile", "Калитка", true)
        activity.added(yard, "yard-tile", "Двор", false)
        assertTrue(activity.texts().contains("Домофон · добавлено"))
        assertNull("добавленная второй раз не предлагается", activity.window.decorView.findViewWithTag<View>("setup_camera_${door.host}"))
        activity.click("setup_next")
        await("комнаты") { activity.texts().contains("Плиток:") }
        activity.click("setup_rooms_next")
        val video = tilesNow().filter { DoorbellConfig.isVideo(it.type) }
        assertEquals("номера плиток — те, под которыми сохранены пароли", listOf("door-tile" to DoorbellConfig.TYPE, "yard-tile" to DoorbellConfig.CAMERA_TYPE),
            video.map { it.id to it.type })
        assertTrue(video.all { it.page == 0 })
        assertEquals("rtsp://192.168.77.60/main", DoorbellConfig.from(video.first().config).rtspMain)
        assertTrue(activity.texts(), tilesNow().any { it.title == "Свет · Кухня" })
    }

    @Test fun camerasStayWhenRoomsAreSkippedAndWithoutHomeAssistant() {
        val activity = cameras(true, door)
        await("найдена") { activity.texts().contains("Найдено: 1") }
        activity.added(door, "door-tile", "Калитка", true)
        activity.click("setup_next")
        await("комнаты") { activity.texts().contains("Плиток:") }
        activity.click("setup_rooms_skip")
        assertEquals(setOf("Часы · Погода · Климат", "Калитка"), tilesNow().map { it.title }.toSet())
        controller!!.pause().stop().destroy(); controller = null
        setUp()
        val homes = mutableListOf<Boolean>()
        val offline = cameras(false, door, homes = homes)
        // Не вошли — мастер начинается сначала; до камер доходим через «Пропустить».
        assertEquals(SetupActivity.Step.START, offline.step)
        offline.click("setup_new"); offline.click("setup_server_skip")
        await("найдена") { offline.texts().contains("Найдено: 1") }
        assertEquals(listOf(false), homes)
        offline.added(door, "door-tile", "Калитка", true)
        offline.click("setup_next")
        assertEquals(SetupActivity.Step.PROTECT, offline.step)
        assertEquals(setOf("Часы · Погода · Климат", "Калитка"), tilesNow().map { it.title }.toSet())
    }

    @Test fun removedCameraIsForgottenAndAddressOutsideHomeIsRefused() {
        val activity = cameras(true, door)
        await("найдена") { activity.texts().contains("Найдено: 1") }
        activity.added(door, "door-tile", "Калитка", true)
        activity.click("setup_camera_remove_${door.host}")
        assertTrue(activity.cameras.isEmpty())
        (activity.view("setup_camera_host") as EditText).setText("8.8.8.8")
        activity.click("setup_camera_typed")
        assertTrue(activity.texts().contains("Нужен адрес в домашней сети"))
        assertNull(shadowOf(activity).peekNextStartedActivityForResult())
        (activity.view("setup_camera_host") as EditText).setText("http://192.168.77.99:80/onvif")
        activity.click("setup_camera_typed")
        assertEquals("192.168.77.99", shadowOf(activity).nextStartedActivityForResult.intent.getStringExtra(CameraSetupActivity.EXTRA_HOST))
    }

    @Test fun rotationKeepsAddedCameras() {
        val activity = cameras(true, door)
        await("найдена") { activity.texts().contains("Найдено: 1") }
        activity.added(door, "door-tile", "Калитка", true)
        val state = android.os.Bundle()
        controller!!.saveInstanceState(state)
        controller!!.pause().stop().destroy()
        val c = Robolectric.buildActivity(SetupActivity::class.java)
        controller = c
        c.get().readHome = { home() }
        c.get().newCameraSearch = search(door)
        c.create(state).start().restoreInstanceState(state).resume().visible()
        assertEquals(listOf("door-tile" to "Калитка"), c.get().cameras.map { it.id to it.title })
        assertEquals("rtsp://192.168.77.60/main", c.get().cameras.single().config.rtspMain)
    }

    // ---- Комнаты ----

    private fun home(): HaHome {
        fun entity(id: String, name: String, area: String, attributes: JSONObject = JSONObject()) =
            JSONObject().put("entity_id", id).put("area_id", area).put("original_name", name) to
                JSONObject().put("entity_id", id).put("state", "off").put("attributes", attributes.put("friendly_name", name))
        val all = listOf(entity("light.kitchen", "Люстра", "kit"), entity("light.led", "Подсветка", "kit"), entity("switch.kettle", "Чайник", "kit"),
            entity("light.bed", "Ночник", "bed"), entity("lock.door", "Входная дверь", "bed"))
        return HaHome.parse(JSONArray().put(JSONObject().put("area_id", "kit").put("name", "Кухня")).put(JSONObject().put("area_id", "bed").put("name", "Спальня")),
            JSONArray(), JSONArray(), JSONArray(all.map { it.first }), JSONArray(all.map { it.second }))
    }

    private fun rooms(): SetupActivity {
        prefs.edit().putBoolean("setup_completed", true).putString("setup_step", "ROOMS").putLong("setup_step_at", System.currentTimeMillis()).commit()
        val c = Robolectric.buildActivity(SetupActivity::class.java)
        controller = c
        c.get().readHome = { home() }
        val activity = c.setup().get()
        await("комнаты прочитаны") { activity.texts().contains("Плиток:") }
        return activity
    }
    private fun SetupActivity.box(tag: String) = view(tag) as android.widget.CheckBox
    private fun SetupActivity.tileTitles() = com.example.smarthomedashboard.data.TileManager(context).loadTiles().map { it.title }.toSet()

    @Test fun roomsAreOfferedCheckedAndCreatedAsTiles() {
        val activity = rooms()
        assertEquals(SetupActivity.Step.ROOMS, activity.step)
        assertTrue(activity.texts(), activity.texts().contains("Плиток: 7 · страниц: 1"))
        assertTrue(activity.box("setup_room_kit").isChecked)
        // Отдельное устройство: раскрыть комнату и снять галочку.
        activity.click("setup_room_open_kit")
        activity.box("setup_item_switch.kettle").isChecked = false
        idle()
        assertTrue(activity.texts(), activity.texts().contains("Плиток: 6"))
        // Вся комната сразу.
        activity.box("setup_room_bed").isChecked = false
        idle()
        assertFalse(activity.box("setup_room_bed").isChecked)
        activity.click("setup_rooms_next")
        assertEquals(SetupActivity.Step.PROTECT, activity.step)
        assertEquals(setOf("Часы · Погода · Климат", "Свет · Кухня", "Кухня · Люстра", "Кухня · Подсветка"), activity.tileTitles())
        assertTrue("замок не выбран — прохода нет", GateStore.load(context).isEmpty())
        // Назад и ещё раз: предложенное заменяется (камер нет — назад сразу к комнатам).
        activity.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(SetupActivity.Step.ROOMS, activity.step)
        assertFalse("выбор помнится", activity.box("setup_room_bed").isChecked)
        activity.box("setup_room_bed").isChecked = true
        idle()
        activity.click("setup_rooms_next")
        assertTrue(activity.tileTitles().containsAll(listOf("Входная дверь", "Спальня · Ночник")))
        assertEquals(listOf("lock.door"), GateStore.load(context).map { it.open })
        // Убрать на последнем шаге.
        activity.click("setup_next")
        assertTrue(activity.texts().contains("Плиток на панели: 6"))
        activity.click("setup_undo")
        assertTrue(activity.tileTitles().isEmpty())
        assertTrue(GateStore.load(context).isEmpty())
    }

    @Test fun restoredPanelSkipsRoomsAndRotationKeepsChoice() {
        val activity = rooms()
        activity.box("setup_room_bed").isChecked = false
        idle()
        controller!!.get().readHome = { home() }
        controller!!.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT; screenWidthDp = 800; screenHeightDp = 1280
        })
        val rotated = controller!!.get()
        rotated.readHome = { home() }
        // Новый экран читает дом заново (подмена успевает не всегда — тогда «Повторить»).
        await("после поворота") { idle(); rotated.texts().contains("Плиток:") || rotated.texts().contains("Повторить") }
        if (rotated.texts().contains("Повторить")) { rotated.click("setup_rooms_again"); await("прочитано") { rotated.texts().contains("Плиток:") } }
        assertFalse(rotated.box("setup_room_bed").isChecked)
        assertTrue(rotated.box("setup_room_kit").isChecked)
        // Плитки из файла копии: предлагать нечего.
        com.example.smarthomedashboard.data.TileManager(context).saveTiles(listOf(com.example.smarthomedashboard.data.TileEntity("mine", "clock", "grid", "Моя", 0, 0, 1, 1)))
        assertFalse(StarterTiles.canPropose(context))
    }

    @Test fun everythingCanBeSkippedAndPanelOpensWithoutServer() {
        val activity = open()
        activity.click("setup_new")
        activity.newCameraSearch = search()
        activity.click("setup_server_skip")
        assertEquals(SetupActivity.Step.CAMERAS, activity.step)
        activity.click("setup_next")
        activity.click("setup_next")
        assertTrue(activity.texts().contains("Home Assistant: не подключён"))
        activity.click("setup_finish")
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertFalse(prefs.getBoolean("setup_completed", false))
        assertNull(SetupActivity.resumeStep(context))
    }

    @Test fun backReturnsOneStepAndRotationKeepsTypedAddress() {
        val activity = open()
        activity.click("setup_new")
        (activity.view("setup_address") as EditText).setText("http://192.168.77.10:8123")
        controller!!.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT; screenWidthDp = 800; screenHeightDp = 1280
        })
        idle()
        val rotated = controller!!.get()
        assertEquals(SetupActivity.Step.SERVER, rotated.step)
        assertEquals("http://192.168.77.10:8123", (rotated.view("setup_address") as EditText).text.toString())
        rotated.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(SetupActivity.Step.START, rotated.step)
        rotated.onBackPressedDispatcher.onBackPressed()
        assertTrue(rotated.isFinishing)
    }

    // ---- Восстановление из файла ----

    private fun copy(local: String) = JSONObject().put("ha_local_host", local).put("ha_remote_host", "https://home.example.com")
        .put("screen_timeout_minutes", 9).put("temp_unit", "F").put("page_count", 2).put("ha_token", "FAKE-IN-FILE")
        .put("tiles_json", JSONArray().put(JSONObject().put("id", "t").put("type", "clock").put("container", "grid")
            .put("title", "Часы").put("x", 0).put("y", 0).put("width", 1).put("height", 1)).toString()).toString()

    @Test fun fileRestoresTilesAndAsksToLoginWithItsAddress() {
        val activity = open()
        activity.restoreFrom { copy("http://192.168.77.10:8123") }
        await("перешли к Home Assistant") { activity.step == SetupActivity.Step.SERVER }
        assertTrue(activity.texts().contains("восстановлены"))
        assertEquals("http://192.168.77.10:8123", (activity.view("setup_address") as EditText).text.toString())
        assertEquals("https://home.example.com", (activity.view("setup_external") as EditText).text.toString())
        assertTrue(tiles.getString("tiles", "")!!.contains("\"t\""))
        assertEquals(9, prefs.getInt("screen_timeout_minutes", 0))
        // Адрес — только заготовка, токен из файла не берётся.
        assertFalse(prefs.contains("ha_local_host"))
        assertFalse(prefs.contains("ha_token"))
        assertFalse(prefs.getBoolean("setup_completed", false))
    }

    @Test fun brokenFileChangesNothing() {
        val activity = open()
        for (bad in listOf("не json", "[1,2]", """{"screen_timeout_minutes":"пять"}""", """{"tiles_json":"[{}]"}""")) {
            activity.restoreFrom { bad }
            await("сообщение") { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null }
            org.robolectric.shadows.ShadowToast.reset()
        }
        activity.restoreFrom { null }
        await("сообщение") { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null }
        assertEquals(SetupActivity.Step.START, activity.step)
        assertTrue(tiles.all.isEmpty())
        assertFalse(prefs.contains("screen_timeout_minutes"))
    }

    // ---- Кто открывается после заставки ----

    private fun splashOpens(home: Boolean = false): String {
        val intent = Intent(context, SplashActivity::class.java).apply { if (home) addCategory(Intent.CATEGORY_HOME) }
        val splash = Robolectric.buildActivity(SplashActivity::class.java, intent).create().get()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        return shadowOf(splash).nextStartedActivity!!.component!!.className
    }

    @Test fun configuredPanelNeverSeesWizardAndInterruptedOneIsResumedOnlyForAnHour() {
        assertEquals(SetupActivity::class.java.name, splashOpens())
        prefs.edit().putBoolean("setup_completed", true).commit()
        assertEquals("настроенная панель", MainActivity::class.java.name, splashOpens())
        prefs.edit().putString("setup_step", "PROTECT").putLong("setup_step_at", System.currentTimeMillis() - 5 * 60_000).commit()
        assertEquals("мастер прервали 5 минут назад", SetupActivity::class.java.name, splashOpens())
        assertEquals("кнопка «Домой» — панель", MainActivity::class.java.name, splashOpens(home = true))
        prefs.edit().putLong("setup_step_at", System.currentTimeMillis() - 2 * 3600_000).commit()
        assertEquals("давно — панель", MainActivity::class.java.name, splashOpens())
        assertNotNull(context)
    }
}
