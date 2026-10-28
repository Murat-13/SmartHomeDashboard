package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HaTokenAccessTest {
    private class Crash : RuntimeException()
    private class Slot : HaTokenSlot {
        var value: String? = null
        var status: SecretStore.Status? = null
        var writeStatus = SecretStore.Status.SUCCESS
        var reads = 0
        var writes = 0
        var afterWrite: (() -> Unit)? = null
        var reading: (() -> Unit)? = null
        override fun read(): SecretStore.ReadResult {
            reads++
            reading?.invoke()
            return SecretStore.ReadResult(status ?: if (value == null) SecretStore.Status.ABSENT else SecretStore.Status.SUCCESS, value)
        }
        override fun write(value: String): SecretStore.Status {
            writes++
            if (writeStatus == SecretStore.Status.SUCCESS) this.value = value
            afterWrite?.invoke()
            return writeStatus
        }
    }
    private class Legacy : LegacyHaToken {
        var value: String? = "FAKE-OLD"
        var stage: String? = null
        var readFailure = false
        var cleanup = true
        var marks = true
        var afterMark: ((String?) -> Unit)? = null
        var removing: (() -> Unit)? = null
        var removes = 0
        override fun read(): String? { if (readFailure) throw LegacyTokenException(); return value }
        override fun phase() = stage
        override fun mark(phase: String?): Boolean {
            if (!marks) return false
            stage = phase
            afterMark?.invoke(phase)
            return true
        }
        override fun removeIfUnchanged(expected: String?): Boolean {
            removes++
            removing?.invoke()
            if (!cleanup || value != expected) return false
            value = null
            stage = null
            return true
        }
    }
    private val slot = Slot()
    private val legacy = Legacy()
    private fun access() = HaTokenAccess(slot, legacy) {}
    private fun ready(result: HaTokenAccess.Result, value: String = "FAKE-OLD") {
        assertEquals(HaTokenAccess.Status.READY, result.status)
        assertEquals(value, result.value)
        assertFalse(result.toString().contains("FAKE-"))
        assertFalse(result.message.contains("FAKE-"))
    }
    private fun crashed(action: () -> Unit) { try { action(); fail("Expected interruption") } catch (_: Crash) {} }

    @Test fun legacyRoundtripThenCleanupIsIdempotent() {
        legacy.removing = { assertEquals("FAKE-OLD", slot.value); assertTrue(slot.reads >= 2) }
        ready(access().read())
        assertNull(legacy.value)
        assertNull(legacy.stage)
        assertEquals(1, slot.writes)
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun authenticatedSecureEntryWinsOverStaleLegacy() {
        slot.value = "FAKE-NEW"
        ready(access().read(), "FAKE-NEW")
        assertNull(legacy.value)
        assertEquals(0, slot.writes)
    }
    @Test fun absentOrBlankLegacyCreatesNoSecretAndDoesNotInventValue() {
        for (value in listOf(null, "", "  ")) {
            legacy.value = value
            val result = access().read()
            assertEquals(HaTokenAccess.Status.ABSENT, result.status)
            assertNull(result.value)
            assertEquals(value, legacy.value)
            assertEquals(0, slot.writes)
        }
    }
    @Test fun secureFailuresNeverFallBackToLegacyOrWriteAnything() {
        for (status in listOf(SecretStore.Status.KEY_UNAVAILABLE, SecretStore.Status.CORRUPTED,
            SecretStore.Status.UNSUPPORTED_VERSION, SecretStore.Status.STORAGE_UNAVAILABLE)) {
            slot.status = status
            val result = access().read()
            assertEquals(HaTokenAccess.Status.SECURE_ERROR, result.status)
            assertNull(result.value)
            assertEquals("FAKE-OLD", legacy.value)
            assertEquals(0, slot.writes)
            assertEquals(HaTokenAccess.Status.SECURE_ERROR, access().replace("FAKE-NEW").status)
        }
    }
    @Test fun writeFailureKeepsLegacyAndCanRetry() {
        slot.writeStatus = SecretStore.Status.WRITE_FAILED
        assertEquals(HaTokenAccess.Status.WRITE_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertNull(slot.value)
        slot.writeStatus = SecretStore.Status.SUCCESS
        ready(access().read())
    }
    @Test fun verifyReadFailureKeepsLegacyAndResumesMigration() {
        slot.afterWrite = { slot.status = SecretStore.Status.KEY_UNAVAILABLE }
        assertEquals(HaTokenAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals("migration", legacy.stage)
        slot.status = null
        slot.afterWrite = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun mismatchingVerificationCannotDeleteLegacyOnRetry() {
        slot.afterWrite = { slot.value = "FAKE-WRONG" }
        assertEquals(HaTokenAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals(HaTokenAccess.Status.VERIFY_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals(0, legacy.removes)
    }
    @Test fun cleanupFailureIsNotSuccessAndNextRunOnlyFinishesCleanup() {
        legacy.cleanup = false
        assertEquals(HaTokenAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals("verified", legacy.stage)
        legacy.cleanup = true
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun failedCleanupWithAlreadyRemovedInMemoryKeyIsCommittedAgain() {
        legacy.removing = { legacy.value = null; legacy.cleanup = false }
        assertEquals(HaTokenAccess.Status.CLEANUP_FAILED, access().read().status)
        legacy.removing = null
        legacy.cleanup = true
        ready(access().read())
        assertEquals(2, legacy.removes)
    }
    @Test fun legacyReadOrJournalFailureDoesNotCreateSecureEntry() {
        legacy.readFailure = true
        assertEquals(HaTokenAccess.Status.LEGACY_ERROR, access().read().status)
        assertEquals(0, slot.writes)
        legacy.readFailure = false
        legacy.marks = false
        assertEquals(HaTokenAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals(0, slot.writes)
    }
    @Test fun interruptionAfterPendingJournalIsRetryable() {
        legacy.afterMark = { if (it == "migration") throw Crash() }
        crashed { access().read() }
        assertEquals("FAKE-OLD", legacy.value)
        assertNull(slot.value)
        legacy.afterMark = null
        ready(access().read())
    }
    @Test fun interruptionAfterSecureWriteDoesNotRewriteOrLoseLegacyOnResume() {
        slot.afterWrite = { throw Crash() }
        crashed { access().read() }
        assertEquals("FAKE-OLD", legacy.value)
        slot.afterWrite = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun interruptionAfterVerifiedMarkerResumesCleanup() {
        legacy.afterMark = { if (it == "verified") throw Crash() }
        crashed { access().read() }
        legacy.afterMark = null
        ready(access().read())
        assertEquals(1, slot.writes)
    }
    @Test fun interruptionBeforeCleanupKeepsBothCopies() {
        legacy.removing = { throw Crash() }
        crashed { access().read() }
        assertEquals("FAKE-OLD", legacy.value)
        assertEquals("FAKE-OLD", slot.value)
        legacy.removing = null
        ready(access().read())
    }
    @Test fun interruptionAfterCleanupDoesNotBringPlaintextBack() {
        legacy.removing = { legacy.value = null; legacy.stage = null; throw Crash() }
        crashed { access().read() }
        legacy.removing = null
        ready(access().read())
        assertNull(legacy.value)
        assertEquals(1, slot.writes)
    }
    @Test fun verifiedJournalCommitFailureKeepsOldCopyAndBlocksFullSuccess() {
        legacy.afterMark = { if (it == "migration") legacy.marks = false }
        assertEquals(HaTokenAccess.Status.CLEANUP_FAILED, access().read().status)
        assertEquals("FAKE-OLD", legacy.value)
        legacy.afterMark = null
        legacy.marks = true
        ready(access().read())
    }
    @Test fun failedReplacementRestoresPriorPhaseAndPriorSecureValue() {
        ready(access().read())
        slot.writeStatus = SecretStore.Status.WRITE_FAILED
        assertEquals(HaTokenAccess.Status.WRITE_FAILED, access().replace("FAKE-NEW").status)
        assertNull(legacy.stage)
        ready(access().read())
    }
    @Test fun interruptedReplacementRequiresExplicitRetryNotLegacyDowngrade() {
        access().read()
        slot.afterWrite = { throw Crash() }
        crashed { access().replace("FAKE-NEW") }
        slot.afterWrite = null
        assertEquals(HaTokenAccess.Status.VERIFY_FAILED, access().read().status)
        ready(access().replace("FAKE-NEW"), "FAKE-NEW")
        assertNull(legacy.value)
    }
    @Test fun explicitReplacementAndEmptyInputNeverWriteLegacy() {
        ready(access().replace("FAKE-NEW"), "FAKE-NEW")
        assertNull(legacy.value)
        assertEquals(HaTokenAccess.Status.EMPTY_INPUT, access().replace("").status)
        ready(access().read(), "FAKE-NEW")
    }
    @Test fun staleReplacementCannotOverrideNewerUserEntry() {
        val revision = access().read().revision
        ready(access().replace("FAKE-NEW", revision), "FAKE-NEW")
        assertEquals(HaTokenAccess.Status.STALE, access().replace("FAKE-STALE", revision).status)
        ready(access().read(), "FAKE-NEW")
    }
    @Test fun oldReadResultIsInvalidatedByReplacement() {
        val old = access().read()
        access().replace("FAKE-NEW")
        assertFalse(HaTokenAccess.isCurrent(old))
    }
    @Test fun concurrentMigrationAndReplacementSerializeWithoutLateCleanupOfNewValue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        slot.afterWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val migration = pool.submit(Callable { access().read() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = pool.submit(Callable {
                access().replace("FAKE-NEW", HaTokenAccess.revision)
            })
            release.countDown()
            migration.get(5, TimeUnit.SECONDS)
            val result = replacement.get(5, TimeUnit.SECONDS)
            if (result.status == HaTokenAccess.Status.STALE) ready(access().replace("FAKE-NEW"), "FAKE-NEW")
            else ready(result, "FAKE-NEW")
            ready(access().read(), "FAKE-NEW")
            assertNull(legacy.value)
        } finally { pool.shutdownNow() }
    }
}
