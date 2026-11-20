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
 *
 * Измерение эхоподавления (docs/talk-tz.md, «Одновременный разговор»):
 * - `talk_debug/probe_echo` — дальний контур: в домофон трижды уходит речь из `speech.pcm`, принятый звук чистится
 *   тем же [DuplexEcho], что в разговоре; записи `sent_ref` (что ушло), `street_rx` (что вернулось) и `street_aec`
 *   (после очистки) показывают, насколько наш вернувшийся голос стал тише и проходит ли голос человека у домофона;
 * - `talk_debug/probe_near` — ближний контур, без домофона: та же речь играет из динамика планшета (как звук улицы в
 *   разговоре), микрофон чистится; записи `street` (что сыграно), `mic` и `mic_aec`.
 */
internal object TalkProbe {
    private const val TAG = "Talk"
    @Volatile private var running = false

    /** Проверить, просили ли измерение; [tiles] читаются, только если просили. */
    fun maybeRun(context: Context, tiles: () -> List<TileEntity>) {
        val dir = context.getExternalFilesDir(null)?.let { File(it, "talk_debug") } ?: return
        val asked = TRIGGERS.firstOrNull { File(dir, it).exists() } ?: return
        if (running) return
        for (name in TRIGGERS) File(dir, name).delete()
        val app = context.applicationContext
        if (asked == "probe_near") {
            running = true
            Thread({ try { near(app, dir) } finally { running = false } }, "talk-probe").apply { isDaemon = true; start() }
            return
        }
        val config = tiles().firstOrNull { it.type == DoorbellConfig.TYPE }?.let { DoorbellConfig.from(it.config) } ?: return
        if (config.talkRtsp.isBlank()) return
        running = true
        DoorbellHub.cameraPassword(app) { password ->
            Thread({
                try { run(app, dir, config.talkRtsp, config.rtspUser, password, asked == "probe_onvif", asked == "probe_echo") }
                finally { running = false }
            }, "talk-probe").apply { isDaemon = true; start() }
        }
    }

    private val TRIGGERS = listOf("probe", "probe_onvif", "probe_echo", "probe_near")

    /**
     * Ближний контур: речь из динамика планшета (тем же путём, что звук улицы в разговоре, в «разговорном» режиме
     * звука) → микрофон → эхоподавление. Домофон не участвует.
     */
    private fun near(context: Context, dir: File) {
        val log = StringBuilder()
        fun note(text: String) {
            Log.i(TAG, "Измерение: $text")
            log.append(System.currentTimeMillis()).append(' ').append(text).append('\n')
            runCatching { File(dir, "probe.log").writeText(log.toString()) }
        }
        File(dir, "probe.done").delete()
        val speech = runCatching { pcm(File(dir, "speech.pcm")) }.getOrNull()
        if (speech == null) { note("нет speech.pcm — играть нечего"); runCatching { File(dir, "probe.done").writeText("no speech") }; return }
        TalkDebug.begin(context)
        val rate = DuplexEcho.RATE
        val duplex = DuplexEcho().apply { active = true; near.log = { note(it) } }
        val mic = MicCapture({ _, _ -> }, { }).apply { echo = duplex }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val mode = audio?.mode
        var track: android.media.AudioTrack? = null
        try {
            runCatching { audio?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }
            val t = StreetAudio().newTrack(rate)
            track = t
            t.play()
            mic.start(context, duplex = false, wideband = true)
            val signal = echoSignal(rate, speech)
            note("ближний контур: ${signal.size * 1000L / rate} мс речи из динамика планшета")
            val frame = rate / 50
            val buf = ShortArray(frame)
            var i = 0
            while (i < signal.size) {
                val count = minOf(frame, signal.size - i)
                signal.copyInto(buf, 0, i, i + count)
                if (count < frame) buf.fill(0, count, frame)
                duplex.played.write(buf, frame)
                TalkDebug.write("street", buf, frame, rate)
                if (t.write(buf, 0, frame) <= 0) Thread.sleep(20)
                i += count
            }
            Thread.sleep(1_000)
            note(duplex.near.summary())
        } catch (e: Exception) {
            note("ошибка: ${e.javaClass.simpleName}")
        } finally {
            mic.stop()
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { if (mode != null) audio?.mode = mode }
            Thread.sleep(300)
            TalkDebug.end()
            runCatching { File(dir, "probe.done").writeText("ok") }
        }
    }

    private fun run(context: Context, dir: File, rtsp: String, user: String, password: String?, onvif: Boolean, echo: Boolean = false) {
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
        // Дальний контур эхоподавления — как в разговоре (только у канала Reolink, 16 кГц): чистит принятый звук.
        val duplex = if (onvif) null else DuplexEcho().apply { far.log = { note(it) } }
        street.duplex(duplex)
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
            if (duplex != null) opened.tap { pcm, n -> duplex.sent.write(pcm, n); TalkDebug.write("sent_ref", pcm, n, DuplexEcho.RATE) }
            val rate = if (opened.wideband) 16_000 else 8_000
            mic.start(context, duplex = false, wideband = true)
            Thread.sleep(SETTLE_MS) // очередь домофона устоялась, как в обычном разговоре
            val speech = runCatching { pcm(File(dir, "speech.pcm")) }.getOrNull()
            val signal = if (echo && speech != null) echoSignal(rate, speech) else signal(rate, speech)
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
            if (duplex != null) note(duplex.far.summary())
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

    /**
     * Сигнал для измерения эхоподавления: речь [speech] (16 кГц) три раза подряд с паузами. Первый проход — контур
     * находит сдвиг и учится, по второму и третьему меряется подавление (на третьем у домофона может говорить человек).
     */
    internal fun echoSignal(rate: Int, speech: ShortArray): ShortArray {
        val step = 16_000 / rate
        val once = ShortArray(speech.size / step) { speech[it * step] }
        val gap = rate * ECHO_GAP_MS / 1000
        val out = ShortArray(rate + ECHO_PASSES * (once.size + gap))
        var at = rate
        repeat(ECHO_PASSES) { once.copyInto(out, at); at += once.size + gap }
        return out
    }

    const val ECHO_PASSES = 3
    const val ECHO_GAP_MS = 1_500
    val LEVELS_DB = listOf(-36, -30, -24, -18, -12, -6, -3)
    val FREQS = listOf(300.0, 500.0, 2_000.0, 3_000.0)
    const val TONE_MS = 800
    const val GAP_MS = 400
    const val SWEEP_MS = 4_000
    private const val SETTLE_MS = 6_000L
    private const val TAIL_MS = 3_000L
}
