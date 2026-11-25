package com.example.smarthomedashboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import com.example.smarthomedashboard.TileScale.Screen
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import com.google.gson.Gson
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
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress

/** Экраны копирования на второй планшет: старый показывает код, новый вводит и применяет. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
class CopyScreensTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("dashboard_prefs", 0)
    private val controllers = mutableListOf<ActivityController<*>>()
    private var service: ((HaService) -> Unit)? = null
    private val silent = mutableSetOf<String>()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("starter_tiles", 0).edit().clear().commit()
        TileManager(context).saveTiles(emptyList())
    }
    @After fun tearDown() { controllers.forEach { c -> runCatching { c.pause().stop().destroy() } } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, ready: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!ready()) { idle(); if (System.currentTimeMillis() > end) throw AssertionError("Не дождались: $what"); Thread.sleep(10) }
        idle()
    }
    private fun allow(activity: PinProtectedActivity) = ReflectionHelpers.setField(activity, "pinGateAccess", PinAccess(object : PinAccess.Storage {
        override fun read() = PinAccess.State(null, null)
        override fun write(state: PinAccess.State) = true
    }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
        clock = object : PinAccess.Clock { override fun elapsed() = 1L; override fun boot() = "b" }, worker = {}))
    private fun <T : PinProtectedActivity> show(c: ActivityController<T>): T {
        controllers += c
        allow(c.get())
        c.create().start().resume().visible()
        if (!c.get().isFinishing) await("доступ") { ReflectionHelpers.getField<Boolean>(c.get(), "pinAuthorized") }
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

    // ---- Старый планшет ----

    private fun out(address: InetAddress? = InetAddress.getByName("127.0.0.1")): CopyOutActivity {
        val c = Robolectric.buildActivity(CopyOutActivity::class.java)
        c.get().homeAddress = { address }
        c.get().newCode = { "K7H2MX9AB" }
        return show(c)
    }

    @Test fun oldTabletShowsCodeAndReportsCopy() {
        TileManager(context).saveTiles(listOf(TileEntity("a", "clock", "grid", "Часы", 10, 10, 1, 1)))
        val activity = out()
        assertEquals("K7H-2MX-9AB", (activity.view("copy_code") as TextView).text.toString())
        assertNotNull(activity.view("copy_qr"))
        assertTrue(activity.idleBusy)
        assertTrue("экран с кодом не снимается", activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        val host = (activity.view("copy_address") as TextView).text.toString().substringAfter("адрес: ")
        runCatching { CopyClient(host).take("AAAAAAAAA") }
        await("неверный код виден") { activity.texts().contains("Неверный код, осталось попыток: 4") }
        val got = CopyClient(host).take("K7H2MX9AB")
        assertTrue(got.config.contains("Часы"))
        assertEquals(Screen(1280, 800, 1f), got.screen)
        await("скопировано") { activity.texts().contains("Скопировано. Продолжайте на новом планшете") }
        assertNull("код больше не показан", activity.view("copy_code"))
        assertFalse(activity.idleBusy)
    }

    @Test fun oldTabletNeedsHomeNetworkAndClosesPageWithScreen() {
        assertTrue(out(null).texts().contains("не в домашней сети"))
        val activity = out()
        val host = (activity.view("copy_address") as TextView).text.toString().substringAfter("адрес: ")
        controllers.last().pause().stop().destroy()
        controllers.removeAt(controllers.size - 1)
        assertTrue(runCatching { CopyClient(host).hello() }.exceptionOrNull()!!.message!!.contains("не отвечает"))
    }

    // ---- Новый планшет ----

    private fun payload(screen: Screen? = Screen(1920, 1200, 1.5f)) = CopyPayload(JSONObject().put("ha_local_host", "http://192.168.77.10:8123").put("temp_unit", "F")
        .put("tiles_json", Gson().toJson(listOf(TileEntity("a", "clock", "grid", "Часы", 96, 114, 1, 1, config = JSONObject().put("button_size", 480).put("widget_height", 300).toString()),
            TileEntity("c", DoorbellConfig.CAMERA_TYPE, "grid", "Двор", 960, 114, 1, 1, page = 1)))).toString(), screen, "samsung SM-X216B", "0.9.63")

    private fun inside(fetch: (String, String) -> CopyPayload = { _, _ -> payload() }, intent: Intent = Intent(context, CopyInActivity::class.java)): CopyInActivity {
        val c = Robolectric.buildActivity(CopyInActivity::class.java, intent)
        c.get().newBrowser = { object : HaBrowser { override fun start(onService: (HaService) -> Unit) { service = onService }; override fun stop() { service = null } } }
        c.get().fetch = fetch
        c.get().greet = { host -> if (host in silent) throw CopyClient.Failed("Старый планшет не отвечает") else "samsung SM-X216B" }
        c.get().screen = { Screen(1280, 800, 1f) }
        return show(c)
    }

    @Test fun newTabletFindsOldAsksCodeAndFitsTiles() {
        val asked = mutableListOf<Pair<String, String>>()
        val activity = inside({ host, code -> asked += host to code; if (code != "K7H2MX9AB") throw CopyClient.Failed("Код не подошёл, осталось попыток: 4", 4); payload() })
        activity.view("copy_start")!!.performClick(); idle()
        assertTrue(activity.texts().contains("девять знаков"))
        activity.input("copy_code").setText("A7H-2MX-9AB")
        activity.view("copy_start")!!.performClick(); idle()
        assertTrue(activity.texts().contains("не нашёлся — введите адрес"))
        silent += "192.168.77.20:39999" // старая запись mDNS: экран с кодом уже закрыт
        service!!(HaService("samsung SM-X216B", "192.168.77.20", 40123, emptyMap())); service!!(HaService("чужой", "8.8.8.8", 1, emptyMap()))
        await("найден") { activity.texts().contains("samsung SM-X216B") }
        service!!(HaService("samsung SM-X216B", "192.168.77.20", 39999, emptyMap())); Thread.sleep(200); idle()
        assertFalse("не домашний адрес не показан", activity.texts().contains("чужой"))
        activity.view("copy_start")!!.performClick()
        await("не подошёл") { activity.texts().contains("осталось попыток: 4") }
        activity.input("copy_code").setText("к7н 2мх 9ав")
        activity.view("copy_start")!!.performClick()
        await("получено") { activity.view("copy_apply") != null }
        assertEquals(listOf("192.168.77.20:40123" to "A7H2MX9AB", "192.168.77.20:40123" to "K7H2MX9AB"), asked)
        assertTrue(activity.texts().contains("Плиток: 2, страниц: 2, с видео: 1"))
        assertTrue(activity.texts().contains("На старом — 1920×1200, здесь — 1280×800"))
        assertTrue("ещё ничего не изменено", TileManager(context).loadTiles().isEmpty() && !prefs.contains("temp_unit"))
        activity.apply()
        assertTrue(activity.isFinishing)
        val tiles = TileManager(context).loadTiles()
        val s = TileScale.landscape(Screen(1920, 1200, 1.5f), Screen(1280, 800, 1f))
        assertEquals((480 * s).toInt(), JSONObject(tiles.first().config).getInt("button_size"))
        assertEquals("F", prefs.getString("temp_unit", ""))
        assertFalse("адрес применяется только после входа", prefs.contains("ha_local_host"))
        assertEquals("http://192.168.77.10:8123", shadowOf(activity).resultIntent.getStringExtra(CopyInActivity.EXTRA_LOCAL))
    }

    @Test fun sizesCanStayAndOwnTilesAreWarnedAbout() {
        TileManager(context).saveTiles(listOf(TileEntity("mine", "clock", "grid", "Моя", 0, 0, 1, 1)))
        val activity = inside()
        activity.input("copy_address").setText("192.168.77.20:40123")
        activity.input("copy_code").setText("K7H2MX9AB")
        activity.view("copy_start")!!.performClick()
        await("получено") { activity.view("copy_apply") != null }
        assertTrue(activity.texts().contains("Плитки этого планшета (1) будут заменены"))
        (activity.view("copy_fit") as CompoundButton).isChecked = false
        activity.apply()
        assertEquals(480, JSONObject(TileManager(context).loadTiles().first().config).getInt("button_size"))
        // Экран такой же или старая версия без размера — предлагать нечего.
        val same = inside({ _, _ -> payload(null) })
        same.input("copy_address").setText("192.168.77.20:40123"); same.input("copy_code").setText("K7H2MX9AB")
        same.view("copy_start")!!.performClick()
        await("получено") { same.view("copy_apply") != null }
        assertNull(same.view("copy_fit"))
    }

    @Test fun onlyHomeAddressesAndBrokenSettingsAreRefused() {
        var asked = 0
        val activity = inside({ _, _ -> asked++; CopyPayload("[]", null, "x", "x") })
        activity.input("copy_code").setText("K7H2MX9AB")
        for (bad in listOf("8.8.8.8:80", "example.com:80", "192.168.77.20", "192.168.77.20:80/x")) {
            activity.input("copy_address").setText(bad)
            activity.view("copy_start")!!.performClick(); idle()
            assertTrue(bad, activity.texts().contains("Нужен адрес в домашней сети"))
        }
        assertEquals(0, asked)
        activity.input("copy_address").setText("http://192.168.77.20:40123/")
        activity.view("copy_start")!!.performClick()
        await("битое") { activity.texts().contains("повреждёнными") }
        assertNull(activity.view("copy_apply"))
    }

    // ---- Ссылка из QR ----

    private fun link(text: String) = Intent(Intent.ACTION_VIEW, Uri.parse(text))

    @Test fun linkOnlyFillsFieldsOnUnconfiguredPanel() {
        assertEquals("192.168.77.20:40123" to "K7H2MX9AB", CopyInActivity.link(link("smarthomedashboard://copy/192.168.77.20:40123/K7H2MX9AB")))
        for (bad in listOf("smarthomedashboard://copy/8.8.8.8:80/K7H2MX9AB", "smarthomedashboard://other/192.168.77.20:1/K7H2MX9AB", "https://copy/192.168.77.20:1/K7H2MX9AB",
            "smarthomedashboard://copy/192.168.77.20:1/SHORT", "smarthomedashboard://copy/host:1/K7H2MX9AB", "smarthomedashboard://copy/192.168.77.20:1",
            "smarthomedashboard://copy/%zz/K7H2MX9AB")) {
            assertNull(bad, CopyInActivity.link(link(bad)))
        }
        var asked = 0
        val activity = inside({ _, _ -> asked++; payload() }, link("smarthomedashboard://copy/192.168.77.20:40123/K7H2MX9AB"))
        assertEquals("192.168.77.20:40123", activity.input("copy_address").text.toString())
        assertEquals("K7H-2MX-9AB", activity.input("copy_code").text.toString())
        assertEquals("сама не копирует", 0, asked)
        activity.view("copy_start")!!.performClick()
        await("получено") { activity.view("copy_apply") != null }
        activity.apply()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals("мастер продолжает со входа", SetupActivity::class.java.name, next.component!!.className)
        assertTrue(next.getBooleanExtra(CopyInActivity.EXTRA_COPIED, false))
        // Настроенная панель: ссылка ничего не открывает.
        prefs.edit().putBoolean("setup_completed", true).commit()
        val refused = inside(intent = link("smarthomedashboard://copy/192.168.77.20:40123/K7H2MX9AB"))
        assertTrue(refused.isFinishing)
        assertNull(refused.view("copy_start"))
    }

    @Test fun wizardContinuesWithLoginAfterCopy() {
        val c = Robolectric.buildActivity(SetupActivity::class.java, Intent(context, SetupActivity::class.java).putExtra(CopyInActivity.EXTRA_COPIED, true)
            .putExtra(CopyInActivity.EXTRA_LOCAL, "http://192.168.77.10:8123").putExtra(CopyInActivity.EXTRA_REMOTE, "https://home.example.com"))
        c.get().newDiscovery = { ctx, change -> HaDiscovery(ctx, change, object : HaBrowser { override fun start(onService: (HaService) -> Unit) {}; override fun stop() {} }, names = emptyList(), network = { null }) }
        val activity = c.setup().get()
        controllers += c
        idle()
        assertEquals(SetupActivity.Step.SERVER, activity.step)
        assertTrue(activity.texts().contains("Плитки и настройки восстановлены"))
        assertEquals("http://192.168.77.10:8123", activity.input("setup_address").text.toString())
        assertEquals("https://home.example.com", activity.input("setup_external").text.toString())
    }
}
