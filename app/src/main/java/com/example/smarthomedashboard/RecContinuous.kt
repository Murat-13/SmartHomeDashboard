package com.example.smarthomedashboard

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

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

    /** Носитель [File] не пишет (несколько раз подряд — служба переходит в память планшета). Поток камеры. */
    internal var onStorageError: (root: File) -> Unit = {}
    /** Часы планшета (в тестах — подмена). */
    internal var wallClock: () -> Long = System::currentTimeMillis
    /** Носитель не пишет — следующая попытка не раньше (в тестах меньше). */
    internal var storageRetryNs = 10_000_000_000L

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
     * Ошибка носителя, а не камеры: запись закрывает свой файл и пробует снова, поток камеры и живое видео не рвутся (аудит
     * 25.11.2026, REC-02). Остановка записи (поток прерван) и прочие ошибки — как раньше, выше.
     */
    protected fun storageError(e: Exception): Boolean = !Thread.currentThread().isInterrupted &&
        generateSequence<Throwable>(e) { it.cause }.take(CAUSES).any { it is IOException && it !is InterruptedIOException }

    /**
     * Общая шкала одной сессии: микросекунды от первого кадра видео. Звук и видео идут с разными отсчётами RTP:
     * звук привязывается ко времени прихода своего первого кадра (ошибка — десятки мс).
     */
    protected class Timeline(private val wallClock: () -> Long) {
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
                anchorWallMs = wallClock()
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

        private var offSince = Long.MIN_VALUE
        private var off = 0L

        /**
         * Сверить шкалу с часами планшета в момент текущего кадра [us] (на ключевых кадрах): время новых файлов — по часам, а не по
         * началу подключения (аудит 25.11.2026, REC-03). Часы поправились (расхождение больше [JUMP_MS]) — сразу; малый уход — если
         * держится [HOLD_US] потока: задержка прихода при сбое сети им не считается.
         */
        fun sync(us: Long) {
            val now = wallClock() - wallMs(us)
            if (kotlin.math.abs(now) > JUMP_MS) { anchorWallMs += now; offSince = Long.MIN_VALUE; return }
            if (kotlin.math.abs(now) <= DRIFT_MS) { offSince = Long.MIN_VALUE; return }
            if (offSince == Long.MIN_VALUE || (now > 0) != (off > 0)) { offSince = us; off = now; return }
            if (kotlin.math.abs(now) < kotlin.math.abs(off)) off = now
            if (us - offSince >= HOLD_US) { anchorWallMs += off; offSince = Long.MIN_VALUE }
        }
    }

    companion object {
        const val TAG = "Recording"
        private const val CAUSES = 5
        internal const val JUMP_MS = 60_000L
        internal const val DRIFT_MS = 2_000L
        internal const val HOLD_US = 20_000_000L
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
        private val timeline = Timeline { wallClock() }
        override var videoClock: Int
            get() = timeline.videoClock
            set(v) { timeline.videoClock = v }
        private var current: Mp4SegmentWriter? = null
        private var file: File? = null
        /** Носитель идущего файла и когда можно пробовать снова после его ошибки. */
        private var fileRoot: File? = null
        private var retryAtNs = 0L
        private var segStartUs = 0L
        private var segStartWall = 0L
        private var lastUs = 0L

        override fun video(frame: VideoFrame, d: VideoDepacketizer) {
            val us = timeline.videoUs(frame)
            if (frame.key) timeline.sync(us)
            val w = current
            if (frame.key) {
                val params = VideoParams(d.codec, d.vps, d.sps ?: return, d.pps ?: return)
                if (w == null || us - segStartUs >= segmentMs * 1_000 || !params.sameAs(w.video) || moved()) {
                    close()
                    stored { open(params, us) }
                }
            }
            val target = current ?: return
            stored { target.writeVideo(frame, us - segStartUs) }
            lastUs = us
        }

        /** Носитель сменился (карту извлекают, она перестала писать) — файл закрывается, новый на другом. */
        private fun moved() = fileRoot?.let { it.path != root()?.path } == true

        private inline fun stored(block: () -> Unit) {
            try { block() } catch (e: Exception) { if (storageError(e)) storageFailed(e) else throw e }
        }

        private fun storageFailed(e: Exception) {
            Log.w(TAG, "Запись: носитель не пишет (${e.javaClass.simpleName}: ${e.message})")
            val broken = fileRoot
            close()
            retryAtNs = System.nanoTime() + storageRetryNs
            report(State.RETRYING, STORAGE_RETRYING)
            broken?.let(onStorageError)
        }

        override fun audioArrival(ts: Long) = timeline.audioArrival(ts)

        override fun audio(frame: AudioFrame, rate: Int) {
            val us = timeline.audioUs(frame, rate) ?: return
            val target = current ?: return
            stored { target.writeAudio(frame, us - segStartUs) }
        }

        private fun open(params: VideoParams, us: Long) {
            val started = System.nanoTime()
            if (started < retryAtNs && root()?.path == fileRoot?.path) return
            try { openFile(params, us) } finally { RecBusy.open(System.nanoTime() - started) }
        }

        private fun openFile(params: VideoParams, us: Long) {
            val base = root()
            if (base == null) { report(State.NO_STORAGE, "Нет места для записи"); return }
            if (!canWrite()) { report(State.NO_SPACE, "Запись остановлена: нет места"); return }
            val dir = RecLayout.dayDir(base, camera, timeline.wallMs(us))
            fileRoot = base
            if (!dir.isDirectory && !dir.mkdirs()) {
                report(State.NO_STORAGE, "Папка записи недоступна")
                retryAtNs = System.nanoTime() + storageRetryNs
                onStorageError(base)
                return
            }
            val start = RecLayout.freeStart(dir, RecFile.Kind.CONTINUOUS, timeline.wallMs(us))
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
        internal const val STORAGE_RETRYING = "носитель не пишет — пробую снова"
    }
}
