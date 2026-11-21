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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
 * затемнён ([PanelMotion.dimmed]) и владелец разрешил ([active]). Под раскрытием ([hiddenBySource]) субпоток плитки
 * продолжает идти (решение владельца): после сворачивания картинка сразу живая, а субпоток почти ничего не стоит.
 * Иначе плеер освобождается (декодер и соединение с домофоном) с короткой задержкой от дребезга.
 * Ошибки — переподключение с нарастающей паузой и чередованием путей. Только главный поток.
 */
@OptIn(UnstableApi::class)
internal class DoorbellVideoView(context: Context) : FrameLayout(context), PanelMotion.Listener {
    /** OFFLINE — домофон недоступен по данным HA: не подключаемся вовсе, пока он не вернётся. */
    enum class Status { IDLE, CONNECTING, PLAYING, SNAPSHOT, NO_SOURCE, ERROR, OFFLINE }

    /**
     * [fallbackRtsp] — субпоток для раскрытия и окна звонка: если основной поток на этом планшете не декодируется
     * вовсе — ни аппаратно, ни программно, — показывается он. Samsung SM-X216B: аппаратный H.264 не больше
     * 1920×1088, основной Reolink — 2560×1920; тогда сначала пробуется программный декодер Android.
     */
    data class Source(
        val rtsp: String,
        val user: String,
        val camera: String,
        val snapshotOnly: Boolean,
        val fallbackRtsp: String = "",
        /** Ключ пароля камеры ([CameraPasswords]): номер плитки «Камера»; пусто — пароль домофона. */
        val secretKey: String = ""
    )

