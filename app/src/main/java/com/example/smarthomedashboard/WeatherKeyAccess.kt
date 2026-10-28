package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** Только WEATHER_KEY. Все операции prefs/Keystore выполняются на worker под общим lock этого назначения. */
internal class WeatherKeyAccess(
    private val slot: WeatherKeySlot,
    private val legacy: WeatherLegacy,
    private val checkWorker: () -> Unit
) {
    enum class Status { READY, ABSENT, SECURE_ERROR, WRITE_FAILED, VERIFY_FAILED, CLEANUP_FAILED, LEGACY_ERROR, CONFIG_WRITE_FAILED, STALE }
    class Configuration(val latitude: String, val longitude: String, val enabled: Boolean) {
        override fun toString() = "WeatherConfiguration"
    }
    class Result internal constructor(
        val status: Status, val value: String? = null, val configuration: Configuration? = null,
        val secureStatus: SecretStore.Status? = null, val revision: Long = WeatherKeyAccess.revision
    ) {
        val usable: Boolean get() = status == Status.READY || status == Status.ABSENT
        override fun toString() = "WeatherKeyResult(status=$status, secureStatus=$secureStatus)"
        val message: String get() = when (status) {
            Status.READY -> if (value.isNullOrEmpty()) "Ключ Яндекса сохранён пустым" else "Ключ Яндекса сохранён"
            Status.ABSENT -> "Ключ Яндекса не настроен"
            Status.SECURE_ERROR -> "Защищённый ключ Яндекса недоступен. Автоматическое восстановление не реализовано"
            Status.WRITE_FAILED -> "Не удалось сохранить ключ Яндекса"
            Status.VERIFY_FAILED -> "Не удалось проверить ключ Яндекса. Повторите явный ввод в настройках"
            Status.CLEANUP_FAILED -> "Не удалось подтвердить удаление открытой копии ключа Яндекса"
            Status.LEGACY_ERROR -> "Не удалось прочитать прежние настройки Яндекса"
            Status.CONFIG_WRITE_FAILED -> "Не удалось подтвердить сохранение координат Яндекса"
            Status.STALE -> "Настройки Яндекса изменились. Откройте экран повторно"
        }
    }

    fun read(): Result = serialized {
        val key = readKey()
        // Координаты не являются fallback для секрета. Их bridge независим от ошибки ключа,
        // чтобы экран мог показать реальные параметры, не назначая другой ключ.
        val configuration = legacy.prepareConfiguration()
        Result(key.status, key.value, configuration, key.secureStatus)
    }

    fun save(newKey: String?, configuration: Configuration, expectedRevision: Long): Result = serialized {
        if (expectedRevision != revision) return@serialized Result(Status.STALE)
        val key = if (newKey == null) readKey() else {
            val current = slot.read()
            if (current.status != SecretStore.Status.SUCCESS && current.status != SecretStore.Status.ABSENT) {
                return@serialized Result(Status.SECURE_ERROR, secureStatus = current.status)
            }
            legacy.readKey() // До замены проверяем доступность единственной legacy-копии.
            storeAndVerify(newKey, "replacement")
        }
        if (!key.usable) return@serialized key
        if (!legacy.saveConfiguration(configuration)) return@serialized Result(Status.CONFIG_WRITE_FAILED)
        REVISION.incrementAndGet()
        Result(key.status, key.value, configuration, key.secureStatus)
    }

    private fun readKey(): Result {
        val stored = slot.read()
        return when (stored.status) {
            SecretStore.Status.SUCCESS -> {
                val value = stored.value ?: return Result(Status.VERIFY_FAILED)
                when (legacy.phase()) {
                    "migration" -> {
                        if (legacy.readKey() != value) return Result(Status.VERIFY_FAILED)
                        if (!legacy.mark("verified")) return Result(Status.CLEANUP_FAILED)
                    }
                    "replacement" -> return Result(Status.VERIFY_FAILED)
                    null, "verified" -> Unit
                    else -> return Result(Status.VERIFY_FAILED)
                }
                finish(value)
            }
            SecretStore.Status.ABSENT -> {
                val value = legacy.readKey()
                // Пустой legacy — отдельное успешное значение; отсутствующий ключ не получает UI-default.
                if (value == null) Result(Status.ABSENT) else storeAndVerify(value, "migration")
            }
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
        val current = legacy.readKey()
        // Повторный commit нужен и после commit=false, уже изменившего карту prefs в памяти.
        if (!legacy.removeIfUnchanged(current)) return Result(Status.CLEANUP_FAILED)
        return Result(Status.READY, value)
    }
    private fun serialized(action: () -> Result): Result {
        checkWorker()
        return synchronized(LOCK) {
            try { action() } catch (failure: WeatherLegacyFailure) { Result(failure.status) }
        }
    }

    companion object {
        private val LOCK = Any()
        private val REVISION = AtomicLong()
        val revision: Long get() = REVISION.get()
        fun isCurrent(result: Result) = result.revision == revision
        fun create(context: Context): WeatherKeyAccess {
            val app = context.applicationContext
            val store = AndroidSecretStore.create(app)
            return WeatherKeyAccess(object : WeatherKeySlot {
                override fun read() = store.read(SecretStore.Slot.WEATHER_KEY)
                override fun write(value: String) = store.write(SecretStore.Slot.WEATHER_KEY, value)
            }, PreferenceWeatherLegacy(
                { app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE) },
                { LegacyWeatherCoordinateDefaults.latitude() }, { LegacyWeatherCoordinateDefaults.longitude() }
            ), { check(Looper.myLooper() != Looper.getMainLooper()) { "Weather key requires a worker thread" } })
        }
    }
}

