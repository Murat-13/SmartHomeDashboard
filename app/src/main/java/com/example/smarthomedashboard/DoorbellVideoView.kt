package com.example.smarthomedashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Видео домофона (docs/doorbell.md). Пути:
 * 1. RTSP напрямую с домофона (дома; логин в config, пароль — в защищённом хранилище);
 * 2. HLS через Home Assistant (`camera/stream`) — вне дома или если прямой путь не отвечает;
 * 3. снимок HA (`entity_picture`) — режим «снимок раз в минуту» и подложка, пока видео подключается.
 *
 * Плеер существует, только пока видео действительно видно: View в окне, показана, окно видно, экран не
 * затемнён ([PanelMotion.dimmed]), плитка не скрыта раскрытием ([hiddenBySource]) и владелец разрешил ([active]).
 * Иначе плеер освобождается (декодер и соединение с домофоном) с короткой задержкой от дребезга.
 * Ошибки — переподключение с нарастающей паузой и чередованием путей. Только главный поток.
 */
@OptIn(UnstableApi::class)
internal class DoorbellVideoView(context: Context) : FrameLayout(context), PanelMotion.Listener {
    enum class Status { IDLE, CONNECTING, PLAYING, SNAPSHOT, NO_SOURCE, ERROR }

    data class Source(
        val rtsp: String,
        val user: String,
        val camera: String,
        val snapshotOnly: Boolean
    )