    private val main = Handler(Looper.getMainLooper())
    private val snapshot = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        tag = "door_snapshot"
    }
    private val texture = TextureView(context).apply { alpha = 0f; tag = "door_texture" }
    /** Чужой плеер на время подключения своего (см. [lendTo], [handOffTo]). Между снимком и своим видео. */
    private val guestTexture = TextureView(context).apply { alpha = 0f; tag = "door_guest" }
    private var guest: ExoPlayer? = null
    /** Владелец одолженного плеера; null — плеер передан насовсем (освобождаем сами). */
    private var guestOwner: DoorbellVideoView? = null
    private var guestAspect = DoorbellConfig.DEFAULT_ASPECT
    /** Кому одолжен свой плеер (вывод картинки сейчас там). */
    private var loanedTo: DoorbellVideoView? = null
    /** Пара (плитка ↔ раскрытие): при закрытии свой плеер отдаётся ей, если у неё ещё нет картинки. */
    var partner: DoorbellVideoView? = null
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
        set(value) {
            field = value
            applyVolume()
        }

    init { synchronized(all) { all += this } }

    /** Приглушение на время разговора (docs/talk-tz.md): звук с улицы не попадает обратно в микрофон. */
    var ducked = false
        set(value) {
            field = value
            applyVolume()
        }

    private val volume get() = if (muted || ducked || streetAudio) 0f else 1f

    /**
     * Идёт разговор как по телефону (docs/talk-tz.md, этап 2): звук с улицы — как голос собеседника (разговорный
     * поток Android), тогда системное подавление эха знает, что вычесть из микрофона.
     */
    var callAudio = false
        set(value) {
            if (field == value) return
            field = value
            player?.let(::applyAudio)
            guest?.let(::applyAudio)
        }

    private fun applyAudio(exo: ExoPlayer) {
        val usage = if (callAudio) androidx.media3.common.C.USAGE_VOICE_COMMUNICATION else androidx.media3.common.C.USAGE_MEDIA
        val content = if (callAudio) androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH else androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
        exo.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(usage).setContentType(content).build(), false)
    }

    private fun applyVolume() {
        if (loanedTo == null) player?.volume = volume
        guest?.volume = volume
        syncSound()
    }

    /**
     * Звук отдельно от картинки (docs/doorbell.md, «Звук отдельно от картинки»). Пока звук играет тот же плеер, что и
     * видео, часами плеера служит звук: рывок к живому растягивается на секунды, а пауза в звуковых пакетах (камера не
     * шлёт их, пока передаёт большой ключевой кадр) останавливает картинку. Поэтому, когда поток идёт через
     * ретранслятор планшета, видеоплеер создаётся без звуковой дорожки, а звук играет этот лёгкий плеер из того же
     * потока — только пока его слышно (раскрытая плитка, окно звонка; в разговоре звук с улицы даёт [StreetAudio]).
     */
    private var soundPlayer: ExoPlayer? = null
    /** Текущий видеоплеер создан без звуковой дорожки. */
    private var splitSound = false
    /** Звук улицы играет отдельный плеер: после приглушения он начинает с живого, без старого запаса. */
    internal val soundSeparate get() = splitSound
    private var soundPassword: String? = null
    private var soundNotBefore = 0L

    private fun syncSound() {
        val src = source
        if (!splitSound || player == null || src == null || volume <= 0f) { stopSound(); return }
        if (soundPlayer != null || SystemClock.elapsedRealtime() < soundNotBefore) return
        val relay = CameraHub.relayUrl(playingRtsp, src.user) ?: return
        val rtsp = playingRtsp
        val password = soundPassword
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(300, 2_000, 150, 300).build())
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
        if (callAudio) applyAudio(exo)
        exo.addListener(object : Player.Listener {
            private var played = false

            override fun onPlayerError(error: PlaybackException) {
                if (soundPlayer !== exo) return
                Log.w(TAG, "Звук домофона: ${error.errorCodeName}")
                stopSound()
                soundNotBefore = SystemClock.elapsedRealtime() + SOUND_RETRY_MS
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (soundPlayer !== exo) return
                if (state == Player.STATE_READY) played = true
                else if (state == Player.STATE_BUFFERING && played) soundStalls++
            }
        })
        exo.setMediaSource(RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .setSocketFactory(ConnectTimeoutSocketFactory(RTSP_CONNECT_TIMEOUT_MS, renumber = !LiveTuning.off(context, "no_seq_fix")) {
                H264Params.forStream(rtsp, src.user, password)
            })
            .createMediaSource(MediaItem.fromUri(Uri.parse(relay))))
        exo.playWhenReady = true
        exo.prepare()
        soundPlayer = exo
    }

    private fun stopSound() {
        soundPlayer?.release()
        soundPlayer = null
    }

    /** Звук держится у живого так же, как картинка: накопил лишнее — чуть быстрее (тон не меняется). */
    private fun paceSound() {
        val exo = soundPlayer ?: return
        val to = LiveCatchUp.TARGET_MS + LiveCatchUp.shiftFor(catchUpFloor)
        val buffered = exo.totalBufferedDuration
        val current = exo.playbackParameters.speed
        val speed = when {
            buffered > to + LiveCatchUp.SOUND_START_MS -> LiveCatchUp.SOUND_SPEED
            buffered < to + LiveCatchUp.SOUND_STOP_MS -> 1f
            else -> current
        }
        if (speed != current) exo.setPlaybackSpeed(speed)
    }

    /**
     * HA сообщает, что домофон недоступен (камера/датчик «unavailable» дольше порога). Как у NVR-клиентов:
     * пока устройства нет, попыток подключения нет; вернулся — подключаемся сразу, без ожидания паузы.
     */
    var deviceOffline = false
        set(value) {
            if (field == value) return
            field = value
            failures = 0
            consecutiveFailures = 0
            if (value) { dropGuest(); releasePlayer() }
            update()
        }

    /** Видео не идёт несколько попыток подряд (адрес не отвечает) — для плиток без сущностей HA это «Нет сигнала». */
    var unreachable = false
        private set(value) { if (field != value) { field = value; onStatus?.invoke(status) } }
    private var consecutiveFailures = 0

    /** Когда был показан последний живой кадр (для «Нет видео с 14:05»). */
    var lastFrameAt: Long? = null
        private set

    /** Владелец разрешает видео (например, окно звонка поверх — компактной плитке не играть). */
    var active = true
        set(value) { if (field != value) { field = value; update() } }

    var status = Status.IDLE
        private set(value) { if (field != value) { field = value; onStatus?.invoke(value) } }
    var onStatus: ((Status) -> Unit)? = null
    /**
     * На экране сейчас поток высокой чёткости (кадр от [HD_HEIGHT] строк) — по реальному разрешению того плеера,
     * чью картинку видно (свой или временный), а не по настройкам. Индикатор «HD» карточки.
     */
    var onQuality: ((Boolean) -> Unit)? = null
    var showingHd = false
        private set
    /** Реальная пропорция кадра (ширина/высота) — для плитки «по видео». */
    var onAspect: ((Float) -> Unit)? = null
    var videoAspect = DoorbellConfig.DEFAULT_ASPECT
        private set

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(guestTexture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            // Токен снимка HA меняется каждые несколько минут — это не новый кадр; невидимой плитке кадр не нужен.
            if (visible && (bitmapMissing() || SystemClock.elapsedRealtime() - pictureLoadedAt >= SNAPSHOT_EVERY_MS)) loadPicture()
        }
        update()
    }

    fun setHiddenBySource(hidden: Boolean) {
        if (hiddenBySource == hidden) return
        hiddenBySource = hidden
        update()
    }

    private fun bitmapMissing() = snapshot.drawable == null

    private val visible get() = isAttachedToWindow && isShown && windowShown && !PanelMotion.dimmed && active

    private fun update() {
        main.removeCallbacks(releaseLater)
        main.removeCallbacks(snapshotTick)
        val src = source
        if (src == null || (src.rtsp.isBlank() && src.camera.isBlank())) {
            releasePlayer(); status = Status.NO_SOURCE; return
        }
        if (deviceOffline) {
            releasePlayer()
            status = Status.OFFLINE
            return
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

    private val releaseLater = Runnable { dropGuest(); releasePlayer(); if (status == Status.PLAYING) status = Status.IDLE }
    private val snapshotTick = Runnable { loadPicture(); update() }
    private var retryPending = false
    /**
     * Плеер подключён, но картинки нет: камера приняла соединение и молчит (зависла, перегружается) или поток
     * не декодируется. В RTSP-модуле Media3 1.11.1 нет таймаута чтения сокета (проверено по классам библиотеки):
     * ответа на запрос он ждёт без предела, и без этой проверки плитка навсегда оставалась бы «Подключение…»
     * без повторов (найдено при подготовке RTSP-стенда 0.9.13).
     */
    private val firstFrameWatchdog = Runnable {
        if (player != null && !firstFrameRendered) failed("нет картинки за ${FIRST_FRAME_TIMEOUT_MS / 1000} с")
    }
    private var firstFrameRendered = false

    /**
     * Живое видео не копит отставание: RTSP-поток идёт в реальном времени, и всё, что плеер накопил сверх
     * минимального буфера (паузы декодера, сбои сети, старт с опозданием), навсегда осталось бы задержкой. Пока
     * в буфере больше [LiveCatchUp.START_MS] — плеер ускоряется (звук без смены тона) и догоняет живой момент.
     */
    private val catchUp = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (playingRtsp.isNotBlank()) {
                val buffered = exo.totalBufferedDuration
                bufferedMin = minOf(bufferedMin, buffered)
                bufferedMax = maxOf(bufferedMax, buffered)
                lastBuffered = buffered
                syncSound()
                paceSound()
                // Неровность прихода измеряется раз в минуту. Плеер, запущенный раньше первого замера (старт
                // приложения — плитка ждёт общий поток и стартует с его первым кадром) или до того, как неровность
                // выросла (ночь → день), поднимает запас сам, а не после серии остановок (замер 21.11.2026).
                if (jitterFloor) {
                    val seed = LiveCatchUp.floorFor(CameraHub.arrivalJitterMs(playingRtsp, source?.user.orEmpty()))
                    if (seed > seedFloor) { seedFloor = seed; if (catchUpFloor < seed) catchUpFloor = seed }
                }
                val videoLag = shownFrame?.last?.let { ScreenDelay.of(exo.bufferedPosition, it.ptsUs, it.atNs, System.nanoTime()) }
                if (videoLag != null) {
                    screenDelay.add(videoLag)
                    // Звук отдельным плеером: на сколько он опережает картинку (только замер, в журнал при закрытии).
                    soundPlayer?.takeIf { it.isPlaying }?.let { sound ->
                        soundLead.add(videoLag, sound.totalBufferedDuration, CameraHub.audioAheadMs(playingRtsp, source?.user.orEmpty()))
                    }
                }
                // Рывок идёт сам (его останавливает [endBurst]); большое отставание, которое держится, — повод для рывка.
                backlogTicks = if (LiveCatchUp.burstMs(buffered, burstTo()) > 0) backlogTicks + 1 else 0
                if (burstUntil != 0L || (backlogTicks >= LiveCatchUp.BURST_CONFIRM_TICKS && startBurst(exo))) {
                    main.postDelayed(this, LiveCatchUp.CHECK_MS)
                    return
                }
                val current = exo.playbackParameters.speed
                // Программный декодер большого кадра 1,5× не тянет, но опоздавшие кадры он показывает сразу
                // ([LiveTuning]): часы плеера уходят вперёд, декодер догоняет в полную силу. В прежнем режиме
                // (кадры придерживаются, опоздавшие не показываются) — только мягкое ускорение.
                val cap = if (softwareHold && decoderInUse?.let(::isSoftwareDecoder) == true) LiveCatchUp.SPEED else LiveCatchUp.FAR_SPEED
                var speed = minOf(LiveCatchUp.speedFor(buffered - LiveCatchUp.shiftFor(catchUpFloor), current, catchUpStart), cap)
                if (current > 1f) {
                    // Запас перестал убывать: ниже плеер не опускается (ждёт кадр от декодера) — это его пол.
                    if (buffered < catchUpBest - 50) { catchUpBest = buffered; catchUpStuck = 0 }
                    else if (++catchUpStuck >= LiveCatchUp.STUCK_TICKS) { hitFloor(exo, catchUpBest); speed = 1f }
                } else {
                    catchUpBest = Long.MAX_VALUE
                    catchUpStuck = 0
                    // Начинаем, только если отставание держится (не всплеск) и после прошлой остановки прошло время.
                    catchUpAbove = if (speed > 1f) catchUpAbove + 1 else 0
                    if (speed > 1f && (catchUpAbove < LiveCatchUp.CONFIRM_TICKS || SystemClock.elapsedRealtime() < catchUpNotBefore)) speed = 1f
                }
                if (speed != current) {
                    if (speed > 1f && current == 1f) Log.i(TAG, "Видео отстаёт на $buffered мс — догоняю живое")
                    exo.setPlaybackSpeed(speed)
                }
            }
            main.postDelayed(this, LiveCatchUp.CHECK_MS)
        }
    }
    /** Запас, ниже которого этот плеер уже не опускался (останавливался): догонять дальше незачем. */
    private var catchUpFloor = 0L
    private var catchUpBest = Long.MAX_VALUE
    private var catchUpStuck = 0
    private var catchUpAbove = 0
    /** Порог, с которого этот плеер начинает догонять ([LiveCatchUp.START_MS]; проверочный выключатель — прежний). */
    private var catchUpStart = LiveCatchUp.START_MS
    private var catchUpNotBefore = 0L
    /** За время показа: запас плеера и число остановок (в журнал при закрытии). */
    private var bufferedMin = Long.MAX_VALUE
    private var bufferedMax = 0L
    private var stalls = 0
    /** Последний показанный кадр текущего плеера (пишет поток плеера) и замеры «от прихода до экрана». */
    private var shownFrame: ShownFrame? = null
    /** Проверочный выключатель [LiveTuning.softwareHold] у текущего плеера. */
    private var softwareHold = false
    private val screenDelay = ScreenDelay()
    /** Звук отдельным плеером против картинки и сколько раз он прерывался за показ (в журнал при закрытии). */
    private val soundLead = SoundLead()
    private var soundStalls = 0
    /** Рендереры живого видео текущего плеера (счётчики выравнивания — в журнал при закрытии). */
    private var liveFactory: LiveRenderers? = null

    /** Запас, который потоку нужен по измеренной неровности прихода кадров ([LiveCatchUp.floorFor]); 0 — не измерено. */
    private var seedFloor = 0L
    /** Запас этого плеера считается по измеренной неровности прихода (проверочный выключатель — нет). */
    private var jitterFloor = false
    /** Рывок к живому: до какого времени идёт (0 — не идёт) и не раньше какого можно начать следующий. */
    private var burstUntil = 0L
    private var burstNotBefore = 0L
    private var backlogTicks = 0
    private var goLive = true
    /** Рывок прервала остановка плеера: продолжить, как только он пойдёт дальше. */
    private var burstInterrupted = false
    /** Свой поток пошёл, но ещё догоняет живое: поверх временной картинки он проявится после рывка. */
    private var revealPending = false
    /** Когда показан первый кадр и через сколько после него картинка оказалась у живого (в журнал). */
    private var firstFrameAt = 0L
    /** Через сколько после создания плеер показал первый кадр (−1 — ещё не показал); смена поверхности его не меняет. */
    private var firstFrameDelayMs = -1L
    private var liveAfterMs = -1L
    /** Последняя остановка плеера: когда началась и что было в этот момент (в журнал, когда плеер пойдёт дальше). */
    private var stallAt = 0L
    private var stallNote = ""
    private var lastBuffered = 0L

    /** До какого запаса идёт рывок: цель догоняющего режима этого плеера и ещё [LiveCatchUp.BURST_MARGIN_MS]. */
    private fun burstTo() = LiveCatchUp.TARGET_MS + LiveCatchUp.shiftFor(catchUpFloor) + LiveCatchUp.BURST_MARGIN_MS

    /**
     * Рывок к живому: плеер начал с накопленной группы кадров (или пережил сбой связи) и отстаёт на секунды. Обычное
     * ускорение сняло бы это за 5–15 с; здесь часы плеера на долю секунды идут в [LiveCatchUp.BURST_SPEED] раза быстрее,
     * звук на это время выключен, декодер догоняет в полную силу (опоздавшие кадры — сразу на экран или прыжок на
     * свежий ключевой кадр). Длительность считается заранее: запас убывает ровно со скоростью часов.
     */
    private fun startBurst(exo: ExoPlayer): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Только у плеера без звуковой дорожки: со звуком часами плеера служит звук, и рывок растягивается на секунды.
        if (!goLive || !splitSound || softwareHold || loanedTo != null || burstUntil != 0L || now < burstNotBefore) return false
        val buffered = exo.totalBufferedDuration
        val ms = LiveCatchUp.burstMs(buffered, burstTo())
        if (ms == 0L) return false
        burstUntil = now + ms
        Log.i(TAG, "Видео отстаёт на $buffered мс — рывок к живому ($ms мс)")
        exo.volume = 0f
        exo.setPlaybackSpeed(LiveCatchUp.BURST_SPEED)
        main.postDelayed(endBurst, ms)
        return true
    }

    private val endBurst = Runnable { finishBurst() }
    private fun finishBurst() {
        main.removeCallbacks(endBurst)
        if (burstUntil == 0L) return
        burstUntil = 0L
        burstNotBefore = SystemClock.elapsedRealtime() + LiveCatchUp.BURST_COOLDOWN_MS
        backlogTicks = 0
        catchUpAbove = 0
        catchUpBest = Long.MAX_VALUE
        catchUpStuck = 0
        player?.let { exo ->
            if (exo.playbackParameters.speed != 1f) exo.setPlaybackSpeed(1f)
            if (loanedTo == null) exo.volume = volume
        }
        if (revealPending) main.postDelayed(revealLater, LiveCatchUp.REVEAL_AFTER_BURST_MS)
    }

    /** Свой поток проявляется поверх временного, затем временный возвращается владельцу или освобождается. */
    private fun reveal() {
        revealPending = false
        main.removeCallbacks(revealLater)
        texture.animate().alpha(1f).setDuration(FADE_MS).withEndAction { dropGuest() }.start()
    }
    private val revealLater = Runnable { if (revealPending && player != null && firstFrameRendered) reveal() }

    /** Первые секунды показа: через сколько после первого кадра картинка оказалась у живого (раз в 100 мс). */
    private val settleProbe = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val since = SystemClock.elapsedRealtime() - firstFrameAt
            val delay = shownFrame?.last?.let { ScreenDelay.of(exo.bufferedPosition, it.ptsUs, it.atNs, System.nanoTime()) }
            // Отставание растёт не с первого кадра (плеер ещё запускает звук и декодер): важен последний момент,
            // когда оно было больше порога.
            if (delay != null && (delay > LiveCatchUp.SETTLED_MS || burstUntil != 0L)) liveAfterMs = since + LiveCatchUp.SETTLE_STEP_MS
            if (since < LiveCatchUp.SETTLE_WATCH_MS) main.postDelayed(this, LiveCatchUp.SETTLE_STEP_MS)
        }
    }

    private fun hitFloor(exo: ExoPlayer, buffered: Long) {
        catchUpFloor = maxOf(buffered.coerceAtLeast(0), seedFloor)
        catchUpAbove = 0
        catchUpNotBefore = SystemClock.elapsedRealtime() + LiveCatchUp.COOLDOWN_MS
        if (exo.playbackParameters.speed != 1f) exo.setPlaybackSpeed(1f)
    }

    private val retry = Runnable { retryPending = false; if (visible && player == null) start() }

    private fun start() {
        val src = source ?: return
        status = Status.CONNECTING
        if (bitmapMissing()) loadPicture()
        if (useHa || src.rtsp.isBlank()) {
            if (src.camera.isBlank()) { useHa = false; startRtsp(src) } else startHls(src)
        } else startRtsp(src)
    }

    /** Текущая попытка идёт через ретранслятор планшета ([CameraHub]). */
    private var viaRelay = false
    /** Ретранслятор только что подвёл — следующая попытка напрямую к камере. */
    private var skipRelay = false

    /** С какого момента эта серия попыток ждёт общий поток записи (0 — не ждала); сбрасывается с первым кадром. */
    private var relayWaitSince = 0L
    /** Прямо сейчас ждём общий поток (подключение отложено). */
    private var waitingRelay = false
    private val relayWait = Runnable {
        val src = source
        if (!visible) { relayWaitSince = 0; waitingRelay = false }
        else if (player == null && !retryPending && src != null && !useHa && src.rtsp.isNotBlank()) startRtsp(src)
    }

    /**
     * Запись на планшете вот-вот даст общий поток этой камеры: ждём его недолго и смотрим через планшет — одно
     * соединение с камерой, картинка сразу с ключевого кадра, звук отдельно. Без этого при запуске приложения плитка
     * успевала подключиться к камере напрямую (два ожидания ключевого кадра подряд — 5–7 с до картинки) и оставалась
     * на прямом соединении до следующего перезапуска плеера; то же — после переподключения записи к камере.
     */
    private fun awaitRelay(src: Source): Boolean {
        main.removeCallbacks(relayWait)
        val rtsp = rtspFor(src)
        val now = SystemClock.elapsedRealtime()
        if (!CameraHub.startingUp(rtsp, src.user, RecorderService.starting(context))) {
            // Ждали — и общий поток готов: смотрим через планшет, даже если перед ожиданием ретранслятор оборвался.
            if (waitingRelay) {
                waitingRelay = false
                skipRelay = false
                Log.i(TAG, "Общий поток записи готов через ${now - relayWaitSince} мс ожидания")
            }
            return false
        }
        // Общий поток идёт (ждёт только ключевой кадр), а ретранслятор только что подвёл — напрямую, как раньше.
        if (skipRelay && CameraHub.relayUrl(rtsp, src.user) != null) return false
        if (relayWaitSince == 0L) relayWaitSince = now
        if (now - relayWaitSince >= RELAY_WAIT_MS) {
            if (waitingRelay) Log.i(TAG, "Общий поток записи не готов за ${now - relayWaitSince} мс — подключаюсь без ожидания")
            waitingRelay = false
            return false
        }
        waitingRelay = true
        main.postDelayed(relayWait, RELAY_POLL_MS)
        return true
    }

    private fun startRtsp(src: Source) {
        if (awaitRelay(src)) return
        val generation = ++attempt
        CameraPasswords.read(context, src.secretKey) { password ->
            if (generation != attempt || !visible || player != null) return@read
            // Этап 4 (docs/recording.md): этот поток уже идёт ради записи — смотрим через планшет, соединение с камерой одно.
            val rtsp = rtspFor(src)
            playingRtsp = rtsp
            val relay = if (skipRelay) null else CameraHub.relayUrl(rtsp, src.user)
            viaRelay = relay != null
            splitSound = viaRelay && !LiveTuning.off(context, "player_audio")
            soundPassword = password
            val url = relay ?: DoorbellConfig.withCredentials(rtsp, src.user, password)
            val sequenceRestarts = java.util.concurrent.atomic.AtomicInteger().also { this.sequenceRestarts = it }
            val media = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
                // Media3 открывает RTSP-сокет без таймаута: недоступный адрес держал бы поток плеера минутами,
                // а освобождение плеера ждёт этот поток в главном — отсюда «жуткие лаги» при выключенной камере.
                .setSocketFactory(ConnectTimeoutSocketFactory(RTSP_CONNECT_TIMEOUT_MS,
                    renumber = !LiveTuning.off(context, "no_seq_fix"), restarts = sequenceRestarts) {
                    H264Params.forStream(rtsp, src.user, password)
                })
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            // Аппаратный декодер этот поток уже не принял — сразу программный, без лишней попытки.
            play(software = rtsp == src.rtsp && rtsp in hardwareRejected, live = true, withSound = !splitSound) { it.setMediaSource(media) }
            syncSound()
        }
    }

    private var attempt = 0
    /** Сколько раз за текущее подключение отсчёт номеров RTP-пакетов начат заново ([RtpSequenceFix]). */
    private var sequenceRestarts: java.util.concurrent.atomic.AtomicInteger? = null
    /** Адрес RTSP текущей попытки (основной или запасной); пусто — HLS через HA. */
    internal var playingRtsp = ""
    /** Декодер видео текущего плеера (для проверки на планшете и журнала). */
    internal var decoderInUse: String? = null
        private set

    /** Основной поток, если планшет его уже не декодировал, — сразу субпоток, без новой неудачи. */
    internal fun rtspFor(src: Source) =
        if (src.fallbackRtsp.isNotBlank() && src.fallbackRtsp != src.rtsp && src.rtsp in undecodable) src.fallbackRtsp else src.rtsp

    /**
     * Основной поток не принял ни один декодер планшета (плеер перебирает их сам, программный — после
     * аппаратных): дальше — субпоток, сразу и без счёта неудач. Не «нет сигнала»: камера в порядке.
     */
    internal fun fallBackFromDecoder(error: PlaybackException): Boolean {
        if (error.errorCode !in DECODER_REJECTED) return false
        val src = source ?: return false
        if (playingRtsp != src.rtsp || src.fallbackRtsp.isBlank() || src.fallbackRtsp == src.rtsp) return false
        undecodable += src.rtsp
        Log.w(TAG, "Основной поток не декодируется на этом планшете ни одним декодером (${error.errorCodeName}) — показываю субпоток")
        viaRelay = false
        releasePlayer()
        status = Status.CONNECTING
        retryPending = true
        main.post(retry)
        return true
    }

    private fun startHls(src: Source) {
        val generation = ++attempt
        playingRtsp = ""
        DoorbellHub.streamUrl(src.camera) { url ->
            if (generation != attempt || !visible || player != null) return@streamUrl
            if (url == null) { failed("HA не выдал поток"); return@streamUrl }
            val media = HlsMediaSource.Factory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000))
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            play { it.setMediaSource(media) }
        }
    }

    private fun play(software: Boolean = false, live: Boolean = false, withSound: Boolean = true, setSource: (ExoPlayer) -> Unit) {
        // Не открылся декодер — плеер пробует следующий: так основной поток выше предела аппаратного декодера
        // показывает программный декодер Android (нагрузка на процессор — только пока он на экране).
        // Живой RTSP: декодеру сообщается, что перестановки кадров нет, — иначе он держит у себя до 16 кадров.
        softwareHold = live && LiveTuning.softwareHold(context)
        goLive = live && !LiveTuning.off(context, "no_golive")
        val liveRenderers = if (live) LiveRenderers(context, softwareLowDelay = !softwareHold, retime = !LiveTuning.off(context, "no_retime")) else null
        liveFactory = liveRenderers
        val renderers = (liveRenderers ?: DefaultRenderersFactory(context)).setEnableDecoderFallback(true)
        // Запас по измеренной неровности прихода кадров этого потока (известна, пока идёт запись): основному потоку
        // Reolink его нужно больше, чем субпотоку, — иначе плеер останавливается на каждом позднем ключевом кадре.
        jitterFloor = live && !LiveTuning.off(context, "no_jitter_floor")
        seedFloor = if (jitterFloor) LiveCatchUp.floorFor(CameraHub.arrivalJitterMs(playingRtsp, source?.user.orEmpty())) else 0
        catchUpFloor = seedFloor
        burstUntil = 0
        burstNotBefore = 0
        burstInterrupted = false
        backlogTicks = 0
        revealPending = false
        firstFrameAt = 0
        firstFrameDelayMs = -1
        soundLead.reset()
        soundStalls = 0
        catchUpStart = if (LiveTuning.off(context, "wide_corridor")) LiveCatchUp.WIDE_START_MS else LiveCatchUp.START_MS
        liveAfterMs = -1
        stallAt = 0
        catchUpBest = Long.MAX_VALUE
        catchUpStuck = 0
        catchUpAbove = 0
        catchUpNotBefore = 0
        bufferedMin = Long.MAX_VALUE
        bufferedMax = 0
        stalls = 0
        screenDelay.reset()
        val shown = ShownFrame()
        shownFrame = shown
        if (software) renderers.setMediaCodecSelector(SOFTWARE_FIRST)
        val exo = ExoPlayer.Builder(context, renderers)
            // Живое видео: минимальный буфер — задержка важнее плавности при обрывах сети.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(300, 2_000, 150, 300).build())
            .build()
        player = exo
        startedAt = SystemClock.elapsedRealtime()
        firstFrameRendered = false
        main.removeCallbacks(firstFrameWatchdog)
        main.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
        exo.volume = volume
        if (callAudio) applyAudio(exo)
        if (!withSound) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
        }
        exo.setVideoTextureView(texture)
        exo.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            shown.last = ShownFrame.At(presentationTimeUs, releaseTimeNs)
        }
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                if (player === exo) Log.i(TAG, "Звук плеера: буфер опустел (не кормили $elapsedSinceLastFeedMs мс)")
            }
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                if (player !== exo) return
                decoderInUse = decoderName
                val main = source?.rtsp ?: return
                if (playingRtsp == main && isSoftwareDecoder(decoderName) && hardwareRejected.add(main)) {
                    Log.i(TAG, "Основной поток — программным декодером ($decoderName): аппаратный его не принял")
                }
            }
        })
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player !== exo || videoSize.width <= 0 || videoSize.height <= 0) return
                val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                if (kotlin.math.abs(aspect - videoAspect) > 0.01f) { videoAspect = aspect; onAspect?.invoke(aspect) }
                fitTexture()
                updateQuality()
            }
            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                firstFrameRendered = true
                main.removeCallbacks(firstFrameWatchdog)
                if (loanedTo != null) return
                failures = 0
                consecutiveFailures = 0
                unreachable = false
                skipRelay = false
                relayWaitSince = 0
                status = Status.PLAYING
                updateQuality()
                firstFrameAt = SystemClock.elapsedRealtime()
                if (firstFrameDelayMs < 0) firstFrameDelayMs = firstFrameAt - startedAt
                main.removeCallbacks(settleProbe)
                main.postDelayed(settleProbe, LiveCatchUp.SETTLE_STEP_MS)
                // Свой поток пошёл. Временная картинка (субпоток плитки) живая, а свой поток начинает с отставанием:
                // пока идёт рывок к живому, он не показывается — иначе время на экране прыгнуло бы назад.
                if (playingRtsp.isNotBlank() && startBurst(exo) && guest != null) revealPending = true else reveal()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === exo && !fallBackFromDecoder(error)) failed(error.errorCodeName)
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Поток домофона не заканчивается: конец — обрыв.
                if (player === exo && state == Player.STATE_ENDED) failed("поток закончился")
                if (player === exo && state == Player.STATE_BUFFERING && firstFrameRendered) {
                    stalls++
                    stallAt = SystemClock.elapsedRealtime()
                    stallNote = "${exo.videoSize.height}p, запас ${exo.totalBufferedDuration} мс (за секунду до неё $lastBuffered), скорость ${exo.playbackParameters.speed}, " +
                        "${stallAt - firstFrameAt} мс после первого кадра" + if (burstUntil != 0L) ", во время рывка" else ""
                }
                if (player === exo && state == Player.STATE_READY && stallAt != 0L) {
                    Log.i(TAG, "Остановка видео на ${SystemClock.elapsedRealtime() - stallAt} мс: $stallNote")
                    stallAt = 0
                    if (burstInterrupted) { burstInterrupted = false; startBurst(exo) }
                }
                if (player === exo && state == Player.STATE_BUFFERING && firstFrameRendered && exo.playbackParameters.speed > 1f) {
                    // Остановка во время рывка — не «пол» плеера: рывок просто заканчивается.
                    if (burstUntil != 0L) { finishBurst(); burstNotBefore = 0; burstInterrupted = true }
                    else hitFloor(exo, minOf(exo.totalBufferedDuration, catchUpBest))
                }
            }
        })
        setSource(exo)
        exo.playWhenReady = true
        exo.prepare()
        main.removeCallbacks(catchUp)
        main.postDelayed(catchUp, LiveCatchUp.CHECK_MS)
    }

    internal fun failed(reason: String) {
        Log.w(TAG, "Видео домофона: $reason" + if (viaRelay) " (через планшет)" else "")
        // Ретранслятор подвёл (запись переподключается, выключена) — следующая попытка напрямую; не вышло напрямую —
        // снова через планшет. Первый сбой ретранслятора не считается недоступностью камеры.
        skipRelay = viaRelay
        if (viaRelay) {
            viaRelay = false
            releasePlayer()
            status = Status.ERROR
            retryPending = true
            main.postDelayed(retry, RETRY_BASE_MS / 4)
            return
        }
        // Камера могла сменить разрешение: SPS/PPS субпотока возьмём заново при следующем подключении.
        if (playingRtsp.isNotBlank()) H264Params.forget(playingRtsp)
        releasePlayer()
        failures++
        val src = source ?: return
        // Есть оба пути — чередуем: дома прямой RTSP, вне дома HA.
        if (src.rtsp.isNotBlank() && src.camera.isNotBlank()) useHa = !useHa
        status = Status.ERROR
        consecutiveFailures++
        if (consecutiveFailures >= UNREACHABLE_AFTER) unreachable = true
        // Нарастающая пауза; адрес не отвечает — редкие проверки (раз в минуту), как у клиентов видеорегистраторов.
        val delay = if (unreachable) RETRY_UNREACHABLE_MS
            else (RETRY_BASE_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(RETRY_MAX_MS)
        retryPending = true
        main.postDelayed(retry, delay)
    }

    private fun releasePlayer() {
        attempt++
        main.removeCallbacks(retry)
        main.removeCallbacks(relayWait)
        main.removeCallbacks(firstFrameWatchdog)
        main.removeCallbacks(catchUp)
        main.removeCallbacks(endBurst)
        main.removeCallbacks(revealLater)
        main.removeCallbacks(settleProbe)
        burstUntil = 0
        revealPending = false
        retryPending = false
        stopSound()
        val exo = player ?: return
        player = null
        logPlaybackStats(exo)
        // Одолженный плеер освобождается: у того, кто его показывал, картинка гаснет до своего потока.
        loanedTo?.let { borrower -> loanedTo = null; borrower.guestReleased(exo) }
        keepLastFrame()
        texture.animate().cancel()
        texture.alpha = 0f
        // Без clearVideoTextureView: он ждёт поток плеера до 2 с (отсоединение поверхности), release() сам
        // отпускает поверхность. Главный поток ждёт плеер только внутри release() (не дольше 0,5 с).
        exo.release()
        updateQuality()
    }

    /** Итог показа в журнал: декодер, разрешение, кадры в секунду и пропуски — для проверки на планшете. */
    private fun logPlaybackStats(exo: ExoPlayer) {
        val counters = exo.videoDecoderCounters ?: return
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        if (seconds < 1f) return
        counters.ensureUpdated()
        val size = exo.videoSize
        Log.i(TAG, "Видео: ${decoderInUse ?: "?"} ${size.width}×${size.height}, показано ${counters.renderedOutputBufferCount} " +
            "(%.1f к/с), пропущено ${counters.droppedBufferCount + counters.skippedOutputBufferCount} за %.0f с".format(
                counters.renderedOutputBufferCount / seconds, seconds) +
            (if (bufferedMax > 0) "; запас плеера $bufferedMin–$bufferedMax мс (нужен от $catchUpFloor), остановок $stalls" else "") +
            (if (splitSound) "; звук отдельно" + (soundLead.summary()?.let { ": $it" } ?: "") +
                (if (soundStalls > 0) ", прерывался $soundStalls раз" else "") else "") +
            (screenDelay.summary()?.let { "; от прихода до экрана: $it" } ?: "") +
            (if (firstFrameAt != 0L) "; первый кадр через $firstFrameDelayMs мс, у живого — через ${liveAfterMs.coerceAtLeast(0)} мс после него" else "") +
            (liveFactory?.retimer?.takeIf { it.moved > 0 }?.let { "; выровнено кадров ${it.moved} (сдвиг до ${it.maxShiftUs / 1000} мс)" } ?: "") +
            (sequenceRestarts?.get()?.takeIf { it > 0 }?.let { "; отсчёт номеров пакетов начат заново $it раз" } ?: ""))
    }

    // ---- Бесшовная смена потока (решение владельца): плитка ↔ раскрытие/окно звонка ----

    /**
     * Одолжить свой играющий плеер [target], пока тот подключает свой поток: картинка там живая сразу.
     * Плеер остаётся нашим; вернётся, когда у [target] пойдёт свой поток или он закроется.
     */
    fun lendTo(target: DoorbellVideoView): Boolean {
        val exo = player ?: return false
        if (status != Status.PLAYING || loanedTo != null || target === this) return false
        if (!target.acceptGuest(exo, this, videoAspect)) return false
        loanedTo = target
        updateQuality()
        return true
    }

    /**
     * Временную картинку берёт только тот, кто сам будет играть живой поток: его первый кадр и отпустит гостя.
     * В режиме «снимок» своего потока нет — гость (основной поток!) декодировался бы до затемнения экрана.
     */
    internal val canHostGuest get() = source?.let { !it.snapshotOnly && (it.rtsp.isNotBlank() || it.camera.isNotBlank()) } == true

    internal fun acceptGuest(exo: ExoPlayer, owner: DoorbellVideoView?, aspect: Float): Boolean {
        if (status == Status.PLAYING || !isAttachedToWindow || !canHostGuest) return false
        dropGuest()
        guest = exo
        guestOwner = owner
        guestAspect = aspect
        exo.setVideoTextureView(guestTexture)
        exo.volume = volume
        guestTexture.alpha = 1f
        fitTexture()
        onStatus?.invoke(status)
        updateQuality()
        return true
    }

    /** Временная картинка больше не нужна: одолженный плеер — владельцу, переданный — освободить. */
    private fun dropGuest() {
        val exo = guest ?: return
        guest = null
        val owner = guestOwner
        guestOwner = null
        guestTexture.alpha = 0f
        exo.clearVideoTextureView(guestTexture)
        if (owner != null) owner.reclaim(exo) else exo.release()
        onStatus?.invoke(status)
        updateQuality()
    }

    private fun reclaim(exo: ExoPlayer) {
        if (player !== exo) return
        loanedTo = null
        exo.setVideoTextureView(texture)
        exo.volume = volume
        updateQuality()
    }

    /** Чью картинку сейчас видно: свой поток (пошёл и не одолжен), иначе временный. */
    private fun updateQuality() {
        val own = player?.takeIf { status == Status.PLAYING && loanedTo == null }
        val shown = own ?: guest
        val hd = (shown?.videoSize?.height ?: 0) >= HD_HEIGHT
        if (hd == showingHd) return
        showingHd = hd
        onQuality?.invoke(hd)
    }

    /** Владелец освободил одолженный плеер (экран затемнён, ошибка). */
    private fun guestReleased(exo: ExoPlayer) {
        if (guest !== exo) return
        guest = null
        guestOwner = null
        guestTexture.alpha = 0f
        onStatus?.invoke(status)
        updateQuality()
        if (revealPending) reveal()
    }

    /** Показывается чужой поток (надпись «Подключение…» не нужна). */
    val hasGuest get() = guest != null

    /** Высота кадра, который сейчас на экране (0 — живого видео нет). */
    val shownHeight: Int get() {
        val own = player?.takeIf { status == Status.PLAYING && loanedTo == null }
        return (own ?: guest)?.videoSize?.height ?: 0
    }

    /**
     * Живой кадр с экрана в разрешении потока (не больше [maxWidth]) — снимок посетителя при звонке. null — видео
     * сейчас не идёт. Главный поток.
     */
    fun currentFrame(maxWidth: Int = 1280): android.graphics.Bitmap? {
        val own = player?.takeIf { status == Status.PLAYING && loanedTo == null }
        val (view, exo, aspect) = when {
            own != null -> Triple(texture, own, videoAspect)
            guest != null -> Triple(guestTexture, guest!!, guestAspect)
            else -> return null
        }
        if (!view.isAvailable) return null
        val w = exo.videoSize.width.takeIf { it > 0 }?.coerceAtMost(maxWidth) ?: return null
        val h = (w / aspect).toInt().coerceAtLeast(1)
        // Кадр TextureView идёт с обрезкой «по плитке» (setTransform) — для снимка трансформация снимается на миг.
        val saved = view.getTransform(null)
        return try {
            view.setTransform(null)
            view.getBitmap(w, h)
        } catch (_: Exception) { null } finally { view.setTransform(saved) }
    }

    /**
     * Закрываемся: если у [partner] картинки ещё нет, а у нас поток идёт — отдать плеер ему насовсем
     * (он освободит его на своём первом кадре). Иначе — обычное освобождение.
     */
    private fun handOffTo(target: DoorbellVideoView?): Boolean {
        val exo = player ?: return false
        if (target == null || status != Status.PLAYING || loanedTo != null) return false
        if (!target.acceptGuest(exo, null, videoAspect)) return false
        player = null
        attempt++
        updateQuality()
        return true
    }

    /** Подложка (последний кадр или снимок) приглушена, пока видео нет: она не должна выглядеть живой картинкой. */
    fun markStale(stale: Boolean) {
        val target = if (stale) STALE_ALPHA else 1f
        if (snapshot.alpha != target) snapshot.animate().alpha(target).setDuration(250).start()
    }

    /** Последний кадр остаётся на месте, пока поток переподключается: вместо чёрной вспышки — картинка. */
    private fun keepLastFrame() {
        if (status != Status.PLAYING || !texture.isAvailable || texture.width <= 0 || texture.height <= 0) return
        // Кадр в пропорциях видео (половинное разрешение — подложка на секунду-другую) и та же обрезка, что у видео.
        val w = (texture.width / 2).coerceAtLeast(1)
        val h = (w / videoAspect).toInt().coerceAtLeast(1)
        val bitmap = try { texture.getBitmap(w, h) } catch (_: Exception) { null } ?: return
        snapshot.setImageBitmap(bitmap)
        lastFrameAt = System.currentTimeMillis()
    }

    /** Кадр заполняет плитку без искажений (обрезка по краям, как у снимка). */
    private fun fitTexture() {
        fit(texture, videoAspect)
        fit(guestTexture, guestAspect)
    }

    private fun fit(view: TextureView, aspect: Float) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0 || h <= 0) return
        val ratio = w / h
        val sx: Float
        val sy: Float
        if (aspect > ratio) { sx = aspect / ratio; sy = 1f } else { sx = 1f; sy = ratio / aspect }
        view.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
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
        dropGuest()
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
        onQuality = null
        onAspect = null
        main.removeCallbacksAndMessages(null)
        pictureCall?.cancel()
        dropGuest()
        // Сворачивание: поток раскрытия досматривается в плитке, пока не пойдёт её субпоток.
        if (!handOffTo(partner)) releasePlayer()
        partner = null
    }

    internal val hasPlayer get() = player != null
    /** Адрес RTSP, с которым плитка работает сейчас (пусто — прямого потока нет). */
    internal val boundRtsp get() = source?.rtsp.orEmpty()

    companion object {
        private val all = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DoorbellVideoView, Boolean>())

        /**
         * Звук с улицы идёт отдельно ([StreetAudio], разговор): все плееры домофона молчат — иначе звук слышен дважды
         * (второй — с задержкой видео, как эхо). Только главный поток.
         */
        var streetAudio = false
            set(value) {
                if (field == value) return
                field = value
                synchronized(all) { all.toList() }.forEach { it.applyVolume() }
            }

        private const val TAG = "DoorbellVideo"
        const val SNAPSHOT_EVERY_MS = 60_000L
        private const val RELEASE_DELAY_MS = 2_500L
        private const val RTSP_TIMEOUT_MS = 6_000L
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val RETRY_UNREACHABLE_MS = 60_000L
        /** Подключение к RTSP: дольше ждать нет смысла — в своей сети камера отвечает за доли секунды. */
        internal const val RTSP_CONNECT_TIMEOUT_MS = 4_000
        /** Подключение с первым кадром: в своей сети — 1–3 с, через HA (HLS) — до 10 с. Дольше — камера молчит. */
        internal const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        /** После ошибки отдельного плеера звука следующая попытка — не раньше. */
        private const val SOUND_RETRY_MS = 5_000L
        /** Сколько живое видео ждёт общий поток записи (ключевой кадр субпотока — раз в 4 с) и как часто смотрит. */
        private const val RELAY_WAIT_MS = 5_000L
        private const val RELAY_POLL_MS = 150L
        /** Столько неудач подряд — адрес считается недоступным (≈ 15 с при паузах 2, 4, 8 с). */
        internal const val UNREACHABLE_AFTER = 3
        private const val STALE_ALPHA = 0.4f
        private const val FADE_MS = 220L
        /** Высота кадра, с которой поток считается основным (HD): субпотоки Reolink/Hikvision — 360–512 строк. */
        const val HD_HEIGHT = 720
        /**
         * Основные потоки, которые не принял ни один декодер этого планшета (адрес без логина), и те, что идут
         * только программным декодером. До перезапуска приложения: после смены разрешения камеры панель перезапускают.
         */
        internal val undecodable = mutableSetOf<String>()
        internal val hardwareRejected = mutableSetOf<String>()

        internal fun isSoftwareDecoder(name: String) = name.startsWith("c2.android.") || name.startsWith("OMX.google.")

        /** Программные декодеры — первыми (порядок внутри групп прежний). */
        private val SOFTWARE_FIRST = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { !it.softwareOnly }
        }
        private val DECODER_REJECTED = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        /** В тестах Robolectric декодеров нет: только снимок. */
        internal val ENABLED = android.os.Build.FINGERPRINT != "robolectric"
    }
}

