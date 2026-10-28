package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WeatherKeyAccessTest {
    private class Crash : RuntimeException()
    private class Slot : WeatherKeySlot {
        var value: String? = null
        var readStatus: SecretStore.Status? = null
        var writeStatus = SecretStore.Status.SUCCESS
        var writes = 0
        var reads = 0
        var afterWrite: (() -> Unit)? = null
        override fun read(): SecretStore.ReadResult {
            reads++
            return SecretStore.ReadResult(readStatus ?: if (value == null) SecretStore.Status.ABSENT else SecretStore.Status.SUCCESS, value)
        }
        override fun write(value: String): SecretStore.Status {
            writes++
            if (writeStatus == SecretStore.Status.SUCCESS) this.value = value
            afterWrite?.invoke()
            return writeStatus
        }
    }
    private class Legacy : WeatherLegacy {
        var value: String? = "FAKE-OLD"
        var journal: String? = null
        var marks = true
        var cleanup = true
        var configurationWrites = true
        var removing: (() -> Unit)? = null
        var marking: ((String?) -> Unit)? = null
        var removes = 0
        var saved = WeatherKeyAccess.Configuration("10.0", "20.0", false)
        override fun readKey() = value
        override fun phase() = journal
        override fun mark(phase: String?): Boolean {
            if (!marks) return false
            journal = phase
            marking?.invoke(phase)
            return true
        }
        override fun removeIfUnchanged(expected: String?): Boolean {
            removes++
            removing?.invoke()
            if (!cleanup || value != expected) return false
            value = null
            journal = null
            return true
        }
        override fun prepareConfiguration() = saved
        override fun saveConfiguration(configuration: WeatherKeyAccess.Configuration): Boolean {
            if (!configurationWrites) return false
            saved = configuration
            return true
        }
    }
    private val slot = Slot()
    private val legacy = Legacy()
    private fun access() = WeatherKeyAccess(slot, legacy) {}
    private fun save(value: String?, revision: Long = WeatherKeyAccess.revision) =
        access().save(value, WeatherKeyAccess.Configuration("30.0", "40.0", true), revision)
    private fun ready(result: WeatherKeyAccess.Result, expected: String = "FAKE-OLD") {
        assertEquals(WeatherKeyAccess.Status.READY, result.status)
        assertEquals(expected, result.value)
        assertFalse(result.toString().contains(expected.takeIf { it.isNotEmpty() } ?: "UNUSED"))
    }
    private fun crash(block: () -> Unit) { try { block(); fail("Expected interruption") } catch (_: Crash) {} }

    @Test fun exactUnicodeAndWhitespaceRoundtripBeforeRemovingLegacy() {
        legacy.value = "  FAKE-Юникод-🔐  "
        legacy.removing = { assertTrue(slot.reads >= 2); assertEquals(legacy.value, slot.value) }
        ready(access().read(), "  FAKE-Юникод-🔐  ")
        assertNull(legacy.value)
        assertNull(legacy.journal)
        legacy.removing = null
        ready(access().read(), "  FAKE-Юникод-🔐  ")
        assertEquals(1, slot.writes)
    }
    @Test fun absentKeyNeverGetsUiCandidateOrAnyWrite() {
        legacy.value = null
        assertEquals(WeatherKeyAccess.Status.ABSENT, access().read().status)
        assertEquals(0, slot.writes)
    }
    @Test fun emptyAndWhitespaceAreSuccessfulValuesNotAbsence() {
        for (value in listOf("", "   ")) {
            slot.value = null; legacy.value = value
            ready(access().read(), value)
            assertNull(legacy.value)
        }
    }
    @Test fun secureWinsOverStaleLegacyIncludingEmptySecure() {
        slot.value = ""
        ready(access().read(), "")
        assertNull(legacy.value)
        assertEquals(0, slot.writes)
    }
    @Test fun keyErrorsDoNotFallbackOverwriteOrRemove() {
        for (status in listOf(SecretStore.Status.KEY_UNAVAILABLE, SecretStore.Status.CORRUPTED,
            SecretStore.Status.UNSUPPORTED_VERSION, SecretStore.Status.STORAGE_UNAVAILABLE)) {
            slot.readStatus = status
            assertEquals(WeatherKeyAccess.Status.SECURE_ERROR, access().read().status)
            assertEquals(WeatherKeyAccess.Status.SECURE_ERROR, save("FAKE-NEW").status)
            assertEquals("FAKE-OLD", legacy.value)
            assertEquals(0, legacy.removes)
            assertEquals(0, slot.writes)
        }
    }
    @Test fun writeFailureKeepsOnlyLegacyCopyAndRetrySucceeds() {
        slot.writeStatus = SecretStore.Status.WRITE_FAILED
        assertEquals(WeatherKeyAccess.Status.WRITE_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertNull(slot.value)
        assertNull(legacy.journal)
        slot.writeStatus = SecretStore.Status.SUCCESS
        ready(access().read())
    }
    @Test fun verifyReadFailureKeepsLegacyAndResumeDoesNotRewrite() {
        slot.afterWrite = { slot.readStatus = SecretStore.Status.KEY_UNAVAILABLE }
        assertEquals(WeatherKeyAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        slot.afterWrite = null; slot.readStatus = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun mismatchNeverDeletesLegacyOnRetry() {
        slot.afterWrite = { slot.value = "FAKE-WRONG" }
        assertEquals(WeatherKeyAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals(WeatherKeyAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals(0, legacy.removes)
    }
    @Test fun cleanupFailureIsNotSuccessAndCleanupIsRetried() {
        legacy.cleanup = false
        assertEquals(WeatherKeyAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        legacy.cleanup = true
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun failedCleanupWithInMemoryRemovalStillRequiresAnotherCommit() {
        legacy.removing = { legacy.value = null; legacy.cleanup = false }
        assertEquals(WeatherKeyAccess.Status.CLEANUP_FAILED, access().read().status)
        legacy.removing = null; legacy.cleanup = true
        ready(access().read())
        assertEquals(2, legacy.removes)
    }
    @Test fun pendingJournalFailureDoesNotWriteSecure() {
        legacy.marks = false
        assertEquals(WeatherKeyAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals(0, slot.writes)
        assertEquals("FAKE-OLD", legacy.value)
    }
    @Test fun verifiedJournalFailureDoesNotDeleteLegacy() {
        legacy.marking = { if (it == "migration") legacy.marks = false }
        assertEquals(WeatherKeyAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        legacy.marking = null; legacy.marks = true
        ready(access().read())
    }
    @Test fun interruptionBeforeSecureWriteResumes() {
        legacy.marking = { if (it == "migration") throw Crash() }
        crash { access().read() }
        assertNull(slot.value)
        legacy.marking = null
        ready(access().read())
    }
    @Test fun interruptionAfterSecureWriteResumesByComparingLegacy() {
        slot.afterWrite = { throw Crash() }
        crash { access().read() }
        slot.afterWrite = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun interruptionAfterVerifiedJournalResumesCleanup() {
        legacy.marking = { if (it == "verified") throw Crash() }
        crash { access().read() }
        legacy.marking = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun interruptionBeforeCleanupKeepsBothCopies() {
        legacy.removing = { throw Crash() }
        crash { access().read() }
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals("FAKE-OLD", slot.value)
        legacy.removing = null
        ready(access().read())
    }
    @Test fun interruptionAfterCleanupNeverRestoresPlaintext() {
        legacy.removing = { legacy.value = null; legacy.journal = null; throw Crash() }
        crash { access().read() }
        legacy.removing = null
        ready(access().read())
        assertNull(legacy.value)
    }
    @Test fun replacementInterruptedBeforeVerificationRequiresExplicitRetry() {
        ready(access().read())
        slot.afterWrite = { throw Crash() }
        crash { save("FAKE-NEW") }
        slot.afterWrite = null
        assertEquals(WeatherKeyAccess.Status.VERIFY_FAILED, access().read().status)
        ready(save("FAKE-NEW"), "FAKE-NEW")
    }
    @Test fun blankUiInputRepresentedByNullKeepsCurrentEmptyOrNonemptyKey() {
        ready(save(null))
        assertEquals(1, slot.writes)
        ready(save(""), "")
        ready(save(null), "")
    }
    @Test fun staleReplacementCannotOverwriteFreshValueAndOldResultIsInvalidated() {
        val old = access().read()
        ready(save("FAKE-FRESH"), "FAKE-FRESH")
        assertFalse(WeatherKeyAccess.isCurrent(old))
        assertEquals(WeatherKeyAccess.Status.STALE, save("FAKE-STALE", old.revision).status)
        ready(access().read(), "FAKE-FRESH")
    }
    @Test fun failedConfigCommitDoesNotReportSuccess() {
        legacy.configurationWrites = false
        assertEquals(WeatherKeyAccess.Status.CONFIG_WRITE_FAILED, save(null).status)
        assertEquals("FAKE-OLD", slot.value)
        assertFalse(legacy.saved.enabled)
    }
    @Test fun failedReplacementPreservesOldSecureValueAndPhase() {
        access().read()
        slot.writeStatus = SecretStore.Status.WRITE_FAILED
        assertEquals(WeatherKeyAccess.Status.WRITE_FAILED, save("FAKE-NEW").status)
        assertNull(legacy.journal)
        ready(access().read())
    }
    @Test fun unknownPhaseDoesNotGetInventedRecovery() {
        slot.value = "FAKE-SECURE"; legacy.journal = "unknown"
        assertEquals(WeatherKeyAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals(0, legacy.removes)
    }
    @Test fun workerGuardRunsBeforeAnyBoundary() {
        val guarded = WeatherKeyAccess(slot, legacy) { error("worker required") }
        try { guarded.read(); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, slot.reads)
        assertEquals(0, slot.writes)
    }
    @Test fun serializedMigrationAndFreshReplacementDoNotLoseNewValue() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        slot.afterWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val migration = pool.submit(Callable { access().read() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = pool.submit(Callable { save("FAKE-FRESH") })
            release.countDown()
            migration.get(5, TimeUnit.SECONDS)
            val result = replacement.get(5, TimeUnit.SECONDS)
            if (result.status == WeatherKeyAccess.Status.STALE) ready(save("FAKE-FRESH"), "FAKE-FRESH")
            else ready(result, "FAKE-FRESH")
            ready(access().read(), "FAKE-FRESH")
            assertNull(legacy.value)
        } finally { pool.shutdownNow() }
    }
    // The retired slot is seeded only to verify compatibility with existing containers.
    @Test fun realJcaSharedContainerPreservesHaAndMqttSlots() {
        val store = weatherTestStore()
        assertEquals(SecretStore.Status.SUCCESS, store.write(SecretStore.Slot.HA_TOKEN, "FAKE-HA"))
        assertEquals(SecretStore.Status.SUCCESS, store.write(SecretStore.Slot.MQTT_PASSWORD, "FAKE-MQTT"))
        val access = WeatherKeyAccess(object : WeatherKeySlot {
            override fun read() = store.read(SecretStore.Slot.WEATHER_KEY)
            override fun write(value: String) = store.write(SecretStore.Slot.WEATHER_KEY, value)
        }, legacy) {}
        ready(access.read())
        ready(access.save("FAKE-NEW", legacy.saved, WeatherKeyAccess.revision), "FAKE-NEW")
        assertEquals("FAKE-HA", store.read(SecretStore.Slot.HA_TOKEN).value)
        assertEquals("FAKE-MQTT", store.read(SecretStore.Slot.MQTT_PASSWORD).value)
    }
    @Test fun resultAndConfigurationStringsDoNotContainValues() {
        val result = access().read()
        assertFalse(result.toString().contains("FAKE-"))
        assertFalse(result.message.contains("FAKE-"))
        assertEquals("WeatherConfiguration", result.configuration.toString())
    }
}
