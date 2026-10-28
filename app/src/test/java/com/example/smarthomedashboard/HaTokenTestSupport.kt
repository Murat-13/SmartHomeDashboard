package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// Тестовая граница S2: настоящий JCA AES-GCM, память вместо диска и искусственный ключ вместо AndroidKeyStore.
internal fun haTokenTestAccess(context: Context): HaTokenAccess {
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
        override fun read() = slot.read(SecretStore.Slot.HA_TOKEN)
        override fun write(value: String) = slot.write(SecretStore.Slot.HA_TOKEN, value)
    }, PreferenceLegacyHaToken { context.getSharedPreferences("dashboard_prefs", 0) },
        { check(Looper.myLooper() != Looper.getMainLooper()) })
}