/**
 * Фабрика сокетов RTSP с таймаутом подключения. Стандартная `SocketFactory.createSocket(host, port)` подключается
 * без таймаута: до недоступного адреса — до системного предела TCP (минуты). Чтение без таймаута, как и было:
 * управляющий канал RTSP подолгу молчит между запросами. Сокеты — [SdpFixingSocket]: описание потока камеры
 * приводится к тому, что примет Media3 (видео H.264 без SPS/PPS в описании у субпотока Reolink).
 */
internal class ConnectTimeoutSocketFactory(
    private val timeoutMs: Int,
    /** Свои номера RTP-пакетов для Media3 ([RtpSequenceFix]); false — номера камеры как есть. */
    private val renumber: Boolean = true,
    /** Сюда считается, сколько раз отсчёт номеров начат заново (для журнала). */
    private val restarts: java.util.concurrent.atomic.AtomicInteger? = null,
    /** Параметры видео H.264, если камера не дала их в описании ([H264Params]). */
    private val h264: (() -> String?)? = null
) : javax.net.SocketFactory() {
    private fun socket() = SdpFixingSocket(h264, if (renumber) RtpSequenceFix(restarts) else null)

    override fun createSocket(): java.net.Socket = socket()

    override fun createSocket(host: String, port: Int): java.net.Socket =
        connect(java.net.InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
        socket().apply {
            bind(java.net.InetSocketAddress(localHost, localPort))
            connectOrClose(this, java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
        connect(java.net.InetSocketAddress(host, port))

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
        socket().apply {
            bind(java.net.InetSocketAddress(localAddress, localPort))
            connectOrClose(this, java.net.InetSocketAddress(address, port))
        }

    private fun connect(address: java.net.InetSocketAddress) = socket().also { connectOrClose(it, address) }

    private fun connectOrClose(socket: java.net.Socket, address: java.net.InetSocketAddress) {
        try { socket.connect(address, timeoutMs) } catch (e: java.io.IOException) { socket.close(); throw e }
    }
}

/**
 * Звук отдельным плеером против картинки (docs/doorbell.md, «Звук и картинка: замер»). Раз в секунду: картинка на
 * экране старше свежего пришедшего кадра на `videoLagMs`, звук в динамике старше свежего пришедшего звука на
 * `soundLagMs`, а сам звук приходит на планшет раньше видео того же момента на `audioAheadMs`. Разница — на сколько
 * звук опережает картинку (минус — отстаёт). Только замер: звук он не трогает.
 */
internal class SoundLead {
    private val samples = IntArray(LIMIT)
    private var total = 0
    private var corrected = true

    fun reset() { total = 0; corrected = true }

    fun add(videoLagMs: Long, soundLagMs: Long, audioAheadMs: Long?) {
        if (audioAheadMs == null) corrected = false
        val lead = videoLagMs - soundLagMs + (audioAheadMs ?: 0)
        if (lead < -MAX_MS || lead > MAX_MS) return
        samples[total % LIMIT] = lead.toInt()
        total++
    }

    /** «звук раньше картинки на N мс (от A до B)»; null — замеров мало. */
    fun summary(): String? {
        val count = minOf(total, LIMIT)
        if (count < MIN_SAMPLES) return null
        val sorted = samples.copyOf(count).also { it.sort() }
        val middle = sorted[count / 2]
        return (if (middle >= 0) "раньше картинки на $middle мс" else "позже картинки на ${-middle} мс") +
            " (от ${sorted.first()} до ${sorted.last()}; плюс — звук раньше" + (if (corrected) "" else "; без поправки на приход") + ")"
    }

    private companion object {
        const val LIMIT = 600
        const val MIN_SAMPLES = 3
        const val MAX_MS = 10_000L
    }
}

/** Последний показанный кадр: время кадра в потоке и момент показа (пишет поток плеера, читает главный). */
internal class ShownFrame {
    class At(val ptsUs: Long, val atNs: Long)
    @Volatile var last: At? = null
}

/**
 * Сколько кадр ждёт у нас — от прихода в плеер до экрана: запас плеера, кадры у декодера и опоздание показа
 * (docs/doorbell.md, «Задержка живого видео»). Замер раз в секунду: на сколько последний показанный кадр старше
 * самого свежего пришедшего. Задержка самой камеры и сети сюда не входит (сеть — в строке «Камера → планшет»).
 */
internal class ScreenDelay {
    private var samples = IntArray(64)
    /** Всего принято замеров; хранятся последние [LIMIT] (кольцом): плитка показывает видео часами. */
    private var total = 0
    private val count: Int get() = minOf(total, LIMIT)

    fun reset() { total = 0 }

    fun add(ms: Long) {
        // Вне разумного (смена потока, пауза источника) — не замер; кадр чуть «свежее» края буфера звука — ноль.
        if (ms < -TOLERANCE_MS || ms > MAX_MS) return
        if (total == samples.size && samples.size < LIMIT) samples = samples.copyOf(minOf(samples.size * 2, LIMIT))
        samples[total % LIMIT] = ms.coerceAtLeast(0).toInt()
        total++
    }

    /** Медиана и 90-й процентиль; null — замеров мало. */
    fun summary(): String? {
        if (count < MIN_SAMPLES) return null
        val sorted = samples.copyOf(count).also { it.sort() }
        // «К концу» — установившееся значение: первые секунды плеер догоняет накопленное при подключении.
        val settled = if (count < 2 * TAIL) "" else
            ", к концу ${IntArray(TAIL) { samples[(total - 1 - it) % LIMIT] }.also { it.sort() }[TAIL / 2]} мс"
        return "медиана ${sorted[count / 2]} мс, 90 % — до ${sorted[(count * 9 / 10).coerceAtMost(count - 1)]} мс$settled"
    }

    companion object {
        private const val TOLERANCE_MS = 500L
        private const val MAX_MS = 10_000L
        private const val MIN_SAMPLES = 5
        private const val TAIL = 8
        internal const val LIMIT = 4_096

        /**
         * Задержка кадра в момент его показа: край буфера [edgeMs] сейчас ([nowNs]) минус время, прошедшее с показа
         * (поток живой — край за это время ушёл вперёд ровно на столько), минус время кадра [ptsUs].
         */
        fun of(edgeMs: Long, ptsUs: Long, shownAtNs: Long, nowNs: Long): Long =
            edgeMs - (nowNs - shownAtNs) / 1_000_000 - ptsUs / 1_000
    }
}

/**
 * Догоняющий режим живого видео: скорость по запасу в буфере плеера. С запасом — гистерезис, чтобы скорость не
 * дёргалась на границе: ускорение с [START_MS], возврат к 1× ниже [TARGET_MS]. Большое отставание — быстрее.
 * У каждого плеера свой «пол» — запас, ниже которого он останавливается (декодер придерживает кадры, программный
 * декодер не успевает): упёрлись — пороги поднимаются на [shiftFor], следующая попытка через [COOLDOWN_MS].
 */
internal object LiveCatchUp {
    const val CHECK_MS = 1_000L
    /**
     * 0,4 с: запас держится в полосе 0,1 с над целью. Раньше было 0,5 с ([WIDE_START_MS], выключатель
     * `video_debug/wide_corridor`): от показа к показу задержка гуляла на 0,2 с (замер 21.11.2026).
     */
    const val START_MS = 400L
    const val WIDE_START_MS = 500L
    const val TARGET_MS = 300L
    private const val FAR_MS = 1_500L
    const val SPEED = 1.2f
    const val FAR_SPEED = 1.5f
    /** Столько проверок подряд запас не убывает при ускорении — дальше догонять нельзя. */
    const val STUCK_TICKS = 5
    /** Столько проверок подряд отставание выше порога — тогда догоняем (всплески не в счёт). */
    const val CONFIRM_TICKS = 3
    /** После упора в пол следующая попытка догнать — не раньше. */
    const val COOLDOWN_MS = 15_000L

    /** Запас, который плееру нужен всегда (кадр-другой у декодера): пол выше него сдвигает пороги. */
    private const val BASE_MS = 200L

    /** На сколько поднять пороги для плеера, который не опускается ниже [floorMs] (цель — пол + 0,1 с). */
    fun shiftFor(floorMs: Long) = (floorMs - BASE_MS).coerceAtLeast(0)

    /** Запас сверх цели, на котором рывок останавливается: дальше — обычное мягкое ускорение. */
    const val BURST_MARGIN_MS = 100L
    const val BURST_SPEED = 4f
    /** Рывок — только если он снимает не меньше стольких миллисекунд отставания; не дольше [BURST_MAX_MS]. */
    private const val BURST_MIN_GAIN_MS = 500L
    private const val BURST_MAX_MS = 1_500L
    /** Столько проверок подряд отставание большое — рывок посреди показа (после сбоя связи). */
    const val BURST_CONFIRM_TICKS = 2
    const val BURST_COOLDOWN_MS = 5_000L
    /** Свой поток проявляется поверх временной картинки через столько после рывка (декодер досматривает хвост). */
    const val REVEAL_AFTER_BURST_MS = 250L
    /** «Далеко от живого» — показанный кадр старше свежего пришедшего больше чем на столько; следим первые [SETTLE_WATCH_MS]. */
    const val SETTLED_MS = 1_200L
    const val SETTLE_WATCH_MS = 20_000L
    const val SETTLE_STEP_MS = 100L

    /**
     * Сколько длится рывок к живому: часы плеера идут в [BURST_SPEED] раза быстрее, поток — в реальном времени, значит,
     * запас убывает со скоростью `BURST_SPEED − 1` и дойдёт с [bufferedMs] до [toMs] за это время. 0 — рывок не нужен.
     */
    fun burstMs(bufferedMs: Long, toMs: Long): Long {
        val gain = bufferedMs - toMs
        if (gain < BURST_MIN_GAIN_MS) return 0
        return (gain / (BURST_SPEED - 1)).toLong().coerceAtMost(BURST_MAX_MS)
    }

    /**
     * «Пол» плеера по измеренной неровности прихода кадров [jitterMs] (самый поздний кадр минуты против обычного):
     * пока кадр опаздывает, плеер показывает запас, и запаса должно хватать. 0 — неровность не измерена.
     */
    fun floorFor(jitterMs: Long): Long = if (jitterMs <= 0) 0 else (jitterMs + FLOOR_MARGIN_MS).coerceAtMost(MAX_FLOOR_MS)
    /** Сверх измеренной неровности: кадр после паузы ещё нужно принять целиком и декодировать (замер 21.11.2026). */
    private const val FLOOR_MARGIN_MS = 100L
    private const val MAX_FLOOR_MS = 1_000L

    /** Отдельный плеер звука: ускоряется, накопив столько сверх цели, и возвращается к обычной скорости ниже [SOUND_STOP_MS]. */
    const val SOUND_START_MS = 400L
    const val SOUND_STOP_MS = 100L
    const val SOUND_SPEED = 1.25f

    fun speedFor(bufferedMs: Long, current: Float, startMs: Long = START_MS): Float = when {
        bufferedMs > FAR_MS -> FAR_SPEED
        bufferedMs > startMs -> SPEED
        current > 1f && bufferedMs > TARGET_MS -> SPEED
        else -> 1f
    }
}
