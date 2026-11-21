package com.example.smarthomedashboard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

/**
 * Звук с улицы на время разговора — отдельно от видео (docs/talk-tz.md, «Звук с улицы в разговоре»).
 *
 * В плеере звук идёт вместе с видео HD и ждёт его (≈ 1,1–1,4 с на SM-X216B: 2560×1920 декодируется программно). На время
 * разговора звук камеры принимается отдельно — через ретранслятор планшета, если поток уже идёт ради записи (новых
 * подключений к камере нет), иначе напрямую — декодируется и сразу играет с запасом [TARGET_MS]; запас больше
 * [MAX_MS] сбрасывается до [TARGET_MS], чтобы задержка не копилась. Звук плеера на это время выключает владелец
 * карточки. AAC (как у Reolink) — декодером Android, G.711 — сам. Не вышло — остаётся звук плеера, как раньше.
 */
/** Источник звука с улицы для разговора (в тестах — подмена). */
internal interface StreetSource {
    /**
     * Подключиться и играть. [onSound] — из фонового потока: `true` — звук пошёл; `false` — не получилось или звук
     * оборвался сам (связь с камерой пропала посреди разговора). После [stop] источник больше ничего не сообщает.
     */
    fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit)
    fun stop()
    /** Когда из динамика последний раз звучала улица громко (`System.nanoTime`, 0 — не звучала). */
    val lastLoudAt: Long get() = 0L
    /** Громкость звучащего сейчас звука с улицы (RMS, держится ~0,3 с после громкого); 0 — ничего не звучит. */
    val playingRms: Double get() = 0.0
    /** Приглушить улицу до момента [until] (`System.nanoTime`): наш голос вернётся эхом из микрофона домофона. */
    fun muteUntil(until: Long) {}
    /** Частота принимаемого звука, Гц; 0 — звук ещё не идёт. */
    val rate: Int get() = 0
    /**
     * Эхоподавление разговора ([DuplexEcho]): из принятого звука вычитается наш вернувшийся голос, а сыгранное
     * запоминается как опора для микрофона. Источник сам включает его ([DuplexEcho.active]), если частота подходит.
     */
    fun duplex(echo: DuplexEcho?) {}
}

internal class StreetAudio : StreetSource {
    /**
     * Один запуск приёма. У остановленного или сменённого запуска поток досматривает свой обрыв сам и общего состояния
     * уже не трогает: иначе «Завершить» и сразу «Ответить» гасили бы новый приём руками старого потока.
     */
    private class Run { @Volatile var session: RtspSession? = null }
    @Volatile private var current: Run? = null
    private fun alive(run: Run) = current === run
    /** Только принимать (измерение [TalkProbe]): динамик планшета молчит. */
    @Volatile var silent = false
    /**
     * Проверка восстановления (файл `talk_debug/drop_street`): один раз оборвать приём через столько миллисекунд после
     * первого звука — как при переподключении камеры посреди разговора. 0 — не обрывать.
     */
    @Volatile var breakAfterMs = 0L
    @Volatile var playedMs = 0L
        private set
    @Volatile var trimmedMs = 0L
        private set
    @Volatile override var lastLoudAt = 0L
        private set
    @Volatile private var mutedUntil = 0L
    @Volatile override var playingRms = 0.0
        private set

    override fun muteUntil(until: Long) { if (until > mutedUntil) mutedUntil = until }
    @Volatile private var echo: DuplexEcho? = null

    override fun duplex(echo: DuplexEcho?) {
        this.echo = echo
        // Звук уже идёт (канал голоса переподключился посреди разговора) — включить сразу.
        if (echo != null && rate == DuplexEcho.RATE) echo.active = true
    }

    /** Наш голос, вернувшийся из микрофона домофона, — вычесть из принятого (на месте); и запись для разбора. */
    private fun clean(pcm: ShortArray, rate: Int): ShortArray {
        TalkDebug.write("street_rx", pcm, pcm.size, rate)
        val d = echo
        if (d != null && d.active) {
            d.far.process(pcm, pcm.size)
            TalkDebug.write("street_aec", pcm, pcm.size, rate)
        }
        return pcm
    }
    @Volatile private var receivedSamples = 0L
    @Volatile private var lostMs = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var rateUsed = 8_000
    @Volatile private var flowing = false
    override val rate get() = if (flowing) rateUsed else 0

