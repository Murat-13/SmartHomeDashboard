package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** Читает и переносит настройки MQTT в фоновом потоке, не создавая сетевое соединение. */
internal class MqttCredentialAccess(
    private val slot: MqttPasswordSlot,
    private val legacy: MqttLegacy,
    private val checkWorker: () -> Unit
) {
    enum class Status { READY, SECURE_ERROR, WRITE_FAILED, VERIFY_FAILED, CLEANUP_FAILED, LEGACY_ERROR, CONFIG_WRITE_FAILED, STALE }
    class Configuration(val host: String, val port: Int, val username: String) {
        override fun toString() = "MqttConfiguration"
    }
    class Result internal constructor(
        val status: Status, val value: String? = null, val configuration: Configuration? = null,
        val secureStatus: SecretStore.Status? = null, val revision: Long = MqttCredentialAccess.revision
    ) {
        val usable: Boolean get() = status == Status.READY
        override fun toString() = "MqttCredentialResult(status=$status, secureStatus=$secureStatus)"
        val message: String get() = when (status) {
            Status.READY -> if (value == "") "Пароль MQTT сохранён пустым" else "Пароль MQTT сохранён"
            Status.SECURE_ERROR -> "Защищённый пароль MQTT недоступен. Автоматическое восстановление не реализовано"
            Status.WRITE_FAILED -> "Не удалось сохранить пароль MQTT"
            Status.VERIFY_FAILED -> "Не удалось проверить пароль MQTT. Повторите явный ввод в настройках"
            Status.CLEANUP_FAILED -> "Не удалось подтвердить удаление открытой копии пароля MQTT"
            Status.LEGACY_ERROR -> "Не удалось прочитать прежние настройки MQTT"
            Status.CONFIG_WRITE_FAILED -> "Не удалось подтвердить сохранение настроек MQTT"
            Status.STALE -> "Настройки MQTT изменились. Откройте экран повторно"
        }
    }

    fun read(): Result = serialized {
        // Проверяем типы всех несекретных полей до миграции пароля.
        val configuration = legacy.prepareConfiguration()
        val key = readPassword()
        Result(key.status, key.value, configuration, key.secureStatus)
    }

    fun save(newPassword: String?, configuration: Configuration, expectedRevision: Long): Result = serialized {
        if (expectedRevision != revision) return@serialized Result(Status.STALE)
        legacy.prepareConfiguration()
        val key = if (newPassword == null) readPassword() else {
            val current = slot.read()
            if (current.status != SecretStore.Status.SUCCESS && current.status != SecretStore.Status.ABSENT) {
                return@serialized Result(Status.SECURE_ERROR, secureStatus = current.status)
            }
            legacy.readPassword()
            storeAndVerify(newPassword, "replacement")
        }
        if (!key.usable) return@serialized key
        if (!legacy.saveConfiguration(configuration)) return@serialized Result(Status.CONFIG_WRITE_FAILED)
        REVISION.incrementAndGet()
        Result(key.status, key.value, configuration, key.secureStatus)
    }

    private fun effectiveLegacy(): String = legacy.readPassword() ?: legacy.defaultPassword()
    private fun readPassword(): Result {
        val stored = slot.read()
        return when (stored.status) {
            SecretStore.Status.SUCCESS -> {
                val value = stored.value ?: return Result(Status.VERIFY_FAILED)
                when (legacy.phase()) {
                    "migration" -> {
                        if (effectiveLegacy() != value) return Result(Status.VERIFY_FAILED)
                        if (!legacy.mark("verified")) return Result(Status.CLEANUP_FAILED)
                    }
                    "replacement" -> return Result(Status.VERIFY_FAILED)
                    null, "verified" -> Unit
                    else -> return Result(Status.VERIFY_FAILED)
                }
                finish(value)
            }
            // Missing имеет прежний effective default, но plaintext в prefs не создаётся.
            SecretStore.Status.ABSENT -> storeAndVerify(effectiveLegacy(), "migration")
            else -> Result(Status.SECURE_ERROR, secureStatus = stored.status)
        }
    }
    private fun storeAndVerify(value: String, phase: String): Result {
        val previous = legacy.phase()
        if (!legacy.mark(phase)) return Result(Status.CLEANUP_FAILED)
        val status = slot.write(value)
        if (status != SecretStore.Status.SUCCESS) {
            if (!legacy.mark(previous)) return Result(Status.CLEANUP_FAILED)
            return Result(Status.WRITE_FAILED, secureStatus = status)
        }
        REVISION.incrementAndGet()
        val checked = slot.read()
        if (checked.status != SecretStore.Status.SUCCESS || checked.value != value) {
            return Result(Status.VERIFY_FAILED, secureStatus = checked.status)
        }
        if (!legacy.mark("verified")) return Result(Status.CLEANUP_FAILED)
        return finish(value)
    }
    private fun finish(value: String): Result {
        val current = legacy.readPassword()
        // commit нужен и после отказа, который уже изменил карту prefs в памяти.
        if (!legacy.removeIfUnchanged(current)) return Result(Status.CLEANUP_FAILED)
        return Result(Status.READY, value)
    }
    private fun serialized(action: () -> Result): Result {
        checkWorker()
        return synchronized(LOCK) {
            try { action() } catch (failure: MqttLegacyFailure) { Result(failure.status) }
        }
    }
    companion object {
        private val LOCK = Any()
        private val REVISION = AtomicLong()
        val revision: Long get() = REVISION.get()
        fun isCurrent(result: Result) = result.revision == revision
        fun create(context: Context): MqttCredentialAccess {
            val app = context.applicationContext
            val store = AndroidSecretStore.create(app)
            val defaults by lazy { MqttManager(app) }
            return MqttCredentialAccess(object : MqttPasswordSlot {
                override fun read() = store.read(SecretStore.Slot.MQTT_PASSWORD)
                override fun write(value: String) = store.write(SecretStore.Slot.MQTT_PASSWORD, value)
            }, PreferenceMqttLegacy({ app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE) },
                { defaults.legacyHostForBridge() }, { defaults.legacyUsernameForBridge }, { defaults.legacyPasswordForBridge }),
                { check(Looper.myLooper() != Looper.getMainLooper()) { "MQTT credentials require a worker thread" } })
        }
    }
}

