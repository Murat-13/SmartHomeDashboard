package com.example.smarthomedashboard

import android.util.Log
import java.io.File

/**
 * Запись с камер: одна RTSP-дорожка камеры на своём потоке (docs/recording.md). Общий цикл для круглосуточной
 * записи ([ContinuousRecorder]) и записи событий ([EventRecorder]): подключение, разбор RTP в кадры, звук
 * (AAC как есть, G.711 → AAC), переподключение 2→30 с. Что делать с кадрами — решает [Sink] наследника.
 */
internal abstract class CameraRecorder(
    val camera: String,
    private val url: String,
    private val user: String,
    private val password: String?,
    private val audio: Boolean,
    private val onStatus: (CameraRecorder, Status) -> Unit,
    private val sessionFactory: (String, String, String?) -> RtspSession,
    private val encoderFactory: () -> AacEncoder,
    private val threadName: String
) {
    enum class State { CONNECTING, RECORDING, RETRYING, NO_SPACE, NO_STORAGE, STOPPED }
    data class Status(val state: State, val detail: String = "", val sinceMs: Long = System.currentTimeMillis())

    /** Приёмник кадров одной RTSP-сессии; метки времени — отсчёты RTP своей дорожки. */
    internal interface Sink {
        var audioParams: AudioParams?
        var videoClock: Int
        fun video(frame: VideoFrame, d: VideoDepacketizer)
        /** Отсчёты G.711 пришли с меткой [ts] (до кодера AAC с его задержкой). */
        fun audioArrival(ts: Long)
        fun audio(frame: AudioFrame, rate: Int)
        fun close()
    }

    @Volatile private var running = false
    private var encoder: AacEncoder? = null
    @Volatile private var session: RtspSession? = null
    private var thread: Thread? = null
    @Volatile var status = Status(State.STOPPED)
        private set

    protected abstract fun newSink(): Sink

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, threadName).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** Остановить и дождаться закрытия текущего файла (не дольше [waitMs]). */
    fun stop(waitMs: Long = 3_000) {
        running = false
        session?.close()
        thread?.interrupt()
        thread?.join(waitMs)
        thread = null
    }

    protected fun report(state: State, detail: String = "") {
        if (status.state == state && status.detail == detail) return
        status = Status(state, detail)
        onStatus(this, status)
    }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            val started = System.nanoTime()
            report(State.CONNECTING)
            val writer = newSink()
            try {
                val s = sessionFactory(url, user, password)
                session = s
                if (!running) { s.close(); break }
                val setups = s.open { t -> (t.isVideo && (t.encoding == "H264" || t.encoding == "H265" || t.encoding == "HEVC")) ||
                    (audio && t.isAudio) }
                val video = setups.firstOrNull { it.track.isVideo } ?: throw RtspException("Камера не отдаёт H.264/H.265")
                val depacketizer = VideoDepacketizer.create(video.track) ?: throw RtspException("Кодек не поддерживается")
                val audioSetup = setups.firstOrNull { it.track.isAudio }
                val aac = audioSetup?.let { AacDepacketizer.create(it.track) }
                // G.711 (PCMA/PCMU) — перекодируется в AAC; не вышло — запись без звука.
                val g711 = if (aac == null) audioSetup?.let { G711Depacketizer.create(it.track) } else null
                encoder = g711?.let {
                    try { encoderFactory() } catch (e: Exception) {
                        Log.w(TAG, "$camera: AAC-кодер недоступен (${e.javaClass.simpleName}), запись без звука")
                        null
                    }
                }
                if (audioSetup != null && aac == null && encoder == null) {
                    Log.i(TAG, "$camera: звук ${audioSetup.track.encoding} не записывается")
                }
                writer.audioParams = aac?.let { AudioParams(it.config, it.sampleRate, it.channels) }
                    ?: encoder?.let { AudioParams(it.config, it.sampleRate, it.channels) }
                val enc = encoder
                writer.videoClock = video.track.clockRate.takeIf { it > 0 } ?: 90_000
                s.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        val rtp = RtpPacket.parse(packet, length) ?: return
                        if (setup === video) {
                            for (frame in depacketizer.push(rtp)) writer.video(frame, depacketizer)
                        } else if (setup === audioSetup && aac != null) {
                            for (frame in aac.push(rtp)) writer.audio(frame, aac.sampleRate)
                        } else if (setup === audioSetup && g711 != null && enc != null) {
                            val pcm = g711.push(rtp)
                            // Привязка звука — по приходу отсчётов, а не по выходу кодера (у него своя задержка).
                            writer.audioArrival(pcm.timestamp)
                            for (frame in enc.encode(pcm)) writer.audio(frame, enc.sampleRate)
                        }
                    }
                })
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "$camera: ${e.javaClass.simpleName}: ${e.message}")
                    report(State.RETRYING, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                try { writer.close() } catch (e: Exception) { Log.w(TAG, "$camera: файл не закрыт: ${e.message}") }
                session?.close()
                session = null
                encoder?.release()
                encoder = null
            }
            if (!running) break
            // Сессия держалась дольше минуты — начинаем отсчёт заново.
            if (System.nanoTime() - started > 60_000_000_000L) backoffMs = 2_000L
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { if (!running) break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        report(State.STOPPED)
    }

    /**
     * Общая шкала одной сессии: микросекунды от первого кадра видео. Звук и видео идут с разными отсчётами RTP:
     * звук привязывается ко времени прихода своего первого кадра (ошибка — десятки мс).
     */
    protected class Timeline {
        var videoClock = 90_000
        private var anchorNanos = 0L
        var anchorWallMs = 0L
            private set
        private var videoAnchorTs = Long.MIN_VALUE
        private var audioAnchorTs = Long.MIN_VALUE
        private var audioAnchorUs = 0L

        val started get() = videoAnchorTs != Long.MIN_VALUE

        fun videoUs(frame: VideoFrame): Long {
            if (videoAnchorTs == Long.MIN_VALUE) {
                videoAnchorTs = frame.timestamp
                anchorNanos = System.nanoTime()
                anchorWallMs = System.currentTimeMillis()
            }
            return (frame.timestamp - videoAnchorTs) * 1_000_000L / videoClock
        }

        fun audioArrival(ts: Long) {
            if (!started || audioAnchorTs != Long.MIN_VALUE) return
            audioAnchorTs = ts
            audioAnchorUs = (System.nanoTime() - anchorNanos) / 1_000
        }

        /** null — видео ещё не началось (звук до первого кадра видео не пишется). */
        fun audioUs(frame: AudioFrame, rate: Int): Long? {
            if (!started) return null
            if (audioAnchorTs == Long.MIN_VALUE) {
                audioAnchorTs = frame.timestamp
                audioAnchorUs = (System.nanoTime() - anchorNanos) / 1_000
            }
            return audioAnchorUs + (frame.timestamp - audioAnchorTs) * 1_000_000L / rate
        }

        /** Время на стене для момента [us] шкалы. */
        fun wallMs(us: Long) = anchorWallMs + us / 1_000
    }

    companion object {
        const val TAG = "Recording"
    }
}

