package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Экран входа в Home Assistant: с телефона, на планшете, токеном (docs/setup-tz.md, часть 1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
class HaLoginScreenTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val access by lazy { haTokenTestAccess(context) }
    private val ha = MockWebServer()
    private val auths = CopyOnWriteArrayList<String>()
    private var controller: ActivityController<HaLoginActivity>? = null
    private val grants = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        ha.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.optString("type") == "auth") {
                        auths += json.getString("access_token")
                        webSocket.send(if (json.getString("access_token") == "GOOD") """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                    }
                }
            })
        }
        ha.start()
    }

    @After fun tearDown() { controller?.let { runCatching { it.pause().stop().destroy() } }; runCatching { ha.shutdown() } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }
    private fun stored() = Executors.newSingleThreadExecutor().let { p -> try { p.submit<HaTokenAccess.Result> { access.read() }.get(5, TimeUnit.SECONDS) } finally { p.shutdownNow() } }

    private fun open(external: String = "", address: InetAddress? = InetAddress.getByName("127.0.0.1"),
                     granted: (String) -> HaLogin.Granted = { HaLogin.Granted("GOOD", "Мой дом", external, false) }): HaLoginActivity {
        val origin = HaServer.origin(ha.url("/").toString())
        val c = Robolectric.buildActivity(HaLoginActivity::class.java,
            HaLoginActivity.intent(context, HaConnector.Target(origin, "", "uuid-1"), ""))
        controller = c
        val activity = c.get()
        activity.homeAddress = { address }
        activity.grant = { _, code, name -> grants += "$code|$name"; granted(code) }
        ReflectionHelpers.setField(activity, "haTokens", access)
        // PIN на чистой панели выключен.
        ReflectionHelpers.setField(activity, "pinGateAccess", PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 1L; override fun boot() = "b" }, worker = {}))
        c.create().start().resume().visible()
        await("доступ без PIN") { ReflectionHelpers.getField<Boolean>(activity, "pinAuthorized") }
        return activity
    }

    private fun HaLoginActivity.view(tag: String): View? = window.decorView.findViewWithTag(tag)
    private fun HaLoginActivity.choose(mode: String) { view("login_mode_$mode")!!.performClick(); idle() }
    private fun HaLoginActivity.texts(): String {
        val out = StringBuilder()
        fun walk(v: View) { if (v is TextView) out.append(v.text).append('\n'); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(window.decorView)
        return out.toString()
    }
    private fun get(url: String): Int {
        val c = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 2_000; c.readTimeout = 5_000
        return try { c.responseCode } finally { c.disconnect() }
    }

    @Test fun phoneLoginSavesAccessAndAddresses() {
        val activity = open(external = "https://home.example.com")
        val link = activity.phoneLink!!
        assertNotNull("QR показан", activity.view("login_qr"))
        assertTrue(activity.idleBusy)
        val state = ReflectionHelpers.getField<PhoneLogin>(activity, "phone").attempt.state
        assertEquals(302, get(link))
        assertEquals(200, get("$link/done?code=CODE&state=$state"))
        await("вход закончен") { activity.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("Мой дом", shadowOf(activity).resultIntent.getStringExtra(HaLoginActivity.EXTRA_NAME))
        assertEquals("GOOD", stored().value)
        assertEquals(listOf("GOOD"), auths.toList())
        assertEquals(HaServer.origin(ha.url("/").toString()), prefs.getString("ha_local_host", null))
        assertEquals("https://home.example.com", prefs.getString("ha_remote_host", null))
        assertEquals("uuid-1", prefs.getString(HaConnector.KEY_UUID, null))
        assertTrue(prefs.getBoolean("setup_completed", false))
        assertTrue(grants.single(), grants.single().startsWith("CODE|Панель — "))
        assertFalse("открытого токена нет", prefs.all.values.any { it == "GOOD" })
        // Страница для телефона закрыта.
        assertTrue(runCatching { get(link) }.isFailure)
    }

    @Test fun tabletPageReturnIsInterceptedAndForeignReturnIsRefused() {
        val activity = open()
        val link = activity.phoneLink!!
        activity.choose("tablet")
        assertTrue("страница телефона закрыта", runCatching { get(link) }.isFailure)
        val web = activity.view("login_web") as WebView
        assertTrue(web.settings.javaScriptEnabled)
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        val attempt = ReflectionHelpers.getField<HaLogin.Attempt>(activity, "webAttempt")
        await("страница входа открыта") { shadowOf(web).lastLoadedUrl == attempt.url }
        // Чужое состояние — вход начинается заново.
        activity.returned(attempt, "${attempt.redirect}&code=A&state=x")
        idle()
        assertTrue(activity.texts().contains("Ответ входа не подошёл"))
        assertTrue(grants.isEmpty())
        val next = ReflectionHelpers.getField<HaLogin.Attempt>(activity, "webAttempt")
        assertNotEquals(attempt.state, next.state)
        activity.returned(attempt, "${attempt.redirect}&code=A&state=${attempt.state}")
        assertTrue("прежняя попытка больше не годится", grants.isEmpty())
        activity.returned(next, "${next.redirect}&code=B&state=${next.state}")
        await("вход закончен") { activity.isFinishing }
        assertEquals("GOOD", stored().value)
        assertNull("страница убрана", activity.view("login_web"))
    }

    @Test fun refusedAccessChangesNothingAndCanBeRetried() {
        var fail = true
        val activity = open(granted = { if (fail) throw HaLogin.LoginException("Этому пользователю Home Assistant вход не разрешён") else HaLogin.Granted("BAD", "", "", true) })
        fun login() {
            val page = ReflectionHelpers.getField<PhoneLogin>(activity, "phone")
            get("${page.link}/done?code=CODE&state=${page.attempt.state}")
        }
        val first = activity.phoneLink
        login()
        await("отказ показан") { activity.texts().contains("вход не разрешён") }
        await("новый QR") { activity.phoneLink != null && activity.phoneLink != first }
        assertFalse(activity.isFinishing)
        assertFalse(prefs.getBoolean("setup_completed", false))
        fail = false
        // Доступ выдан, но подключиться с ним не вышло — прежнее не тронуто.
        login()
        await("подключение не прошло") { activity.texts().contains("токен") }
        assertEquals(HaTokenAccess.Status.ABSENT, stored().status)
        assertFalse(prefs.contains("ha_local_host"))
    }

    @Test fun tokenIsTheFallbackAndIsNeverKeptInView() {
        val activity = open()
        activity.choose("token")
        val input = activity.view("login_token") as EditText
        assertFalse(input.isSaveEnabled)
        activity.view("login_token_go")!!.performClick()
        assertTrue(activity.texts().contains("Вставьте токен"))
        input.setText("BAD")
        activity.view("login_token_go")!!.performClick()
        await("отказ") { activity.texts().contains("токен", ignoreCase = true) && auths.contains("BAD") && !activity.isFinishing && !ReflectionHelpers.getField<Boolean>(activity, "working") }
        assertEquals(HaTokenAccess.Status.ABSENT, stored().status)
        input.setText("GOOD")
        activity.view("login_token_go")!!.performClick()
        await("вход закончен") { activity.isFinishing }
        assertEquals("", input.text.toString())
        assertEquals("GOOD", stored().value)
    }

    @Test fun withoutHomeNetworkPhoneLoginExplainsItself() {
        val activity = open(address = null)
        assertNull(activity.phoneLink)
        assertTrue(activity.texts().contains("не в домашней сети"))
        assertTrue("вернуться на панель нельзя, пока её нет", activity.idleBusy)
        prefs.edit().putBoolean("setup_completed", true).commit()
        assertFalse(activity.idleBusy)
    }

    @Test fun rotationKeepsTheSameQr() {
        val activity = open()
        val link = activity.phoneLink
        activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        })
        idle()
        assertEquals(link, activity.phoneLink)
        assertEquals(302, get(link!!))
    }
}