    private val main = Handler(Looper.getMainLooper())
    private val snapshot = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        tag = "door_snapshot"
    }
    private val texture = TextureView(context).apply { alpha = 0f; tag = "door_texture" }
    private var player: ExoPlayer? = null
    private var source: Source? = null
    private var picture: String? = null
    private var pictureLoadedAt = 0L
    private var pictureCall: Call? = null
    private var failures = 0
    private var useHa = false
    private var hiddenBySource = false
    private var windowShown = true
    private var startedAt = 0L

    /** Звук: в компактной плитке выключен, в раскрытой и окне звонка — включён. */
    var muted = true
        set(value) { field = value; player?.volume = if (value) 0f else 1f }

    /** Владелец разрешает видео (например, окно звонка поверх — компактной плитке не играть). */
    var active = true
        set(value) { if (field != value) { field = value; update() } }

    var status = Status.IDLE
        private set(value) { if (field != value) { field = value; onStatus?.invoke(value) } }
    var onStatus: ((Status) -> Unit)? = null
    /** Реальная пропорция кадра (ширина/высота) — для плитки «по видео». */
    var onAspect: ((Float) -> Unit)? = null
    var videoAspect = DoorbellConfig.DEFAULT_ASPECT
        private set

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = fitTexture()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fitTexture()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    /** Новые настройки или новый снимок HA. Плеер перезапускается только при смене источника. */
    fun bind(next: Source, picturePath: String?) {
        if (next != source) {
            source = next
            failures = 0
            useHa = next.rtsp.isBlank()
            releasePlayer()
        }
        if (picturePath != picture) {
            picture = picturePath
            // Токен снимка HA меняется каждые несколько минут — это не новый кадр, лишняя загрузка не нужна.
            if (bitmapMissing() || SystemClock.elapsedRealtime() - pictureLoadedAt >= SNAPSHOT_EVERY_MS) loadPicture()
        }
        update()
    }

    fun setHiddenBySource(hidden: Boolean) {
        if (hiddenBySource == hidden) return
        hiddenBySource = hidden
        update()
    }

    private fun bitmapMissing() = snapshot.drawable == null

    private val visible get() = isAttachedToWindow && isShown && windowShown && !hiddenBySource && !PanelMotion.dimmed && active

    private fun update() {
        main.removeCallbacks(releaseLater)
        main.removeCallbacks(snapshotTick)
        val src = source
        if (src == null || (src.rtsp.isBlank() && src.camera.isBlank())) {
            releasePlayer(); status = Status.NO_SOURCE; return
        }
        if (!visible) {
            // Короткая пауза: перелистывание страницы туда-обратно не рвёт соединение с домофоном.
            if (player != null) main.postDelayed(releaseLater, RELEASE_DELAY_MS)
            return
        }
        if (src.snapshotOnly || !ENABLED) {
            releasePlayer()
            status = if (bitmapMissing()) Status.CONNECTING else Status.SNAPSHOT
            if (SystemClock.elapsedRealtime() - pictureLoadedAt >= SNAPSHOT_EVERY_MS) loadPicture()
            main.postDelayed(snapshotTick, SNAPSHOT_EVERY_MS)
            return
        }
        if (player == null && !retryPending) start()
    }

    private val releaseLater = Runnable { releasePlayer(); if (status == Status.PLAYING) status = Status.IDLE }
    private val snapshotTick = Runnable { loadPicture(); update() }
    private var retryPending = false
    private val retry = Runnable { retryPending = false; if (visible && player == null) start() }

    private fun start() {
        val src = source ?: return
        status = Status.CONNECTING
        if (bitmapMissing()) loadPicture()
        if (useHa || src.rtsp.isBlank()) {
            if (src.camera.isBlank()) { useHa = false; startRtsp(src) } else startHls(src)
        } else startRtsp(src)
    }

    private fun startRtsp(src: Source) {
        val generation = ++attempt
        DoorbellHub.cameraPassword(context) { password ->
            if (generation != attempt || !visible || player != null) return@cameraPassword
            val url = DoorbellConfig.withCredentials(src.rtsp, src.user, password)
            val media = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            play { it.setMediaSource(media) }
        }
    }

    private var attempt = 0

    private fun startHls(src: Source) {
        val generation = ++attempt
        DoorbellHub.streamUrl(src.camera) { url ->
            if (generation != attempt || !visible || player != null) return@streamUrl
            if (url == null) { failed("HA не выдал поток"); return@streamUrl }
            val media = HlsMediaSource.Factory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000))
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            play { it.setMediaSource(media) }
        }
    }

    private fun play(setSource: (ExoPlayer) -> Unit) {
        val exo = ExoPlayer.Builder(context)
            // Живое видео: минимальный буфер — задержка важнее плавности при обрывах сети.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(300, 2_000, 150, 300).build())
            .build()
        player = exo
        startedAt = SystemClock.elapsedRealtime()
        exo.volume = if (muted) 0f else 1f
        exo.setVideoTextureView(texture)
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player !== exo || videoSize.width <= 0 || videoSize.height <= 0) return
                val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                if (kotlin.math.abs(aspect - videoAspect) > 0.01f) { videoAspect = aspect; onAspect?.invoke(aspect) }
                fitTexture()
            }
            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                failures = 0
                status = Status.PLAYING
                texture.animate().alpha(1f).setDuration(220).start()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === exo) failed(error.errorCodeName)
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Поток домофона не заканчивается: конец — обрыв.
                if (player === exo && state == Player.STATE_ENDED) failed("поток закончился")
            }
        })
        setSource(exo)
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun failed(reason: String) {
        Log.w(TAG, "Видео домофона: $reason")
        releasePlayer()
        failures++
        val src = source ?: return
        // Есть оба пути — чередуем: дома прямой RTSP, вне дома HA.
        if (src.rtsp.isNotBlank() && src.camera.isNotBlank()) useHa = !useHa
        status = Status.ERROR
        val delay = (RETRY_BASE_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(RETRY_MAX_MS)
        retryPending = true
        main.postDelayed(retry, delay)
    }

    private fun releasePlayer() {
        attempt++
        main.removeCallbacks(retry)
        retryPending = false
        val exo = player ?: return
        player = null
        texture.animate().cancel()
        texture.alpha = 0f
        exo.clearVideoTextureView(texture)
        exo.release()
    }

    /** Кадр заполняет плитку без искажений (обрезка по краям, как у снимка). */
    private fun fitTexture() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0) return
        val view = w / h
        val sx: Float
        val sy: Float
        if (videoAspect > view) { sx = videoAspect / view; sy = 1f } else { sx = 1f; sy = view / videoAspect }
        texture.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitTexture() }
    }

    private fun loadPicture() {
        val path = picture ?: return
        val base = DoorbellHub.httpBase() ?: return
        pictureCall?.cancel()
        val call = DoorbellHub.http.newCall(Request.Builder().url(base + path).build())
        pictureCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val bitmap: Bitmap? = response.use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()?.let { bytes ->
                        // Компактной плитке не нужен полный кадр 2560×1920: уменьшаем при декодировании.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= 960) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                main.post {
                    if (pictureCall !== call || bitmap == null) return@post
                    pictureLoadedAt = SystemClock.elapsedRealtime()
                    snapshot.setImageBitmap(bitmap)
                    if (status == Status.CONNECTING && source?.snapshotOnly == true) status = Status.SNAPSHOT
                }
            }
        })
    }

    override fun motionChanged() = update()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PanelMotion.add(this)
        update()
    }

    override fun onDetachedFromWindow() {
        PanelMotion.remove(this)
        main.removeCallbacksAndMessages(null)
        pictureCall?.cancel()
        pictureCall = null
        releasePlayer()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == View.VISIBLE
        update()
    }

    fun dispose() {
        onStatus = null
        onAspect = null
        main.removeCallbacksAndMessages(null)
        pictureCall?.cancel()
        releasePlayer()
    }

    internal val hasPlayer get() = player != null

    companion object {
        private const val TAG = "DoorbellVideo"
        const val SNAPSHOT_EVERY_MS = 60_000L
        private const val RELEASE_DELAY_MS = 2_500L
        private const val RTSP_TIMEOUT_MS = 6_000L
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        /** В тестах Robolectric декодеров нет: только снимок. */
        internal val ENABLED = android.os.Build.FINGERPRINT != "robolectric"
    }
}
