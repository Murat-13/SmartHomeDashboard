package com.example.smarthomedashboard

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.edit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Звук разговора на этом планшете (docs/talk-tz.md, «Самопроверка звука»): что решила проверка. Динамик и микрофон у всех
 * планшетов разные, а своё эхоподавление ([DuplexEcho]) измерено только на одном. Проверки не было — всё как раньше.
 */
internal object TalkTablet {
    enum class Mode {
        /** Своё эхоподавление, микрофон без обработки Android — как без проверки. */
        OWN,
        /** Своё эхоподавление и вместе с ним подавление эха Android (планшет, где одно своё не справляется). */
        SYSTEM,
        /** Говорит кто-то один ([EchoGate]): убрать эхо на этом планшете не вышло. */
        TURNS
    }

    /** [gain] — во сколько раз убавлен звук улицы в динамике планшета (1 — не убавлен). */
    data class Profile(val mode: Mode, val gain: Float, val reductionDb: Int, val delayMs: Int, val voicePercent: Int, val atMs: Long)

    private const val PREFS = "tablet_talk"

    fun profile(context: Context): Profile? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = p.getString("mode", null)?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: return null
        return Profile(mode, p.getFloat("gain", 1f).takeIf { it.isFinite() }?.coerceIn(TalkSelfCheck.MIN_GAIN, 1f) ?: 1f, p.getInt("db", 0), p.getInt("delay", 0),
            p.getInt("voice", 0), p.getLong("at", 0))
    }

    fun save(context: Context, profile: Profile) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putString("mode", profile.mode.name); putFloat("gain", profile.gain); putInt("db", profile.reductionDb)
        putInt("delay", profile.delayMs); putInt("voice", profile.voicePercent); putLong("at", profile.atMs)
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
}

/**
 * Самопроверка: планшет играет своим динамиком похожий на речь сигнал тем же путём, что звук улицы в разговоре, слушает
 * своим микрофоном и считает, насколько своё эхоподавление убирает эхо. Не убирает — пробует тише, затем вместе с
 * подавлением эха Android, затем оставляет разговор по очереди. Домофон не участвует, никуда ничего не отправляется.
 */
internal object TalkSelfCheck {
    private const val TAG = "TalkCheck"
    const val RATE = DuplexEcho.RATE
    /** Тише, чем вчетверо, улицу не убавляем: её не было бы слышно. */
    const val MIN_GAIN = 0.25f
    /** С такого подавления эха разговор хороший: московский планшет, на котором владелец доволен разговором, даёт 26–30 дБ. */
    const val GOOD_DB = 20.0
    private const val LEAD_S = 1.5
    private const val LEARN_S = 4.0
    private const val VOICE_S = 9.0
    private const val TAIL_S = 0.6
    private const val VOICE_RMS = 3_200.0
    /** Шум комнаты громче этого (−30 дБ) — измерение ненадёжно. */
    private const val NOISY_RMS = 1_000.0
    /** Тихая комната (−42 дБ): в ней видно и слабое эхо. */
    private const val QUIET_RMS = 260.0
    private const val CLIP = 32_300

    enum class Verdict { GOOD, GOOD_QUIETER, GOOD_SYSTEM, ECHO, NOISY, DEAF, NARROW, NO_MIC, CANCELLED }

    /** Итог: что решено ([profile] null — решать нечего) и что сказать человеку. */
    class Result(val verdict: Verdict, val profile: TalkTablet.Profile?, val text: String)

    /** Одна попытка: микрофон до и после эхоподавления и сколько опоры было записано к каждому кадру по [FRAME]. */
    class Take(val rate: Int, val pre: ShortArray, val post: ShortArray, val refAt: LongArray, val alignment: Long, val locked: Boolean, val delayMs: Int,
               val linearDb: Double, val peak: Int)

