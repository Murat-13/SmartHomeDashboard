package com.example.smarthomedashboard.deep

import android.app.Activity
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smarthomedashboard.BatteryModernConfig
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.HaTokenAccess
import com.example.smarthomedashboard.HomeClimateConfig
import com.example.smarthomedashboard.InverterModernConfig
import com.example.smarthomedashboard.PowerModernConfig
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val TAG = "DeepTest"

internal fun log(message: String) { Log.i(TAG, message) }

internal val instrumentation get() = InstrumentationRegistry.getInstrumentation()
internal val appContext: Context get() = instrumentation.targetContext
internal fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

/**
 * Эмулятор, а не настоящий планшет: тесты стирают настройки и плитки приложения. На физическом устройстве (например,
 * если кто-то запустит connectedDebugAndroidTest на стене) тест прерывается до любого изменения данных.
 */
internal fun isEmulator(): Boolean {
    val b = android.os.Build.FINGERPRINT + " " + android.os.Build.HARDWARE + " " + android.os.Build.PRODUCT + " " + android.os.Build.MODEL
    return listOf("generic", "emulator", "ranchu", "goldfish", "sdk_gphone", "sdk_gtablet", "google_sdk", "Android SDK built for")
        .any { b.contains(it, ignoreCase = true) }
}

/** Чистое состояние приложения: только искусственные настройки, плитки и токен фальшивого HA. */
internal fun prepareApp(ha: FakeHomeAssistant, tiles: List<TileEntity>) {
    check(isEmulator()) { "Сквозные тесты стирают данные приложения и запускаются только на эмуляторе" }
    appContext.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).edit().clear()
        .putBoolean("setup_completed", true).putBoolean("starter_tiles_done", true).putBoolean("welcome_shown", true)
        .putString("ha_local_host", ha.address).putString("ha_remote_host", "")
        .putInt("screen_timeout_minutes", 0).putString("motion_sensor_id", "")
        .putBoolean("use_yandex_weather", false).putBoolean("kiosk_mode", false)
        .commit()
    appContext.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    check(TileManager(appContext).saveTiles(tiles))
    val result = HaTokenAccess.create(appContext).replace(ha.token)
    check(result.status == HaTokenAccess.Status.READY) { "Токен не записан: ${result.status}" }
}

/** Плитки всех типов с искусственными сущностями; [doorbell] — настройки видео домофона. */
internal fun allTiles(doorbell: DoorbellConfig = DoorbellConfig(camera = "camera.deep_door",
    visitor = "binary_sensor.deep_visitor", lock = "lock.deep_gate", light = "light.deep_porch")): List<TileEntity> {
    fun size(w: Int, h: Int) = JSONObject().put("button_size", w).put("widget_height", h)
    fun fill(config: String, prefix: String): String {
        var n = 0
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    val v = value.opt(key)
                    if (v is String && v.isEmpty() && key.contains("entity")) value.put(key, "sensor.${prefix}_${n++}") else walk(v)
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        return JSONObject(config).also(::walk).toString()
    }
    val sensor = size(300, 220).put("collapsed_sensor_configs", JSONArray().put(JSONObject().put("entity_id", "sensor.deep_temp")
        .put("display_name", "Температура").put("decimals", 1))).put("expanded_sensor_configs", JSONArray()
        .put(JSONObject().put("entity_id", "sensor.deep_hum").put("display_name", "Влажность")))
    return listOf(
        TileEntity("deep_sensor", "sensor", "grid", "Датчик", 20, 120, 1, 1, config = sensor.toString()),
        TileEntity("deep_battery", "battery", "grid", "Батарея", 340, 120, 1, 1, config = size(260, 220).put("entity_id", "sensor.deep_soc").toString()),
        TileEntity("deep_weather", "weather", "grid", "Погода", 620, 120, 1, 1, config = size(300, 220).put("entity_id", "weather.deep_home").toString()),
        TileEntity("deep_bms", BatteryModernConfig.TYPE, "grid", "АКБ", 20, 360, 1, 1,
            config = fill(BatteryModernConfig.newTileConfig(), "deep_bms")),
        TileEntity("deep_grid", PowerModernConfig.TYPE, "grid", "Сеть", 560, 360, 1, 1,
            config = fill(PowerModernConfig.newTileConfig(PowerModernConfig.PROFILE_GRID), "deep_grid")),
        TileEntity("deep_inverter", InverterModernConfig.TYPE, "grid", "Инвертор", 880, 360, 1, 1,
            config = fill(InverterModernConfig.newTileConfig(), "deep_inv")),
        TileEntity("deep_climate", HomeClimateConfig.TYPE, "grid", "Климат", 960, 120, 1, 1,
            config = HomeClimateConfig(weather = HomeClimateConfig.Weather(entityId = "weather.deep_home"),
                rooms = listOf(HomeClimateConfig.Room("r1", "Гостиная", null, "sensor.deep_temp", "sensor.deep_hum"))).writeTo("{}")),
        TileEntity("deep_door", DoorbellConfig.TYPE, "grid", "Калитка", 1300, 120, 1, 1,
            config = doorbell.writeTo(DoorbellConfig.newTileConfig(size(480, 360).toString()))),
        TileEntity("deep_light", "button", "bottom_panel", "Свет", 20, 800, 1, 1,
            config = JSONObject().put("entity_id", "light.deep_kitchen").put("button_style", "modern").toString()),
        TileEntity("deep_group", "group", "bottom_panel", "Двор", 200, 800, 1, 1,
            config = JSONObject().put("entity_ids", JSONArray(listOf("light.deep_yard1", "light.deep_yard2", "switch.deep_pump")))
                .put("child_names", JSONArray(listOf("Фонарь", "Прожектор", "Насос"))).put("button_style", "modern").toString())
    )
}

