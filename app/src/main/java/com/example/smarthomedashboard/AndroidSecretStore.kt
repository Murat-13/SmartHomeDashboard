package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal object AndroidSecretStore {
    fun create(context: Context): SecretStore {
        val app = context.applicationContext
        // Создание объекта не читает prefs/файлы и не обращается к Keystore.
        return SecretStore(
            AtomicSecretContainerFile(location = { File(app.noBackupFilesDir, "network-secrets/container.bin") }),
            AndroidSecretKeyProvider(),
            { check(Looper.myLooper() != Looper.getMainLooper()) { "SecretStore requires a worker thread" } }
        )
    }
}

internal class AndroidSecretKeyProvider(
    private val alias: String = "smarthomedashboard.network-secrets.key.v1"
) : SecretKeyProvider {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun existing(): SecretKey? {
        val store = store()
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey ?: throw KeyStoreException()
    }

    override fun create(): SecretKey {
        existing()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }
}

/**
 * Один процесс, вызовы сериализованы SecretStore. Временный файл расположен рядом с основным.
 * Сначала полный ciphertext + fsync, затем атомарный rename POSIX (доступен на API24).
 * Ошибка записи/sync/rename не заменяет старый файл. После commit не выполняем операций,
 * способных выдать ложный отказ уже совершённой замены. Это не защита от rollback всей файловой системы.
 */
internal class AtomicSecretContainerFile(
    private val location: () -> File,
    private val commit: (File, File) -> Unit = { from, to ->
        try { Os.rename(from.absolutePath, to.absolutePath)
        } catch (_: ErrnoException) { throw IOException() }
    },
    private val exists: (File) -> Boolean = { path ->
        // File.exists() скрывает ошибки доступа как false: это могло бы разрешить новый ключ.
        try { Os.stat(path.absolutePath); true
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) false else throw IOException()
        }
    }
) : SecretContainerFile {
    override fun read(): ByteArray? {
        val base = location()
        if (!exists(base)) {
            // Незавершённая первая запись не должна разрешать создание нового ключа поверх ciphertext.
            if (exists(temporary(base))) throw IOException()
            return null
        }
        return base.inputStream().use {
            val bytes = it.readBytesBounded()
            bytes
        }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        // Предел выше максимально допустимого контейнера: oversized-файл вернётся как CORRUPTED.
        while (output.size() < 200_000) {
            val count = read(buffer, 0, minOf(buffer.size, 200_000 - output.size()))
            if (count == -1) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    override fun replace(ciphertext: ByteArray) {
        val base = location()
        val parent = base.parentFile ?: throw IOException()
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException()
        val temp = temporary(base)
        try {
            FileOutputStream(temp).use {
                it.write(ciphertext)
                it.fd.sync()
            }
            commit(temp, base)
        } catch (e: IOException) {
            temp.delete()
            throw e
        } catch (e: SecurityException) {
            throw e
        }
    }

    private fun temporary(base: File) = File(base.parentFile, base.name + ".new")
}