    /** Планшет: сыграть сигнал и записать микрофон. null — микрофон не открылся. В тестах — модель комнаты. */
    interface Rig {
        fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean): Take?
        /** Громкость разговора: сейчас и наибольшая; null — неизвестно. */
        val volume: Pair<Int, Int>?
    }

    const val FRAME = RATE / 50

    /** Что измерено в попытке. */
    internal class Measure(val noise: Double, val echo: Double, val left: Double, val reductionDb: Double, val clipped: Boolean, val take: Take) {
        /** Эха не слышно: убрано достаточно или в тихой комнате после очистки осталась одна тишина. */
        val clean get() = reductionDb >= GOOD_DB || noise <= QUIET_RMS && left <= noise * 1.4
        val echoOverNoiseDb get() = 20 * log10(echo / noise.coerceAtLeast(1.0))
    }

    /** Вся проверка (блокирующая, рабочий поток); [progress] — что происходит. */
    fun check(rig: Rig, now: Long, progress: (String) -> Unit, cancelled: () -> Boolean): Result {
        val stop = Result(Verdict.CANCELLED, null, "Проверка остановлена")
        val attempts = listOf(Triple(TalkTablet.Mode.OWN, 1f, "Играю сигнал и слушаю…"), Triple(TalkTablet.Mode.OWN, 0.5f, "Пробую тише…"),
            Triple(TalkTablet.Mode.SYSTEM, 1f, "Пробую с подавлением эха Android…"), Triple(TalkTablet.Mode.SYSTEM, 0.5f, "Пробую с подавлением эха Android и тише…"))
        var best: Measure? = null
        for ((i, attempt) in attempts.withIndex()) {
            val (mode, gain, text) = attempt
            progress(if (i == 0) "Слушаю тишину, затем играю сигнал — около 12 секунд. Не говорите." else "$text Не говорите.")
            val signal = signal(gain)
            val take = rig.take(signal, mode == TalkTablet.Mode.SYSTEM, cancelled)
            if (cancelled()) return stop
            if (take == null) return Result(Verdict.NO_MIC, null, "Микрофон не открылся: его занимает другое приложение или разрешение не дано")
            if (take.rate != RATE) return Result(Verdict.NARROW, null, "Микрофон этого планшета даёт только 8 кГц — разговор идёт по очереди: пока говорит один, второй приглушён")
            val m = measure(signal, take)
            Log.i(TAG, "Попытка $mode ×$gain: шум %.0f, эхо %.0f, осталось %.0f, тише на %.0f дБ, вычитанием %.0f дБ, задержка %d мс, пик %d%s".format(
                m.noise, m.echo, m.left, m.reductionDb, take.linearDb, take.delayMs, take.peak, if (take.locked) "" else ", сдвиг не найден"))
            val faint = i == 0 && m.echoOverNoiseDb < HEARD_DB
            if (!m.clean && m.noise > NOISY_RMS || faint && m.noise > QUIET_RMS) return Result(Verdict.NOISY, null, "В комнате шумно — повторите, когда станет тише")
            if (faint) return deaf(rig, m, now)
            if (m.clean) return good(m, mode, gain, now, signal)
            if (best == null || m.reductionDb > best.reductionDb) best = m
            // Убавить помогает, только когда динамик или микрофон перегружены: иначе сразу к подавлению Android.
        }
        val m = best!!
        return Result(Verdict.ECHO, TalkTablet.Profile(TalkTablet.Mode.TURNS, 1f, m.reductionDb.toInt(), m.take.delayMs, 0, now),
            "Есть эхо: этот планшет убирает только ${m.reductionDb.toInt()} дБ из нужных ${GOOD_DB.toInt()}. Включён разговор по очереди: пока говорит один, второй приглушён.")
    }

    private fun deaf(rig: Rig, m: Measure, now: Long): Result {
        val (level, max) = rig.volume ?: (1 to 1)
        if (level * QUIET_SHARE <= max) return Result(Verdict.DEAF, null, "Динамик почти не слышно: громкость разговора — $level из $max. Прибавьте и проверьте ещё раз")
        return Result(Verdict.GOOD, TalkTablet.Profile(TalkTablet.Mode.OWN, 1f, 0, 0, 100, now),
            "Хорошо: микрофон почти не слышит динамик — эхо убирать не нужно.")
    }

    private fun good(m: Measure, mode: TalkTablet.Mode, gain: Float, now: Long, signal: ShortArray): Result {
        val voice = voicePasses(signal, m.take)
        val profile = TalkTablet.Profile(mode, gain, m.reductionDb.toInt(), m.take.delayMs, voice, now)
        val how = "эхо тише на ${m.reductionDb.toInt()} дБ, " + when {
            voice >= VOICE_CLEAR -> "ваш голос слышен и поверх гостя"
            voice >= VOICE_FAINT -> "ваш голос поверх гостя слышен, но тише"
            else -> "поверх гостя ваш голос почти не слышен — говорите, когда он замолчит"
        }
        return when {
            mode == TalkTablet.Mode.SYSTEM -> Result(Verdict.GOOD_SYSTEM, profile, "Хорошо, вместе с подавлением эха Android: $how." + quieter(gain))
            gain < 1f -> Result(Verdict.GOOD_QUIETER, profile, "Хорошо: $how." + quieter(gain))
            else -> Result(Verdict.GOOD, profile, "Хорошо: $how.")
        }
    }

    private fun quieter(gain: Float) = if (gain < 1f) " Звук улицы убавлен вдвое: на полной громкости динамик искажает, и эхо не убрать." else ""

    /** Микрофон слышит динамик хотя бы на столько громче тишины. */
    private const val HEARD_DB = 8.0
    private const val VOICE_CLEAR = 60
    private const val VOICE_FAINT = 30
    /** Громкость меньше четверти — «почти не слышно». */
    private const val QUIET_SHARE = 4

    // ---- Сигнал и измерение (без Android: проверяется обычными тестами) ----

    /** Тишина, «речь», тишина. */
    internal fun signal(gain: Float): ShortArray {
        val voice = TalkSignal.voice(VOICE_S, SEED, PITCH, VOICE_RMS * gain, pause = 0.5)
        val lead = (LEAD_S * RATE).toInt()
        val out = ShortArray(lead + voice.size + (TAIL_S * RATE).toInt())
        for (i in voice.indices) out[lead + i] = voice[i].coerceIn(-32_767.0, 32_767.0).toInt().toShort()
        return out
    }

    private const val SEED = 25L
    private const val PITCH = 150.0

    internal fun measure(signal: ShortArray, take: Take): Measure {
        val lead = (LEAD_S * RATE).toLong()
        // Эхо возвращается до 0,7 с: тишина — пока сигнал ещё не звучал; измерение — после обучения и до конца «речи».
        val quiet = span(take, (0.2 * RATE).toLong(), lead - RATE / 5)
        val learned = lead + (LEARN_S * RATE).toLong()
        val loud = span(take, learned, lead + (VOICE_S * RATE).toLong())
        val noise = rms(take.pre, quiet.first, quiet.second).coerceAtLeast(1.0)
        val shift = take.alignment.toInt()
        val extra = 2 * EchoLoop.BLOCK
        val before = if (take.locked) coherent(signal, take.pre, shift, loud.first, loud.second) else 0.0
        val after = if (take.locked) coherent(signal, take.post, shift + extra, loud.first + extra, minOf(loud.second + extra, take.post.size)) else 0.0
        // Сдвиг не найден (эха мало или оно не похоже на сигнал) — по громкости за вычетом шума.
        val echo = rms(take.pre, loud.first, loud.second)
        val left = rms(take.post, loud.first, loud.second)
        val db = if (before > 0 && after > 0) 10 * log10(before / after) else 20 * log10(echo / left.coerceAtLeast(1.0))
        return Measure(noise, echo, left, db, take.peak >= CLIP, take)
    }

    /** Отсчёты микрофона, к которым было записано от [from] до [to] отсчётов опоры. */
    private fun span(take: Take, from: Long, to: Long): Pair<Int, Int> {
        val a = take.refAt.indexOfFirst { it >= from }.let { if (it < 0) 0 else it }
        val b = take.refAt.indexOfLast { it <= to }.let { if (it < 0) 0 else it + 1 }
        return a * FRAME to minOf(b * FRAME, take.pre.size)
    }

    internal fun rms(x: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        val end = minOf(to, x.size)
        for (i in from until end) s += x[i].toDouble() * x[i]
        return sqrt(s / (end - from).coerceAtLeast(1))
    }

    /**
     * Мощность той части [signal], что повторяет опору [ref] (совпадает с ней по спектрам, усреднённым за отрезок): так эхо
     * отделяется от фона и чужого голоса. [shift] — на сколько отсчётов сигнал отстаёт от опоры.
     */
    internal fun coherent(ref: ShortArray, signal: ShortArray, shift: Int, from: Int, to: Int, rate: Int = RATE): Double {
        val n = 4_096
        val fft = Fft(n)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val sxx = DoubleArray(n / 2); val sre = DoubleArray(n / 2); val sim = DoubleArray(n / 2)
        val xr = DoubleArray(n); val xi = DoubleArray(n); val dr = DoubleArray(n); val di = DoubleArray(n)
        var t = from
        while (t + n <= minOf(to, signal.size)) {
            val r0 = t - shift
            if (r0 >= 0 && r0 + n <= ref.size) {
                for (i in 0 until n) { xr[i] = ref[r0 + i] * window[i]; xi[i] = 0.0; dr[i] = signal[t + i] * window[i]; di[i] = 0.0 }
                fft.forward(xr, xi); fft.forward(dr, di)
                for (k in 0 until n / 2) {
                    sxx[k] += xr[k] * xr[k] + xi[k] * xi[k]
                    sre[k] += dr[k] * xr[k] + di[k] * xi[k]
                    sim[k] += di[k] * xr[k] - dr[k] * xi[k]
                }
            }
            t += n / 2
        }
        var sum = 0.0
        for (k in 300 * n / rate until 4_000 * n / rate) if (sxx[k] > 0) sum += (sre[k] * sre[k] + sim[k] * sim[k]) / sxx[k]
        return sum
    }

    /**
     * Сколько голоса хозяина останется, если он заговорит одновременно с гостем: к записи микрофона подмешан второй голос той же
     * громкости, что эхо, и она прогнана через эхоподавление заново. Процент.
     */
    internal fun voicePasses(signal: ShortArray, take: Take): Int {
        val lead = (LEAD_S * RATE).toLong()
        val (from, to) = span(take, lead + (LEARN_S * RATE).toLong(), lead + (VOICE_S * RATE).toLong())
        if (to - from < RATE) return 0
        val other = TalkSignal.voice((to - from) / RATE.toDouble(), SEED + 1, PITCH * 1.35, rms(take.pre, from, to).coerceAtLeast(200.0), pause = 0.5)
        val mixed = take.pre.copyOf()
        for (i in from until minOf(to, from + other.size)) mixed[i] = (mixed[i] + other[i - from]).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
        val duplex = DuplexEcho()
        var written = 0
        val buf = ShortArray(FRAME)
        val out = ShortArray(mixed.size)
        for (frame in take.refAt.indices) {
            val until = minOf(take.refAt[frame].toInt(), signal.size)
            if (until > written) { duplex.played.write(signal.copyOfRange(written, until), until - written); written = until }
            val at = frame * FRAME
            if (at + FRAME > mixed.size) break
            System.arraycopy(mixed, at, buf, 0, FRAME)
            duplex.near.process(buf, FRAME)
            System.arraycopy(buf, 0, out, at, FRAME)
        }
        val extra = 2 * EchoLoop.BLOCK
        var dot = 0.0; var energy = 0.0
        for (i in other.indices) {
            val at = from + i + extra
            if (at >= out.size) break
            dot += out[at] * other[i]; energy += other[i] * other[i]
        }
        return if (energy <= 0) 0 else (100 * dot / energy).toInt().coerceIn(0, 100)
    }

    // ---- Настоящий планшет ----

    @Volatile private var running: Thread? = null
    @Volatile private var stopped = false
    val busy get() = running?.isAlive == true

    /** Разговор или «Говорить» начинается — проверка уступает. */
    fun cancel() { stopped = true }

    /** Запустить проверку в фоне; [progress] и [done] — из рабочего потока. false — уже идёт. */
    fun start(context: Context, progress: (String) -> Unit, done: (Result) -> Unit): Boolean {
        if (busy) return false
        stopped = false
        val app = context.applicationContext
        running = Thread({
            val result = try { check(TabletRig(app), System.currentTimeMillis(), progress) { stopped } }
                catch (e: RuntimeException) { Log.w(TAG, "Проверка: ${e.javaClass.simpleName}"); Result(Verdict.NO_MIC, null, "Проверить не получилось") }
            Log.i(TAG, "Итог: ${result.verdict} ${result.profile?.mode ?: ""}")
            done(result)
        }, "talk-self-check").apply { isDaemon = true; start() }
        return true
    }

    /** Тем же путём, что в разговоре: режим «разговор», дорожка звука улицы, микрофон разговора. */
    private class TabletRig(private val context: Context) : Rig {
        private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        override val volume get() = audio?.let { it.getStreamVolume(AudioManager.STREAM_VOICE_CALL) to it.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }

        override fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean): Take? {
            val duplex = DuplexEcho().apply { active = true }
            val pre = ShortArray(signal.size + 2 * RATE); val post = ShortArray(pre.size); val refAt = LongArray(pre.size / FRAME)
            var preAt = 0; var postAt = 0; var peak = 0
            val mic = MicCapture({ _, _ -> }, {})
            mic.echo = duplex
            mic.monitor = { after, pcm, n ->
                if (after) { if (postAt + n <= post.size) { System.arraycopy(pcm, 0, post, postAt, n); postAt += n } }
                else if (preAt + n <= pre.size && n == FRAME) {
                    System.arraycopy(pcm, 0, pre, preAt, n)
                    for (i in 0 until n) peak = maxOf(peak, abs(pcm[i].toInt()))
                    refAt[preAt / FRAME] = duplex.played.total
                    preAt += n
                }
            }
            val mode = audio?.mode
            var track: android.media.AudioTrack? = null
            try {
                runCatching { audio?.mode = AudioManager.MODE_IN_COMMUNICATION }
                if (!mic.start(context, duplex = system, wideband = true)) return null
                if (mic.rate != RATE) return Take(mic.rate, pre, post, refAt, 0, false, 0, 0.0, 0)
                val until = System.currentTimeMillis() + MIC_WAIT_MS
                while (preAt < RATE / 5 && System.currentTimeMillis() < until) Thread.sleep(20)
                if (preAt == 0) return null
                val t = StreetAudio().newTrack(RATE)
                track = t
                t.play()
                val buf = ShortArray(FRAME)
                var i = 0
                while (i < signal.size && !cancelled()) {
                    val n = minOf(FRAME, signal.size - i)
                    signal.copyInto(buf, 0, i, i + n)
                    if (n < FRAME) buf.fill(0, n, FRAME)
                    duplex.played.write(buf, FRAME)
                    if (t.write(buf, 0, FRAME) <= 0) Thread.sleep(20)
                    i += n
                }
                if (!cancelled()) Thread.sleep(500)
            } finally {
                mic.stop()
                runCatching { track?.stop() }
                runCatching { track?.release() }
                runCatching { if (mode != null) audio?.mode = mode }
                Thread.sleep(200)
            }
            val near = duplex.near
            return Take(RATE, pre.copyOf(preAt), post.copyOf(postAt), refAt.copyOf(preAt / FRAME), near.alignment, near.locked, near.echoDelayMs, near.linearDb, peak)
        }
    }

    private const val MIC_WAIT_MS = 2_000L
}

