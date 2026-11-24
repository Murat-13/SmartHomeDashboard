package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Профиль планшета (docs/diagnostics-tz.md, часть 2): опыт показа видео, который переживает перезапуск приложения.
 *
 * - Основной поток, который не принял ни один декодер ([DoorbellVideoView.undecodable]), и тот, что идёт только
 *   программным декодером ([DoorbellVideoView.hardwareRejected]). Раньше после каждого запуска HD сначала падал и только
 *   потом переходил на субпоток или на программный декодер.
 * - Основной поток, который программный декодер не успевает показывать (решение владельца 3): в раскрытой плитке и окне
 *   звонка — субпоток. «Не успевает» — два показа подряд выброшено от 20 % кадров или показано меньше 80 % частоты
 *   камеры. Остановки сюда не входят: их чаще даёт сеть, а не декодер, — HD пропадал бы из-за сбоя Wi-Fi.
 * - Неровность прихода кадров каждого потока: с ней плеер стартует после запуска приложения, а не набирает запас
 *   остановками в первые минуты.
 *
 * Опыт сбрасывается при смене прошивки Android или версии приложения, при смене размера потока у камеры, через
 * [TTL_MS] (поток проверяется заново) и кнопкой «Забыть опыт». Адреса камер не хранятся — только их хэш.
 */
internal object TabletProfile {
    /** Что запомнено о потоке камеры. */
    data class Record(val undecodable: Boolean = false, val software: Boolean = false, val heavy: Boolean = false,
                      val atMs: Long = 0, val width: Int = 0, val height: Int = 0)

    private const val PREFS = "tablet_profile"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val DECODER = "decoder."
    private const val JITTER = "jitter."
    /** Через неделю поток проверяется заново: камеру могли перенастроить, а размер мы видим не всегда. */
    internal const val TTL_MS = 7L * 24 * 3600 * 1000
    private const val HEAVY_STRIKES = 2
    private const val MIN_SHOW_SECONDS = 10f
    private const val MAX_DROPPED = 0.2f
    private const val MIN_FPS_SHARE = 0.8

    @Volatile private var prefs: SharedPreferences? = null
    private val records = ConcurrentHashMap<String, Record>()
    private val jitter = ConcurrentHashMap<String, Long>()
    private val strikes = ConcurrentHashMap<String, Int>()
    internal var now: () -> Long = System::currentTimeMillis

    internal fun key(url: String) = Integer.toHexString(url.trim().hashCode())

