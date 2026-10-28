package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper

/** Removes retired connection settings without recreating the shared encrypted container. */
internal class MqttRemovalMigration(
    private val prefs: SharedPreferences,
    private val deletePassword: () -> SecretStore.Status,
    private val checkWorker: () -> Unit
) {
    fun run(): Boolean {
        checkWorker()
        return synchronized(LOCK) {
            try {
                if (prefs.all[COMPLETED] == true && KEYS.none(prefs::contains)) {
                    return@synchronized true
                }
                val status = try { deletePassword() } catch (_: Exception) { SecretStore.Status.STORAGE_UNAVAILABLE }
                // Even an unreadable container must not leave a plaintext password in preferences.
                val cleaned = prefs.edit().apply {
                    KEYS.forEach { remove(it) }
                    remove(COMPLETED)
                }.commit() && KEYS.none(prefs::contains)
                if (!cleaned || status !in setOf(SecretStore.Status.SUCCESS, SecretStore.Status.ABSENT)) {
                    return@synchronized false
                }
                val marked = prefs.edit().putBoolean(COMPLETED, true).commit() &&
                    prefs.getBoolean(COMPLETED, false) && KEYS.none(prefs::contains)
                if (!marked) {
                    // commit(false) may still change the in-memory map: never trust that marker on retry.
                    prefs.edit().remove(COMPLETED).commit()
                }
                marked
            } catch (_: Exception) {
                try { prefs.edit().remove(COMPLETED).commit() } catch (_: Exception) {}
                false
            }
        }
    }

    companion object {
        private val LOCK = Any()
        internal const val COMPLETED = "mqtt_removal_completed"
        internal val KEYS = setOf(
            "mqtt_password", "mqtt_password_transfer_phase", "mqtt_host", "mqtt_port", "mqtt_username"
        )

        fun create(context: Context): MqttRemovalMigration {
            val app = context.applicationContext
            val store = AndroidSecretStore.create(app)
            return MqttRemovalMigration(
                app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE),
                { store.delete(SecretStore.Slot.MQTT_PASSWORD) },
                { check(Looper.myLooper() != Looper.getMainLooper()) { "Settings cleanup requires a worker thread" } }
            )
        }
    }
}
