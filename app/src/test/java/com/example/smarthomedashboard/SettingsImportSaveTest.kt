package com.example.smarthomedashboard

import android.app.AlertDialog
import android.os.Looper
import android.widget.EditText
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/**
 * Сценарий владельца (0.9.2, этап 4): чистая установка → адрес и токен HA → импорт копии → сохранение →
 * выход. Поддельный HA — локальный MockWebServer; настоящий дом не участвует.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsImportSaveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val tilesPrefs = context.getSharedPreferences("tiles_prefs", 0)
    private val servers = mutableListOf<MockWebServer>()
    private val tokens = haTokenTestAccess(context)
    private val unreachable = "http://127.0.0.1:1"

    /** Поддельный HA: auth_required → auth_ok. */
    private class Peer : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (JSONObject(text).optString("type") == "auth") webSocket.send("""{"type":"auth_ok"}""")
        }
    }

    private fun ha(): String = MockWebServer().also { server ->
        servers += server
        repeat(4) { server.enqueue(MockResponse().withWebSocketUpgrade(Peer())) }
        server.start()
    }.url("/").toString()

    @Before fun freshInstall() {
        prefs.edit().clear().commit()
        tilesPrefs.edit().clear().commit()
        java.io.File(context.filesDir, "backups").deleteRecursively()
    }

    @After fun stop() = servers.forEach { runCatching { it.shutdown() } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (!condition() && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        assertTrue(what, condition())
    }

    /** Как после SetupActivity: адрес и токен сохранены, первичная настройка завершена. */
    private fun setUpHa(address: String) {
        prefs.edit().putBoolean("setup_completed", true).putString("ha_local_host", address)
            .putInt("screen_timeout_minutes", 5).putString("temp_unit", "C").commit()
        weatherWorker { tokens.replace("FAKE-TOKEN") }
    }

    private fun settings(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).create().get().also {
        allowSettingsTestAccess(it)
        ReflectionHelpers.setField(it, "haTokens", tokens)
        ReflectionHelpers.setField(it, "weatherKeys", weatherKeyTestAccess(it))
        weatherInvoke(it, "loadWeatherConfiguration")
        await("настройки Яндекса прочитаны") { !ReflectionHelpers.getField<Boolean>(it, "weatherLoading") }
    }

    private fun backup(address: String) = JSONObject()
        .put("ha_local_host", address).put("ha_remote_host", "")
        .put("screen_timeout_minutes", 9).put("temp_unit", "F").put("kiosk_mode", true)
        .put("yandex_lat", "55.5").put("yandex_lon", "37.5")
        .put("tiles_json", JSONArray().put(JSONObject().put("id", "t").put("type", "clock").put("container", "grid")
            .put("title", "Часы").put("x", 0).put("y", 0).put("width", 1).put("height", 1)).toString())
        .toString()

    private fun import(activity: SettingsActivity, text: String) = weatherInvoke(activity, "importText", text)

    private fun latestDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog?

    private fun storedToken() = weatherWorker { tokens.read().value }

    private fun assertImportedSettingsKept() {
        assertEquals("tiles_json восстановлены", true, tilesPrefs.getString("tiles", "")!!.contains("\"t\""))
        assertEquals("импортированные настройки не перезаписаны устаревшей формой", 9, prefs.getInt("screen_timeout_minutes", 0))
        assertEquals("F", prefs.getString("temp_unit", null))
        assertTrue(prefs.getBoolean("kiosk_mode", false))
        assertEquals("55.5", prefs.getString("yandex_lat", null))
        assertEquals("FAKE-TOKEN", storedToken())
    }

    @Test fun importWithSameAddressRestartsAndLaterSaveExitsWithoutLoop() {
        val current = ha()
        setUpHa(current)
        val activity = settings()
        import(activity, backup(current))
        assertTrue("после импорта панель перезапускается", activity.isFinishing)
        assertImportedSettingsKept()

        // Повторный вход: «Сохранить» без изменений выходит сразу и ничего не портит.
        val again = settings()
        again.findViewById<android.view.View>(R.id.btnSave).performClick()
        await("сохранение без изменений завершает экран") { again.isFinishing }
        assertImportedSettingsKept()
        assertEquals(current, prefs.getString("ha_local_host", null))
    }

    @Test fun acceptedImportedAddressIsSavedOnlyAfterProbeAndPanelRestartsWithImportedSettings() {
        setUpHa(ha())
        val fromFile = ha()
        val activity = settings()
        import(activity, backup(fromFile))
        val offer = latestDialog()!!
        assertEquals("В файле другой адрес Home Assistant", shadowOf(offer).title)
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("после проверки адреса панель перезапускается") { activity.isFinishing }
        assertEquals(fromFile, prefs.getString("ha_local_host", null))
        assertImportedSettingsKept()
    }

    @Test fun unreachableImportedAddressKeepsCurrentExplainsAndExitsInsteadOfLooping() {
        val current = ha()
        setUpHa(current)
        val activity = settings()
        import(activity, backup(unreachable))
        latestDialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("пользователь видит причину") { latestDialog()?.let { shadowOf(it).title } == "Адрес из файла не применён" }
        assertEquals("текущий адрес не изменён", current, prefs.getString("ha_local_host", null))
        assertEquals("форма вернулась к сохранённому адресу", current, activity.findViewById<EditText>(R.id.etHaLocalHost).text.toString())
        latestDialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue("импорт завершается перезапуском панели", activity.isFinishing)
        assertImportedSettingsKept()
    }

    @Test fun failedSaveOnLeaveExplainsReasonAndOffersExit() {
        val current = ha()
        setUpHa(current)
        val activity = settings()
        activity.findViewById<EditText>(R.id.etHaLocalHost).setText(unreachable)
        weatherInvoke(activity, "leave")
        val leave = latestDialog()!!
        leave.getButton(AlertDialog.BUTTON_POSITIVE).performClick() // «Сохранить»
        await("причина показана диалогом") { latestDialog()?.let { shadowOf(it).title } == "Не удалось сохранить" }
        val failed = latestDialog()!!
        assertTrue(shadowOf(failed).message.toString().contains("Прежнее подключение не изменено"))
        assertFalse(activity.isFinishing)
        failed.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() // «Выйти без сохранения»
        idle()
        assertTrue(activity.isFinishing)
        assertEquals(current, prefs.getString("ha_local_host", null))
        assertEquals("FAKE-TOKEN", storedToken())
    }

    @Test fun exportDialogTextMatchesCurrentFileContents() {
        setUpHa(ha())
        val activity = settings()
        weatherInvoke(activity, "exportConfig")
        val text = shadowOf(latestDialog()!!).message.toString()
        assertFalse("устаревшая фраза удалена", text.contains("пока не входит"))
        assertTrue(text.contains("адреса Home Assistant"))
        assertTrue(text.contains("токен Home Assistant, ключ Яндекса и PIN"))
    }
}
