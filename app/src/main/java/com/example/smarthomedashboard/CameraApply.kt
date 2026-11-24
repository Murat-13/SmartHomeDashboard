package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Применить» совет диагностики к камере (docs/diagnostics-tz.md, этап 4). По решению владельца — выключено, пока не
 * включат выключателем в разделе «Диагностика видео». Камера Reolink — их API (`SetEnc`), остальные — ONVIF
 * (`SetVideoEncoderConfiguration`). После записи значение читается снова; прежние значения хранятся неделю — для
 * «Вернуть как было». Во время звонка, разговора и записи события не применяется: камера перезапускает поток.
 */
internal object CameraApply {
    class Outcome(val ok: Boolean, val text: String)

    /** Что уже изменено и как вернуть: путь (Reolink — ключ, ONVIF — путь в настройках) → прежнее значение. */
    data class Saved(val atMs: Long, val reolink: Boolean, val main: Boolean, val before: Map<String, String>, val label: String)

    private const val SETTINGS = "tablet_settings"
    private const val KEY_ENABLED = "camera_apply"
    private const val PREFS = "camera_diagnostics"
    internal const val KEEP_MS = 7L * 24 * 3600 * 1000
    internal var now: () -> Long = System::currentTimeMillis

    fun enabled(context: Context) = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }

    /** Сейчас нельзя: у калитки звонок или разговор, или пишется ролик события. null — можно. */
    fun blocked(tileId: String): String? = RecordingEvents.of(tileId)?.takeIf { it.isNotEmpty() }
        ?.let { "Сейчас идёт звонок, разговор или запись события — камера перезапустит поток. Подождите, пока закончится" }

    /** Где менять: Reolink (их API) или ONVIF; что именно — пути и значения для [change]. */
    private fun edits(reolink: Boolean, change: CameraDiagnostics.Change, current: Map<String, String> = emptyMap()): Map<String, String> =
        when (change.field) {
            CameraDiagnostics.Change.Field.FPS -> if (reolink) mapOf("frameRate" to change.to) else mapOf("RateControl/FrameRateLimit" to change.to)
            // У Reolink `gop` — во сколько раз реже частоты (1–4); бывает и в кадрах: тогда — частота.
            CameraDiagnostics.Change.Field.GOP -> if (reolink) mapOf("gop" to (if ((current["gop"]?.toIntOrNull() ?: 1) <= REOLINK_GOP_MULTIPLIER_MAX) "1" else change.to))
                else mapOf("H264/GovLength" to change.to)
            CameraDiagnostics.Change.Field.SIZE -> {
                val (w, h) = change.to.split('x').map { it.toInt() }
                if (reolink) mapOf("size" to "$w*$h") else mapOf("Resolution/Width" to "$w", "Resolution/Height" to "$h")
            }
            CameraDiagnostics.Change.Field.CODEC -> if (reolink) mapOf("vType" to "h264") else mapOf("Encoding" to "H264")
            CameraDiagnostics.Change.Field.BITRATE -> if (reolink) mapOf("bitRate" to change.to) else mapOf("RateControl/BitrateLimit" to change.to)
        }
    private const val REOLINK_GOP_MULTIPLIER_MAX = 4

    /** Применить [change] к камере плитки [tileId] (блокирующее, рабочий поток). */
    fun apply(context: Context, tileId: String, config: DoorbellConfig, password: String, change: CameraDiagnostics.Change): Outcome {
        blocked(tileId)?.let { return Outcome(false, it) }
        val reolink = CameraLight.looksReolink(config)
        return try {
            val before = write(context, tileId, config, password, reolink, change.main) { current -> edits(reolink, change, current) }
            remember(context, config, Saved(now(), reolink, change.main, before, change.label))
            Log.i(TAG, "Настройка камеры: ${change.label} — камера приняла")
            Outcome(true, "Камера приняла изменение (${change.label}). Поток перезапустится — проверю заново через 3–4 минуты.")
        } catch (e: Onvif.NotApplied) {
            Log.w(TAG, "Настройка камеры: ${change.label} — ${e.message}")
            Outcome(false, "${e.message}. Больше ничего не трогаю.")
        } catch (e: Exception) {
            Log.w(TAG, "Настройка камеры: ${change.label} — отказ (${e.javaClass.simpleName})")
            Outcome(false, "Камера не приняла изменение (${(e.message ?: e.javaClass.simpleName).replaceFirstChar { it.lowercase() }}). " +
                "Больше ничего не трогаю.")
        }
    }

    /** «Вернуть как было»: прежние значения, в обратном порядке. */
    fun rollback(context: Context, tileId: String, config: DoorbellConfig, password: String): Outcome {
        blocked(tileId)?.let { return Outcome(false, it) }
        val saved = saved(context, config).ifEmpty { return Outcome(false, "Возвращать нечего") }
        return try {
            for (s in saved.reversed()) write(context, tileId, config, password, s.reolink, s.main) { s.before }
            forget(context, config)
            Log.i(TAG, "Настройки камеры возвращены: ${saved.joinToString { it.label }}")
            Outcome(true, "Вернул как было. Поток перезапустится — проверю заново через 3–4 минуты.")
        } catch (e: Exception) {
            Log.w(TAG, "Возврат настроек камеры — отказ (${e.javaClass.simpleName})")
            Outcome(false, "Камера не приняла возврат (${(e.message ?: e.javaClass.simpleName).replaceFirstChar { it.lowercase() }})")
        }
    }

    /** Записать [edits] (по текущим значениям) в нужный поток; возвращает прежние значения. */
    private fun write(context: Context, tileId: String, config: DoorbellConfig, password: String, reolink: Boolean, main: Boolean,
                      edits: (Map<String, String>) -> Map<String, String>): Map<String, String> {
        if (reolink) {
            val driver = CameraLight.driver(context, tileId, config, password) ?: throw CameraException("Не указан адрес или логин камеры")
            val stream = if (main) "mainStream" else "subStream"
            // Текущие значения нужны для `gop`: у Reolink это множитель частоты или кадры — выбираем по нынешнему.
            return driver.changeEnc(stream, edits(driver.currentEnc(stream)))
        }
        val target = CameraDiagnosis.target(config, password, tileId)
        val info = Onvif.probe(target.host, config.onvifPort, config.rtspUser.ifBlank { "admin" }, password)
        val stream = (if (main) info.main else info.sub) ?: throw Onvif.OnvifException("Камера не сообщила этот поток")
        if (stream.config.isBlank()) throw Onvif.OnvifException("Камера не сообщила настройки кодировщика")
        return Onvif.changeEncoder(target.host, info.port, config.rtspUser.ifBlank { "admin" }, password, stream.config, edits(emptyMap()))
    }

    private fun key(config: DoorbellConfig) = "applied_" + Integer.toHexString(
        DoorbellConfig.playableRtsp(config.rtspMain).trim().ifBlank { DoorbellConfig.playableRtsp(config.rtspSub).trim() }.hashCode())

    /** Изменения этой камеры за последнюю неделю, по порядку. */
    fun saved(context: Context, config: DoorbellConfig): List<Saved> = try {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(config), null)
        val array = text?.let { JSONArray(it) } ?: JSONArray()
        (0 until array.length()).map { array.getJSONObject(it) }.map { j ->
            val before = j.getJSONObject("before")
            Saved(j.getLong("at"), j.getBoolean("reolink"), j.getBoolean("main"), before.keys().asSequence().associateWith { before.getString(it) },
                j.getString("label"))
        }.filter { now() - it.atMs < KEEP_MS }
    } catch (_: Exception) { emptyList() }

    private fun remember(context: Context, config: DoorbellConfig, s: Saved) {
        val all = saved(context, config) + s
        val array = JSONArray()
        all.forEach { a -> array.put(JSONObject().put("at", a.atMs).put("reolink", a.reolink).put("main", a.main).put("label", a.label)
            .put("before", JSONObject().apply { a.before.forEach { (k, v) -> put(k, v) } })) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(config), array.toString()) }
    }

    private fun forget(context: Context, config: DoorbellConfig) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(key(config)) }

    private const val TAG = "CameraApply"
}