internal interface WeatherKeySlot {
    fun read(): SecretStore.ReadResult
    fun write(value: String): SecretStore.Status
}
internal interface WeatherLegacy {
    fun readKey(): String?
    fun phase(): String?
    fun mark(phase: String?): Boolean
    fun removeIfUnchanged(expected: String?): Boolean
    fun prepareConfiguration(): WeatherKeyAccess.Configuration
    fun saveConfiguration(configuration: WeatherKeyAccess.Configuration): Boolean
}
internal class WeatherLegacyFailure(val status: WeatherKeyAccess.Status) : RuntimeException("Weather settings unavailable")

internal class PreferenceWeatherLegacy(
    private val preferences: () -> SharedPreferences,
    private val latitudeDefault: () -> String,
    private val longitudeDefault: () -> String
) : WeatherLegacy {
    private fun <T> read(action: () -> T): T = try { action() }
        catch (_: ClassCastException) { throw WeatherLegacyFailure(WeatherKeyAccess.Status.LEGACY_ERROR) }
        catch (_: SecurityException) { throw WeatherLegacyFailure(WeatherKeyAccess.Status.LEGACY_ERROR) }
    override fun readKey(): String? = read { preferences().getString("yandex_api_key", null) }
    override fun phase(): String? = read { preferences().getString("yandex_key_transfer_phase", null) }
    override fun mark(phase: String?): Boolean = try {
        preferences().edit().putString("yandex_key_transfer_phase", phase).commit()
    } catch (_: SecurityException) { false }
    override fun removeIfUnchanged(expected: String?): Boolean = try {
        val prefs = preferences()
        if (prefs.getString("yandex_api_key", null) != expected) false else
            prefs.edit().remove("yandex_api_key").remove("yandex_key_transfer_phase").commit() && !prefs.contains("yandex_api_key")
    } catch (_: ClassCastException) { false
    } catch (_: SecurityException) { false }

    override fun prepareConfiguration(): WeatherKeyAccess.Configuration = read {
        val prefs = preferences()
        val missingLat = !prefs.contains("yandex_lat")
        val missingLon = !prefs.contains("yandex_lon")
        val lat = if (missingLat) latitudeDefault() else prefs.getString("yandex_lat", null)
            ?: throw WeatherLegacyFailure(WeatherKeyAccess.Status.LEGACY_ERROR)
        val lon = if (missingLon) longitudeDefault() else prefs.getString("yandex_lon", null)
            ?: throw WeatherLegacyFailure(WeatherKeyAccess.Status.LEGACY_ERROR)
        val enabled = prefs.getBoolean("use_yandex_weather", false)
        val edit = prefs.edit()
        if (missingLat) edit.putString("yandex_lat", lat)
        if (missingLon) edit.putString("yandex_lon", lon)
        // Даже без новых значений commit подтверждает повтор после commit=false:
        // значения могли появиться в памяти prefs, не став подтверждённой записью на диске.
        if (!edit.commit() || prefs.getString("yandex_lat", null) != lat || prefs.getString("yandex_lon", null) != lon) {
            throw WeatherLegacyFailure(WeatherKeyAccess.Status.CONFIG_WRITE_FAILED)
        }
        WeatherKeyAccess.Configuration(lat, lon, enabled)
    }
    override fun saveConfiguration(configuration: WeatherKeyAccess.Configuration): Boolean = try {
        val prefs = preferences()
        val edit = prefs.edit()
        if (prefs.getString("yandex_lat", null) != configuration.latitude) edit.putString("yandex_lat", configuration.latitude)
        if (prefs.getString("yandex_lon", null) != configuration.longitude) edit.putString("yandex_lon", configuration.longitude)
        if (prefs.getBoolean("use_yandex_weather", false) != configuration.enabled) edit.putBoolean("use_yandex_weather", configuration.enabled)
        edit.commit() && prefs.getString("yandex_lat", null) == configuration.latitude &&
            prefs.getString("yandex_lon", null) == configuration.longitude &&
            prefs.getBoolean("use_yandex_weather", false) == configuration.enabled
    } catch (_: ClassCastException) { false
    } catch (_: SecurityException) { false }
}

/** Только закрытый legacy bridge S4b: в S4e эти личные defaults должны быть удалены из бинарника. */
internal object LegacyWeatherCoordinateDefaults {
    fun latitude(): String = ""
    fun longitude(): String = ""
}
