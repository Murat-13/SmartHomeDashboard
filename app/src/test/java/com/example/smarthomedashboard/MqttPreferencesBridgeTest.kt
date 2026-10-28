package com.example.smarthomedashboard

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MqttPreferencesBridgeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("mqtt-artificial", 0)
    private fun legacy(p: SharedPreferences = prefs) = PreferenceMqttLegacy({ p },
        { "mqtt.example.invalid" }, { "FAKE-USER" }, { "FAKE-DEFAULT" })
    private fun access(p: SharedPreferences = prefs, store: SecretStore = weatherTestStore()) =
        MqttCredentialAccess(object : MqttPasswordSlot {
            override fun read() = store.read(SecretStore.Slot.MQTT_PASSWORD)
            override fun write(value: String) = store.write(SecretStore.Slot.MQTT_PASSWORD, value)
        }, legacy(p)) {}

    @Test fun productionFactoryIsLazyAndWorkerGuardPrecedesPrefsAndKeystore() {
        val access = MqttCredentialAccess.create(app)
        try { access.read(); fail("Worker required") } catch (_: IllegalStateException) {}
        assertTrue(app.getSharedPreferences("dashboard_prefs", 0).all.isEmpty())
    }
    @Test fun missingFieldsCaptureOnlyEffectiveConfigurationWithoutPlaintextPassword() {
        prefs.edit().putString("unknown", "kept").putString("yandex_lat", "")
            .putString("yandex_last_temp", "FAKE-CACHE").commit()
        val reader = access()
        val result = reader.read()
        assertEquals(MqttCredentialAccess.Status.READY, result.status)
        assertEquals("FAKE-DEFAULT", result.value)
        assertEquals(mapOf("unknown" to "kept", "yandex_lat" to "", "yandex_last_temp" to "FAKE-CACHE",
            "mqtt_host" to "mqtt.example.invalid", "mqtt_port" to 1883, "mqtt_username" to "FAKE-USER"), prefs.all)
        assertEquals("FAKE-DEFAULT", reader.read().value)
        assertFalse(prefs.contains("mqtt_password"))
    }
    @Test fun existingEmptyNonemptyAndWhitespaceAreExactAndNeverDefaulted() {
        for (value in listOf("", "  ", "FAKE-Юникод")) {
            prefs.edit().clear().putString("mqtt_host", value).putInt("mqtt_port", 0)
                .putString("mqtt_username", value).putString("mqtt_password", value).commit()
            val reader = access()
            val result = reader.read()
            assertEquals(value, result.value)
            assertEquals(value, result.configuration!!.host)
            assertEquals(0, result.configuration.port)
            assertEquals(value, result.configuration.username)
            assertEquals(mapOf("mqtt_host" to value, "mqtt_port" to 0, "mqtt_username" to value), prefs.all)
            assertEquals(value, reader.read().value)
        }
    }
    @Test fun oneMissingFieldDoesNotRewritePresentFields() {
        prefs.edit().putString("mqtt_host", "").putInt("mqtt_port", 65535).commit()
        val result = access().read()
        assertEquals("", result.configuration!!.host)
        assertEquals(65535, result.configuration.port)
        assertEquals("FAKE-USER", result.configuration.username)
    }
    @Test fun wrongTypesAreNotAbsenceAndRemainUnchanged() {
        for (field in listOf("mqtt_host", "mqtt_port", "mqtt_username", "mqtt_password")) {
            prefs.edit().clear().putString("mqtt_host", "").putInt("mqtt_port", 1883)
                .putString("mqtt_username", "").putString("mqtt_password", "FAKE-OLD").apply {
                    if (field == "mqtt_port") putString(field, "") else putBoolean(field, true)
                }.commit()
            val before = prefs.all
            val store = weatherTestStore()
            assertEquals(MqttCredentialAccess.Status.LEGACY_ERROR, access(store = store).read().status)
            assertEquals(before, prefs.all)
            assertEquals(SecretStore.Status.ABSENT, store.read(SecretStore.Slot.MQTT_PASSWORD).status)
        }
    }
    @Test fun staleExpectedLegacyCannotRemoveFreshInput() {
        prefs.edit().putString("mqtt_password", "FAKE-FRESH").commit()
        assertFalse(legacy().removeIfUnchanged("FAKE-OLD"))
        assertEquals("FAKE-FRESH", prefs.getString("mqtt_password", null))
    }
    @Test fun configCommitFalseAfterInMemoryChangeRequiresRetry() {
        var failCommit = true
        var commits = 0
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val edit = prefs.edit()
                return object : SharedPreferences.Editor by edit {
                    override fun commit(): Boolean { commits++; val written = edit.commit(); return !failCommit && written }
                }
            }
        }
        val store = weatherTestStore()
        assertEquals(MqttCredentialAccess.Status.CONFIG_WRITE_FAILED, access(wrapped, store).read().status)
        assertEquals(SecretStore.Status.ABSENT, store.read(SecretStore.Slot.MQTT_PASSWORD).status)
        val previous = commits
        failCommit = false
        assertEquals(MqttCredentialAccess.Status.READY, access(wrapped, store).read().status)
        assertTrue(commits > previous)
    }
    @Test fun cleanupCommitFalseAfterMemoryRemovalDoesNotReportSuccess() {
        prefs.edit().putString("mqtt_password", "FAKE-OLD").commit()
        var failCleanup = true
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val edit = prefs.edit()
                var removing = false
                return object : SharedPreferences.Editor by edit {
                    override fun remove(key: String): SharedPreferences.Editor {
                        if (key == "mqtt_password") removing = true
                        edit.remove(key); return this
                    }
                    override fun commit(): Boolean {
                        val written = edit.commit()
                        return written && !(removing && failCleanup)
                    }
                }
            }
        }
        val store = weatherTestStore()
        assertEquals(MqttCredentialAccess.Status.CLEANUP_FAILED, access(wrapped, store).read().status)
        assertEquals("FAKE-OLD", store.read(SecretStore.Slot.MQTT_PASSWORD).value)
        failCleanup = false
        assertEquals(MqttCredentialAccess.Status.READY, access(wrapped, store).read().status)
    }
    @Test fun secureErrorNeverCallsPasswordDefault() {
        val reader = MqttCredentialAccess(object : MqttPasswordSlot {
            override fun read() = SecretStore.ReadResult(SecretStore.Status.KEY_UNAVAILABLE)
            override fun write(value: String): SecretStore.Status = error("No write")
        }, PreferenceMqttLegacy({ prefs }, { "mqtt.example.invalid" }, { "FAKE-USER" }, { error("No secret fallback") })) {}
        assertEquals(MqttCredentialAccess.Status.SECURE_ERROR, reader.read().status)
        assertFalse(prefs.contains("mqtt_password"))
    }
}
