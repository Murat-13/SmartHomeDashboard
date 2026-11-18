package com.example.smarthomedashboard

import android.util.Log
import java.io.File

/**
 * Запись с камер: непрерывная дорожка (24/7) одной камеры на своём потоке (docs/recording.md).
 * Сессия RTSP → кадры → отрезки fMP4 по [segmentMs] (режется на ключевом кадре). Обрыв — переподключение 2→30 с,
 * текущий отрезок закрывается, на шкале будет пропуск. Новый отрезок открывается, только если [canWrite]
 * (есть место сверх неприкосновенного остатка).
 */
internal class ContinuousRecorder(
    val camera: String,
    private val url: String,
    private val user: String,
    private val password: String?,
    private val audio: Boolean,
    private val root: () -> File?,
    private val canWrite: () -> Boolean,
    private val onSegment: (RecFile) -> Unit,
    private val onStatus: (ContinuousRecorder, Status) -> Unit,
    private val segmentMs: Long = SEGMENT_MS,
    private val sessionFactory: (String, String, String?) -> RtspSession = { u, l, p -> RtspSession(u, l, p) }
) {
    enum class State { CONNECTING, RECORDING, RETRYING, NO_SPACE, NO_STORAGE, STOPPED }
    data class Status(val state: State, val detail: String = "", val sinceMs: Long = System.currentTimeMillis())

    @Volatile private var running = false
    @Volatile private var session: RtspSession? = null
    private var thread: Thread? = null
    @Volatile var status = Status(State.STOPPED)
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "rec-$camera").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** Остановить и дождаться закрытия текущего отрезка (не дольше [waitMs]). */
    fun stop(waitMs: Long = 3_000) {
        running = false
        session?.close()
        thread?.interrupt()
        thread?.join(waitMs)
        thread = null
    }

    private fun report(state: State, detail: String = "") {
        if (status.state == state && status.detail == detail) return
        status = Status(state, detail)
        onStatus(this, status)
    }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            val started = System.nanoTime()
            report(State.CONNECTING)
            val writer = SegmentRotation()
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
                if (audioSetup != null && aac == null) {
                    Log.i(TAG, "$camera: звук ${audioSetup.track.encoding} не записывается (нужен AAC)")
                }
                writer.audioParams = aac?.let { AudioParams(it.config, it.sampleRate, it.channels) }
                writer.videoClock = video.track.clockRate.takeIf { it > 0 } ?: 90_000
                s.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {
                        val rtp = RtpPacket.parse(packet, length) ?: return
                        if (setup === video) {
                            for (frame in depacketizer.push(rtp)) writer.video(frame, depacketizer)
                        } else if (setup === audioSetup && aac != null) {
                            for (frame in aac.push(rtp)) writer.audio(frame, aac.sampleRate)
                        }
                    }
                })
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "$camera: ${e.javaClass.simpleName}: ${e.message}")
                    report(State.RETRYING, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                try { writer.close() } catch (e: Exception) { Log.w(TAG, "$camera: отрезок не закрыт: ${e.message}") }
                session?.close()
                session = null
            }
            if (!running) break
            // Сессия держалась дольше минуты — начинаем отсчёт заново.
            if (System.nanoTime() - started > 60_000_000_000L) backoffMs = 2_000L
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { if (!running) break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        report(State.STOPPED)
    }

    /** Нарезка потока на отрезки; живёт одну RTSP-сессию. Метки сессии — от первого кадра видео. */
    private inner class SegmentRotation {
        var audioParams: AudioParams? = null
        var videoClock = 90_000
        private var anchorNanos = 0L
        private var anchorWallMs = 0L
        private var videoAnchorTs = Long.MIN_VALUE
        private var audioAnchorTs = Long.MIN_VALUE
        private var audioAnchorUs = 0L
        private var current: Mp4SegmentWriter? = null
        private var file: File? = null
        private var segStartUs = 0L
        private var segStartWall = 0L
        private var lastUs = 0L

        fun video(frame: VideoFrame, d: VideoDepacketizer) {
            if (videoAnchorTs == Long.MIN_VALUE) {
                videoAnchorTs = frame.timestamp
                anchorNanos = System.nanoTime()
                anchorWallMs = System.currentTimeMillis()
            }
            val us = (frame.timestamp - videoAnchorTs) * 1_000_000L / videoClock
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

        fun audio(frame: AudioFrame, rate: Int) {
            if (videoAnchorTs == Long.MIN_VALUE) return
            if (audioAnchorTs == Long.MIN_VALUE) {
                // Звук и видео идут с разными отсчётами RTP: звук привязывается ко времени прихода первого кадра.
                audioAnchorTs = frame.timestamp
                audioAnchorUs = (System.nanoTime() - anchorNanos) / 1_000
            }
            val us = audioAnchorUs + (frame.timestamp - audioAnchorTs) * 1_000_000L / rate
            val target = current ?: return
            target.writeAudio(frame, us - segStartUs)
        }

        private fun open(params: VideoParams, us: Long) {
            val base = root()
            if (base == null) { report(State.NO_STORAGE, "Нет места для записи"); return }
            if (!canWrite()) { report(State.NO_SPACE, "Запись остановлена: нет места"); return }
            val start = anchorWallMs + us / 1_000
            val dir = RecLayout.dayDir(base, camera, start)
            if (!dir.isDirectory && !dir.mkdirs()) { report(State.NO_STORAGE, "Папка записи недоступна"); return }
            val f = File(dir, RecLayout.openName(RecFile.Kind.CONTINUOUS, start))
            current = Mp4SegmentWriter(f, params, audioParams)
            file = f
            segStartUs = us
            segStartWall = start
            report(State.RECORDING)
        }

        fun close() {
            val w = current ?: return
            val f = file
            current = null
            file = null
            try { w.close() } finally {
                // Отрезок без единого фрагмента (обрыв сразу после ключевого кадра) — не хранить пустышку.
                if (f != null && (try { Mp4Repair.truncateToLastFragment(f) } catch (_: Exception) { -1L }) <= 0) {
                    f.delete()
                } else if (f != null) {
                    // Последний кадр длится примерно столько же, сколько предыдущие: добавляем 1/25 с.
                    val duration = (lastUs - segStartUs) / 1_000 + 40
                    val done = File(f.parentFile, RecLayout.closedName(RecFile.Kind.CONTINUOUS, segStartWall, duration))
                    if (f.renameTo(done)) {
                        onSegment(RecFile(done, camera, RecFile.Kind.CONTINUOUS, segStartWall, duration, done.length()))
                    }
                }
            }
        }
    }

    companion object {
        const val SEGMENT_MS = 60_000L
        private const val TAG = "Recording"
    }
}
