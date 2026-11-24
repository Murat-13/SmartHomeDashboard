package com.example.smarthomedashboard.deep

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.ConnectionStatus
import com.example.smarthomedashboard.HaLoginActivity
import com.example.smarthomedashboard.HaTokenAccess
import com.example.smarthomedashboard.MainActivity
import com.example.smarthomedashboard.MainViewModel
import com.example.smarthomedashboard.SetupActivity
import com.example.smarthomedashboard.SplashActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Первая настройка на эмуляторе с настоящим Home Assistant стенда (tools/ha-stand): haStand=http://10.0.2.2:8124,
 * haUser, haPass. Без них пропускается (на CI стенда нет). Данные приложения на эмуляторе стираются.
 */
@RunWith(AndroidJUnit4::class)
class SetupDeepTest {
    private val stand = argument("haStand").orEmpty()
    private val user = argument("haUser").orEmpty()
    private val pass = argument("haPass").orEmpty()
    private val prefs get() = appContext.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    @Before fun clean() {
        assumeTrue("нужен стенд Home Assistant", stand.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty())
        check(isEmulator()) { "Стираются данные приложения — только на эмуляторе" }
        prefs.edit().clear().commit()
        appContext.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private inline fun <reified T : Activity> screen(): T {
        var found: T? = null
        waitFor(20_000, T::class.java.simpleName) { (resumedActivity() as? T)?.also { found = it } != null }
        return found!!
    }
    private fun view(activity: Activity, tag: String): View? = onMain { activity.window.decorView.findViewWithTag(tag) }
    private fun click(activity: Activity, tag: String) {
        waitFor(10_000, tag) { onMain { activity.window.decorView.findViewWithTag<View>(tag)?.isShown == true } }
        onMain { activity.window.decorView.findViewWithTag<View>(tag).performClick() }
    }
    private fun js(web: WebView, code: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        onMain { web.evaluateJavascript(code) { out = it.orEmpty(); latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    private fun openLogin(): HaLoginActivity {
        appContext.startActivity(Intent(appContext, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val setup = screen<SetupActivity>()
        click(setup, "setup_new")
        waitFor(10_000, "поле адреса") { view(setup, "setup_address") != null }
        onMain { setup.window.decorView.findViewWithTag<EditText>("setup_address").setText(stand) }
        click(setup, "setup_next")
        return screen<HaLoginActivity>()
    }

    private fun finishWizardAndCheckPanel() {
        val setup = screen<SetupActivity>()
        waitFor(20_000, "шаг «Защита»") { onMain { setup.window.decorView.findViewWithTag<View>("setup_pin") } != null }
        assertEquals(stand, prefs.getString("ha_local_host", null))
        assertTrue(prefs.getBoolean("setup_completed", false))
        val token = java.util.concurrent.Executors.newSingleThreadExecutor().let { p ->
            try { p.submit<HaTokenAccess.Result> { HaTokenAccess.create(appContext).read() }.get(10, TimeUnit.SECONDS) } finally { p.shutdownNow() }
        }
        assertEquals(HaTokenAccess.Status.READY, token.status)
        assertTrue("постоянный доступ", token.value!!.length > 100)
        click(setup, "setup_next")
        click(setup, "setup_finish")
        val panel = screen<MainActivity>()
        // Строка состояния показывает и ошибки операций (на стенде нет weather.home) — смотрим само соединение.
        val model = onMain { ViewModelProvider(panel)[MainViewModel::class.java] }
        waitFor(30_000, "панель подключена") { model.uiState.value.connectionStatus == ConnectionStatus.Connected }
        // Повторный запуск — сразу панель, мастера нет.
        appContext.startActivity(Intent(appContext, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        screen<MainActivity>()
    }

    /** Настоящая страница входа Home Assistant в WebView: логин и пароль вводятся в её поля. */
    @Test fun loginOnTabletPage() {
        val login = openLogin()
        click(login, "login_mode_tablet")
        waitFor(20_000, "страница входа") { view(login, "login_web") != null }
        val web = view(login, "login_web") as WebView
        // Поля страницы — внутри теневых деревьев компонентов Home Assistant.
        val find = "function all(root, out){root.querySelectorAll('*').forEach(function(e){out.push(e); if(e.shadowRoot) all(e.shadowRoot,out);}); return out;}" +
            "var els=all(document,[]);var fields=els.filter(function(e){return e.tagName==='INPUT'&&e.offsetParent!==null&&e.type!=='checkbox';});"
        waitFor(60_000, "поля логина и пароля") { js(web, "(function(){${find}return fields.length;})()").toIntOrNull()?.let { it >= 2 } == true }
        val fill = "(function(){$find" +
            "function put(e,v){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(e,v);" +
            "e.dispatchEvent(new Event('input',{bubbles:true,composed:true}));e.dispatchEvent(new Event('change',{bubbles:true,composed:true}));}" +
            "put(fields[0],${JSONObject.quote(user)});put(fields[1],${JSONObject.quote(pass)});" +
            "var b=els.filter(function(e){return /BUTTON/.test(e.tagName)&&e.offsetParent!==null&&/log|вход|войти/i.test(e.textContent||e.innerText||'');});" +
            "if(!b.length) b=els.filter(function(e){return e.tagName==='HA-BUTTON'||e.tagName==='MWC-BUTTON';});" +
            "if(b.length){b[b.length-1].click();} return b.length;})()"
        waitFor(30_000, "кнопка входа") { (js(web, fill).toIntOrNull() ?: 0) > 0 }
        finishWizardAndCheckPanel()
    }

    /** Вход с телефона: «телефон» — этот же тест: открывает ссылку QR и проходит вход вызовами страницы. */
    @Test fun loginFromPhoneByQr() {
        val login = openLogin()
        waitFor(20_000, "QR") { onMain { phoneLink(login) } != null }
        val link = onMain { phoneLink(login) }!!
        val http = OkHttpClient.Builder().followRedirects(false).build()
        val authorize = http.newCall(Request.Builder().url(link).build()).execute().use { r ->
            assertEquals(302, r.code); r.header("Location")!!
        }
        val url = android.net.Uri.parse(authorize)
        val client = url.getQueryParameter("client_id")!!
        fun post(path: String, body: JSONObject) = http.newCall(Request.Builder().url(stand + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { JSONObject(it.body!!.string()) }
        val flow = post("/auth/login_flow", JSONObject().put("client_id", client).put("redirect_uri", url.getQueryParameter("redirect_uri"))
            .put("handler", JSONArray(listOf("homeassistant", JSONObject.NULL))).put("code_challenge", url.getQueryParameter("code_challenge"))
            .put("code_challenge_method", "S256"))
        val code = post("/auth/login_flow/" + flow.getString("flow_id"), JSONObject().put("client_id", client).put("username", user).put("password", pass))
            .getString("result")
        val back = android.net.Uri.parse(url.getQueryParameter("redirect_uri")).buildUpon().appendQueryParameter("code", code)
            .appendQueryParameter("state", url.getQueryParameter("state")).build().toString()
        http.newCall(Request.Builder().url(back).build()).execute().use { r ->
            assertEquals(200, r.code)
            assertTrue(r.body!!.string().contains("Вернитесь к планшету"))
        }
        finishWizardAndCheckPanel()
    }

    /** Неверный пароль на настоящей странице: панель ничего не сохраняет и остаётся на входе. */
    @Test fun wrongAddressesStayOnStep() {
        appContext.startActivity(Intent(appContext, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val setup = screen<SetupActivity>()
        click(setup, "setup_new")
        for ((address, text) in listOf("10.0.2.2:1" to "Ошибка сети", "http://home.example.com" to "без шифрования")) {
            waitFor(10_000, "поле адреса") { view(setup, "setup_address") != null }
            onMain { setup.window.decorView.findViewWithTag<EditText>("setup_address").setText(address) }
            click(setup, "setup_next")
            waitFor(20_000, text) { onMain { texts(setup.window.decorView).contains(text) } }
            assertTrue(resumedActivity() is SetupActivity)
        }
        assertFalse(prefs.getBoolean("setup_completed", false))
    }

    private fun phoneLink(activity: HaLoginActivity): String? = HaLoginActivity::class.java.getDeclaredField("phone").let { f ->
        f.isAccessible = true
        f.get(activity)?.let { page -> page.javaClass.getDeclaredMethod("getLink").invoke(page) as String }
    }

    private fun texts(v: View): String = when (v) {
        is TextView -> v.text.toString() + "\n"
        is android.view.ViewGroup -> (0 until v.childCount).joinToString("") { texts(v.getChildAt(it)) }
        else -> ""
    }
}
