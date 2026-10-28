package com.example.smarthomedashboard

import android.os.Looper
import android.util.SparseArray
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Программные Views/реальные методы; JCA с тестовым key provider, HTTP перехвачен до сети.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherConsumerIntegrationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("dashboard_prefs", 0)
    private val access by lazy { weatherKeyTestAccess(app) }
    private fun set(target: Any, name: String, value: Any?) = ReflectionHelpers.setField(target, name, value)
    private fun <T> get(target: Any, name: String): T = ReflectionHelpers.getField(target, name)
    private fun settings(keyAccess: WeatherKeyAccess = access): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).get().also { activity ->
            weatherLive(activity)
            set(activity, "haTokens", haTokenTestAccess(activity))
            listOf("etHaLocalHost", "etHaRemoteHost", "etHaToken",
                "etAdminPin", "etPinSessionMinutes", "etScreenTimeout").forEach {
                set(activity, it, EditText(activity))
            }
            set(activity, "tvHaTokenStatus", TextView(activity))
            set(activity, "btnSave", Button(activity))
            set(activity, "rgTempUnit", RadioGroup(activity))
            set(activity, "spinnerTheme", Spinner(activity).apply { adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_item, listOf("system", "light", "dark")) })
            set(activity, "cbKioskMode", CheckBox(activity))
            weatherInvoke(activity, "prepareTokenInput")
            allowSettingsTestAccess(activity)
            attachWeatherSettingsFields(activity, keyAccess)
        }
    private fun seed(key: String = "FAKE-WEATHER") {
        prefs.edit().putString("yandex_api_key", key).putString("yandex_lat", "10.0")
            .putString("yandex_lon", "20.0").putBoolean("use_yandex_weather", true).commit()
    }
    private fun callback(updated: () -> Unit = {}, error: (String) -> Unit = {}) =
        object : YandexWeatherManager.WeatherCallback {
            override fun onWeatherUpdated() = updated()
            override fun onError(message: String) = error(message)
        }
    private fun response(request: Request, temperature: Int = 12): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test")
        .body(("""{"fact":{"temp":""" + temperature + ""","condition":"clear","humidity":40,"pressure_mm":740,"wind_speed":3,"wind_dir":"n"},"forecasts":[{"date":"2030-01-01","parts":{"day":{"temp_avg":13,"condition":"clear"},"night":{"temp_avg":4}},"hours":[{"hour":"8","temp":11,"condition":"clear"}]}]}""")
            .toResponseBody("application/json".toMediaType())).build()
    private fun manager(keyAccess: WeatherKeyAccess = access, intercept: (Request) -> Response): YandexWeatherManager =
        YandexWeatherManager(app).also {
            set(it, "weatherKeys", keyAccess)
            set(it, "client", OkHttpClient.Builder().addInterceptor { chain -> intercept(chain.request()) }.build())
        }
    private fun failingAccess(status: SecretStore.Status): WeatherKeyAccess = WeatherKeyAccess(object : WeatherKeySlot {
        override fun read() = SecretStore.ReadResult(status)
        override fun write(value: String): SecretStore.Status = error("Recovery is forbidden")
    }, PreferenceWeatherLegacy({ prefs }, { "10.0" }, { "20.0" }), {
        check(Looper.myLooper() != Looper.getMainLooper())
    })

    @Test fun savedKeyIsNotPrefilledOrSavedInViewStateAndMigratesExactly() {
        seed("  FAKE-Юникод  ")
        val activity = settings()
        val input = get<EditText>(activity, "etYandexApiKey")
        assertEquals("", input.text.toString())
        assertEquals("  FAKE-Юникод  ", weatherWorker { access.read() }.value)
        assertFalse(prefs.contains("yandex_api_key"))
        input.id = R.id.etYandexApiKey
        input.setText("FAKE-UNSAVED")
        val state = SparseArray<android.os.Parcelable>()
        input.saveHierarchyState(state)
        assertEquals(0, state.size())
        val status = get<TextView>(activity, "tvYandexKeyStatus").text.toString()
        assertFalse(status.contains("FAKE-"))
    }
    @Test fun blankSaveKeepsKeyAndExplicitReplacementUsesSecureWriter() {
        seed()
        val first = settings()
        weatherInvoke(first, "saveCurrentSettings")
        weatherAwait { first.isFinishing }
        assertEquals("FAKE-WEATHER", weatherWorker { access.read() }.value)
        val next = settings()
        get<EditText>(next, "etYandexApiKey").setText("FAKE-NEW")
        weatherInvoke(next, "saveCurrentSettings")
        weatherAwait { next.isFinishing }
        assertEquals("FAKE-NEW", weatherWorker { access.read() }.value)
        assertFalse(prefs.contains("yandex_api_key"))
        assertEquals("", get<EditText>(next, "etYandexApiKey").text.toString())
        val export = weatherInvoke(next, "buildExportConfig") as org.json.JSONObject
        assertFalse(export.has("yandex_api_key"))
        assertFalse(export.has("yandex_key_transfer_phase"))
        assertFalse(export.toString().contains("FAKE-NEW"))
    }
    @Test fun cancelNewInputDoesNotReplaceKeyOrSettings() {
        seed()
        val activity = settings()
        val before = prefs.all
        get<EditText>(activity, "etYandexApiKey").setText("FAKE-CANCELLED")
        activity.finish()
        assertEquals("FAKE-WEATHER", weatherWorker { access.read() }.value)
        assertEquals(before, prefs.all)
    }
    @Test fun savingOtherSettingsCannotAssignMissingWeatherKey() {
        prefs.edit().putString("unknown_field", "kept").putString("yandex_last_temp", "FAKE-CACHE").commit()
        val activity = settings()
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { activity.isFinishing }
        assertEquals(WeatherKeyAccess.Status.ABSENT, weatherWorker { access.read() }.status)
        assertFalse(prefs.contains("yandex_api_key"))
        assertEquals("kept", prefs.getString("unknown_field", null))
        assertEquals("FAKE-CACHE", prefs.getString("yandex_last_temp", null))
    }
    @Test fun secureErrorDoesNotPrefillFallbackOrPermitRecoveryReplacement() {
        seed()
        val activity = settings(failingAccess(SecretStore.Status.CORRUPTED))
        get<EditText>(activity, "etYandexApiKey").setText("FAKE-ATTEMPT")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { get<Button>(activity, "btnSave").isEnabled }
        assertFalse(activity.isFinishing)
        assertEquals("FAKE-WEATHER", prefs.getString("yandex_api_key", null))
        assertFalse(get<TextView>(activity, "tvYandexKeyStatus").text.contains("FAKE-"))
        assertEquals("10.0", prefs.getString("yandex_lat", null))
    }
    @Test fun staleSettingsFormCannotOverwriteFreshReplacementOrConfiguration() {
        seed()
        val activity = settings()
        val current = weatherWorker { access.read() }
        weatherWorker { access.save("FAKE-FRESH", WeatherKeyAccess.Configuration("30.0", "40.0", false), current.revision) }
        weatherInvoke(activity, "loadWeatherConfiguration")
        weatherAwait { !get<Boolean>(activity, "weatherLoading") }
        get<EditText>(activity, "etYandexApiKey").setText("FAKE-STALE")
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { get<Button>(activity, "btnSave").isEnabled }
        assertFalse(activity.isFinishing)
        val latest = weatherWorker { access.read() }
        assertEquals("FAKE-FRESH", latest.value)
        assertEquals("30.0", latest.configuration!!.latitude)
        assertFalse(latest.configuration.enabled)
    }
    @Test fun realRequestReceivesSecureKeyAndSnapshotAndUpdatesExistingCacheWithoutExtraRequest() {
        seed()
        val requests = CopyOnWriteArrayList<Request>()
        val manager = manager { request -> requests.add(request); response(request) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var updated = 0
        try {
            manager.fetchWeather(scope, callback(updated = { updated++ }))
            weatherAwait { updated == 1 }
            assertEquals(1, requests.size)
            assertEquals("FAKE-WEATHER", requests.single().header("X-Yandex-API-Key"))
            assertEquals("10.0", requests.single().url.queryParameter("lat"))
            assertEquals("20.0", requests.single().url.queryParameter("lon"))
            assertFalse(prefs.contains("yandex_api_key"))
            assertEquals("12", manager.currentTemp)
            assertEquals("13", manager.forecasts.single().tempDay)
            assertEquals("11", manager.hourlyForecasts.single().temp)
            assertEquals("12", prefs.getString("yandex_last_temp", null))
        } finally { scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun disabledWeatherBridgesLocallyWithoutAnyNetworkOrCacheChange() {
        prefs.edit().putString("yandex_last_temp", "FAKE-CACHE").putBoolean("use_yandex_weather", false).commit()
        val manager = manager { error("No HTTP for disabled source") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            manager.fetchWeather(scope, callback(updated = { fail() }, error = { fail() }))
            weatherAwait { scope.coroutineContext[Job]!!.children.none() }
            assertEquals("10.0", prefs.getString("yandex_lat", null))
            assertEquals("20.0", prefs.getString("yandex_lon", null))
            assertEquals("FAKE-CACHE", prefs.getString("yandex_last_temp", null))
        } finally { scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun explicitEmptySecureKeyDoesNotRequestOrRevertToDefault() {
        seed("")
        val manager = manager { error("No HTTP for an empty key") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var errorMessage: String? = null
        try {
            manager.fetchWeather(scope, callback(error = { errorMessage = it }))
            weatherAwait { errorMessage != null }
            assertEquals("", weatherWorker { access.read() }.value)
            assertFalse(prefs.contains("yandex_api_key"))
            assertTrue(prefs.getBoolean("use_yandex_weather", false))
        } finally { scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun cancelWhileKeyLoadsNeverStartsHttpOrPublishesAnything() {
        seed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val delayed = WeatherKeyAccess(object : WeatherKeySlot {
            override fun read(): SecretStore.ReadResult {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return SecretStore.ReadResult(SecretStore.Status.SUCCESS, "FAKE-SECURE")
            }
            override fun write(value: String): SecretStore.Status = error("Unexpected write")
        }, PreferenceWeatherLegacy({ prefs }, { "10.0" }, { "20.0" }), {
            check(Looper.myLooper() != Looper.getMainLooper())
        })
        val manager = manager(delayed) { error("Cancelled read must not request") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            manager.fetchWeather(scope, callback(updated = { fail() }, error = { fail() }))
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.cancelPendingRequests()
            release.countDown()
            weatherAwait { scope.coroutineContext[Job]!!.children.none() }
            assertFalse(prefs.contains("yandex_last_temp"))
        } finally { release.countDown(); scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun changedCoordinatesWhileHttpIsPendingDiscardOldResponse() {
        seed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var callbacks = 0
        val manager = manager { request ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(request)
        }
        try {
            manager.fetchWeather(scope, callback(updated = { callbacks++ }, error = { callbacks++ }))
            weatherAwait { entered.count == 0L }
            prefs.edit().putString("yandex_lon", "40.0").commit()
            release.countDown()
            weatherAwait { get<Call?>(manager, "activeCall")?.isExecuted() == true &&
                scope.coroutineContext[Job]!!.children.none() }
            // OkHttp завершается независимо от main looper; дождёмся его dispatcher.
            weatherAwait { get<OkHttpClient>(manager, "client").dispatcher.runningCallsCount() == 0 }
            assertEquals(0, callbacks)
            assertFalse(prefs.contains("yandex_last_temp"))
        } finally { release.countDown(); scope.cancel(); manager.cancelPendingRequests() }
    }

    @Test fun unrelatedSavePreservesExactExistingCoordinatesAndMissingSourceFlag() {
        prefs.edit().putString("yandex_api_key", "FAKE-WEATHER").putString("yandex_lat", "  10.0  ")
            .putString("yandex_lon", "20.0 ").commit()
        val activity = settings()
        weatherInvoke(activity, "saveCurrentSettings")
        weatherAwait { activity.isFinishing }
        assertEquals("  10.0  ", prefs.getString("yandex_lat", null))
        assertEquals("20.0 ", prefs.getString("yandex_lon", null))
        assertFalse(prefs.contains("use_yandex_weather"))
    }
    @Test fun invalidHeaderKeyProducesSafeErrorWithoutHttpOrUncaughtException() {
        seed("FAKE\nINVALID")
        var failure: Throwable? = null
        var status: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, throwable -> failure = throwable })
        val manager = manager { error("Invalid header must not request") }
        try {
            manager.fetchWeather(scope, callback(error = { status = it }))
            weatherAwait { scope.coroutineContext[Job]!!.children.none() }
            assertNull(failure)
            assertNotNull(status)
            assertFalse(status!!.contains("FAKE"))
            assertEquals("FAKE\nINVALID", weatherWorker { access.read() }.value)
        } finally { scope.cancel(); manager.cancelPendingRequests() }
    }


    @Test fun supersededResponseCannotOverwriteTheLatestRequest() {
        seed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var updates = 0
        val manager = manager { request ->
            val number = requests.incrementAndGet()
            if (number == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            response(request, if (number == 1) 11 else 22)
        }
        try {
            manager.fetchWeather(scope, callback(updated = { updates++ }))
            weatherAwait { entered.count == 0L }
            manager.fetchWeather(scope, callback(updated = { updates++ }))
            weatherAwait { updates == 1 && manager.currentTemp == "22" }
            release.countDown()
            weatherAwait { get<OkHttpClient>(manager, "client").dispatcher.runningCallsCount() == 0 }
            weatherAwait { scope.coroutineContext[Job]!!.children.none() }
            assertEquals(2, requests.get())
            assertEquals(1, updates)
            assertEquals("22", manager.currentTemp)
            assertEquals("22", prefs.getString("yandex_last_temp", null))
        } finally { release.countDown(); scope.cancel(); manager.cancelPendingRequests() }
    }

    @Test fun restoringLegacyWindowStateDoesNotRestoreAFormerlyPrefilledKey() {
        val old = settings()
        val oldInput = get<EditText>(old, "etYandexApiKey")
        oldInput.id = R.id.etYandexApiKey
        oldInput.isSaveEnabled = true
        oldInput.isSaveFromParentEnabled = true
        oldInput.setText("FAKE-LEGACY-VIEW-STATE")
        old.window.setContentView(FrameLayout(old).apply { addView(oldInput) })
        val windowState = old.window.saveHierarchyState()
        val control = settings()
        val controlInput = get<EditText>(control, "etYandexApiKey")
        controlInput.id = R.id.etYandexApiKey
        controlInput.isSaveEnabled = true
        controlInput.isSaveFromParentEnabled = true
        control.window.setContentView(FrameLayout(control).apply { addView(controlInput) })
        val controlState = android.os.Bundle().apply { putBundle("android:viewHierarchyState", windowState) }
        android.app.Activity::class.java.getDeclaredMethod("onRestoreInstanceState", android.os.Bundle::class.java)
            .apply { isAccessible = true }.invoke(control, controlState)
        assertEquals("FAKE-LEGACY-VIEW-STATE", controlInput.text.toString())
        val current = settings()
        val input = get<EditText>(current, "etYandexApiKey")
        input.id = R.id.etYandexApiKey
        current.window.setContentView(FrameLayout(current).apply { addView(input) })
        val state = android.os.Bundle().apply { putBundle("android:viewHierarchyState", windowState) }
        android.app.Activity::class.java.getDeclaredMethod("onRestoreInstanceState", android.os.Bundle::class.java)
            .apply { isAccessible = true }.invoke(current, state)
        assertEquals("", input.text.toString())
    }

    @Test fun finishedSettingsIgnoresLateWeatherConfiguration() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val delayed = WeatherKeyAccess(object : WeatherKeySlot {
            override fun read(): SecretStore.ReadResult {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return SecretStore.ReadResult(SecretStore.Status.SUCCESS, "FAKE-SECURE")
            }
            override fun write(value: String): SecretStore.Status = error("Unexpected write")
        }, PreferenceWeatherLegacy({ prefs }, { "10.0" }, { "20.0" }), {
            check(Looper.myLooper() != Looper.getMainLooper())
        })
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        weatherLive(activity)
        set(activity, "weatherKeys", delayed)
        listOf("etYandexApiKey", "etYandexLat", "etYandexLon").forEach { set(activity, it, EditText(activity)) }
        set(activity, "cbUseYandexWeather", CheckBox(activity))
        set(activity, "tvYandexKeyStatus", TextView(activity))
        weatherInvoke(activity, "prepareWeatherInput")
        weatherInvoke(activity, "loadWeatherConfiguration")
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        activity.finish()
        release.countDown()
        weatherAwait { activity.lifecycleScope.coroutineContext[Job]!!.children.none() }
        assertEquals("", get<EditText>(activity, "etYandexLat").text.toString())
        assertEquals("", get<EditText>(activity, "etYandexApiKey").text.toString())
        assertEquals("Чтение настроек Яндекса...", get<TextView>(activity, "tvYandexKeyStatus").text.toString())
    }

    @Test fun destroyedScopeAndCancelledHttpCannotMutateCache() {
        seed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val manager = manager { request ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(request)
        }
        try {
            manager.fetchWeather(scope, callback(updated = { fail() }, error = { fail() }))
            weatherAwait { entered.count == 0L }
            scope.cancel(); manager.cancelPendingRequests()
            release.countDown()
            weatherAwait { get<OkHttpClient>(manager, "client").dispatcher.runningCallsCount() == 0 }
            assertFalse(prefs.contains("yandex_last_temp"))
        } finally { release.countDown(); scope.cancel(); manager.cancelPendingRequests() }
    }
    @Test fun sessionDurationBelowFiveMinutesCannotBeSaved() {
        val activity=settings()
        val input=get<EditText>(activity,"etPinSessionMinutes")
        val before=prefs.all.toMap()
        for(value in listOf("0","4","-1","")) {
            input.setText(value)
            weatherInvoke(activity,"saveCurrentSettings")
            assertNotNull(input.error)
            assertEquals(before,prefs.all)
            assertFalse(activity.isFinishing)
        }
    }
}
