package com.example.smarthomedashboard

import android.content.SharedPreferences
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MqttRemovalMigrationTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mqtt-removal-test", 0)

    private class Container : SecretContainerFile {
        var bytes: ByteArray? = null
        var failWrite = false
        var writes = 0
        override fun read() = bytes?.clone()
        override fun replace(ciphertext: ByteArray) {
            if (failWrite) throw IOException("artificial failure")
            writes++
            bytes = ciphertext.clone()
        }
    }
    private class Keys : SecretKeyProvider {
        var key: SecretKey? = null
        var creations = 0
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey().also { key = it; creations++ }
    }
    private val file = Container()
    private val keys = Keys()
    private val store = SecretStore(file, keys) {}
    private fun migration(p: SharedPreferences = prefs, delete: () -> SecretStore.Status = {
        store.delete(SecretStore.Slot.MQTT_PASSWORD)
    }) = MqttRemovalMigration(p, delete) {}
    private fun seed() {
        assertTrue(prefs.edit().putString("mqtt_password", "FAKE-OPEN")
            .putString("mqtt_password_transfer_phase", "verified")
            .putString("mqtt_host", "mqtt.example.invalid").putInt("mqtt_port", 1883)
            .putString("mqtt_username", "FAKE-USER").putString("unrelated", "kept").commit())
        // Historical format is constructed independently of current slot enum values/order.
        // This catches accidental changes to bit 2, format/key versions and AAD.
        val header = java.nio.ByteBuffer.allocate(16)
            .putInt(0x53485353).putInt(1).putInt(1).putInt(7).array()
        val payload = java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { output ->
                for (value in listOf("FAKE-HA", "FAKE-RETIRED", "FAKE-WEATHER")) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
        }.toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keys.create())
        cipher.updateAAD("SmartHomeDashboard/network-secrets".toByteArray(Charsets.UTF_8) + header)
        val encrypted = cipher.doFinal(payload)
        payload.fill(0)
        file.bytes = header + cipher.iv + java.nio.ByteBuffer.allocate(4).putInt(encrypted.size).array() + encrypted
        assertEquals("FAKE-RETIRED", store.read(SecretStore.Slot.MQTT_PASSWORD).value)
    }
    private fun assertPreserved() {
        assertEquals("FAKE-HA", store.read(SecretStore.Slot.HA_TOKEN).value)
        assertEquals("FAKE-WEATHER", store.read(SecretStore.Slot.WEATHER_KEY).value)
        assertEquals("kept", prefs.getString("unrelated", null))
    }
    @Test fun upgradeRemovesOnlyRetiredSlotAndPreferencesAndIsIdempotent() {
        seed()
        assertTrue(migration().run())
        assertTrue(MqttRemovalMigration.KEYS.none(prefs::contains))
        assertTrue(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
        assertEquals(SecretStore.Status.ABSENT, store.read(SecretStore.Slot.MQTT_PASSWORD).status)
        assertPreserved()
        val before = file.bytes!!.clone()
        val count = file.writes
        val preferences = prefs.all
        assertTrue(migration(delete = { error("Completed migration must not access storage") }).run())
        assertArrayEquals(before, file.bytes)
        assertEquals(count, file.writes)
        assertEquals(preferences, prefs.all)
    }
    @Test fun failedSecureWriteStillCleansPlaintextAndRetriesWithoutLosingOtherSlots() {
        seed()
        val before = file.bytes!!.clone()
        file.failWrite = true
        assertFalse(migration().run())
        assertFalse(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
        assertTrue(MqttRemovalMigration.KEYS.none(prefs::contains))
        assertArrayEquals(before, file.bytes)
        assertPreserved()
        file.failWrite = false
        assertTrue(migration().run())
        assertEquals(SecretStore.Status.ABSENT, store.read(SecretStore.Slot.MQTT_PASSWORD).status)
        assertPreserved()
    }
    private fun failingPreferences(fail: () -> Boolean) = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val edit = prefs.edit()
            return object : SharedPreferences.Editor by edit {
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    edit.putBoolean(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    val written = edit.commit()
                    return !fail() && written
                }
            }
        }
    }
    @Test fun failedPreferencesCommitRetriesEvenAfterInMemoryRemoval() {
        seed()
        var fail = true
        val wrapped = failingPreferences { fail }
        assertFalse(migration(wrapped).run())
        assertFalse(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
        assertPreserved()
        fail = false
        assertTrue(migration(wrapped).run())
        assertPreserved()
    }
    @Test fun failedCompletionCommitNeverLeavesTrustedInMemoryMarker() {
        seed()
        var commits = 0
        val wrapped = failingPreferences { ++commits == 2 }
        assertFalse(migration(wrapped).run())
        assertFalse(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
        assertTrue(migration(wrapped).run())
        assertPreserved()
    }
    @Test fun corruptedContainerIsNotRecreatedAndPreferencesAreStillCleaned() {
        seed()
        file.bytes = file.bytes!!.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corrupted = file.bytes!!.clone()
        val writes = file.writes
        val creations = keys.creations
        assertFalse(migration().run())
        assertTrue(MqttRemovalMigration.KEYS.none(prefs::contains))
        assertFalse(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
        assertArrayEquals(corrupted, file.bytes)
        assertEquals(writes, file.writes)
        assertEquals(creations, keys.creations)
    }
    @Test fun unavailableStorageAndKeyAreRetriedWhilePlaintextIsRemoved() {
        for (failure in listOf(SecretStore.Status.KEY_UNAVAILABLE, SecretStore.Status.STORAGE_UNAVAILABLE)) {
            prefs.edit().clear().putString("mqtt_password", "FAKE-OPEN").commit()
            assertFalse(migration(delete = { failure }).run())
            assertFalse(prefs.contains("mqtt_password"))
            assertFalse(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
            assertTrue(migration(delete = { SecretStore.Status.ABSENT }).run())
        }
    }
    @Test fun freshInstallDoesNotCreateAContainerOrKey() {
        assertTrue(migration().run())
        assertNull(file.bytes)
        assertEquals(0, keys.creations)
        assertTrue(prefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
    }
    @Test fun workerGuardRunsBeforeAnyIo() {
        val migration = MqttRemovalMigration(prefs, { error("No storage access") }) { error("Worker required") }
        try { migration.run(); fail("Expected worker rejection") } catch (_: IllegalStateException) {}
        assertTrue(prefs.all.isEmpty())
    }
}
