package com.example.smarthomedashboard

import android.util.Log
import java.io.File

/**
 * Запись с камер: одна дорожка камеры (docs/recording.md). Подключение, разбор RTP, звук и переподключение — в общем
 * потоке камеры ([CameraHub], этап 4): одно соединение на поток, сколько бы записей и плееров его ни использовали.
 * Что делать с кадрами — решает [Sink] наследника: круглосуточные отрезки ([ContinuousRecorder]) или ролики
 * событий ([EventRecorder]). [threadName] — прежнее имя потока, оставлено для журналов.
 */
@Suppress("UNUSED_PARAMETER")
internal abstract class CameraRecorder(
    val camera: String,
    private val url: String,
    private val user: String,
    private val password: String?,
    private val audio: Boolean,
    private val onStatus: (CameraRecorder, Status) -> Unit,
    private val sessionFactory: (String, String, String?) -> RtspSession,
    private val encoderFactory: () -> AacEncoder,
    threadName: String
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

    private var stream: SharedStream? = null
    @Volatile var status = Status(State.STOPPED)
        private set

    /** Писать звук (кадры звука этой записи не передаются, если выключено). */
    internal val wantsAudio get() = audio

    /** Новый приёмник на сессию камеры (из потока камеры). */
    internal abstract fun newSink(): Sink

    fun start() {
        if (stream != null) return
        stream = CameraHub.attach(CameraHub.Key(url.trim(), user, password), this, sessionFactory, encoderFactory)
    }

    /**
     * Остановить: текущий файл закрывается сразу; последняя запись закрывает и поток камеры (ждём ≤ [waitMs]). Файл
     * дописывается и переименовывается в потоке записи [RecIo] — ждём и его (≤ [waitMs]): после остановки файлы закрыты.
     */
    fun stop(waitMs: Long = 3_000) {
        val s = stream ?: return
        stream = null
        CameraHub.detach(s, this, waitMs)
        RecIo.flush(waitMs)
        report(State.STOPPED)
    }

    /**
     * Состояние общего потока (подключение, повтор); null — без изменений. После [stop] запись уже отключена от
     * потока и состояний не получает.
     */
    internal fun streamStatus(state: State?, detail: String) {
        if (state != null) report(state, detail)
    }

    protected fun report(state: State, detail: String = "") {
        if (status.state == state && status.detail == detail) return
        status = Status(state, detail)
        onStatus(this, status)
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
            val started = System.nanoTime()
            try { openFile(params, us) } finally { RecBusy.open(System.nanoTime() - started) }
        }

        private fun openFile(params: VideoParams, us: Long) {
            val base = root()
            if (base == null) { report(State.NO_STORAGE, "Нет места для записи"); return }
            if (!canWrite()) { report(State.NO_SPACE, "Запись остановлена: нет места"); return }
            val start = timeline.wallMs(us)
            val dir = RecLayout.dayDir(base, camera, start)
            if (!dir.isDirectory && !dir.mkdirs()) { report(State.NO_STORAGE, "Папка записи недоступна"); return }
            val f = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
            current = Mp4SegmentWriter(f, params, audioParams, queued = true)
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
            // Дописать, обрезать и переименовать — в потоке записи: поток камеры раздаёт живое видео и носитель не ждёт.
            RecLayout.finishLater(w, f, camera, RecFile.Kind.CONTINUOUS, segStartWall, (lastUs - segStartUs) / 1_000, onSegment)
        }
    }

    companion object {
        const val SEGMENT_MS = 60_000L
    }
}
