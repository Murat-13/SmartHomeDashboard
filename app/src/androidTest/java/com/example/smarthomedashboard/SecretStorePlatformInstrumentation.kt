package com.example.smarthomedashboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Настоящие AndroidKeyStore и Os.rename. Отдельный runner без AndroidX test-зависимостей.
 * Запускать только на отдельно разрешённой тестовой установке, не на рабочем планшете.
 * Работает исключительно со случайным тестовым alias и собственным тестовым каталогом.
 */
class SecretStorePlatformInstrumentation : Instrumentation() {
    private var operation = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        operation = arguments?.getString("mode").orEmpty()
        start()
    }

    override fun onStart() {
        if (operation.isNotEmpty()) {
            // Один зарегистрированный runner: upgrade-проверки не требуют Activity или второго UID.
            val result = if (operation.startsWith("weather")) WeatherUpgradeChecks.run(targetContext, operation)
            else HaTokenUpgradeChecks.run(targetContext, operation)
            finish(if (result.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        val id = UUID.randomUUID().toString()
        val alias = "smarthomedashboard.s2.instrumentation.$id"
        // Instrumentation выполняется с UID приложения, а context принадлежит test APK.
        // Изолированные тестовые файлы создаём через targetContext в каталоге своего UID.
        val directory = File(targetContext.noBackupFilesDir, "s2-platform-$id")
        val base = File(directory, "container.bin")
        val testPrefs = targetContext.getSharedPreferences("s3-platform-$id", android.content.Context.MODE_PRIVATE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var step = "start"
        var passed: Boolean
        try {
            step = "target-storage-owner"
            check(targetContext.applicationInfo.uid == android.os.Process.myUid())
            check(context.applicationInfo.uid != android.os.Process.myUid())
            check(Looper.myLooper() != Looper.getMainLooper())
            val provider = AndroidSecretKeyProvider(alias)
            fun store() = SecretStore(AtomicSecretContainerFile(location = { base }), provider) {
                check(Looper.myLooper() != Looper.getMainLooper())
            }
            step = "absence"
            check(store().read(SecretStore.Slot.HA_TOKEN).status == SecretStore.Status.ABSENT)
            check(!keyStore.containsAlias(alias))
            step = "real-keystore-roundtrip"
            val firstWrite = store().write(SecretStore.Slot.HA_TOKEN, "FAKE-Юникод-🔐")
            step = "first-write-$firstWrite"
            check(firstWrite == SecretStore.Status.SUCCESS)
            step = "nonexportable-key"
            check(provider.existing()!!.encoded == null)
            step = "first-native-read"
            val first = base.readBytes()
            step = "second-write"
            check(store().write(SecretStore.Slot.HA_TOKEN, "FAKE-Юникод-🔐") == SecretStore.Status.SUCCESS)
            val second = base.readBytes()
            check(!first.copyOfRange(16, 28).contentEquals(second.copyOfRange(16, 28)))
            check(store().read(SecretStore.Slot.HA_TOKEN).value == "FAKE-Юникод-🔐")
            check(store().write(SecretStore.Slot.MQTT_PASSWORD, "") == SecretStore.Status.SUCCESS)
            check(store().read(SecretStore.Slot.MQTT_PASSWORD).value == "")
            step = "delete-single-slot"
            check(store().delete(SecretStore.Slot.MQTT_PASSWORD) == SecretStore.Status.SUCCESS)
            check(store().delete(SecretStore.Slot.MQTT_PASSWORD) == SecretStore.Status.ABSENT)
            check(store().read(SecretStore.Slot.HA_TOKEN).value == "FAKE-Юникод-🔐")
            step = "authenticated-tamper"
            val committed = base.readBytes()
            base.writeBytes(committed.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            check(store().read(SecretStore.Slot.HA_TOKEN).status == SecretStore.Status.CORRUPTED)
            base.writeBytes(committed)
            step = "ha-token-legacy-migration"
            check(store().delete(SecretStore.Slot.HA_TOKEN) == SecretStore.Status.SUCCESS)
            check(testPrefs.edit().putString("ha_token", "FAKE-PLATFORM-LEGACY").commit())
            val tokens = HaTokenAccess(object : HaTokenSlot {
                override fun read() = store().read(SecretStore.Slot.HA_TOKEN)
                override fun write(value: String) = store().write(SecretStore.Slot.HA_TOKEN, value)
            }, PreferenceLegacyHaToken { testPrefs }, {
                check(Looper.myLooper() != Looper.getMainLooper())
            })
            check(tokens.read().value == "FAKE-PLATFORM-LEGACY")
            check(!testPrefs.contains("ha_token"))
            check(!testPrefs.contains("ha_token_transfer_phase"))
            check(testPrefs.edit().putString("ha_token", "FAKE-STALE").commit())
            check(tokens.read().value == "FAKE-PLATFORM-LEGACY")
            check(!testPrefs.contains("ha_token"))
            check(tokens.replace("FAKE-PLATFORM-NEW").status == HaTokenAccess.Status.READY)
            check(tokens.read().value == "FAKE-PLATFORM-NEW")
            step = "weather-key-bridge"
            check(store().write(SecretStore.Slot.MQTT_PASSWORD, "FAKE-UNRELATED-MQTT") == SecretStore.Status.SUCCESS)
            check(testPrefs.edit().putString("yandex_api_key", "  FAKE-WEATHER-LEGACY  ")
                .putString("yandex_lat", "").putBoolean("use_yandex_weather", false)
                .putString("yandex_last_temp", "FAKE-CACHE").commit())
            val weather = WeatherKeyAccess(object : WeatherKeySlot {
                override fun read() = store().read(SecretStore.Slot.WEATHER_KEY)
                override fun write(value: String) = store().write(SecretStore.Slot.WEATHER_KEY, value)
            }, PreferenceWeatherLegacy({ testPrefs }, { "10.0" }, { "20.0" }), {
                check(Looper.myLooper() != Looper.getMainLooper())
            })
            val initialWeather = weather.read()
            check(initialWeather.status == WeatherKeyAccess.Status.READY && initialWeather.value == "  FAKE-WEATHER-LEGACY  ")
            val weatherConfiguration = checkNotNull(initialWeather.configuration)
            check(weatherConfiguration.latitude == "" && weatherConfiguration.longitude == "20.0")
            check(!testPrefs.contains("yandex_api_key") && !testPrefs.contains("yandex_key_transfer_phase"))
            check(weather.save("FAKE-WEATHER-NEW", weatherConfiguration, initialWeather.revision).status == WeatherKeyAccess.Status.READY)
            check(weather.read().value == "FAKE-WEATHER-NEW")
            check(testPrefs.getString("yandex_last_temp", null) == "FAKE-CACHE")
            check(tokens.read().value == "FAKE-PLATFORM-NEW")
            check(store().read(SecretStore.Slot.MQTT_PASSWORD).value == "FAKE-UNRELATED-MQTT")
            step = "weather-missing-and-empty-isolated"
            // Только случайное test storage: общая upgrade-фикстура здесь не используется.
            check(store().delete(SecretStore.Slot.WEATHER_KEY) == SecretStore.Status.SUCCESS)
            check(testPrefs.edit().remove("yandex_api_key").remove("yandex_key_transfer_phase")
                .remove("yandex_lat").remove("yandex_lon").remove("use_yandex_weather").commit())
            val missingWeather = weather.read()
            check(missingWeather.status == WeatherKeyAccess.Status.ABSENT && missingWeather.value == null)
            check(store().read(SecretStore.Slot.WEATHER_KEY).status == SecretStore.Status.ABSENT)
            check(!testPrefs.contains("yandex_api_key") && !testPrefs.contains("use_yandex_weather"))
            val missingConfiguration = checkNotNull(missingWeather.configuration)
            check(missingConfiguration.latitude == "10.0" && missingConfiguration.longitude == "20.0")
            check(!missingConfiguration.enabled)
            check(testPrefs.edit().putString("yandex_api_key", "")
                .putString("yandex_lat", "").putString("yandex_lon", "")
                .putBoolean("use_yandex_weather", false).commit())
            val emptyWeather = weather.read()
            check(emptyWeather.status == WeatherKeyAccess.Status.READY && emptyWeather.value == "")
            val emptyConfiguration = checkNotNull(emptyWeather.configuration)
            check(emptyConfiguration.latitude == "" && emptyConfiguration.longitude == "")
            check(!emptyConfiguration.enabled)
            check(!testPrefs.contains("yandex_api_key") && !testPrefs.contains("yandex_key_transfer_phase"))
            check(store().read(SecretStore.Slot.WEATHER_KEY).value == "")
            check(testPrefs.getString("yandex_last_temp", null) == "FAKE-CACHE")
            check(tokens.read().value == "FAKE-PLATFORM-NEW")
            check(store().read(SecretStore.Slot.MQTT_PASSWORD).value == "FAKE-UNRELATED-MQTT")
            step = "retired-mqtt-removal"
            check(testPrefs.edit().putString("mqtt_password", "FAKE-RETIRED-OPEN")
                .putString("mqtt_password_transfer_phase", "verified")
                .putString("mqtt_host", "mqtt.example.invalid").putInt("mqtt_port", 1883)
                .putString("mqtt_username", "FAKE-RETIRED-USER").commit())
            val removal = MqttRemovalMigration(testPrefs,
                { store().delete(SecretStore.Slot.MQTT_PASSWORD) },
                { check(Looper.myLooper() != Looper.getMainLooper()) })
            check(removal.run())
            check(MqttRemovalMigration.KEYS.none(testPrefs::contains))
            check(store().read(SecretStore.Slot.MQTT_PASSWORD).status == SecretStore.Status.ABSENT)
            check(tokens.read().value == "FAKE-PLATFORM-NEW")
            check(store().read(SecretStore.Slot.WEATHER_KEY).value == "")
            check(testPrefs.getString("yandex_last_temp", null) == "FAKE-CACHE")
            check(removal.run())
            val migrated = base.readBytes()
            step = "lost-test-key"
            keyStore.deleteEntry(alias)
            check(store().read(SecretStore.Slot.HA_TOKEN).status == SecretStore.Status.KEY_UNAVAILABLE)
            check(store().write(SecretStore.Slot.HA_TOKEN, "FAKE-NEW") == SecretStore.Status.KEY_UNAVAILABLE)
            check(!keyStore.containsAlias(alias))
            check(migrated.contentEquals(base.readBytes()))
            check(tokens.read().status == HaTokenAccess.Status.SECURE_ERROR)
            check(!testPrefs.contains("ha_token"))
            check(weather.read().status == WeatherKeyAccess.Status.SECURE_ERROR)
            check(weather.save("FAKE-NO-RECOVERY", emptyConfiguration, WeatherKeyAccess.revision).status == WeatherKeyAccess.Status.SECURE_ERROR)
            check(!keyStore.containsAlias(alias) && migrated.contentEquals(base.readBytes()))
            check(testPrefs.edit().remove(MqttRemovalMigration.COMPLETED)
                .putString("mqtt_password", "FAKE-RETIRED-RETRY").commit())
            check(!removal.run())
            check(!testPrefs.contains("mqtt_password"))
            check(!testPrefs.getBoolean(MqttRemovalMigration.COMPLETED, false))
            check(!keyStore.containsAlias(alias) && migrated.contentEquals(base.readBytes()))
            passed = true
        } catch (_: AssertionError) {
            passed = false // Результат ниже содержит только идентификатор этапа, без проверяемых данных.
        } catch (_: Exception) {
            passed = false // Исключения провайдера не передаём в отчёт instrumentation.
        } finally {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            directory.deleteRecursively()
            testPrefs.edit().clear().commit()
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            if (passed) putString("result", "S2/S3/S4b/S4c platform checks passed") else putString("failed_step", step)
        })
    }
}
