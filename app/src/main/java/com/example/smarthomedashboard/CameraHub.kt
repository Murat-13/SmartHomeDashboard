package com.example.smarthomedashboard

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Общий модуль камеры (этап 4, docs/recording.md): одно RTSP-подключение на поток камеры, сколько бы его ни
 * использовали. Круглосуточная запись, запись событий и живое видео плитки получают один и тот же поток:
 * записи — кадрами ([CameraRecorder]), живое видео — через локальный ретранслятор [RtspRelay] (плеер плитки
 * подключается к планшету, а не к камере). С двумя пишущими планшетами домофон держит 4 сессии вместо 6.
 *
 * Поток открыт, пока к нему подключена хоть одна запись. Без записи живое видео идёт к камере напрямую, как раньше.
 */
internal object CameraHub {
    /** Сколько ждать продвижения видео при живом соединении ([VideoWatch]); в тестах меньше. */
    @Volatile internal var videoStallMs = VideoWatch.STALL_MS

    data class Key(val url: String, val user: String, val password: String?)

    private val streams = ConcurrentHashMap<Key, SharedStream>()

    /** Папка проверочных выключателей `video_debug` ([LiveTuning]); null — неизвестна (тесты, служба не запускалась). */
    @Volatile var debugDir: java.io.File? = null
    /** Проверочный режим включён сейчас (спрашивается при каждом подключении к камере). */
    @Volatile var debugAllowed: () -> Boolean = { false }

    /** Подключить запись к общему потоку камеры (поток создаётся при первой). Поток управления записью. */
    @Synchronized
    fun attach(key: Key, recorder: CameraRecorder,
               sessionFactory: (String, String, String?) -> FeedSession, encoderFactory: () -> AacEncoder): SharedStream {
        val stream = streams.getOrPut(key) { SharedStream(key, sessionFactory, encoderFactory) }
        stream.add(recorder)
        return stream
    }

    /** Отключить запись; последняя — поток закрывается (ждём не дольше [waitMs]). */
    fun detach(stream: SharedStream, recorder: CameraRecorder, waitMs: Long) {
        val last = synchronized(this) {
            val empty = stream.remove(recorder)
            if (empty) streams.remove(stream.key, stream)
            empty
        }
        if (last) stream.stop(waitMs)
    }

    /**
     * Живое видео держит общий поток само (docs/cameras.md, «Показ своим клиентом»): камеру, которую встроенный плеер не
     * понимает (TLS, SHA-256, свои странности), читает наш RTSP-клиент, а плеер смотрит через планшет. Записи для этого не нужны.
     */
    class Hold internal constructor(internal val stream: SharedStream) {
        private val released = java.util.concurrent.atomic.AtomicBoolean()
        /** Отпустить; последний — поток закрывается. С любого потока: прощание с камерой идёт в фоне. */
        fun release() {
            if (released.compareAndSet(false, true)) Thread({ unhold(stream) }, "camera-unhold").apply { isDaemon = true; start() }
        }
    }

    @Synchronized
    fun hold(key: Key, sessionFactory: (String, String, String?) -> FeedSession = ReolinkVideo::feed,
             encoderFactory: () -> AacEncoder = { AacEncoder() }): Hold {
        val stream = streams.getOrPut(key) { SharedStream(key, sessionFactory, encoderFactory) }
        stream.hold()
        return Hold(stream)
    }

    private fun unhold(stream: SharedStream) {
        val last = synchronized(this) {
            val empty = stream.unhold()
            if (empty) streams.remove(stream.key, stream)
            empty
        }
        if (last) stream.stop(STOP_MS)
    }

    /** Поток [url] идёт и ключевой кадр в нём уже был — показ начнётся сразу. */
    fun ready(url: String, user: String) = streams.values.any { it.key.url == url && it.key.user == user && it.live && !it.awaitingKeyFrame }

    /** Поток [url] уже открыт или открывается (записью или живым видео). */
    fun has(url: String, user: String) = streams.values.any { it.key.url == url.trim() && it.key.user == user }

    private const val STOP_MS = 2_000L