/** Начальные значения: все сущности, на которые ссылаются плитки. */
internal fun seedStates(ha: FakeHomeAssistant, tiles: List<TileEntity>): List<String> {
    val ids = tiles.flatMap { Regex("""\b(?:sensor|binary_sensor|light|switch|weather|camera|lock|select)\.[a-z0-9_]+""").findAll(it.config).map { m -> m.value }.toList() }.distinct()
    ids.forEach { id ->
        when (id.substringBefore('.')) {
            "light", "switch" -> ha.set(id, "off")
            "binary_sensor" -> ha.set(id, "off")
            "lock" -> ha.set(id, "locked")
            "camera" -> ha.set(id, "idle", JSONObject().put("entity_picture", "/api/camera_proxy/$id?token=x"))
            "weather" -> ha.set(id, "cloudy", JSONObject().put("temperature", 6).put("humidity", 80).put("wind_speed", 3))
            else -> ha.set(id, "42", JSONObject().put("unit_of_measurement", "W"))
        }
    }
    ha.set("sun.sun", "below_horizon")
    return ids
}

internal fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
    val until = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        check(SystemClock.uptimeMillis() < until) { "Не дождались: $what" }
        Thread.sleep(50)
    }
}

internal fun <T> onMain(block: () -> T): T {
    var value: Result<T>? = null
    instrumentation.runOnMainSync { value = runCatching(block) }
    return value!!.getOrThrow()
}

/** Касание центра view через систему ввода (как пальцем). */
internal fun tap(activity: Activity, view: View) {
    val location = IntArray(2)
    onMain { view.getLocationOnScreen(location) }
    val x = location[0] + view.width / 2f
    val y = location[1] + view.height / 2f
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0))
}

/**
 * Задержки главного потока по кадрам Choreographer: разрыв между соседними кадрами — сколько главный поток не мог
 * нарисовать кадр (подвисание). Именно это владелец видит как «лаги» на стене.
 */
internal class StallMonitor : Choreographer.FrameCallback {
    @Volatile private var running = false
    private var last = 0L
    // Гистограмма по 10 мс до 5 с: память постоянна и в многочасовом прогоне (не искажает замер кучи).
    private val buckets = LongArray(501)
    private var frames = 0L
    private var max = 0L

    fun start() = onMain {
        running = true; last = 0L; frames = 0; max = 0; buckets.fill(0)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun snapshot(): Summary = onMain { Summary(frames, max, buckets.clone()) }

    fun stop(): Summary = onMain {
        running = false
        Summary(frames, max, buckets.clone())
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last != 0L) {
            val gap = (frameTimeNanos - last) / 1_000_000
            frames++
            if (gap > max) max = gap
            buckets[(gap / 10).toInt().coerceIn(0, buckets.size - 1)]++
        }
        last = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    class Summary(val frames: Long, val max: Long, private val buckets: LongArray) {
        fun over(ms: Long): Long = (((ms / 10) + 1).toInt() until buckets.size).sumOf { buckets[it] }
        fun percentile(p: Double): Long {
            val target = (frames * p).toLong()
            var seen = 0L
            buckets.forEachIndexed { i, n -> seen += n; if (seen >= target) return i * 10L }
            return max
        }
        override fun toString() = "кадров $frames, макс. разрыв $max мс, p99 ≈${percentile(0.99)} мс, >100 мс: ${over(100)}, >500 мс: ${over(500)}"
    }
}

/** Снимок ресурсов процесса после сборки мусора: куча Java, native, потоки, файловые дескрипторы. */
internal data class Resources(val javaKb: Long, val nativeKb: Long, val threads: Int, val fds: Int) {
    companion object {
        fun sample(): Resources {
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100) }
            val rt = Runtime.getRuntime()
            return Resources((rt.totalMemory() - rt.freeMemory()) / 1024, Debug.getNativeHeapAllocatedSize() / 1024,
                Thread.getAllStackTraces().size, File("/proc/self/fd").list()?.size ?: -1)
        }
    }
    override fun toString() = "java ${javaKb / 1024} МБ, native ${nativeKb / 1024} МБ, потоков $threads, fd $fds"
}

/** Ждёт пустой очереди главного потока; дольше [timeoutMs] — подвисание. */
internal fun mainThreadResponds(timeoutMs: Long): Boolean {
    val latch = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post { latch.countDown() }
    return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