    /** Отпечаток: прошивка Android и версия приложения — сменились, опыт уже не про этот планшет. */
    internal var fingerprint: (Context) -> String = { context ->
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) { 0L }
        "${Build.FINGERPRINT}|$version"
    }

    /** Загрузить опыт (запуск приложения). Отпечаток сменился — опыт стирается. */
    @Synchronized
    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        records.clear(); jitter.clear(); strikes.clear()
        val print = fingerprint(context)
        if (p.getString(KEY_FINGERPRINT, null) != print) {
            if (p.all.isNotEmpty()) Log.i(TAG, "Android или приложение обновились — опыт показа видео забыт")
            p.edit(commit = false) { clear(); putString(KEY_FINGERPRINT, print) }
            return
        }
        for ((k, v) in p.all) {
            when {
                k.startsWith(DECODER) && v is String -> parse(v)?.takeIf { now() - it.atMs < TTL_MS }?.let { records[k.removePrefix(DECODER)] = it }
                k.startsWith(JITTER) && v is Long -> jitter[k.removePrefix(JITTER)] = v
            }
        }
    }

    /** Забыть всё («Забыть опыт» на экране «Планшет»). Главный поток: наборы плеера — его. */
    @Synchronized
    fun forget() {
        records.clear(); jitter.clear(); strikes.clear()
        DoorbellVideoView.undecodable.clear()
        DoorbellVideoView.hardwareRejected.clear()
        prefs?.let { p -> val print = p.getString(KEY_FINGERPRINT, null); p.edit { clear(); print?.let { putString(KEY_FINGERPRINT, it) } } }
    }

    fun record(url: String): Record? = records[key(url)]
    fun undecodable(url: String) = record(url)?.undecodable == true
    fun software(url: String) = record(url)?.software == true
    fun heavy(url: String) = record(url)?.heavy == true

    fun markUndecodable(url: String, width: Int = 0, height: Int = 0) = update(url, width, height) { it.copy(undecodable = true) }
    fun markSoftware(url: String, width: Int = 0, height: Int = 0) = update(url, width, height) { it.copy(software = true) }

    /**
     * Показ основного потока [url] закончился. Программный декодер дважды подряд не успел — поток «тяжёлый»: дальше
     * раскрытая плитка показывает субпоток. true — так решено сейчас.
     */
    fun mainShown(url: String, show: ShowSummary, cameraFps: Double?): Boolean {
        if (!show.software || show.seconds < MIN_SHOW_SECONDS || heavy(url)) return false
        val slow = show.droppedShare >= MAX_DROPPED || (cameraFps != null && cameraFps > 0 && show.shownFps < cameraFps * MIN_FPS_SHARE)
        val k = key(url)
        if (!slow) { strikes.remove(k); return false }
        val n = (strikes[k] ?: 0) + 1
        strikes[k] = n
        if (n < HEAVY_STRIKES) return false
        strikes.remove(k)
        update(url, show.width, show.height) { it.copy(heavy = true) }
        Log.i(TAG, "Основной поток ${show.width}×${show.height}: программный декодер не успевает (показано " +
            "${String.format(java.util.Locale.US, "%.1f", show.shownFps)} к/с, выброшено ${(show.droppedShare * 100).toInt()} %) — в раскрытой плитке будет субпоток")
        return true
    }

    /**
     * Камера отдаёт поток [url] размером [width]×[height]: запомненное про другой размер (камеру перенастроили) — забыть.
     */
    fun cameraSize(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val k = key(url)
        val r = records[k] ?: return
        if (r.width > 0 && (r.width != width || r.height != height)) {
            records.remove(k)
            prefs?.edit { remove(DECODER + k) }
            // Наборы плеера — на главном потоке (этот вызов — из потока камеры).
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                DoorbellVideoView.undecodable.remove(url.trim())
                DoorbellVideoView.hardwareRejected.remove(url.trim())
            }
            Log.i(TAG, "Камера сменила размер потока (${r.width}×${r.height} → $width×$height) — опыт показа этого потока забыт")
        }
    }

    /** Неровность прихода потока [url] (обычная за последние минуты) — запомнить для следующего запуска. */
    fun rememberJitter(url: String, ms: Long) {
        if (ms <= 0) return
        val k = key(url)
        if (jitter[k] == ms) return
        jitter[k] = ms
        prefs?.edit { putLong(JITTER + k, ms) }
    }

    /** Запомненная неровность потока [url], мс; 0 — не знаем. */
    fun jitter(url: String): Long = jitter[key(url)] ?: 0

    private fun update(url: String, width: Int, height: Int, change: (Record) -> Record) {
        val k = key(url)
        val old = records[k] ?: Record()
        val next = change(old).copy(atMs = now(), width = width.takeIf { it > 0 } ?: old.width, height = height.takeIf { it > 0 } ?: old.height)
        records[k] = next
        prefs?.edit { putString(DECODER + k, toJson(next)) }
    }

    private fun toJson(r: Record) = JSONObject().put("undecodable", r.undecodable).put("software", r.software).put("heavy", r.heavy)
        .put("at", r.atMs).put("w", r.width).put("h", r.height).toString()

    private fun parse(text: String): Record? = try {
        val j = JSONObject(text)
        Record(j.optBoolean("undecodable"), j.optBoolean("software"), j.optBoolean("heavy"), j.optLong("at"), j.optInt("w"), j.optInt("h"))
    } catch (_: Exception) { null }

    private const val TAG = "TabletProfile"
}