    /**
     * Адрес ретранслятора для живого видео: поток [url] с логином [user] уже идёт (его держит запись) — плеер
     * подключается к планшету по одноразовому адресу. null — потока нет, смотреть напрямую.
     */
    fun relayUrl(url: String, user: String): String? {
        val stream = streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live } ?: return null
        val port = RtspRelay.ensureStarted() ?: return null
        return "rtsp://127.0.0.1:$port/${stream.token}"
    }

    /**
     * Неровность прихода кадров потока [url] за последнюю минуту: на сколько самый поздний кадр пришёл позже обычного.
     * Плееру нужен запас не меньше ([LiveCatchUp.floorFor]). 0 — поток не открыт или ещё не измерен.
     */
    fun arrivalJitterMs(url: String, user: String): Long =
        streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live }?.arrivalJitterMs ?: 0

    /** Неровность прихода кадров, по которой плеер держит запас: наибольшая за последние минуты ([JitterMemory]). */
    fun floorJitterMs(url: String, user: String): Long =
        streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live }?.floorJitterMs ?: 0

    /**
     * Общий поток [url] вот-вот будет готов к показу — живому видео стоит недолго подождать его, а не открывать второе
     * соединение с камерой (docs/doorbell.md, «Старт через общий поток»): запись на планшете только запускается
     * ([recordingStarting]) либо поток записи подключается (переподключается) к камере, либо он уже идёт, но первого
     * ключевого кадра в нём ещё не было — плеер всё равно ждал бы этот кадр, а из общего потока получит его сразу.
     */
    fun startingUp(url: String, user: String, recordingStarting: Boolean): Boolean {
        val stream = streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user } ?: return recordingStarting
        return !stream.live || stream.awaitingKeyFrame
    }

    /**
     * На сколько звук потока [url] приходит на планшет раньше видео, снятого в тот же момент (по отчётам RTCP за
     * последнюю минуту); null — ещё не измерено. Нужно, чтобы сравнить звук и картинку на планшете ([SoundLead]).
     */
    fun audioAheadMs(url: String, user: String): Long? =
        streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live }?.audioAheadMs

    /** Сводка потока [url] для диагностики ([StreamStats]); null — поток не идёт (запись на этом планшете выключена). */
    fun stats(url: String, user: String): StreamSnapshot? =
        streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live }?.stats?.snapshot()

    /**
     * Сколько миллисекунд назад камера прислала последний кадр видео потока [url]; null — поток не идёт. Для журнала
     * остановок живого видео ([StallProbe]): остановка из-за камеры или сети видна по паузе здесь.
     */
    fun sinceLastFrameMs(url: String, user: String): Long? =
        streams.values.firstOrNull { it.key.url == url.trim() && it.key.user == user && it.live }?.lastFrameAtMs
            ?.takeIf { it > 0 }?.let { android.os.SystemClock.elapsedRealtime() - it }

    internal fun byToken(token: String): SharedStream? = streams.values.firstOrNull { it.token == token }

    /** Для тестов: сколько общих потоков открыто. */
    internal val openStreams get() = streams.size
}

/**
 * Один поток камеры: подключение, разбор RTP в кадры, звук (AAC как есть, G.711 → AAC для записи), переподключение
 * 2→30 с. Кадры раздаются подключённым записям, сырые RTP-пакеты — клиентам ретранслятора. Свой поток чтения.
 */
