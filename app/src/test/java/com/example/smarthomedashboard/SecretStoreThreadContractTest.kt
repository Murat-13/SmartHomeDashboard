package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24])
class SecretStoreThreadContractTest {
    @Test fun constructionIsLazyAndEveryOperationRejectsMainThreadBeforeDiskOrKey() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "network-secrets")
        val prefsBefore = context.getSharedPreferences("dashboard_prefs", 0).all.toMap()
        val store = AndroidSecretStore.create(context)
        assertFalse(directory.exists())
        for (operation in listOf<() -> Any>(
            { store.read(SecretStore.Slot.HA_TOKEN) },
            { store.write(SecretStore.Slot.HA_TOKEN, "FAKE-THREAD") },
            { store.delete(SecretStore.Slot.HA_TOKEN) }
        )) {
            try { operation(); fail("Main thread must be rejected") }
            catch (e: IllegalStateException) { assertFalse(e.message!!.contains("FAKE-")) }
        }
        assertFalse(directory.exists())
        assertEquals(prefsBefore, context.getSharedPreferences("dashboard_prefs", 0).all)
    }
    @Test fun workerReadNeverFallsBackToLegacyPrefsWhenPlatformStorageIsUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "network-secrets")
        val prefs = context.getSharedPreferences("dashboard_prefs", 0)
        prefs.edit().putString("ha_token", "FAKE-EXISTING-PREF").commit()
        val before = prefs.all.toMap()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<SecretStore.ReadResult> {
                AndroidSecretStore.create(context).read(SecretStore.Slot.HA_TOKEN)
            }.get(10, TimeUnit.SECONDS)
            // В этой JVM нет настоящего Os.stat API24. Это проверка отказа без fallback,
            // а успешный Android filesystem/Keystore проверяет отдельный platform runner.
            assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, result.status)
            assertNull(result.value)
            assertFalse(directory.exists())
            assertEquals(before, prefs.all)
        } finally { pool.shutdownNow() }
    }
}
