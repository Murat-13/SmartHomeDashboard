package com.example.smarthomedashboard

import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MqttSettingsIntegrationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("dashboard_prefs", 0)
    private val access by lazy { mqttTestAccess(app) }
    private fun set(target: Any, name: String, value: Any?) = ReflectionHelpers.setField(target, name, value)
    private fun <T> get(target: Any, name: String): T = ReflectionHelpers.getField(target, name)
    private fun settings(keyAccess: MqttCredentialAccess = access): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).get().also { activity ->
            weatherLive(activity)
            set(activity, "haTokens", haTokenTestAccess(activity))
            set(activity, "mqttCredentials", keyAccess)
            listOf("etHaLocalHost", "etHaRemoteHost", "etHaToken", "etMqttHost", "etMqttPort",
                "etMqttUsername", "etMqttPassword", "etAdminPin", "etPinSessionMinutes", "etScreenTimeout").forEach {
                set(activity, it, EditText(activity))
            }
            set(activity, "tvHaTokenStatus", TextView(activity))
            set(activity, "btnSave", Button(activity))
            set(activity, "rgTempUnit", RadioGroup(activity))
            set(activity, "spinnerTheme", Spinner(activity).apply { adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_item, listOf("system", "light", "dark")) })
            set(activity, "cbKioskMode", CheckBox(activity))
            weatherInvoke(activity, "prepareTokenInput")
            attachMqttSettingsFields(activity, keyAccess)
            attachWeatherSettingsFields(activity)
        }

    @Test fun storedPasswordNeverPrefillsOrEntersViewStateAndBlankSaveKeepsIt() {
        prefs.edit().putString("mqtt_password", "  FAKE-OLD  ").putString("mqtt_host", "")
            .putInt("mqtt_port", 1883).putString("mqtt_username", "  ").commit()
        val activity = settings()
        val input = get<EditText>(activity, "etMqttPassword")
        assertEquals("", input.text.toString())
        assertFalse(input.isSaveEnabled)
        assertFalse(input.isSaveFromParentEnabled)
        assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
        input.id = R.id.etMqttPassword
        input.setText("FAKE-UNSAVED")
        val state = android.util.SparseArray<android.os.Parcelable>()
        input.saveHierarchyState(state)
        assertEquals(0, state.size())
        input.text.clear()
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { activity.isFinishing }
        assertEquals("  FAKE-OLD  ", weatherWorker { access.read() }.value)
        assertEquals("", prefs.getString("mqtt_host", null))
        assertEquals("  ", prefs.getString("mqtt_username", null))
        assertFalse(get<TextView>(activity, "tvMqttPasswordStatus").text.contains("FAKE-"))
    }
    @Test fun emptyPasswordSurvivesBlankInputAndReplacementPreservesWhitespace() {
        prefs.edit().putString("mqtt_password", "").commit()
        val first = settings()
        weatherInvoke(first, "saveCurrentSettings")
        weatherAwait { first.isFinishing }
        assertEquals("", weatherWorker { access.read() }.value)
        val second = settings()
        get<EditText>(second, "etMqttPassword").setText("  FAKE-NEW  ")
        weatherInvoke(second, "saveCurrentSettings")
        weatherAwait { second.isFinishing }
        assertEquals("  FAKE-NEW  ", weatherWorker { access.read() }.value)
        assertEquals("", get<EditText>(second, "etMqttPassword").text.toString())
        val export = weatherInvoke(second, "buildExportConfig") as org.json.JSONObject
        assertFalse(export.has("mqtt_password"))
        assertFalse(export.has("mqtt_password_transfer_phase"))
        assertFalse(export.toString().contains("FAKE-NEW"))
    }
    @Test fun cancelledReplacementDoesNotChangeAnything() {
        val activity = settings()
        val before = prefs.all
        get<EditText>(activity, "etMqttPassword").setText("FAKE-CANCELLED")
        activity.finish()
        assertEquals(before, prefs.all)
        assertEquals("FAKE-DEFAULT-MQTT", weatherWorker { access.read() }.value)
    }
    @Test fun staleSettingsCannotOverwriteFreshPasswordOrConnectionConfiguration() {
        val activity = settings()
        val before = weatherWorker { access.read() }
        weatherWorker { access.save("FAKE-FRESH", MqttCredentialAccess.Configuration("", 1884, " NEW "), before.revision) }
        weatherInvoke(activity, "loadMqttConfiguration")
        weatherAwait { !get<Boolean>(activity, "mqttLoading") }
        get<EditText>(activity, "etMqttPassword").setText("FAKE-STALE")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { get<Button>(activity, "btnSave").isEnabled }
        assertFalse(activity.isFinishing)
        val result = weatherWorker { access.read() }
        assertEquals("FAKE-FRESH", result.value)
        assertEquals(1884, result.configuration!!.port)
        assertEquals(" NEW ", result.configuration.username)
    }
    @Test fun secureFailureCannotFallbackReplaceOrClaimSaveSuccess() {
        prefs.edit().putString("mqtt_password", "FAKE-OLD").commit()
        val bad = MqttCredentialAccess(object : MqttPasswordSlot {
            override fun read() = SecretStore.ReadResult(SecretStore.Status.CORRUPTED)
            override fun write(value: String): SecretStore.Status = error("No recovery write")
        }, PreferenceMqttLegacy({ prefs }, { "mqtt.example.invalid" }, { "FAKE-USER" }, { error("No fallback") }),
            { check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) })
        val activity = settings(bad)
        assertEquals("", get<EditText>(activity, "etMqttPassword").text.toString())
        get<EditText>(activity, "etMqttPassword").setText("FAKE-RETRY")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { get<Button>(activity, "btnSave").isEnabled }
        assertFalse(activity.isFinishing)
        assertEquals("FAKE-OLD", prefs.getString("mqtt_password", null))
        assertFalse(get<TextView>(activity, "tvMqttPasswordStatus").text.contains("FAKE-"))
    }
    @Test fun lateReadCannotUpdatePausedScreen() {
        val activity = settings()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val delayed = MqttCredentialAccess(object : MqttPasswordSlot {
            override fun read(): SecretStore.ReadResult {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                finished.countDown()
                return SecretStore.ReadResult(SecretStore.Status.SUCCESS, "FAKE-LATE")
            }
            override fun write(value: String): SecretStore.Status = error("No write")
        }, PreferenceMqttLegacy({ prefs }, { "mqtt.example.invalid" }, { "FAKE-USER" }, { "FAKE-DEFAULT" })) {}
        set(activity, "mqttCredentials", delayed)
        weatherInvoke(activity, "loadMqttConfiguration")
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        weatherInvoke(activity, "onPause")
        val status = get<TextView>(activity, "tvMqttPasswordStatus").text.toString()
        release.countDown()
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Очередь IO завершаем до проверки main callback, без sleep.
        weatherWorker { delayed.read() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(status, get<TextView>(activity, "tvMqttPasswordStatus").text.toString())
        assertEquals("", get<EditText>(activity, "etMqttPassword").text.toString())
    }
    @Test fun dormantManagerReadsSecureSnapshotWithoutCreatingAClient() {
        val manager = MqttManager(app)
        set(manager, "credentials", access)
        val result = weatherWorker { weatherInvoke(manager, "loadConnectionConfiguration") } as MqttCredentialAccess.Result
        assertEquals("FAKE-DEFAULT-MQTT", result.value)
        assertEquals("mqtt.example.invalid", result.configuration!!.host)
        assertNull(get<Any?>(manager, "mqttClient"))
    }
    @Test fun savingPasswordMustNotWritePlaintextPreferences() {
        val activity = settings()
        get<EditText>(activity, "etMqttHost").setText("mqtt.example.invalid")
        get<EditText>(activity, "etMqttPassword").setText("FAKE-MQTT-NEW")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { activity.isFinishing }
        assertFalse("Password must use protected storage", prefs.contains("mqtt_password"))
    }
    @Test fun savingOtherSettingsMustNotReplaceExplicitEmptyHost() {
        val activity = settings()
        get<EditText>(activity, "etMqttHost").setText("")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { activity.isFinishing }
        assertTrue("Explicit empty host must remain empty", prefs.getString("mqtt_host", null) == "")
    }
    @Test fun sessionDurationBelowFiveMinutesCannotBeSaved() {
        val activity=settings()
        val input=get<EditText>(activity,"etPinSessionMinutes")
        val before=prefs.all.toMap()
        for(value in listOf("0","4","-1","")) {
            input.setText(value)
            weatherInvoke(activity,"saveCurrentSettings")
            assertNotNull(input.error)
            assertEquals(before,prefs.all)
            assertFalse(activity.isFinishing)
        }
    }
}