internal class SharedStream(
    val key: CameraHub.Key,
    private val sessionFactory: (String, String, String?) -> FeedSession,
    private val encoderFactory: () -> AacEncoder
) {
    /** На сколько самый поздний кадр за последнюю минуту пришёл позже обычного (0 — ещё не измерено). */
    @Volatile var arrivalJitterMs = 0L
        private set
    /** Она же — наибольшая за последние минуты: одна плохая минута уходит из памяти, и запас плеера снижается обратно. */
    @Volatile var floorJitterMs = 0L
        private set
    private val jitterMemory = JitterMemory()

    init {
        // Профиль планшета: до первого замера — неровность, запомненная в прошлый раз (плеер не набирает запас остановками).
        TabletProfile.jitter(key.url).takeIf { it > 0 }?.let { floorJitterMs = jitterMemory.add(it) }
    }

    /** Сводка текущей сессии для диагностики ([StreamStats]); новая сессия камеры — новая сводка. */
    @Volatile var stats: StreamStats? = null
        private set

    /** Когда пришёл последний целый кадр видео (часы [android.os.SystemClock.elapsedRealtime]); 0 — ещё не было. */
    @Volatile var lastFrameAtMs = 0L
        private set

    /** Одноразовая часть адреса ретранслятора: без неё другие приложения планшета не увидят видео. */
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private class Attached(val recorder: CameraRecorder) {
        var sink: CameraRecorder.Sink? = null
        /** Запись отключена: новых приёмников ей не создаём (их бы никто не закрыл). Под замком записи. */
        var gone = false
    }

    private val consumers = CopyOnWriteArrayList<Attached>()
    @Volatile private var running = false
    @Volatile private var session: FeedSession? = null
    private var thread: Thread? = null
    /** Описание потока для ретранслятора; null — поток сейчас не идёт. */
    @Volatile var relaySdp: String? = null
        private set
    /** Текущая сессия камеры: номер дорожки ретранслятора по каналу RTP. */
    @Volatile private var relayChannels: Map<Int, Int> = emptyMap()
    private val relayClients = CopyOnWriteArrayList<RtspRelay.Client>()
    /** Видео с последнего ключевого кадра — для нового плеера ([RecentVideo]); замок — он же порядок «старое, потом живое». */
    private val recent = RecentVideo()
    /** Дорожка видео у ретранслятора в текущей сессии камеры. */
    @Volatile private var relayVideoTrack = -1
    /** Поток идёт, но первого ключевого кадра в нём ещё не было ([CameraHub.startingUp]). */
    val awaitingKeyFrame get() = live && recent.pending
    /** Через сколько после съёмки приходят видео и звук (по часам камеры, за последнюю минуту); null — не измерено. */
    @Volatile private var videoArrivalMs: Long? = null
    @Volatile private var audioArrivalMs: Long? = null
    val audioAheadMs: Long? get() { val video = videoArrivalMs; val audio = audioArrivalMs; return if (video != null && audio != null) video - audio else null }
    /** Параметры идущей сессии; пишется под замком потока, читается в [add] под ним же. */
    @Volatile private var current: SessionInfo? = null

    private class SessionInfo(val videoClock: Int, val audioParams: AudioParams?)

    val live get() = relaySdp != null

    fun add(recorder: CameraRecorder) {
        val a = Attached(recorder)
        // Под замком потока: поток чтения не может раздать приёмники новой сессии «мимо» этой записи
        // (аудит 20.11.2026, A2). Порядок замков тот же, что у потока чтения: поток → запись.
        synchronized(this) {
            synchronized(a) {
                consumers += a
                // Поток уже идёт — запись начинает со следующего ключевого кадра этой же сессии.
                current?.let { begin(a, it) }
            }
        }
        recorder.streamStatus(if (live) null else CameraRecorder.State.CONNECTING, "")
        ensureRunning("cam-${RecLayout.cameraKey(recorder.camera)}")
    }

    private fun ensureRunning(name: String) {
        if (running) return
        running = true
        thread = Thread({ loop() }, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** Сколько живых видео держат поток сами ([CameraHub.hold]); под замком [CameraHub]. */
    private var holds = 0
    internal fun hold() { holds++; ensureRunning("cam-view") }
    /** true — поток больше никому не нужен. */
    internal fun unhold(): Boolean { holds--; return holds <= 0 && consumers.isEmpty() }

    /** true — поток больше никому не нужен. */
    fun remove(recorder: CameraRecorder): Boolean {
        consumers.firstOrNull { it.recorder === recorder }?.let { a ->
            synchronized(a) { a.gone = true; end(a); consumers.remove(a) }
        }
        return consumers.isEmpty() && holds <= 0
    }

    fun stop(waitMs: Long) {
        running = false
        session?.close()
        relayClients.forEach { it.close() }
        thread?.interrupt()
        if (thread !== Thread.currentThread()) thread?.join(waitMs)
        thread = null
    }

    private fun begin(a: Attached, info: SessionInfo) {
        if (a.gone) return
        a.sink = a.recorder.newSink().also {
            it.videoClock = info.videoClock
            it.audioParams = if (a.recorder.wantsAudio) info.audioParams else null
        }
    }

    private fun end(a: Attached) {
        val sink = a.sink ?: return
        a.sink = null
        try { sink.close() } catch (e: Exception) { Log.w(TAG, "${key.url.hashCode()}: файл не закрыт: ${e.message}") }
    }

    private inline fun each(block: (Attached, CameraRecorder.Sink) -> Unit) {
        for (a in consumers) synchronized(a) { a.sink?.let { block(a, it) } }
    }

    private fun broadcast(state: CameraRecorder.State, detail: String = "") = consumers.forEach { it.recorder.streamStatus(state, detail) }

    private fun loop() {
        var backoffMs = 2_000L
        var refused = false
        while (running) {
            val started = System.nanoTime()
            broadcast(CameraRecorder.State.CONNECTING)
            var encoder: AacEncoder? = null
            var trace: ArrivalTrace? = null
            try {
                val s = sessionFactory(key.url, key.user, key.password)
                session = s
                if (!running) { s.close(); break }
                // Звук берётся всегда (если он есть): записи без звука его просто не пишут, а живому видео он нужен.
                val setups = s.open { t -> (t.isVideo && (t.encoding == "H264" || t.encoding == "H265" || t.encoding == "HEVC")) || t.isAudio }
                val video = setups.firstOrNull { it.track.isVideo } ?: throw RtspException("Камера не отдаёт H.264/H.265")
                val depacketizer = VideoDepacketizer.create(video.track) ?: throw RtspException("Кодек не поддерживается")
                val audioSetup = setups.firstOrNull { it.track.isAudio }
                val aac = audioSetup?.let { AacDepacketizer.create(it.track) }
                // G.711 (PCMA/PCMU) — для записи перекодируется в AAC; не вышло — запись без звука. Кодер создаётся
                // всегда (не только если звук нужен записям, уже подключённым к началу сессии): запись со звуком
                // может подключиться к идущей сессии (A2). Кодирует он, только пока звук кому-то нужен.
                val g711 = if (aac == null) audioSetup?.let { G711Depacketizer.create(it.track) } else null
                encoder = g711?.let {
                    try { encoderFactory() } catch (e: Exception) {
                        Log.w(TAG, "AAC-кодер недоступен (${e.javaClass.simpleName}), запись без звука")
                        null
                    }
                }
                val info = SessionInfo(video.track.clockRate.takeIf { it > 0 } ?: 90_000,
                    aac?.let { AudioParams(it.config, it.sampleRate, it.channels) }
                        ?: encoder?.let { AudioParams(it.config, it.sampleRate, it.channels) })
                synchronized(this) {
                    current = info
                    for (a in consumers) synchronized(a) { end(a); begin(a, info) }
                }
                relayChannels = setups.withIndex().associate { (i, setup) -> setup.rtpChannel to i }
                relayVideoTrack = setups.indexOf(video)
                recent.clear()
                relaySdp = Media3Sdp.compatible(RtspRelay.sdpFor(s.sdp, setups)) { H264Params.cached(key.url) }
                // Камера не дала SPS/PPS в описании (субпоток Reolink): берём их из потока и дописываем для плеера.
                val sniffer = H264Params.Collector().takeIf {
                    relaySdp?.let { sdp -> SdpParser.parse(sdp).any(Media3Sdp::lacksH264Params) } == true
                }
                var sniffing = sniffer != null
                val enc = encoder
                val probe = ArrivalDelay(video.track.clockRate.takeIf { it > 0 } ?: 90_000)
                val audioProbe = audioSetup?.track?.clockRate?.takeIf { it > 0 }?.let { ArrivalDelay(it) }
                trace = ArrivalTrace.open(CameraHub.debugDir?.takeIf { CameraHub.debugAllowed() }, key.url)
                val frames = trace
                val summary = StreamStats(video.track.clockRate.takeIf { it > 0 } ?: 90_000).also { stats = it }
                val watch = VideoWatch(CameraHub.videoStallMs)
                s.read(object : RtspSession.Sink {
                    /** За минуту: байты и кадры видео и самое долгое время, на которое получатели кадра заняли этот поток. */
                    private var videoBytes = 0L
                    private var videoFrames = 0
                    private var busyMaxNs = 0L
                    /** Метка последнего ключевого кадра — для [ArrivalTrace]. */
                    private var keyTs = -1L

                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        watch.check()
                        val readNs = if (setup === video) System.nanoTime() else 0L
                        if (setup === video) {
                            // Под одним замком с подключением плеера: он получает сохранённое и дальше живое без пропуска.
                            synchronized(recent) {
                                recent.add(packet, length)
                                relay(setup.rtpChannel, rtcp = false, packet, length)
                            }
                        } else relay(setup.rtpChannel, rtcp = false, packet, length)
                        val rtp = RtpPacket.parse(packet, length) ?: return
                        if (setup === video) {
                            frames?.packet(rtp.timestamp, rtp.length, readNs)
                            watch.packet(rtp.timestamp)
                            summary.packet(rtp.sequence, rtp.timestamp, rtp.length, readNs,
                                RtpKey.isKey(depacketizer.codec == VideoDepacketizer.Codec.H265, rtp.payload, rtp.offset, rtp.length))
                            videoBytes += rtp.length
                            if (rtp.marker) { videoFrames++; lastFrameAtMs = android.os.SystemClock.elapsedRealtime() }
                            if (rtp.marker) probe.frame(rtp.timestamp)?.let {
                                arrivalJitterMs = (probe.slowest - it).coerceIn(0, MAX_JITTER_MS)
                                floorJitterMs = jitterMemory.add(arrivalJitterMs)
                                TabletProfile.rememberJitter(key.url, jitterMemory.usual)
                                videoArrivalMs = it
                                // Неровный приход — главный предел задержки живого видео (docs/doorbell.md): плееру нужен
                                // запас не меньше самой длинной паузы. «Запись занимала поток» — наша доля в этих паузах.
                                Log.i(TAG, "Камера → планшет (≈ ${videoBytes * 8 / ArrivalDelay.REPORT_MS} кбит/с, " +
                                    "%.1f к/с): кадр приходит через $it мс ".format(videoFrames * 1000f / ArrivalDelay.REPORT_MS) +
                                    "после съёмки (по часам камеры, RTCP), самый поздний — через ${probe.slowest} мс; " +
                                    "запись занимала поток чтения до ${busyMaxNs / 1_000_000} мс (${RecBusy.take()})")
                                videoBytes = 0
                                videoFrames = 0
                                busyMaxNs = 0
                            }
                            if (sniffing && sniffer != null) {
                                sniffer.push(rtp.payload, rtp.offset, rtp.length)
                                sniffer.params?.let { params ->
                                    sniffing = false
                                    H264Params.remember(key.url, params)
                                    relaySdp = relaySdp?.let { sdp -> Media3Sdp.compatible(sdp) { params } }
                                }
                            }
                            val started = System.nanoTime()
                            for (frame in depacketizer.push(rtp)) {
                                watch.frame()
                                // Метка кадра у разборщика — развёрнутая (64 бита), у пакета — 32 бита.
                                if (frame.key) keyTs = frame.timestamp and 0xFFFFFFFFL
                                recent.frame(frame.key)
                                each { _, sink -> sink.video(frame, depacketizer) }
                            }
                            busyMaxNs = maxOf(busyMaxNs, System.nanoTime() - started)
                            if (!summary.hasCodec && depacketizer.sps != null) {
                                summary.codec(depacketizer.codec, depacketizer.sps)
                                summary.snapshot().let { TabletProfile.cameraSize(key.url, it.width, it.height) }
                            }
                            if (rtp.marker) summary.frameEnd(rtp.timestamp, readNs, keyTs == rtp.timestamp)
                            if (frames != null) {
                                frames.busy(System.nanoTime() - readNs)
                                if (rtp.marker) frames.frame(rtp.timestamp, readNs, keyTs == rtp.timestamp)
                            }
                        } else if (setup === audioSetup && aac != null) {
                            audioProbe?.frame(rtp.timestamp)?.let {
                                audioArrivalMs = it
                                Log.i(TAG, "Камера → планшет: звук приходит через $it мс после записи (по часам камеры, RTCP)")
                            }
                            for (frame in aac.push(rtp)) each { a, sink -> if (a.recorder.wantsAudio) sink.audio(frame, aac.sampleRate) }
                        } else if (setup === audioSetup && g711 != null && enc != null && consumers.any { it.recorder.wantsAudio }) {
                            val pcm = g711.push(rtp)
                            // Привязка звука — по приходу отсчётов, а не по выходу кодера (у него своя задержка).
                            each { a, sink -> if (a.recorder.wantsAudio) sink.audioArrival(pcm.timestamp) }
                            for (frame in enc.encode(pcm)) each { a, sink -> if (a.recorder.wantsAudio) sink.audio(frame, enc.sampleRate) }
                        }
                    }

                    override fun onRtcp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        watch.check()
                        if (setup === video) {
                            probe.senderReport(packet, length)
                            frames?.senderReport(System.nanoTime(), probe.srRtp, probe.srWallMs)
                        }
                        else if (setup === audioSetup) audioProbe?.senderReport(packet, length)
                        relay(setup.rtpChannel, rtcp = true, packet, length)
                    }
                })
            } catch (e: Exception) {
                refused = e is RtspException && e.status == 401
                if (running) {
                    Log.w(TAG, "${e.javaClass.simpleName}: ${e.message}")
                    broadcast(CameraRecorder.State.RETRYING, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                // Новая сессия камеры — новые номера и метки RTP: клиенты ретранслятора переподключаются сами.
                relaySdp = null
                relayVideoTrack = -1
                videoArrivalMs = null
                audioArrivalMs = null
                recent.clear()
                relayClients.forEach { it.close() }
                synchronized(this) {
                    current = null
                    for (a in consumers) synchronized(a) { end(a) }
                }
                session?.close()
                session = null
                encoder?.release()
                trace?.close()
            }
            if (!running) break
            // Сессия держалась дольше минуты — начинаем отсчёт заново.
            if (System.nanoTime() - started > 60_000_000_000L) backoffMs = 2_000L
            // Камера не приняла пароль: пробовать его каждые 30 с незачем (часть камер за это блокирует вход) — раз в 5 минут.
            // Новый пароль из редактора запускает запись заново сразу (аудит 25.11.2026, REC-06).
            if (refused) { backoffMs = REFUSED_RETRY_MS; refused = false }
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { if (!running) break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        relaySdp = null
        broadcast(CameraRecorder.State.STOPPED)
    }

    private fun relay(channel: Int, rtcp: Boolean, packet: ByteArray, length: Int) {
        if (relayClients.isEmpty()) return
        val track = relayChannels[channel] ?: return
        for (c in relayClients) c.offer(track, rtcp, packet, length)
    }

    /**
     * Плеер начал смотреть: сначала видео с последнего ключевого кадра (картинка сразу, а не через секунды ожидания
     * следующего ключевого), потом живые пакеты. Отставание на возраст ключевого кадра плеер снимает рывком к живому.
     */
    internal fun addClient(client: RtspRelay.Client) {
        synchronized(recent) {
            val track = relayVideoTrack
            if (track >= 0) recent.replay { data, offset, length -> client.offer(track, rtcp = false, data, length, offset) }
            relayClients += client
        }
    }
    internal fun removeClient(client: RtspRelay.Client) { relayClients -= client }

    private companion object {
        /** Больше — уже не неровность прихода, а сбой связи. */
        const val MAX_JITTER_MS = 1_000L
        const val REFUSED_RETRY_MS = 300_000L
        const val TAG = "CameraHub"
    }
}

/**
 * Соединение с камерой живо, а видео не движется: камера шлёт только звук или её часы ушли назад (аудит 25.11.2026, REC-01). Отрезок
 * записи меняется только на кадре видео — звук копился бы в памяти без предела, а «● REC» горел бы. [stallMs] без продвижения видео
 * или [FRAME_MS] без единого целого кадра (у камер с редким ключевым кадром после потери пакета их нет десятки секунд) — ошибка
 * сессии, поток подключается заново.
 */
internal class VideoWatch(private val stallMs: Long, private val clock: () -> Long = System::nanoTime) {
    private val unwrapper = RtpTimestampUnwrapper()
    private var last = Long.MIN_VALUE
    private var movedNs = clock()
    private var frameNs = movedNs
    private var noticedNs = Long.MIN_VALUE

    /** Пакет видео с меткой RTP [timestamp]. */
    fun packet(timestamp: Long) {
        val ts = unwrapper.unwrap(timestamp)
        if (ts > last) { last = ts; movedNs = clock() }
    }

    /** Целый кадр. */
    fun frame() { frameNs = clock() }

    /** На каждом пакете сессии, в том числе звука и служебном. */
    fun check() {
        val now = clock()
        val stalled = now - movedNs > stallMs * 1_000_000 || now - frameNs > maxOf(FRAME_MS, stallMs) * 1_000_000
        if (!stalled) { noticedNs = Long.MIN_VALUE; return }
        // Стояла вся сеть и только что ожила: первым мог прийти звук — видео получает ещё немного времени.
        if (noticedNs == Long.MIN_VALUE) noticedNs = now
        if (now - noticedNs > stallMs * 1_000_000 / GRACE_PART) throw RtspException("Камера не присылает видео")
    }

    companion object {
        const val STALL_MS = 15_000L
        const val FRAME_MS = 60_000L
        private const val GRACE_PART = 5
    }
}

/**
 * Локальный RTSP-ретранслятор живого видео (127.0.0.1, только TCP interleaved — как просит плеер плитки).
 * Отдаёт описание потока камеры и пересылает её RTP-пакеты как есть: плеер видит ту же камеру, но соединение с
 * камерой одно. Адрес — с одноразовым токеном потока ([SharedStream.token]); чужой адрес — 404.
 */
internal object RtspRelay {
    @Volatile private var server: ServerSocket? = null

    /** Порт ретранслятора; null — не удалось открыть сокет (тогда живое видео идёт напрямую). */
    @Synchronized
    fun ensureStarted(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread({ accept(s) }, "rtsp-relay").apply { isDaemon = true; start() }
            s.localPort
        } catch (e: Exception) {
            Log.w("RtspRelay", "Ретранслятор не запущен: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun accept(s: ServerSocket) {
        var failures = 0
        while (!s.isClosed) {
            val socket = try { s.accept().also { failures = 0 } } catch (_: Exception) {
                // Разовая ошибка accept не останавливает ретранслятор (второй аудит 25.11.2026, NEW-CAM-2).
                if (s.isClosed || ++failures >= CameraHookServer.ACCEPT_FAILURES) break
                try { Thread.sleep(CameraHookServer.ACCEPT_PAUSE_MS) } catch (_: InterruptedException) { break }
                continue
            }
            Thread({ Client(socket).serve() }, "rtsp-relay-client").apply { isDaemon = true; start() }
        }
    }

    /**
     * Описание потока для плеера: только дорожки, для которых выполнен SETUP у камеры, со своими короткими
     * адресами (`track0`, `track1`…), кроме тех, что плеер не примет ([Media3Sdp.rejected]: звук AAC без fmtp —
     * иначе Media3 отвергает всё описание). Остальное описание камеры — как есть.
     */
    fun sdpFor(raw: String, setups: List<RtspSession.Setup>): String {
        val lines = raw.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }.let { if (it < 0) lines.size else it }
        val out = ArrayList<String>()
        lines.take(firstMedia).filterNot { it.startsWith("a=control:") }.forEach { out += it }
        out += "a=control:*"
        var i = firstMedia
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("m=")) j++
            val block = lines.subList(i, j)
            val track = SdpParser.parse(block.joinToString("\r\n")).firstOrNull()
            val index = setups.indexOfFirst { it.track == track }
            if (track != null && index >= 0 && !Media3Sdp.rejected(track)) {
                block.filterNot { it.startsWith("a=control:") }.forEach { out += it }
                out += "a=control:track$index"
            }
            i = j
        }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    /** Один плеер. Запросы — на своём потоке; пакеты — через ограниченную очередь своим потоком записи. */
    internal class Client(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
        /** Номер дорожки → канал interleaved, который попросил плеер. */
        private val channels = ConcurrentHashMap<Int, Int>()
        @Volatile private var playing = false
        @Volatile private var closed = false
        private var stream: SharedStream? = null
        private val session = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun offer(track: Int, rtcp: Boolean, packet: ByteArray, length: Int, offset: Int = 0) {
            if (!playing || closed) return
            val channel = channels[track]?.let { if (rtcp) it + 1 else it } ?: return
            val frame = ByteArray(length + 4)
            frame[0] = '$'.code.toByte(); frame[1] = channel.toByte(); frame[2] = (length shr 8).toByte(); frame[3] = length.toByte()
            System.arraycopy(packet, offset, frame, 4, length)
            // Плеер не успевает (завис, медленный) — отключаем: он переподключится, а камера и запись не ждут.
            if (!queue.offer(frame)) close()
        }

        fun close() {
            if (closed) return
            closed = true
            stream?.removeClient(this)
            try { socket.close() } catch (_: Exception) {}
        }

        fun serve() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 120_000
                Thread({ pump() }, "rtsp-relay-out").apply { isDaemon = true; start() }
                while (!closed) {
                    val first = input.read()
                    if (first < 0) break
                    if (first == '$'.code) { skipInterleaved(); continue } // RTCP плеера (отчёты получателя)
                    val request = readRequest(first.toChar()) ?: break
                    handle(request)
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        private fun pump() {
            try {
                while (!closed) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    synchronized(output) { output.write(frame) }
                    if (queue.isEmpty()) synchronized(output) { output.flush() }
                }
            } catch (_: Exception) {
                close()
            }
        }

        private class Request(val method: String, val uri: String, val headers: Map<String, String>)

        private fun readRequest(first: Char): Request? {
            val line = first + readLine()
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine()
                if (h.isEmpty()) break
                headers[h.substringBefore(':').trim().lowercase(Locale.ROOT)] = h.substringAfter(':', "").trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > 64 * 1024) return null
            repeat(length) { if (input.read() < 0) throw EOFException() }
            val parts = line.split(' ')
            return Request(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty(), headers)
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw EOFException()
            }
            return sb.toString()
        }

        private fun skipInterleaved() {
            input.read()
            val length = (input.read() shl 8) or input.read()
            if (length < 0) throw EOFException()
            repeat(length) { if (input.read() < 0) throw EOFException() }
        }

        private fun reply(cseq: String?, status: String, headers: List<Pair<String, String>> = emptyList(), body: String = "") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val text = StringBuilder("RTSP/1.0 $status\r\nCSeq: ${cseq.orEmpty()}\r\nServer: SmartHomeDashboard\r\n")
            headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
            if (bytes.isNotEmpty()) text.append("Content-Length: ").append(bytes.size).append("\r\n")
            text.append("\r\n")
            synchronized(output) {
                output.write(text.toString().toByteArray(Charsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
        }

        private fun handle(r: Request) {
            val cseq = r.headers["cseq"]
            val path = r.uri.substringAfter("://", r.uri).substringAfter('/', "")
            val token = path.substringBefore('/')
            val s = CameraHub.byToken(token)
            if (r.method != "OPTIONS" && (s == null || (stream != null && stream !== s))) { reply(cseq, "404 Not Found"); return }
            when (r.method) {
                "OPTIONS" -> reply(cseq, "200 OK", listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"))
                "DESCRIBE" -> {
                    val sdp = s!!.relaySdp ?: run { reply(cseq, "503 Service Unavailable"); return }
                    val base = "rtsp://127.0.0.1:${socket.localPort}/$token/"
                    reply(cseq, "200 OK", listOf("Content-Type" to "application/sdp", "Content-Base" to base), sdp)
                }
                "SETUP" -> {
                    val track = Regex("track(\\d+)$").find(path.trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()
                    val transport = r.headers["transport"].orEmpty()
                    val channel = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()
                    if (track == null) { reply(cseq, "404 Not Found"); return }
                    if (channel == null || !transport.contains("TCP", ignoreCase = true)) { reply(cseq, "461 Unsupported Transport"); return }
                    channels[track] = channel
                    reply(cseq, "200 OK", listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}",
                        "Session" to "$session;timeout=60"))
                }
                "PLAY" -> {
                    val target = s!!
                    if (!target.live) { reply(cseq, "503 Service Unavailable"); return }
                    stream = target
                    reply(cseq, "200 OK", listOf("Session" to session, "Range" to "npt=0.000-"))
                    playing = true
                    target.addClient(this)
                }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(cseq, "200 OK", listOf("Session" to session))
                "TEARDOWN" -> { reply(cseq, "200 OK", listOf("Session" to session)); close() }
                else -> reply(cseq, "405 Method Not Allowed")
            }
        }

        private companion object {
            /** ~ несколько секунд основного потока: дольше плеер не может отставать. */
            const val QUEUE = 2_000
        }
    }
}

/**
 * Видеопакеты RTP с последнего ключевого кадра (docs/doorbell.md, «Быстрый старт плитки»). Плеер, подключившийся к
 * ретранслятору, без них ждёт следующий ключевой кадр камеры — у субпотока до 4 с чёрной плитки после пробуждения
 * экрана, смены страницы или сохранения настроек. С ними первый кадр — сразу.
 *
 * Буфер один и заранее выделен (пакеты идут сотнями в секунду — без мусора для сборщика). Группа кадров, которая в
 * него не помещается (основной поток: до 1,3 МБ за 2 с), не хранится вовсе — основной поток и так подменяется
 * картинкой плитки, пока не придёт его ключевой кадр. Поток без битов marker не хранится: границы кадров неизвестны.
 */
internal class RecentVideo(capacity: Int = CAPACITY) {
    private val data = ByteArray(capacity)
    private var size = 0
    /** Где в буфере начался текущий кадр и лежит ли он там целиком. */
    private var frameStart = 0
    private var frameWhole = true
    private var frameEnded = true
    /** Буфер начинается с ключевого кадра. */
    private var valid = false
    /** Столько ближайших ключевых кадров пакеты не копятся (группа не поместилась). */
    private var skipGroups = 0

    /** Очередной видеопакет камеры (заголовок RTP — в начале [packet]). */
    @Synchronized fun add(packet: ByteArray, length: Int) {
        if (frameEnded) { frameStart = size; frameWhole = skipGroups == 0; frameEnded = false }
        when {
            skipGroups > 0 -> frameWhole = false
            size + PREFIX + length > data.size -> { reset(); skipGroups = SKIP_GROUPS }
            else -> {
                data[size] = (length ushr 8).toByte()
                data[size + 1] = length.toByte()
                System.arraycopy(packet, 0, data, size + PREFIX, length)
                size += PREFIX + length
            }
        }
        if (length > 1 && (packet[1].toInt() and MARKER) != 0) frameEnded = true
    }

    /** Кадр из только что принятых пакетов разобран; [key] — ключевой: с него начинается новая группа. */
    @Synchronized fun frame(key: Boolean) {
        if (!key) return
        if (skipGroups > 0) { skipGroups--; return }
        // Кадр закрыт не битом marker: границы кадров неизвестны — такой поток какое-то время не копим вовсе.
        if (!frameEnded) { reset(); skipGroups = SKIP_GROUPS; return }
        // Кадр лежит не целиком — начать с него нельзя; следующий ключевой подойдёт.
        if (!frameWhole) { reset(); return }
        System.arraycopy(data, frameStart, data, 0, size - frameStart)
        size -= frameStart
        frameStart = 0
        valid = true
    }

    /** Ключевого кадра в буфере ещё нет, но он ожидается (поток только начался): плееру стоит его подождать. */
    val pending: Boolean @Synchronized get() = !valid && skipGroups == 0

    /** Сохранённые пакеты по порядку: буфер, смещение, длина. Ничего — если ключевого кадра в буфере нет. */
    @Synchronized fun replay(each: (ByteArray, Int, Int) -> Unit) {
        if (!valid) return
        var at = 0
        while (at < size) {
            val length = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
            each(data, at + PREFIX, length)
            at += PREFIX + length
        }
    }

    @Synchronized fun clear() {
        reset()
        frameEnded = true
        skipGroups = 0
    }

    /** Буфер пуст; кадр, который сейчас идёт, уже не целый. */
    private fun reset() {
        size = 0
        frameStart = 0
        valid = false
        frameWhole = false
    }

    private companion object {
        /** Группа кадров субпотока: 4 с × 0,4 Мбит/с ≈ 200 КБ; с запасом. Основной поток сюда не помещается. */
        const val CAPACITY = 512 * 1024
        const val PREFIX = 2
        const val MARKER = 0x80
        /** ≈ минута основного потока (ключевой кадр раз в 2 с): столько не копим после неудачи, потом пробуем снова. */
        const val SKIP_GROUPS = 30
    }
}

/**
 * Задержка камеры до планшета (кодирование + сеть): по отчётам отправителя RTCP камера сообщает, какому времени её
 * часов соответствует метка RTP; кадр пришёл — разница с часами планшета. Имеет смысл, когда часы камеры и планшета
 * сверены (NTP); итог — медиана за минуту, в журнал (для разбора задержки живого видео).
 */
internal class ArrivalDelay(private val clockRate: Int, private val now: () -> Long = System::currentTimeMillis) {
    /** Последний отчёт отправителя: часы камеры (мс) и метка RTP того же момента. */
    var srWallMs = 0L
        private set
    var srRtp = 0L
        private set
    private val samples = ArrayList<Long>()
    private var since = 0L

    fun senderReport(packet: ByteArray, length: Int) {
        // Составной пакет RTCP: ищем отчёт отправителя (тип 200).
        var off = 0
        while (off + 20 <= length) {
            val type = packet[off + 1].toInt() and 0xFF
            val words = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
            if (type == 200) {
                val sec = u32(packet, off + 8)
                val frac = u32(packet, off + 12)
                srWallMs = (sec - NTP_TO_UNIX_S) * 1000 + (frac * 1000 shr 32)
                srRtp = u32(packet, off + 16)
                return
            }
            off += (words + 1) * 4
        }
    }

    /** Самая большая задержка за последнюю отчётную минуту (кадр, пришедший позже всех). */
    var slowest = 0L
        private set

    /** Кадр с меткой [rtpTimestamp] пришёл сейчас. Раз в минуту — медиана задержки, иначе null. */
    fun frame(rtpTimestamp: Long): Long? {
        if (srWallMs == 0L || clockRate <= 0) return null
        val diff = ((rtpTimestamp - srRtp) shl 32) shr 32 // разница по модулю 2^32 со знаком
        val shot = srWallMs + diff * 1000 / clockRate
        val t = now()
        // Часы планшета ушли назад: минута «не кончалась» бы, а замеры копились бы в памяти (аудит 25.11.2026, REC-06).
        if (t < since || samples.size >= MAX_SAMPLES) { samples.clear(); since = t }
        samples += t - shot
        if (since == 0L) since = t
        if (t - since < REPORT_MS) return null
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        slowest = sorted.last()
        samples.clear()
        since = t
        return median
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    companion object {
        private const val NTP_TO_UNIX_S = 2_208_988_800L
        const val REPORT_MS = 60_000L
        const val MAX_SAMPLES = 20_000
    }
}