internal interface MqttPasswordSlot {
    fun read(): SecretStore.ReadResult
    fun write(value: String): SecretStore.Status
}
internal interface MqttLegacy {
    fun readPassword(): String?
    fun defaultPassword(): String
    fun phase(): String?
    fun mark(phase: String?): Boolean
    fun removeIfUnchanged(expected: String?): Boolean
    fun prepareConfiguration(): MqttCredentialAccess.Configuration
    fun saveConfiguration(configuration: MqttCredentialAccess.Configuration): Boolean
}
internal class MqttLegacyFailure(val status: MqttCredentialAccess.Status) : RuntimeException("MQTT settings unavailable")

internal class PreferenceMqttLegacy(
    private val preferences: () -> SharedPreferences,
    private val hostDefault: () -> String,
    private val usernameDefault: () -> String,
    private val passwordDefault: () -> String
) : MqttLegacy {
    private fun <T> read(action: () -> T): T = try { action() }
        catch (_: ClassCastException) { throw MqttLegacyFailure(MqttCredentialAccess.Status.LEGACY_ERROR) }
        catch (_: SecurityException) { throw MqttLegacyFailure(MqttCredentialAccess.Status.LEGACY_ERROR) }
    override fun readPassword(): String? = read {
        val prefs = preferences()
        if (prefs.contains("mqtt_password")) prefs.getString("mqtt_password", null)
            ?: throw MqttLegacyFailure(MqttCredentialAccess.Status.LEGACY_ERROR) else null
    }
    override fun defaultPassword() = passwordDefault()
    override fun phase(): String? = read { preferences().getString("mqtt_password_transfer_phase", null) }
    override fun mark(phase: String?): Boolean = try {
        preferences().edit().putString("mqtt_password_transfer_phase", phase).commit()
    } catch (_: SecurityException) { false }
    override fun removeIfUnchanged(expected: String?): Boolean = try {
        val prefs = preferences()
        if (prefs.getString("mqtt_password", null) != expected) false else
            prefs.edit().remove("mqtt_password").remove("mqtt_password_transfer_phase").commit() && !prefs.contains("mqtt_password")
    } catch (_: ClassCastException) { false
    } catch (_: SecurityException) { false }

    override fun prepareConfiguration(): MqttCredentialAccess.Configuration = read {
        val prefs = preferences()
        val missingHost = !prefs.contains("mqtt_host")
        val missingPort = !prefs.contains("mqtt_port")
        val missingUsername = !prefs.contains("mqtt_username")
        val host = if (missingHost) hostDefault() else prefs.getString("mqtt_host", null)
            ?: throw MqttLegacyFailure(MqttCredentialAccess.Status.LEGACY_ERROR)
        val port = prefs.getInt("mqtt_port", 1883)
        val username = if (missingUsername) usernameDefault() else prefs.getString("mqtt_username", null)
            ?: throw MqttLegacyFailure(MqttCredentialAccess.Status.LEGACY_ERROR)
        val editor = prefs.edit()
        if (missingHost) editor.putString("mqtt_host", host)
        if (missingPort) editor.putInt("mqtt_port", port)
        if (missingUsername) editor.putString("mqtt_username", username)
        // Подтверждаем и retry после commit=false, даже если значения уже видны в памяти.
        if (!editor.commit() || prefs.getString("mqtt_host", null) != host ||
            prefs.getInt("mqtt_port", 1883) != port || prefs.getString("mqtt_username", null) != username) {
            throw MqttLegacyFailure(MqttCredentialAccess.Status.CONFIG_WRITE_FAILED)
        }
        MqttCredentialAccess.Configuration(host, port, username)
    }
    override fun saveConfiguration(configuration: MqttCredentialAccess.Configuration): Boolean = try {
        val prefs = preferences()
        val editor = prefs.edit()
        if (prefs.getString("mqtt_host", null) != configuration.host) editor.putString("mqtt_host", configuration.host)
        if (prefs.getInt("mqtt_port", 1883) != configuration.port) editor.putInt("mqtt_port", configuration.port)
        if (prefs.getString("mqtt_username", null) != configuration.username) editor.putString("mqtt_username", configuration.username)
        editor.commit() && prefs.getString("mqtt_host", null) == configuration.host &&
            prefs.getInt("mqtt_port", 1883) == configuration.port && prefs.getString("mqtt_username", null) == configuration.username
    } catch (_: ClassCastException) { false
    } catch (_: SecurityException) { false }
}