/**
 * Непрерывная дорожка (24/7) одной камеры: отрезки fMP4 по [segmentMs] (режется на ключевом кадре). Обрыв —
 * текущий отрезок закрывается, на шкале будет пропуск. Новый отрезок открывается, только если [canWrite]
 * (есть место сверх неприкосновенного остатка).
 */
internal class ContinuousRecorder(
    camera: String,
    url: String,
    user: String,
    password: String?,
    audio: Boolean,
    private val root: () -> File?,
    private val canWrite: () -> Boolean,
    private val onSegment: (RecFile) -> Unit,
    onStatus: (CameraRecorder, Status) -> Unit,
    private val segmentMs: Long = SEGMENT_MS,
    sessionFactory: (String, String, String?) -> RtspSession = { u, l, p -> RtspSession(u, l, p) },
    encoderFactory: () -> AacEncoder = { AacEncoder() }
) : CameraRecorder(camera, url, user, password, audio, onStatus, sessionFactory, encoderFactory, "rec-$camera") {

    override fun newSink(): Sink = SegmentRotation()

    /** Нарезка потока на отрезки; живёт одну RTSP-сессию. */
    private inner class SegmentRotation : Sink {
        override var audioParams: AudioParams? = null
        private val timeline = Timeline()
        override var videoClock: Int
            get() = timeline.videoClock
            set(v) { timeline.videoClock = v }
        private var current: Mp4SegmentWriter? = null
        private var file: File? = null
        private var segStartUs = 0L
        private var segStartWall = 0L
        private var lastUs = 0L

        override fun video(frame: VideoFrame, d: VideoDepacketizer) {
            val us = timeline.videoUs(frame)
            val w = current
            if (frame.key) {
                val params = VideoParams(d.codec, d.vps, d.sps ?: return, d.pps ?: return)
                if (w == null || us - segStartUs >= segmentMs * 1_000 || !params.sameAs(w.video)) {
                    close()
                    open(params, us)
                }
            }
            val target = current ?: return
            target.writeVideo(frame, us - segStartUs)
            lastUs = us
        }

        override fun audioArrival(ts: Long) = timeline.audioArrival(ts)

        override fun audio(frame: AudioFrame, rate: Int) {
            val us = timeline.audioUs(frame, rate) ?: return
            val target = current ?: return
            target.writeAudio(frame, us - segStartUs)
        }

        private fun open(params: VideoParams, us: Long) {
            val base = root()
            if (base == null) { report(State.NO_STORAGE, "Нет места для записи"); return }
            if (!canWrite()) { report(State.NO_SPACE, "Запись остановлена: нет места"); return }
            val start = timeline.wallMs(us)
            val dir = RecLayout.dayDir(base, camera, start)
            if (!dir.isDirectory && !dir.mkdirs()) { report(State.NO_STORAGE, "Папка записи недоступна"); return }
            val f = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
            current = Mp4SegmentWriter(f, params, audioParams)
            file = f
            segStartUs = us
            segStartWall = start
            report(State.RECORDING)
        }

        override fun close() {
            val w = current ?: return
            val f = file
            current = null
            file = null
            RecLayout.finish(w, f, camera, RecFile.Kind.CONTINUOUS, segStartWall, (lastUs - segStartUs) / 1_000)?.let(onSegment)
        }
    }

    companion object {
        const val SEGMENT_MS = 60_000L
    }
}
