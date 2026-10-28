package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// Настоящий JCA AES-GCM; только выдача ключа заменена. Это НЕ проверка Android Keystore.
class SecretStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Keys : SecretKeyProvider {
        var key: SecretKey? = null
        var unavailable = false
        var created = 0
        override fun existing(): SecretKey? {
            if (unavailable) throw GeneralSecurityException("FAKE-PROVIDER-DETAIL")
            return key
        }
        override fun create(): SecretKey {
            if (unavailable) throw GeneralSecurityException("FAKE-PROVIDER-DETAIL")
            created++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
    }
    private class MemoryFile : SecretContainerFile {
        var bytes: ByteArray? = null
        var readFailure = false
        var writeFailure = false
        var writes = 0
        override fun read(): ByteArray? {
            if (readFailure) throw IOException("FAKE-DISK-DETAIL")
            return bytes?.clone()
        }
        override fun replace(ciphertext: ByteArray) {
            if (writeFailure) throw IOException("FAKE-DISK-DETAIL")
            writes++
            bytes = ciphertext.clone()
        }
    }
    private val file = MemoryFile()
    private val keys = Keys()
    private fun store() = SecretStore(file, keys) {}
    private val ha = SecretStore.Slot.HA_TOKEN
    private val mqtt = SecretStore.Slot.MQTT_PASSWORD
    private val weather = SecretStore.Slot.WEATHER_KEY
    private val ok = SecretStore.Status.SUCCESS
    private fun assertRead(slot: SecretStore.Slot, value: String) {
        val result = store().read(slot)
        assertEquals(ok, result.status)
        assertEquals(value, result.value)
    }
    private fun assertReadFailure(status: SecretStore.Status) {
        val result = store().read(ha)
        assertEquals(status, result.status)
        assertNull(result.value)
        assertFalse(result.toString().contains("FAKE-"))
    }

