package com.example.smarthomedashboard

import org.robolectric.util.ReflectionHelpers

import android.content.Context
import android.os.Looper
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// Настоящий JCA AES-GCM; выдача ключа/файл подменены. Это не AndroidKeyStore.
internal fun weatherTestStore(checkWorker: () -> Unit = {}): SecretStore = SecretStore(
    object : SecretContainerFile {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun replace(ciphertext: ByteArray) { bytes = ciphertext.clone() }
    },
    object : SecretKeyProvider {
        private var key: SecretKey? = null
        override fun existing() = key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey().also { key = it }
    }, checkWorker)

internal fun weatherKeyTestAccess(context: Context, store: SecretStore = weatherTestStore {
    check(Looper.myLooper() != Looper.getMainLooper())
}): WeatherKeyAccess = WeatherKeyAccess(object : WeatherKeySlot {
    override fun read() = store.read(SecretStore.Slot.WEATHER_KEY)
    override fun write(value: String) = store.write(SecretStore.Slot.WEATHER_KEY, value)
}, PreferenceWeatherLegacy({ context.getSharedPreferences("dashboard_prefs", 0) }, { "10.0" }, { "20.0" }),
    { check(Looper.myLooper() != Looper.getMainLooper()) })

internal fun weatherAwait(condition: () -> Boolean) {
    val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
    while (!condition() && System.nanoTime() < until) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.yield()
    }
    org.junit.Assert.assertTrue("Async condition timed out", condition())
}

internal fun weatherLive(activity: androidx.appcompat.app.AppCompatActivity) {
    activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    androidx.activity.ComponentActivity::class.java.declaredFields.single {
        it.type == androidx.savedstate.SavedStateRegistryController::class.java
    }.apply {
        isAccessible = true
        (get(activity) as androidx.savedstate.SavedStateRegistryController).performRestore(null)
    }
    androidx.fragment.app.FragmentActivity::class.java.declaredFields.single {
        it.type == androidx.fragment.app.FragmentController::class.java
    }.apply {
        isAccessible = true
        (get(activity) as androidx.fragment.app.FragmentController).attachHost(null)
    }
    (activity.lifecycle as androidx.lifecycle.LifecycleRegistry)
        .handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
}

internal fun weatherInvoke(target: Any, name: String, vararg arguments: Any?): Any? = try {
    target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(target, *arguments)
} catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }

internal fun attachWeatherSettingsFields(activity: SettingsActivity, access: WeatherKeyAccess = weatherKeyTestAccess(activity)) {
    val set = { name: String, value: Any -> org.robolectric.util.ReflectionHelpers.setField(activity, name, value) }
    set("weatherKeys", access)
    listOf("etYandexApiKey", "etYandexLat", "etYandexLon").forEach { set(it, android.widget.EditText(activity)) }
    set("cbUseYandexWeather", android.widget.CheckBox(activity))
    set("tvYandexKeyStatus", android.widget.TextView(activity))
    weatherInvoke(activity, "prepareWeatherInput")
    weatherInvoke(activity, "loadWeatherConfiguration")
    weatherAwait { !ReflectionHelpers.getField<Boolean>(activity, "weatherLoading") }
}

internal fun <T> weatherWorker(action: () -> T): T {
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    return try { pool.submit(java.util.concurrent.Callable { action() }).get(5, java.util.concurrent.TimeUnit.SECONDS) }
    finally { pool.shutdownNow() }
}