    override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) {
        if (current != null) return
        val run = Run()
        current = run
        playedMs = 0; trimmedMs = 0; receivedSamples = 0; lostMs = 0; startedAt = 0
        Thread({ run(run, rtsp, user, password, onSound) }, "street-audio").apply { isDaemon = true; start() }
    }

    override fun stop() {
        val run = current ?: return
        current = null
        flowing = false
        echo?.active = false
        run.session?.close()
    }

    private fun run(run: Run, rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var player: Thread? = null
        var started = false
        var mine: DuplexEcho? = null
        try {
            val relay = CameraHub.relayUrl(rtsp, user)
            val s = RtspSession(relay ?: rtsp, user, password, connectTimeoutMs = 3_000, readTimeoutMs = 10_000)
            run.session = s
            if (!alive(run)) { s.close(); return }
            val setup = s.open { it.isAudio && it.direction != "sendonly" &&
                (AacDepacketizer.create(it) != null || G711Depacketizer.create(it) != null) }.first()
            val aac = AacDepacketizer.create(setup.track)
            val g711 = if (aac == null) G711Depacketizer.create(setup.track) else null
            val rate = aac?.sampleRate ?: 8_000
            rateUsed = rate
            flowing = true
            // Эхоподавление считает на 16 кГц (звук Reolink); при другой частоте разговор остаётся с прежней защитой.
            mine = echo
            mine?.active = rate == DuplexEcho.RATE
            val fifo = ShortFifo(rate)
            val t = newTrack(rate)
            track = t
            if (silent) t.setVolume(0f)
            t.play()
            player = Thread({ playout(run, t, fifo, rate) }, "street-audio-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
            val dec = aac?.let { decoder(it) }
            codec = dec
            val info = MediaCodec.BufferInfo()
            val limit = rate * MAX_QUEUE_MS / 1000
            val gaps = RtpGaps()
            Log.i(TAG, "Звук с улицы — отдельно от видео (${if (relay != null) "через планшет" else "напрямую"}, " +
                "${if (aac != null) "AAC" else setup.track.encoding} ${rate / 1000} кГц)")
            s.read(object : RtspSession.Sink {
                override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                    if (!alive(run)) { s.close(); return }
                    val rtp = RtpPacket.parse(packet, length) ?: return
                    if (startedAt == 0L) startedAt = System.nanoTime()
                    if (g711 != null) {
                        val pcm = clean(g711.push(rtp).samples, rate)
                        receivedSamples += pcm.size
                        fifo.push(pcm, pcm.size, limit)
                    } else if (dec != null && aac != null) {
                        // Пакеты потерялись по дороге: на их место — тишина той же длины. Эхоподавление считает
                        // отсчёты: без этого сдвиг между «отправлено» и «вернулось» уехал бы.
                        val lost = gaps.missing(rtp.sequence) * aac.samplesPerFrame
                        if (lost > 0) {
                            drain(dec, info, fifo, limit)
                            val fill = clean(ShortArray(lost), rate)
                            fifo.push(fill, fill.size, limit)
                            lostMs += lost * 1000L / rate
                        }
                        for (frame in aac.push(rtp)) {
                            val index = dec.dequeueInputBuffer(5_000)
                            if (index >= 0) {
                                val buf = dec.getInputBuffer(index) ?: continue
                                buf.clear(); buf.put(frame.data)
                                dec.queueInputBuffer(index, 0, frame.data.size, frame.timestamp, 0)
                            }
                            drain(dec, info, fifo, limit)
                        }
                    }
                    if (!started) { started = true; onSound(true) }
                    val breakAfter = breakAfterMs
                    if (breakAfter > 0 && System.nanoTime() - startedAt > breakAfter * 1_000_000) {
                        breakAfterMs = 0
                        Log.i(TAG, "Звук с улицы: проверочный обрыв приёма")
                        s.close()
                    }
                }
            })
        } catch (e: Exception) {
            if (alive(run)) Log.w(TAG, "Звук с улицы отдельно ${if (started) "оборвался" else "не пошёл"}: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            // Остановили ([stop]) — общее состояние уже принадлежит следующему запуску или сброшено; оборвалось само —
            // сбрасываем и сообщаем: разговор вернёт звук плеера и запустит приём заново.
            val broken = alive(run)
            if (broken) { current = null; flowing = false; mine?.active = false }
            player?.interrupt()
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            run.session?.close()
            if (started && broken) Log.i(TAG, "Звук с улицы: сыграно ${playedMs / 1000} с, сброшено лишнего запаса $trimmedMs мс; " +
                "получено ${receivedSamples * 1000 / rateUsed.coerceAtLeast(1)} мс звука за ${(System.nanoTime() - startedAt) / 1_000_000} мс" +
                if (lostMs > 0) ", потеряно в пути $lostMs мс" else "")
            if (broken) onSound(false)
        }
    }

    private fun decoder(aac: AacDepacketizer): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, aac.sampleRate, aac.channels).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(aac.config))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, 0); start() }
    }

    private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo, fifo: ShortFifo, limit: Int) {
        while (true) {
            val out = dec.dequeueOutputBuffer(info, 0)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { Log.i(TAG, "Звук с улицы: декодер выдаёт ${dec.outputFormat}"); continue }
            if (out < 0) return
            val buf = dec.getOutputBuffer(out)
            if (buf != null && info.size > 0) {
                val channels = dec.outputFormat.let { if (it.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1 }
                buf.position(info.offset)
                val shorts = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                val all = ShortArray(info.size / 2).also { shorts.get(it) }
                val mono = if (channels <= 1) all else ShortArray(all.size / channels) { all[it * channels] }
                receivedSamples += mono.size
                clean(mono, rateUsed)
                fifo.push(mono, mono.size, limit)
            }
            dec.releaseOutputBuffer(out, false)
        }
    }

    internal fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 1000 * 60 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
        // Без «быстрого» режима вывода: на Samsung он может идти мимо подавления эха (эхо у калитки, проверка 20.11.2026).
        return builder.build()
    }

    /**
     * Играть по 20 мс. Камера шлёт звук пачками (разброс 100–200 мс, проверка 20.11.2026): запас [TARGET_MS]; кончился —
     * тишина, пока не накопится снова (не дробить речь); вырос больше [MAX_MS] — сбросить до [TARGET_MS].
     */
    private fun playout(run: Run, track: AudioTrack, fifo: ShortFifo, rate: Int) {
        val chunk = rate / 50
        val buf = ShortArray(chunk)
        val scratch = ShortArray(rate)
        var buffering = true
        try {
            while (alive(run)) {
                val excess = Playout.excess(fifo.size, rate)
                if (excess > 0) { fifo.pop(scratch, excess); trimmedMs += excess * 1000L / rate }
                buffering = Playout.buffering(fifo.size, rate, buffering, chunk)
                if (buffering) {
                    buf.fill(0)
                    playingRms *= 0.85
                } else {
                    val n = fifo.pop(buf, chunk)
                    if (n < chunk) buf.fill(0, n, chunk)
                    TalkDebug.write("street_raw", buf, chunk, rate)
                    // Наш голос, вернувшийся из микрофона домофона (его подавление эха не справляется), — тишина.
                    val muted = System.nanoTime() < mutedUntil
                    if (muted) buf.fill(0)
                    // Пока приглушено (это наш же голос вернулся из домофона), громкость улицы не в счёт: иначе защита
                    // приняла бы эхо за речь гостя и закрыла бы наш микрофон посреди фразы.
                    var sum = 0.0
                    for (i in 0 until chunk) sum += buf[i].toDouble() * buf[i]
                    val rms = kotlin.math.sqrt(sum / chunk)
                    playingRms = maxOf(rms, playingRms * 0.85)
                    if (!muted && rms > LOUD_RMS) lastLoudAt = System.nanoTime()
                }
                TalkDebug.write("street", buf, chunk, rate)
                // То, что сейчас прозвучит из динамика, — опора для микрофона (в том числе тишина: счёт отсчётов общий).
                if (!silent) echo?.takeIf { it.active }?.played?.write(buf, chunk)
                // Дорожка сама держит темп (запись блокирует); отказ записи — не крутиться впустую.
                if (track.write(buf, 0, chunk) <= 0) Thread.sleep(20) else playedMs += 20
            }
        } catch (_: Exception) {}
    }

    /**
     * Потерянные пакеты — по номерам RTP, а не по меткам времени: домофон Reolink ставит метки не по счёту отсчётов
     * (за минуту они «убегают» на 15 кадров, хотя ни один кадр не потерян — измерение стендом 20.11.2026; вставка
     * тишины по меткам сдвигала эхо на 64 мс каждые несколько секунд).
     */
    internal class RtpGaps {
        private var last = -1

        /** Сколько пакетов пропало перед пакетом с номером [sequence] (0 — ничего; больше [LIMIT] — разрыв, не потеря). */
        fun missing(sequence: Int): Int {
            val gap = if (last < 0) 0 else ((sequence - last - 1) and 0xFFFF)
            last = sequence
            return if (gap in 1..LIMIT) gap else 0
        }

        private companion object { const val LIMIT = 16 }
    }

    /** Чистая логика запаса воспроизведения (для тестов). */
    internal object Playout {
        /** Сколько отсчётов сбросить: запас больше [MAX_MS] — оставить [TARGET_MS]. */
        fun excess(size: Int, rate: Int): Int {
            val max = rate * MAX_MS / 1000
            val target = rate * TARGET_MS / 1000
            return if (size > max) size - target else 0
        }

        /** Копим запас: начать играть, когда набралось [TARGET_MS]; кончилось (меньше порции) — снова копить. */
        fun buffering(size: Int, rate: Int, was: Boolean, chunk: Int): Boolean =
            if (was) size < rate * TARGET_MS / 1000 else size < chunk
    }

    companion object {
        const val TARGET_MS = 150
        const val MAX_MS = 400
        private const val MAX_QUEUE_MS = 500
        /** Громкость звука с улицы (RMS), от которой включается защита от эха: речь, а не фоновый шум. */
        const val LOUD_RMS = 700.0
        private const val TAG = "Talk"
    }
}
