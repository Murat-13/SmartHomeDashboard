package com.example.smarthomedashboard

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.lang.reflect.InvocationTargetException

// Пять разделов, немедленное применение безопасных параметров и защита адреса HA до проверки.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsSectionsTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("dashboard_prefs", 0)
    private val activity by lazy {
        prefs.edit().clear().putString("ha_local_host", "http://previous.example.invalid")
            .putInt("screen_timeout_minutes", 5).putInt("pin_session_minutes", 60).commit()
        Robolectric.buildActivity(SettingsActivity::class.java).create().get().also {
            allowSettingsTestAccess(it)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun nav() = activity.findViewById<LinearLayout>(R.id.settingsNav)
    private fun visible(id: Int) = activity.findViewById<View>(id).visibility == View.VISIBLE
    private fun invoke(name: String) = try {
        SettingsActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(activity)
    } catch (e: InvocationTargetException) { throw e.targetException }

    @Test fun navigationShowsOneSectionInAgreedOrder() {
        assertEquals(listOf("Соединения", "Экран", "Безопасность", "Устройство", "О приложении"),
            (0 until nav().childCount).map { (nav().getChildAt(it) as TextView).text.toString() })
        assertTrue(visible(R.id.sectionConnections))
        assertTrue(nav().getChildAt(0).isSelected)
        nav().getChildAt(3).performClick()
        assertTrue(visible(R.id.sectionDevice))
        listOf(R.id.sectionConnections, R.id.sectionScreen, R.id.sectionSecurity, R.id.sectionAbout).forEach { assertFalse(visible(it)) }
        assertTrue(nav().getChildAt(3).isSelected)
        assertFalse(nav().getChildAt(0).isSelected)
    }

    @Test fun safeSettingsApplyImmediatelyAndInvalidValuesAreNotWritten() {
        activity.findViewById<EditText>(R.id.etScreenTimeout).setText("7")
        assertEquals(7, prefs.getInt("screen_timeout_minutes", 0))
        activity.findViewById<EditText>(R.id.etScreenTimeout).setText("0")
        assertEquals(7, prefs.getInt("screen_timeout_minutes", 0))
        activity.findViewById<EditText>(R.id.etPinSessionMinutes).setText("0")
        assertEquals(60, prefs.getInt("pin_session_minutes", 0))
        activity.findViewById<EditText>(R.id.etPinSessionMinutes).setText("1001")
        assertEquals(60, prefs.getInt("pin_session_minutes", 0))
        activity.findViewById<EditText>(R.id.etPinSessionMinutes).setText("1")
        assertEquals(1, prefs.getInt("pin_session_minutes", 0))
        activity.findViewById<EditText>(R.id.etPinSessionMinutes).setText("45")
        assertEquals(45, prefs.getInt("pin_session_minutes", 0))
        activity.findViewById<CompoundButton>(R.id.rbFahrenheit).isChecked = true
        assertEquals("F", prefs.getString("temp_unit", null))
        val haptics = activity.findViewById<CompoundButton>(R.id.cbModernHaptics)
        haptics.isChecked = !haptics.isChecked
        assertEquals(haptics.isChecked, prefs.getBoolean(ModernHaptics.PREF, !haptics.isChecked))
        val motion = activity.findViewById<CompoundButton>(R.id.cbMotionEnabled)
        assertTrue(motion.isChecked) // по умолчанию — как раньше
        motion.isChecked = false
        assertFalse(prefs.getBoolean(MainActivity.PREF_MOTION_ENABLED, true))
        // Адрес HA так не применяется: только через проверку.
        activity.findViewById<EditText>(R.id.etHaLocalHost).setText("http://other.example.invalid")
        assertEquals("http://previous.example.invalid", prefs.getString("ha_local_host", null))
    }

    @Test fun changedAddressIsNotSavedBeforeSuccessfulProbe() {
        // Чтение токена ждёт сигнала теста: раньше быстрый отказ чтения мог завершить проверку синхронно
        // внутри saveConnections (withContext(IO) без приостановки), и кнопка уже снова была включена.
        val tokenRead = java.util.concurrent.CountDownLatch(1)
        org.robolectric.util.ReflectionHelpers.setField(activity, "haTokens", haTokenTestAccess(activity, tokenRead))
        activity.findViewById<EditText>(R.id.etHaLocalHost).setText("http://new.example.invalid")
        invoke("saveConnections")
        assertEquals("http://previous.example.invalid", prefs.getString("ha_local_host", null))
        assertFalse(activity.findViewById<View>(R.id.btnSave).isEnabled)
        invoke("cancelProbe")
        tokenRead.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("http://previous.example.invalid", prefs.getString("ha_local_host", null))
        assertFalse(activity.isFinishing)
    }

    @Test fun insecureRemoteNeedsExplicitConsentAndLocalHttpStaysQuiet() {
        val consent = activity.findViewById<CompoundButton>(R.id.cbAllowInsecureRemote)
        val warning = activity.findViewById<TextView>(R.id.tvHaTransportWarning)
        activity.findViewById<EditText>(R.id.etHaLocalHost).setText("http://192.168.1.20:8123")
        activity.findViewById<EditText>(R.id.etHaRemoteHost).setText("")
        assertFalse("Домашний http — без предупреждения", warning.visibility == View.VISIBLE)
        assertFalse(consent.visibility == View.VISIBLE)

        activity.findViewById<EditText>(R.id.etHaRemoteHost).setText("http://203.0.113.7:8123")
        assertTrue(warning.visibility == View.VISIBLE)
        assertTrue(consent.visibility == View.VISIBLE)
        consent.performClick()
        assertFalse("До подтверждения разрешение не включено", consent.isChecked)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(consent.isChecked)

        consent.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(consent.isChecked)
        // Разрешение — часть данных подключения: сохраняется только через проверку подключения.
        assertFalse(prefs.getBoolean(HomeAssistantAddress.PREF_ALLOW_INSECURE_REMOTE, false))
        assertTrue(invoke("haChanged") as Boolean)
    }

    @Test fun leavingAsksOnlyWhenConnectionDataIsUnsaved() {
        activity.findViewById<EditText>(R.id.etHaToken).setText("FAKE-UNSAVED")
        invoke("leave")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("Продолжить редактирование", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
        assertFalse(activity.isFinishing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
    }

    @Test fun aboutShowsVersionChannelAndUpdateStatusWithoutRealGithub() {
        val original = AppUpdater.depsFactory
        // Адрес не HTTPS GitHub: политика загрузчика отказывает до сети, настоящий GitHub не вызывается.
        AppUpdater.depsFactory = { ctx ->
            AppUpdater.Deps(UpdateHttp(okhttp3.OkHttpClient(), "http://127.0.0.1:9/releases"), PackageApkCheck(ctx), File(ctx.cacheDir, "u"))
        }
        try {
            nav().getChildAt(4).performClick()
            assertTrue(visible(R.id.sectionAbout))
            // Номер версии меняется с каждым выпуском (формула — AppVersionTest); здесь — что на экране именно она.
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            assertEquals("Версия ${info.versionName} · сборка ${info.longVersionCode}",
                activity.findViewById<TextView>(R.id.tvAppVersion).text.toString())
            assertTrue(activity.findViewById<CompoundButton>(R.id.rbUpdateStable).isChecked)
            val status = activity.findViewById<TextView>(R.id.tvUpdateStatus)
            assertEquals("Канал: стабильный · Обновления ещё не проверялись", status.text.toString())
            activity.findViewById<CompoundButton>(R.id.rbUpdateTest).isChecked = true
            assertEquals("test", prefs.getString(AppUpdater.PREF_CHANNEL, null))
            val deadline = System.currentTimeMillis() + 10_000
            while (!status.text.contains("нет связи с GitHub") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(status.text.toString(), status.text.startsWith("Канал: тестовый · Обновления: нет связи с GitHub"))
            assertTrue(activity.findViewById<View>(R.id.btnCheckUpdates).isEnabled)
        } finally {
            AppUpdater.depsFactory = original
        }
    }

    @Test fun cleanFormLeavesWithoutQuestion() {
        invoke("leave")
        assertTrue(activity.isFinishing)
    }

    @Test fun rendersSectionsLandscape() = render("landscape", 1280, 752)

    @Config(qualifiers = "ru-rRU-w800dp-h1280dp-port-mdpi")
    @Test fun rendersSectionsPortrait() = render("portrait", 800, 1232)

    private fun render(name: String, width: Int, height: Int) {
        for (index in listOf(0, 1, 3, 4)) {
            nav().getChildAt(index).performClick()
            val root = activity.findViewById<View>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/reports/settings-sections/$name-$index.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
