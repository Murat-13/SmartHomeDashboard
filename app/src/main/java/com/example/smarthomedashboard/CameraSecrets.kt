package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Пароли камер — свой у каждой плитки «Камера» (docs/camera-tz.md, решение владельца 20.11.2026). Отдельный файл
 * `camera_secrets.xml` и **отдельный** ключ Android Keystore: контейнер секретов ([SecretStore]: токен HA, ключ погоды,
 * пароль домофона) и его ключ не затрагиваются. Значение — AES-GCM, AAD — назначение и номер плитки: пароль одной
 * камеры не расшифруется как пароль другой. В резервную копию файл не попадает (ключ Keystore на другой планшет
 * всё равно не переносится). Блокирующие методы — только с рабочего потока.
 */
internal class CameraSecrets(
    context: Context,
    private val keys: SecretKeyProvider = AndroidSecretKeyProvider(ALIAS)
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Итог чтения: [sure] — хранилище ответило (пароль есть или его точно нет); нет — сбой, «пароля нет» значить не может. */
    class Loaded(val value: String?, val sure: Boolean)

    fun read(tileId: String): String? = load(tileId).value

    fun load(tileId: String): Loaded = synchronized(LOCK) {
        val stored = prefs.getString(tileId, null) ?: return Loaded(null, sure = true)
        val value = decrypt(tileId, stored)
        Loaded(value, sure = value != null)
    }

    private fun decrypt(tileId: String, stored: String): String? {
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            if (bytes.size < IV + 16) return null
            val key = keys.existing() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV))
            cipher.updateAAD(aad(tileId))
            String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Пароль камеры не читается: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Пустое значение — удалить. true — сохранено (или удалено). */
    fun write(tileId: String, value: String): Boolean = synchronized(LOCK) {
        if (value.isEmpty()) return prefs.edit().remove(tileId).commit()
        return try {
            val key = keys.existing() ?: keys.create()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(tileId))
            val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val out = cipher.iv + sealed
            prefs.edit().putString(tileId, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            Log.w(TAG, "Пароль камеры не сохранён: ${e.javaClass.simpleName}")
            false
        }
    }

    fun has(tileId: String) = prefs.contains(tileId)

    /** Ключа нет, а записанное им есть: новый ключ сделал бы прежние пароли нечитаемыми. */
    fun keyMissing(): Boolean = synchronized(LOCK) { prefs.all.isNotEmpty() && try { keys.existing() == null } catch (_: Exception) { true } }

    private fun aad(tileId: String) = "SmartHomeDashboard/camera-password/v1/$tileId".toByteArray(Charsets.UTF_8)

    companion object {
        const val PREFS = "camera_secrets"
        const val ALIAS = "smarthomedashboard.camera-secrets.key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12
        private const val TAG = "CameraSecrets"
        private val LOCK = Any()
    }
}

/**
 * Пароль камеры плитки: у «Домофона» — прежний слот [SecretStore.Slot.CAMERA_PASSWORD] (без изменений), у «Камеры» —
 * свой в [CameraSecrets]. [secretKey] — номер плитки камеры; пустой — домофон.
 */
internal object CameraPasswords {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-passwords").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = mutableMapOf<String, String?>()

    /** Подменяется в тестах (Robolectric без Android Keystore). */
    internal var secrets: (Context) -> CameraSecrets = { CameraSecrets(it) }

    fun read(context: Context, secretKey: String, result: (String?) -> Unit) {
        if (secretKey.isEmpty()) { DoorbellHub.cameraPassword(context, result); return }
        synchronized(cache) { if (secretKey in cache) { result(cache[secretKey]); return } }
        val app = context.applicationContext
        io.execute {
            val loaded = secrets(app).load(secretKey)
            // Сбой хранилища не запоминаем: следующее обращение прочитает снова (аудит 25.11.2026, REC-04).
            if (loaded.sure) synchronized(cache) { cache[secretKey] = loaded.value }
            main.post { result(loaded.value) }
        }
    }

    /** Блокирующее — для службы записи (рабочий поток). */
    fun blocking(context: Context, secretKey: String): String? = load(context, secretKey).value

    /** То же, с признаком «хранилище ответило» ([CameraSecrets.Loaded.sure]). */
    fun load(context: Context, secretKey: String): CameraSecrets.Loaded =
        if (secretKey.isEmpty()) try {
            DoorbellHub.loadPassword(context)
        } catch (_: Exception) { CameraSecrets.Loaded(null, sure = false) }
        else secrets(context.applicationContext).load(secretKey)

    fun save(context: Context, secretKey: String, value: String, done: (Boolean) -> Unit) {
        if (secretKey.isEmpty()) { DoorbellHub.savePassword(context, value, done); return }
        val app = context.applicationContext
        io.execute {
            val ok = secrets(app).write(secretKey, value)
            synchronized(cache) { cache.remove(secretKey) }
            main.post { done(ok) }
        }
    }

    /** Есть ли записанный пароль (читается ли — не важно). У домофона ([secretKey] пуст) — неизвестно, считается, что есть. */
    fun has(context: Context, secretKey: String): Boolean = secretKey.isEmpty() || secrets(context.applicationContext).has(secretKey)

    /** Записать и убедиться, что читается обратно (перенос из открытого места); [done] — на главном потоке. */
    fun move(context: Context, secretKey: String, value: String, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val store = secrets(app)
            val ok = !store.keyMissing() && store.write(secretKey, value) && store.read(secretKey) == value
            synchronized(cache) { cache.remove(secretKey) }
            main.post { done(ok) }
        }
    }

    internal fun clearCache() = synchronized(cache) { cache.clear() }
}
