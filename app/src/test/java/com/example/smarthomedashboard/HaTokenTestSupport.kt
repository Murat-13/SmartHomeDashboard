package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// Настоящий JCA AES-GCM проверяется с памятью вместо диска и искусственным ключом вместо AndroidKeyStore.
// [gate] задерживает чтение токена до сигнала теста: фоновая проверка не может завершиться раньше,
// чем тест проверит промежуточное состояние экрана (иначе withContext(IO) мог вернуться синхронно).
internal fun haTokenTestAccess(context: Context, gate: java.util.concurrent.CountDownLatch? = null): HaTokenAccess {
    val slot = SecretStore(object : SecretContainerFile {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun replace(ciphertext: ByteArray) { bytes = ciphertext.clone() }
    }, object : SecretKeyProvider {
        private var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey().also { key = it }
    }, { check(Looper.myLooper() != Looper.getMainLooper()) })
    return HaTokenAccess(object : HaTokenSlot {
        override fun read(): SecretStore.ReadResult {
            gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            return slot.read(SecretStore.Slot.HA_TOKEN)
        }
        override fun write(value: String) = slot.write(SecretStore.Slot.HA_TOKEN, value)
    }, PreferenceLegacyHaToken { context.getSharedPreferences("dashboard_prefs", 0) },
        { check(Looper.myLooper() != Looper.getMainLooper()) })
}
