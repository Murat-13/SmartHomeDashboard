package com.example.smarthomedashboard

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Синхронный внутренний API: вызывать только с рабочего потока.
 * Production создаётся через AndroidSecretStore.create; он проверяет поток до диска/Keystore.
 * Здесь нет prefs, миграции, сетевых действий или автоматического восстановления ключа.
 */
internal class SecretStore(
    private val file: SecretContainerFile,
    private val keys: SecretKeyProvider,
    private val checkWorkerThread: () -> Unit
) {
    enum class Slot(val bit: Int) { HA_TOKEN(1), MQTT_PASSWORD(2), WEATHER_KEY(4) }
    enum class Status {
        SUCCESS, ABSENT, KEY_UNAVAILABLE, STORAGE_UNAVAILABLE,
        CORRUPTED, UNSUPPORTED_VERSION, WRITE_FAILED
    }

    // Не data class: автоматически сгенерированный toString раскрыл бы значение.
    class ReadResult internal constructor(val status: Status, val value: String? = null) {
        override fun toString(): String = "SecretReadResult(status=$status)"
    }

    fun read(slot: Slot): ReadResult = operation(
        failure = { ReadResult(it) }
    ) {
        val snapshot = load()
        try {
            val value = snapshot.values[slot]
            if (value == null) ReadResult(Status.ABSENT)
            else ReadResult(Status.SUCCESS, decodeText(value))
        } finally { snapshot.clear() }
    }

    fun write(slot: Slot, value: String): Status = operation(failure = { it }) {
        if (value.length > MAX_VALUE_BYTES) fail(Status.WRITE_FAILED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) { fail(Status.WRITE_FAILED) }
        try {
            if (bytes.size > MAX_VALUE_BYTES) fail(Status.WRITE_FAILED)
            val snapshot = load()
            try {
                val key = snapshot.key ?: keyAccess { keys.existing() ?: keys.create() }
                snapshot.values.put(slot, bytes)?.fill(0)
                persist(snapshot.values, key)
            } finally { snapshot.clear() }
        } finally { bytes.fill(0) }
    }

    fun delete(slot: Slot): Status = operation(failure = { it }) {
        val snapshot = load()
        try {
            val removed = snapshot.values.remove(slot) ?: return@operation Status.ABSENT
            removed.fill(0)
            // Даже последний слот удаляется записью аутентифицированного пустого контейнера.
            // Ключ остаётся; потерю файла/ключа нельзя маскировать регенерацией.
            persist(snapshot.values, snapshot.key ?: fail(Status.KEY_UNAVAILABLE))
        } finally { snapshot.clear() }
    }

    private class Snapshot(val values: MutableMap<Slot, ByteArray>, val key: SecretKey?) {
        fun clear() { values.values.forEach { it.fill(0) } }
    }

    private fun load(): Snapshot {
        val bytes = try { file.read() } catch (_: IOException) {
            fail(Status.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) { fail(Status.STORAGE_UNAVAILABLE) }
        if (bytes == null) return Snapshot(linkedMapOf(), null)
        if (bytes.size !in MIN_FILE_BYTES..MAX_FILE_BYTES) fail(Status.CORRUPTED)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC) fail(Status.CORRUPTED)
        if (input.readInt() != FORMAT_VERSION || input.readInt() != KEY_VERSION) fail(Status.UNSUPPORTED_VERSION)
        val mask = input.readInt()
        if (mask and 7 != mask) fail(Status.CORRUPTED)
        val iv = ByteArray(12).also { input.readFully(it) }
        val length = input.readInt()
        if (length < 16 || length != input.available()) fail(Status.CORRUPTED)
        val ciphertext = ByteArray(length).also { input.readFully(it) }
        val key = keyAccess { keys.existing() ?: fail(Status.KEY_UNAVAILABLE) }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(mask))
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) { fail(Status.CORRUPTED)
        } catch (_: BadPaddingException) { fail(Status.CORRUPTED)
        } catch (_: GeneralSecurityException) { fail(Status.KEY_UNAVAILABLE)
        } catch (_: ProviderException) { fail(Status.KEY_UNAVAILABLE)
        } catch (_: SecurityException) { fail(Status.KEY_UNAVAILABLE) }
        val values = linkedMapOf<Slot, ByteArray>()
        try {
            val payload = DataInputStream(ByteArrayInputStream(plaintext))
            for (slot in Slot.entries) if (mask and slot.bit != 0) {
                val size = payload.readInt()
                if (size !in 0..MAX_VALUE_BYTES || size > payload.available()) fail(Status.CORRUPTED)
                val value = ByteArray(size).also { payload.readFully(it) }
                values[slot] = value
                decodeText(value) // Невалидный UTF-8 не превращается в успешную строку с подстановками.
            }
            if (payload.available() != 0) fail(Status.CORRUPTED)
            return Snapshot(values, key)
        } catch (_: IOException) {
            values.values.forEach { it.fill(0) }
            fail(Status.CORRUPTED)
        } catch (e: StoreFailure) {
            values.values.forEach { it.fill(0) }
            throw e
        } finally { plaintext.fill(0) }
    }

    private fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { fail(Status.CORRUPTED) }

    private fun persist(values: Map<Slot, ByteArray>, key: SecretKey): Status {
        val mask = values.keys.fold(0) { mask, slot -> mask or slot.bit }
        val plaintext = ByteBuffer.allocate(values.values.sumOf { 4 + it.size }).apply {
            Slot.entries.forEach { slot -> values[slot]?.let { putInt(it.size); put(it) } }
        }.array()
        val encrypted: Pair<ByteArray, ByteArray>
        try {
            encrypted = keyAccess {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                // Провайдер сам создаёт новый случайный IV; не задаём IV при шифровании.
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(aad(mask))
                cipher.iv to cipher.doFinal(plaintext)
            }
        } finally { plaintext.fill(0) }
        if (encrypted.first.size != 12) fail(Status.KEY_UNAVAILABLE)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.write(header(mask))
            it.write(encrypted.first)
            it.writeInt(encrypted.second.size)
            it.write(encrypted.second)
        }
        try { file.replace(output.toByteArray())
        } catch (_: IOException) { fail(Status.WRITE_FAILED)
        } catch (_: SecurityException) { fail(Status.WRITE_FAILED) }
        return Status.SUCCESS
    }

    private fun header(mask: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.writeInt(FORMAT_VERSION); it.writeInt(KEY_VERSION); it.writeInt(mask)
        }
    }.toByteArray()

    // AAD связывает контейнер с назначением, версией ключа/формата и точным соответствием слотов.
    // Значения в payload расположены в фиксированном порядке битов; перестановка слотов ломает tag.
    private fun aad(mask: Int): ByteArray =
        "SmartHomeDashboard/network-secrets".toByteArray(Charsets.UTF_8) + header(mask)

    private fun <T> keyAccess(block: () -> T): T = try { block()
    } catch (_: GeneralSecurityException) { fail(Status.KEY_UNAVAILABLE)
    } catch (_: IOException) { fail(Status.KEY_UNAVAILABLE)
    } catch (_: ProviderException) { fail(Status.KEY_UNAVAILABLE)
    } catch (_: SecurityException) { fail(Status.KEY_UNAVAILABLE) }

    private fun <T> operation(failure: (Status) -> T, block: () -> T): T {
        checkWorkerThread()
        // Один процесс приложения, все экземпляры: read-modify-write не теряет соседний слот.
        return synchronized(LOCK) { try { block() } catch (e: StoreFailure) { failure(e.status) } }
    }

    private class StoreFailure(val status: Status) : RuntimeException("Secret store operation failed")
    private fun fail(status: Status): Nothing = throw StoreFailure(status)

    private companion object {
        val LOCK = Any()
        const val MAGIC = 0x53485353
        const val FORMAT_VERSION = 1
        const val KEY_VERSION = 1
        const val MAX_VALUE_BYTES = 65536
        const val MIN_FILE_BYTES = 48
        const val MAX_FILE_BYTES = 3 * (MAX_VALUE_BYTES + 4) + MIN_FILE_BYTES
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

// Внутренние границы для тестов отказов. Ни production API слотов, ни криптоалгоритм не подменяются.
internal interface SecretKeyProvider {
    fun existing(): SecretKey?
    fun create(): SecretKey
}
internal interface SecretContainerFile {
    fun read(): ByteArray?
    /** При отказе до commit предыдущий контейнер должен оставаться доступным. */
    fun replace(ciphertext: ByteArray)
}
