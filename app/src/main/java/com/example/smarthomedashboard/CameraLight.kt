package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Прожектор камеры (docs/camera-tz.md, часть 3, решения владельца 20.11.2026): кнопка в раскрытой плитке «Камера».
 * Если в редакторе выбрана сущность HA (`light`/`switch` прожектора из интеграции камеры) — через HA, состояние — из HA.
 * Иначе — у Reolink напрямую (`GetWhiteLed`/`SetWhiteLed`, пароль плитки, тот же доверенный сертификат, что у
 * вебхука); у модели без прожектора кнопки нет. Результаты — на главном потоке.
 */
internal object CameraLight {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-light").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private const val TAG = "CameraLight"

    /** Reolink-драйвер камеры (в тестах — подмена). */
    internal var driver: (Context, String, DoorbellConfig, String) -> ReolinkDriver? = { context, tileId, config, password ->
        val host = config.host.ifBlank { DirectEvents.cameraHost(config).orEmpty() }
        if (host.isBlank() || config.rtspUser.isBlank()) null else {
            val prefs = context.getSharedPreferences(DirectEvents.PREFS, Context.MODE_PRIVATE)
            ReolinkDriver("https://$host", config.rtspUser, password,
                ReolinkDriver.client(host, prefs.getString("$tileId.light_pin", null)) { pin -> prefs.edit().putString("$tileId.light_pin", pin).apply() })
        }
    }

    /** Состояние прожектора: из HA, если сущность выбрана, иначе с камеры. null — прожектора нет / не узнали. */
    fun query(context: Context, tileId: String, config: DoorbellConfig, result: (Boolean?) -> Unit) {
        if (config.light.isNotBlank()) { result(haState(config)); return }
        if (!looksReolink(config)) { result(null); return }
        val app = context.applicationContext
        CameraPasswords.read(app, tileId) { password ->
            if (password.isNullOrEmpty()) { result(null); return@read }
            io.execute {
                val state = withDriver(app, tileId, config, password) { it.floodlight() }
                main.post { result(state) }
            }
        }
    }

    /** Включить/выключить; [done] — новое состояние (null — не получилось). */
    fun set(context: Context, tileId: String, config: DoorbellConfig, on: Boolean, done: (Boolean?) -> Unit) {
        if (config.light.isNotBlank()) {
            val sent = DoorbellHub.actions?.call(config.light.substringBefore('.'), if (on) "turn_on" else "turn_off", config.light, emptyMap()) { r ->
                done(if (r == ServiceCommandResult.ACKNOWLEDGED) on else null)
            } == true
            if (!sent) done(null)
            return
        }
        val app = context.applicationContext
        CameraPasswords.read(app, tileId) { password ->
            if (password.isNullOrEmpty()) { done(null); return@read }
            io.execute {
                val state = withDriver(app, tileId, config, password) { it.setFloodlight(on); it.floodlight() }
                Log.i(TAG, if (state == on) "Прожектор ${if (on) "включён" else "выключен"}" else "Прожектор не переключился")
                main.post { done(state) }
            }
        }
    }

    /** Состояние сущности HA прожектора. */
    fun haState(config: DoorbellConfig): Boolean? =
        (DoorbellHub.readings?.of(config.light) as? BatteryReading.Value)?.raw?.let { it == "on" }

    /** Reolink: по модели из ONVIF или по пути потока (`h264Preview_01_…`, `Preview_01_…`). */
    fun looksReolink(config: DoorbellConfig): Boolean =
        config.model.contains("reolink", ignoreCase = true) || listOf(config.rtspMain, config.rtspSub).any { "Preview_0" in it }

    private fun <T> withDriver(context: Context, tileId: String, config: DoorbellConfig, password: String, block: (ReolinkDriver) -> T?): T? {
        val d = driver(context, tileId, config, password) ?: return null
        return try { block(d) } catch (e: Exception) {
            Log.w(TAG, "Прожектор: ${e.javaClass.simpleName}")
            null
        } finally { d.close() }
    }
}