    @Test fun noKeyOrFileCreatedByConstructionReadOrAbsentDelete() {
        val store = store()
        assertEquals(SecretStore.Status.ABSENT, store.read(ha).status)
        assertEquals(SecretStore.Status.ABSENT, store.delete(ha))
        assertNull(file.bytes)
        assertEquals(0, keys.created)
    }
    @Test fun unicodeAndEmptyStringDifferFromAbsence() {
        assertEquals(ok, store().write(ha, "FAKE-Юникод-温度-🔐"))
        assertRead(ha, "FAKE-Юникод-温度-🔐")
        assertEquals(ok, store().write(mqtt, ""))
        assertRead(mqtt, "")
        assertEquals(SecretStore.Status.ABSENT, store().read(weather).status)
    }
    @Test fun eachReplacementUsesFreshIvAndCiphertext() {
        store().write(ha, "FAKE-SAME")
        val first = file.bytes!!.clone()
        store().write(ha, "FAKE-SAME")
        val second = file.bytes!!
        assertFalse(first.copyOfRange(16, 28).contentEquals(second.copyOfRange(16, 28)))
        assertFalse(first.contentEquals(second))
        assertEquals(1, keys.created)
        assertFalse(String(second, Charsets.ISO_8859_1).contains("FAKE-SAME"))
    }
    @Test fun replacementPreservesOtherSlotsAndSurvivesNewInstance() {
        store().write(ha, "FAKE-HA")
        store().write(mqtt, "FAKE-MQTT")
        store().write(weather, "FAKE-WEATHER")
        store().write(ha, "FAKE-NEW")
        assertRead(ha, "FAKE-NEW")
        assertRead(mqtt, "FAKE-MQTT")
        assertRead(weather, "FAKE-WEATHER")
    }
    @Test fun resultAndErrorsNeverRenderPlaintext() {
        store().write(ha, "FAKE-SECRET-RESULT")
        assertFalse(store().read(ha).toString().contains("FAKE-"))
        keys.unavailable = true
        assertReadFailure(SecretStore.Status.KEY_UNAVAILABLE)
    }
    @Test fun ciphertextAndTagTamperingAreRejectedWithoutChangingFile() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        for (index in listOf(32, original.lastIndex)) {
            file.bytes = original.clone().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            val tampered = file.bytes!!.clone()
            assertReadFailure(SecretStore.Status.CORRUPTED)
            assertEquals(SecretStore.Status.CORRUPTED, store().write(ha, "FAKE-NEW"))
            assertEquals(SecretStore.Status.CORRUPTED, store().delete(ha))
            assertArrayEquals(tampered, file.bytes)
        }
    }
    @Test fun ivTamperingIsRejected() {
        store().write(ha, "FAKE-HA")
        file.bytes!![16] = (file.bytes!![16].toInt() xor 1).toByte()
        assertReadFailure(SecretStore.Status.CORRUPTED)
    }
    @Test fun aadSlotSubstitutionIsRejectedEvenForPreviouslyAbsentSlot() {
        store().write(ha, "FAKE-HA")
        ByteBuffer.wrap(file.bytes!!).putInt(12, 2)
        val result = store().read(mqtt)
        assertEquals(SecretStore.Status.CORRUPTED, result.status)
        assertNull(result.value)
    }
    @Test fun changingSlotRosterCannotHideAnEntryAsAbsent() {
        store().write(ha, "FAKE-HA")
        ByteBuffer.wrap(file.bytes!!).putInt(12, 0)
        assertReadFailure(SecretStore.Status.CORRUPTED)
    }
    @Test fun versionsAreExplicitAndNeverOverwritten() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        for (position in listOf(4, 8)) {
            file.bytes = original.clone().also { ByteBuffer.wrap(it).putInt(position, 99) }
            val changed = file.bytes!!.clone()
            assertReadFailure(SecretStore.Status.UNSUPPORTED_VERSION)
            assertEquals(SecretStore.Status.UNSUPPORTED_VERSION, store().write(ha, "FAKE-NEW"))
            assertEquals(SecretStore.Status.UNSUPPORTED_VERSION, store().delete(ha))
            assertArrayEquals(changed, file.bytes)
        }
        assertEquals(1, keys.created)
    }
    @Test fun truncatedExtraAndOversizedFilesAreRejected() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        for (changed in listOf(byteArrayOf(), original.copyOf(12), original.copyOf(original.size - 1),
            original + byteArrayOf(0), ByteArray(200_001))) {
            file.bytes = changed
            assertReadFailure(SecretStore.Status.CORRUPTED)
            assertArrayEquals(changed, file.bytes)
        }
    }
    @Test fun malformedHeaderFieldsAreRejected() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        for ((offset, value) in listOf(0 to 0, 12 to 8, 28 to -1, 28 to Int.MAX_VALUE)) {
            file.bytes = original.clone().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertReadFailure(SecretStore.Status.CORRUPTED)
        }
    }
    @Test fun unavailableKeyDoesNotEraseOrRegenerateCiphertext() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        keys.unavailable = true
        assertReadFailure(SecretStore.Status.KEY_UNAVAILABLE)
        assertEquals(SecretStore.Status.KEY_UNAVAILABLE, store().write(ha, "FAKE-NEW"))
        assertEquals(SecretStore.Status.KEY_UNAVAILABLE, store().delete(ha))
        assertArrayEquals(original, file.bytes)
        assertEquals(1, keys.created)
        keys.unavailable = false
        assertRead(ha, "FAKE-HA")
    }
    @Test fun lostKeyDoesNotAutomaticallyGenerateReplacement() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        keys.key = null
        assertReadFailure(SecretStore.Status.KEY_UNAVAILABLE)
        assertEquals(SecretStore.Status.KEY_UNAVAILABLE, store().write(mqtt, "FAKE-NEW"))
        assertArrayEquals(original, file.bytes)
        assertEquals(1, keys.created)
    }
    @Test fun wrongExistingKeyReportsAuthenticationFailure() {
        store().write(ha, "FAKE-HA")
        keys.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertReadFailure(SecretStore.Status.CORRUPTED)
        assertEquals(1, keys.created)
    }
    @Test fun firstKeyFailureCreatesNoContainer() {
        keys.unavailable = true
        assertEquals(SecretStore.Status.KEY_UNAVAILABLE, store().write(ha, "FAKE-HA"))
        assertNull(file.bytes)
        assertEquals(0, keys.created)
    }
    @Test fun storageReadFailureDoesNotCreateKeyOrWrite() {
        file.readFailure = true
        assertReadFailure(SecretStore.Status.STORAGE_UNAVAILABLE)
        assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, store().write(ha, "FAKE-HA"))
        assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, store().delete(ha))
        assertEquals(0, keys.created)
        assertEquals(0, file.writes)
    }
    @Test fun replacementWriteFailurePreservesPreviousSuccessfulContainer() {
        store().write(ha, "FAKE-OLD")
        val old = file.bytes!!.clone()
        file.writeFailure = true
        assertEquals(SecretStore.Status.WRITE_FAILED, store().write(ha, "FAKE-NEW"))
        assertArrayEquals(old, file.bytes)
        assertRead(ha, "FAKE-OLD")
    }
    @Test fun failedInitialWriteCanBeRetriedWithSameKey() {
        file.writeFailure = true
        assertEquals(SecretStore.Status.WRITE_FAILED, store().write(ha, "FAKE-HA"))
        assertNull(file.bytes)
        file.writeFailure = false
        assertEquals(ok, store().write(ha, "FAKE-HA"))
        assertEquals(1, keys.created)
    }
    @Test fun deletingOneAndRepeatingDeleteKeepsOtherSlotsAndKey() {
        store().write(ha, "FAKE-HA")
        store().write(mqtt, "FAKE-MQTT")
        assertEquals(ok, store().delete(ha))
        val committed = file.bytes!!.clone()
        assertEquals(SecretStore.Status.ABSENT, store().delete(ha))
        assertArrayEquals(committed, file.bytes)
        assertRead(mqtt, "FAKE-MQTT")
        assertEquals(1, keys.created)
    }
    @Test fun failedDeletePreservesAllPreviousValues() {
        store().write(ha, "FAKE-HA")
        store().write(mqtt, "FAKE-MQTT")
        file.writeFailure = true
        assertEquals(SecretStore.Status.WRITE_FAILED, store().delete(ha))
        assertRead(ha, "FAKE-HA")
        assertRead(mqtt, "FAKE-MQTT")
    }
    @Test fun lastDeleteKeepsAuthenticatedEmptyContainerAndOriginalKey() {
        store().write(ha, "FAKE-HA")
        assertEquals(ok, store().delete(ha))
        assertNotNull(file.bytes)
        assertEquals(SecretStore.Status.ABSENT, store().read(ha).status)
        keys.key = null
        assertReadFailure(SecretStore.Status.KEY_UNAVAILABLE)
        assertEquals(SecretStore.Status.KEY_UNAVAILABLE, store().write(ha, "FAKE-NEW"))
        assertEquals(1, keys.created)
    }
    @Test fun oversizeAndInvalidUnicodeWritesDoNotReplaceGoodValue() {
        store().write(ha, "FAKE-OLD")
        val old = file.bytes!!.clone()
        assertEquals(SecretStore.Status.WRITE_FAILED, store().write(ha, "x".repeat(65537)))
        assertEquals(SecretStore.Status.WRITE_FAILED, store().write(ha, String(charArrayOf(0xD800.toChar()))))
        assertArrayEquals(old, file.bytes)
        assertRead(ha, "FAKE-OLD")
    }
    @Test fun malformedAuthenticatedPayloadIsNotReportedAsSuccess() {
        store().write(ha, "FAKE-HA")
        val original = file.bytes!!.clone()
        val header = original.copyOfRange(0, 16)
        val prefix = "SmartHomeDashboard/network-secrets".toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.key)
        cipher.updateAAD(prefix + header)
        val encrypted = cipher.doFinal(byteArrayOf(0, 0, 0, 1, 0xFF.toByte()))
        file.bytes = header + cipher.iv + ByteBuffer.allocate(4).putInt(encrypted.size).array() + encrypted
        assertReadFailure(SecretStore.Status.CORRUPTED)
    }
    @Test fun concurrentInstancesSerializeReadModifyWriteAndKeepEverySlot() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val tracked = object : SecretContainerFile {
            override fun read(): ByteArray? {
                val current = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, current) }
                try { Thread.yield(); return file.read() } finally { active.decrementAndGet() }
            }
            override fun replace(ciphertext: ByteArray) = file.replace(ciphertext)
        }
        val pool = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 6).map { worker ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(20) {
                        val slot = SecretStore.Slot.entries[worker % 3]
                        assertEquals(ok, SecretStore(tracked, keys) {}.write(slot, "FAKE-" + slot.name))
                    }
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            SecretStore.Slot.entries.forEach { assertRead(it, "FAKE-" + it.name) }
            assertEquals(1, keys.created)
            assertEquals(1, maximum.get())
        } finally { pool.shutdownNow() }
    }
    private fun disk(base: File, commit: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }) = AtomicSecretContainerFile({ base }, commit, exists = { it.exists() })

    @Test fun inaccessibleFileMetadataDoesNotLookAbsentOrCreateKey() {
        val base = File(temporary.root, "container.bin")
        val unavailable = AtomicSecretContainerFile({ base }, exists = { throw IOException("FAKE-ACCESS") })
        val store = SecretStore(unavailable, keys) {}
        assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, store.read(ha).status)
        assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, store.write(ha, "FAKE-NEW"))
        assertEquals(0, keys.created)
        assertFalse(base.exists())
    }

    @Test fun realDiskRoundTripWithJvmAtomicRenameAndNewInstances() {
        val base = File(temporary.root, "private/container.bin")
        val first = SecretStore(disk(base), keys) {}
        assertEquals(ok, first.write(ha, "FAKE-DISK"))
        assertEquals("FAKE-DISK", SecretStore(disk(base), keys) {}.read(ha).value)
        assertFalse(base.readText(Charsets.ISO_8859_1).contains("FAKE-DISK"))
        assertFalse(File(base.parentFile, base.name + ".new").exists())
    }
    @Test fun renameFailureLeavesPreviousRealDiskContainerReadable() {
        val base = File(temporary.root, "private/container.bin")
        SecretStore(disk(base), keys) {}.write(ha, "FAKE-OLD")
        val old = base.readBytes()
        val failing = SecretStore(disk(base) { _, _ -> throw IOException("FAKE-RENAME") }, keys) {}
        assertEquals(SecretStore.Status.WRITE_FAILED, failing.write(ha, "FAKE-NEW"))
        assertArrayEquals(old, base.readBytes())
        assertEquals("FAKE-OLD", SecretStore(disk(base), keys) {}.read(ha).value)
    }
    @Test fun incompleteFirstDiskWriteIsNotSilentlyTreatedAsFreshStore() {
        val base = File(temporary.root, "container.bin")
        val pending = File(base.parentFile, base.name + ".new")
        pending.writeBytes(byteArrayOf(1, 2, 3))
        val store = SecretStore(disk(base), keys) {}
        assertEquals(SecretStore.Status.STORAGE_UNAVAILABLE, store.write(ha, "FAKE-HA"))
        assertArrayEquals(byteArrayOf(1, 2, 3), pending.readBytes())
        assertEquals(0, keys.created)
    }
    @Test fun oldCommittedFileWinsOverInterruptedReplacementWithoutReadingTemp() {
        val base = File(temporary.root, "container.bin")
        SecretStore(disk(base), keys) {}.write(ha, "FAKE-OLD")
        File(base.parentFile, base.name + ".new").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("FAKE-OLD", SecretStore(disk(base), keys) {}.read(ha).value)
    }
}