/** Искусственная речь: слоги с основным тоном и формантами, паузы между фразами — сигнал самопроверки и модели в тестах. */
internal object TalkSignal {
    private const val RATE = DuplexEcho.RATE

    /** [seconds] секунд, основной тон около [pitch] Гц, громкость слогов [rms]. */
    fun voice(seconds: Double, seed: Long, pitch: Double, rms: Double, pause: Double = 1.0): DoubleArray {
        val rnd = java.util.Random(seed)
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        val phase = DoubleArray(48)
        var t = (rnd.nextDouble() * 0.3 * RATE).toInt()
        while (t < n) {
            repeat(4 + rnd.nextInt(7)) {
                val dur = ((0.10 + 0.18 * rnd.nextDouble()) * RATE).toInt()
                val voiced = rnd.nextDouble() < 0.8
                val f0 = pitch * (0.85 + 0.3 * rnd.nextDouble())
                val formants = doubleArrayOf(300 + 500 * rnd.nextDouble(), 900 + 1_400 * rnd.nextDouble(), 2_400 + 800 * rnd.nextDouble())
                val widths = doubleArrayOf(80.0, 120.0, 160.0)
                val amp = 0.5 + 0.5 * rnd.nextDouble()
                val harmonics = minOf(phase.size, (3_800 / f0).toInt())
                val weights = DoubleArray(harmonics) { h ->
                    val f = (h + 1) * f0
                    var a = 0.0
                    for (j in 0..2) { val d = (f - formants[j]) / widths[j]; a += 1.0 / (1.0 + d * d) }
                    a / sqrt(h + 1.0)
                }
                var prevNoise = 0.0
                for (i in 0 until dur) {
                    if (t + i >= n) break
                    val env = sin(PI * i / dur).let { it * it }
                    var s = 0.0
                    if (voiced) {
                        val f = f0 * (1 + 0.03 * sin(2 * PI * 5 * i / RATE))
                        for (h in 0 until harmonics) {
                            phase[h] += 2 * PI * (h + 1) * f / RATE
                            s += weights[h] * sin(phase[h])
                        }
                    } else {
                        val w = rnd.nextGaussian()
                        s = 0.6 * (w - prevNoise)
                        prevNoise = w
                    }
                    out[t + i] = env * amp * s
                }
                t += dur + ((0.02 + 0.06 * rnd.nextDouble()) * RATE).toInt()
            }
            t += ((0.3 + 0.6 * rnd.nextDouble()) * pause * RATE).toInt()
        }
        var sum = 0.0; var count = 0
        for (v in out) if (v != 0.0) { sum += v * v; count++ }
        val scale = if (count == 0) 0.0 else rms / sqrt(sum / count)
        for (i in out.indices) out[i] *= scale
        return out
    }
}
