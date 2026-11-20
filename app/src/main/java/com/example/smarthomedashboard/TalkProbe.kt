package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import com.example.smarthomedashboard.data.TileEntity
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Измерительный стенд разговора (docs/talk-tz.md, «Измерение тракта»): планшет сам отправляет в домофон калиброванные
 * сигналы (тоны 1 кГц разной громкости, тоны разных частот, скользящий тон, речь из файла) и записывает микрофоном
 * домофона, что реально играет его динамик. По записям считаются искажения, уровень и задержка возврата — вместо
 * оценки «на слух».
 *
 * Запускается только вручную через adb: файл `talk_debug/probe` (протокол Reolink) или `talk_debug/probe_onvif`
 * (обратный канал ONVIF) рядом с `talk_debug/on` ([TalkDebug]); без них ничего не делает. Динамик планшета молчит,
 * записи остаются на планшете в той же папке. Речь — из `talk_debug/speech.pcm` (16 кГц, 16 бит), если положен.
 */
internal object TalkProbe {
    private const val TAG = "Talk"
    @Volatile private var running = false

    /** Проверить, просили ли измерение; [tiles] читаются, только если просили. */
    fun maybeRun(context: Context, tiles: () -> List<TileEntity>) {
        val dir = context.getExternalFilesDir(null)?.let { File(it, "talk_debug") } ?: return
        val reolink = File(dir, "probe")
        val onvif = File(dir, "probe_onvif")
        val useOnvif = when { reolink.exists() -> false; onvif.exists() -> true; else -> return }
        if (running) return
        reolink.delete(); onvif.delete()
        val config = tiles().firstOrNull { it.type == DoorbellConfig.TYPE }?.let { DoorbellConfig.from(it.config) } ?: return
        if (config.talkRtsp.isBlank()) return
        running = true
        val app = context.applicationContext
        DoorbellHub.cameraPassword(app) { password ->
            Thread({
                try { run(app, dir, config.talkRtsp, config.rtspUser, password, useOnvif) } finally { running = false }
            }, "talk-probe").apply { isDaemon = true; start() }
        }
    }

    private fun run(context: Context, dir: File, rtsp: String, user: String, password: String?, onvif: Boolean) {
        val log = StringBuilder()
        fun note(text: String) {
            Log.i(TAG, "Измерение: $text")
            log.append(System.currentTimeMillis()).append(' ').append(text).append('\n')
            runCatching { File(dir, "probe.log").writeText(log.toString()) }
        }
        File(dir, "probe.done").delete()
        TalkDebug.begin(context)
        val street = StreetAudio().apply { silent = true }
        val mic = MicCapture({ _, _ -> }, { })
        var channel: TalkChannel? = null
        try {
            street.start(rtsp, user, password) { ok -> note("звук домофона принимается: $ok") }
            val opened: TalkChannel = if (onvif) {
                TalkSession(RtspSession(rtsp, user, password, connectTimeoutMs = 4_000, readTimeoutMs = 30_000, require = TalkSession.BACKCHANNEL))
            } else {
                BaichuanTalkSession(runCatching { java.net.URI(rtsp.trim()).host }.getOrNull().orEmpty(), user, password.orEmpty())
            }
            channel = opened
            note("канал: ${if (onvif) "ONVIF" else "Reolink"} (${opened.open()})")
            val rate = if (opened.wideband) 16_000 else 8_000
            mic.start(context, duplex = false, wideband = true)
            Thread.sleep(SETTLE_MS) // очередь домофона устоялась, как в обычном разговоре
            val speech = runCatching { pcm(File(dir, "speech.pcm")) }.getOrNull()
            val signal = signal(rate, speech)
            note("сигнал: ${signal.size * 1000L / rate} мс, $rate Гц, речь: ${speech != null}")
            opened.beginTalkspurt()
            val frame = rate / 50
            val buf = ShortArray(frame)
            val start = System.nanoTime()
            var i = 0
            var n = 0
            while (i < signal.size && opened.alive) {
                val count = minOf(frame, signal.size - i)
                signal.copyInto(buf, 0, i, i + count)
                if (count < frame) buf.fill(0, count, frame)
                TalkDebug.write("sent", buf, frame, rate)
                opened.sendAt(buf, frame, rate)
                i += count
                n++
                val wait = start + n * 20_000_000L - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            Thread.sleep(TAIL_MS)
            note("готово: отправлено $n кадров, канал жив: ${opened.alive}")
        } catch (e: Exception) {
            note("ошибка: ${e.javaClass.simpleName}")
        } finally {
            mic.stop()
            runCatching { channel?.close() }
            street.stop()
            Thread.sleep(500)
            TalkDebug.end()
            runCatching { File(dir, "probe.done").writeText("ok") }
        }
    }

    private fun pcm(file: File): ShortArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
    }

    /**
     * Тестовый сигнал: тишина, тон 1 кГц на уровнях [LEVELS_DB] (от пика шкалы), тоны [FREQS] на −18 дБ, скользящий
     * тон 200 Гц → верх полосы, речь [speech] (16 кГц; для канала 8 кГц прореживается), тишина.
     */
    internal fun signal(rate: Int, speech: ShortArray?): ShortArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(v: Double) { val s = v.toInt().coerceIn(-32_767, 32_767); out.write(s and 0xFF); out.write((s shr 8) and 0xFF) }
        fun silence(ms: Int) = repeat(rate * ms / 1000) { put(0.0) }
        fun fade(i: Int, n: Int): Double {
            val edge = rate / 100
            return when { i < edge -> 0.5 - 0.5 * cos(PI * i / edge); i >= n - edge -> 0.5 - 0.5 * cos(PI * (n - i) / edge); else -> 1.0 }
        }
        fun tone(freq: Double, db: Int) {
            val amp = 32_767.0 * 10.0.pow(db / 20.0)
            val n = rate * TONE_MS / 1000
            for (i in 0 until n) put(amp * fade(i, n) * sin(2 * PI * freq * i / rate))
            silence(GAP_MS)
        }
        silence(1_000)
        for (db in LEVELS_DB) tone(1_000.0, db)
        for (f in FREQS) if (f < rate * 0.42) tone(f, -18)
        val top = minOf(6_000.0, rate * 0.42)
        val n = rate * SWEEP_MS / 1000
        val k = ln(top / 200.0)
        val amp = 32_767.0 * 10.0.pow(-18 / 20.0)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val seconds = SWEEP_MS / 1000.0
            val phase = 2 * PI * 200.0 * seconds / k * (Math.exp(k * t / seconds) - 1)
            put(amp * fade(i, n) * sin(phase))
        }
        silence(500)
        if (speech != null) {
            val step = 16_000 / rate
            var i = 0
            while (i + step <= speech.size) { put(speech[i].toDouble()); i += step }
            silence(500)
        }
        silence(2_000)
        val bytes = out.toByteArray()
        return ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
    }

    val LEVELS_DB = listOf(-36, -30, -24, -18, -12, -6, -3)
    val FREQS = listOf(300.0, 500.0, 2_000.0, 3_000.0)
    const val TONE_MS = 800
    const val GAP_MS = 400
    const val SWEEP_MS = 4_000
    private const val SETTLE_MS = 6_000L
    private const val TAIL_MS = 3_000L
}
