package com.example.smarthomedashboard.deep

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarthomedashboard.CopyInActivity
import com.example.smarthomedashboard.CopyOutActivity
import com.example.smarthomedashboard.HaScan
import com.example.smarthomedashboard.SetupActivity
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Копирование на второй планшет (docs/setup-tz.md, часть 5) на эмуляторе: старый экран с кодом и новый — на одном устройстве,
 * через настоящую сеть Android. Данные приложения на эмуляторе стираются.
 */
@RunWith(AndroidJUnit4::class)
class CopyDeepTest {
    private val prefs get() = appContext.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
    private val tiles get() = TileManager(appContext)

    @Before fun clean() {
        check(isEmulator()) { "Стираются данные приложения — только на эмуляторе" }
        assumeTrue("нужна сеть эмулятора", HaScan.lan(appContext) != null)
        prefs.edit().clear().commit()
        appContext.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private inline fun <reified T : Activity> screen(): T {
        var found: T? = null
        waitFor(20_000, T::class.java.simpleName) { (resumedActivity() as? T)?.also { found = it } != null }
        return found!!
    }
    private fun click(activity: Activity, tag: String) {
        waitFor(20_000, tag) { onMain { activity.window.decorView.findViewWithTag<View>(tag)?.let { it.isShown && it.isEnabled } == true } }
        onMain { activity.window.decorView.findViewWithTag<View>(tag).performClick() }
    }
    private fun texts(v: View): String = when (v) {
        is TextView -> v.text.toString() + "\n"
        is android.view.ViewGroup -> (0 until v.childCount).joinToString("") { texts(v.getChildAt(it)) }
        else -> ""
    }
    private fun shown(activity: Activity, text: String) = onMain { texts(activity.window.decorView).contains(text) }
    private fun input(activity: Activity, tag: String, value: String) = onMain { activity.window.decorView.findViewWithTag<EditText>(tag).setText(value) }

    /** Старый планшет: экран с кодом; настройки сняты — дальше это «новый» планшет. */
    private fun old(): Pair<String, String> {
        tiles.saveTiles(listOf(TileEntity("a", "clock", "grid", "Часы старого", 40, 40, 1, 1,
            config = JSONObject().put("button_size", 400).put("widget_height", 200).toString()), TileEntity("b", "button", "bottom_panel", "Свет", 40, 600, 1, 1, page = 1)))
        prefs.edit().putString("ha_local_host", "http://10.0.2.2:8123").putString("temp_unit", "F").commit()
        appContext.startActivity(Intent(appContext, CopyOutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val out = screen<CopyOutActivity>()
        waitFor(20_000, "код") { onMain { out.window.decorView.findViewWithTag<View>("copy_code") } != null }
        val code = onMain { out.window.decorView.findViewWithTag<TextView>("copy_code").text.toString() }
        val host = onMain { out.window.decorView.findViewWithTag<TextView>("copy_address").text.toString() }.substringAfter("адрес: ")
        prefs.edit().clear().commit()
        tiles.saveTiles(emptyList())
        return code to host
    }

    private fun checkCopied() {
        val setup = screen<SetupActivity>()
        waitFor(20_000, "вход") { shown(setup, "Плитки и настройки восстановлены") }
        val copied = tiles.loadTiles()
        assertEquals(listOf("Часы старого", "Свет"), copied.map { it.title })
        assertEquals("тот же экран — размеры те же", 400, JSONObject(copied.first().config).getInt("button_size"))
        assertEquals(1, copied.last().page)
        assertEquals("F", prefs.getString("temp_unit", ""))
        assertFalse("адрес — только после входа", prefs.contains("ha_local_host"))
        assertEquals("http://10.0.2.2:8123", onMain { setup.window.decorView.findViewWithTag<EditText>("setup_address").text.toString() })
    }

    @Test fun newTabletCopiesByCode() {
        val (code, host) = old()
        appContext.startActivity(Intent(appContext, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val wizard = screen<SetupActivity>()
        click(wizard, "setup_copy")
        val copy = screen<CopyInActivity>()
        // Нашёлся ли по mDNS — зависит от сети эмулятора: только в журнал.
        val end = System.currentTimeMillis() + 10_000
        while (!shown(copy, android.os.Build.MODEL) && System.currentTimeMillis() < end) Thread.sleep(200)
        log("Старый планшет по mDNS: ${shown(copy, android.os.Build.MODEL)}")
        if (!shown(copy, android.os.Build.MODEL)) input(copy, "copy_address", host)
        input(copy, "copy_code", "AAA-AAA-AAA")
        click(copy, "copy_start")
        waitFor(30_000, "неверный код") { shown(copy, "Код не подошёл, осталось попыток: 4") }
        val started = System.currentTimeMillis()
        input(copy, "copy_code", code)
        click(copy, "copy_start")
        waitFor(30_000, "получено") { shown(copy, "Плиток: 2, страниц: 2") }
        log("Копирование: ${System.currentTimeMillis() - started} мс")
        assertTrue("до «Применить» ничего не меняется", tiles.loadTiles().isEmpty())
        click(copy, "copy_apply")
        checkCopied()
    }

    /** Страница из QR в настоящем браузерном движке: код из «#» попадает в ссылку панели, в сеть — нет. */
    @Test fun qrPageBuildsPanelLink() {
        val (code, host) = old()
        val plain = code.replace("-", "")
        val done = java.util.concurrent.CountDownLatch(1)
        var href = ""
        // Ссылка держится до конца: без неё сборщик мусора закрывает браузер посреди загрузки (так упало на CI).
        val web = onMain {
            android.webkit.WebView(appContext).apply {
                @android.annotation.SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript("document.getElementById('a').href") { href = it.orEmpty(); done.countDown() }
                    }
                }
                loadUrl("http://$host/#$plain")
            }
        }
        val opened = done.await(40, java.util.concurrent.TimeUnit.SECONDS)
        onMain { web.destroy() }
        assertTrue("страница открылась", opened)
        assertEquals("\"intent://copy/$host/$plain#Intent;scheme=smarthomedashboard;package=com.example.smarthomedashboard;end\"", href)
    }

    @Test fun qrLinkOpensCopyWithoutCopyingByItself() {
        val (code, host) = old()
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("smarthomedashboard://copy/$host/${code.replace("-", "")}"))
            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val copy = screen<CopyInActivity>()
        waitFor(10_000, "заполнено") { onMain { copy.window.decorView.findViewWithTag<EditText>("copy_code")?.text?.toString() } == code }
        assertEquals(host, onMain { copy.window.decorView.findViewWithTag<EditText>("copy_address").text.toString() })
        assertTrue(tiles.loadTiles().isEmpty())
        click(copy, "copy_start")
        waitFor(30_000, "получено") { shown(copy, "Плиток: 2") }
        click(copy, "copy_apply")
        checkCopied()
    }
}
