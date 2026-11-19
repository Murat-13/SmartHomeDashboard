package com.example.smarthomedashboard

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Архив записей камеры (docs/recording.md) — экран на Compose. Лента событий (новые первыми, фильтр по типу, день,
 * календарь), шкала времени с неподвижной риской по центру (как у Reolink: ленту ведут пальцем, масштаб — двумя
 * пальцами), плеер по локальным файлам (1×–8×, ±10 с, кадр, «◀ Событие ▶», увеличение картинки пальцами),
 * «Сохранить» (звёздочка), «Удалить», «Поделиться» роликом или отрезком, кадр в галерею, вкладка «Звонки» со снимками
 * посетителей и строка о месте под записи. Открывается из раскрытой плитки домофона и из окна звонка.
 */
@OptIn(UnstableApi::class)
class ArchiveActivity : AppCompatActivity() {
    private lateinit var tileId: String
    private lateinit var title: String
    private lateinit var archive: RecArchive
    private var index: RecIndex? = null
    private var player: ExoPlayer? = null
    private val ui = mutableStateOf(ArchiveUi())
    private val thumbs = mutableStateMapOf<String, Bitmap?>()
    private var snapshot = RecArchive.Snapshot(emptyList(), emptyList())
    private var texture: TextureView? = null
    private val ringListener: () -> Unit = { loadRings() }
    private var cameras: List<Pair<String, String>> = emptyList()
    private var doorbellIds: Set<String> = emptySet()

    private fun titleOf(tile: com.example.smarthomedashboard.data.TileEntity?): String = tile?.title?.ifBlank { null }
        ?: if (tile?.type == DoorbellConfig.CAMERA_TYPE) DoorbellConfig.CAMERA_TITLE else DoorbellConfig.DEFAULT_TITLE

    /** Другая камера: тот же экран, её записи (плеер останавливается, шкала и списки — заново). */
    private fun switchCamera(id: String) {
        if (id == tileId) return
        tileId = id
        title = cameras.firstOrNull { it.first == id }?.second ?: title
        player?.stop()
        loadedDay = null
        thumbs.clear()
        previewCache.clear()
        ui.value = ArchiveUi(title = title, cameras = cameras, camera = id, doorbell = id in doorbellIds, spanMs = ui.value.spanMs)
        reload(keepDay = null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileId = intent.getStringExtra(EXTRA_TILE).orEmpty()
        val tiles = TileManager(this).loadTiles()
        val tile = tiles.firstOrNull { it.id == tileId }
        title = titleOf(tile)
        // Камеры этого планшета для переключения (docs/camera-tz.md): домофон и плитки «Камера».
        cameras = tiles.filter { it.type in CameraSource.TYPES }.map { it.id to titleOf(it) }
        doorbellIds = tiles.filter { it.type == DoorbellConfig.TYPE }.map { it.id }.toSet()
        index = try { RecIndex(this) } catch (_: Exception) { null }
        archive = RecArchive(this, index = { index })
        ui.value = ui.value.copy(title = title, cameras = cameras, camera = tileId, doorbell = tileId in doorbellIds, tab = if (intent.getBooleanExtra(EXTRA_CALLS, false)) ArchiveUi.Tab.CALLS else ArchiveUi.Tab.RECORDS)
        // HD-ролики событий (Reolink 2560×1920) аппаратный декодер Samsung SM-X216B не принимает — следующий декодер
        // (программный) берётся сам, как у живого видео домофона.
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { ui.value = ui.value.copy(playing = isPlaying) }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0)
                        ui.value = ui.value.copy(videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            })
        }
        setContent {
            ArchiveScreen(ui.value, thumbs, actions, video = { VideoSurface() })
        }
        DoorbellHub.addRingListener(ringListener)
        val at = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0 }
        // PIN архива «весь архив»: без PIN экран закрывается (тот же PIN, что у настроек).
        if (RecordingDevice(this).archivePin == RecordingDevice.ArchivePin.ALL) {
            ui.value = ui.value.copy(locked = true)
            PinSecurityUi.authorize(this, { ui.value = ui.value.copy(locked = false); reload(latest = true, at = at) }, { finish() })
        } else {
            reload(latest = intent.getBooleanExtra(EXTRA_LATEST, false), at = at)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        DoorbellHub.removeRingListener(ringListener)
        previewThread.shutdownNow()
        player?.release()
        player = null
        index?.close()
        super.onDestroy()
    }

    /** Прочитать диск; [latest] — сразу открыть последний ролик (из окна звонка); [at] — встать на этот момент. */
    private fun reload(latest: Boolean = false, keepDay: Long? = ui.value.day, at: Long? = null) {
        ui.value = ui.value.copy(loading = true)
        loadRings()
        lifecycleScope.launch {
            val camera = RecLayout.cameraKey(tileId)
            snapshot = withContext(Dispatchers.IO) { archive.load(camera) }
            val days = snapshot.days
            val selected = (at?.let(RecArchive::dayStart) ?: keepDay)?.takeIf { it in days } ?: days.firstOrNull()
            ui.value = ui.value.copy(timeline = timelineOf(ui.value.filter))
            selectDay(selected, days, recenter = ui.value.center == null)
            when {
                at != null -> openMoment(at)
                latest -> snapshot.events.firstOrNull()?.let { play(it) }
            }
            // Ролики, записанные до появления оглавления (или если оно не успело записаться), — в очередь: после
            // этого в них работает перемотка. Сначала события (новые первыми), затем 24/7 выбранного дня.
            val files = snapshot.events.map { it.file } + snapshot.continuous.filter { RecArchive.dayStart(it.startMs) == selected }.reversed()
            withContext(Dispatchers.IO) { files.forEach { f -> if (Mp4Finalize.isFragmented(f.path)) Mp4Finalize.enqueue(f.path) } }
            loadStorage()
        }
    }

    private fun loadStorage() {
        lifecycleScope.launch {
            val keep = TileManager(this@ArchiveActivity).loadTiles().firstOrNull { it.id == tileId }
                ?.let { RecordingConfig.from(it.config).keepContinuousDays } ?: RecordingConfig().keepContinuousDays
            val limit = RecordingDevice(this@ArchiveActivity).limitGb
            val text = withContext(Dispatchers.IO) { try { RecArchive.storageText(archive.storage(limit, keep)) } catch (_: Exception) { null } }
            val copy = withContext(Dispatchers.IO) { ArchiveCopy.statusText(this@ArchiveActivity) }
            ui.value = ui.value.copy(storage = text, copyStatus = copy)
        }
    }

    private fun loadRings() {
        lifecycleScope.launch {
            val rings = withContext(Dispatchers.IO) { RingLog.list(this@ArchiveActivity, tileId) }
            rings.forEach { thumbs.remove(ringKey(it)) }
            ui.value = ui.value.copy(rings = rings)
        }
    }

    /** День списка событий; [recenter] — шкала встаёт на последнюю запись этого дня. */
    private fun selectDay(selected: Long?, days: List<Long> = ui.value.days, recenter: Boolean = false) {
        val center = if (recenter && selected != null) {
            val end = selected + 86_400_000L
            ArchiveTimeline.latest(snapshot.continuous.filter { it.startMs in selected until end },
                snapshot.events.filter { it.file.startMs in selected until end })?.minus(60_000) ?: (selected + 12 * 3_600_000L)
        } else ui.value.center
        ui.value = ui.value.copy(loading = false, days = days, day = selected, week = false,
            events = filtered(selected, ui.value.filter, week = false), center = center)
    }

    private fun filtered(dayStart: Long?, filter: String?, week: Boolean = ui.value.week) = snapshot.events.filter { e ->
        val inRange = if (week) e.file.startMs >= weekStart() else (dayStart == null || RecArchive.dayStart(e.file.startMs) == dayStart)
        inRange && (filter == null || filter in e.labels)
    }

    /** Начало недели для «Неделя»: полночь шесть дней назад (сегодня и 6 дней до него). */
    private fun weekStart(): Long = RecArchive.dayStart(System.currentTimeMillis() - 6 * 86_400_000L)

    /** Шкала целиком (все дни): участки 24/7 и ролики событий с их длительностью. */
    private fun timelineOf(filter: String?): ArchiveUi.Timeline {
        val segments = snapshot.continuous.sortedBy { it.startMs }
        val coverage = buildList {
            var start = -1L
            var end = -1L
            for (s in segments) {
                if (start >= 0 && s.startMs - end <= ArchiveDay.GAP_MS) { end = maxOf(end, s.endMs); continue }
                if (start >= 0) add(start until end)
                start = s.startMs; end = s.endMs
            }
            if (start >= 0) add(start until end)
        }
        val events = snapshot.events.filter { filter == null || filter in it.labels }
            .map { ArchiveUi.Mark(it.file.startMs, it.file.endMs, it.labels.firstOrNull() ?: "") }
        return ArchiveUi.Timeline(coverage, events)
    }

    // ---- Плеер ----

    private fun play(event: RecArchive.Event, offsetMs: Long = 0) {
        val p = player ?: return
        p.setSeekParameters(SeekParameters.EXACT)
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(event.file.path)), offsetMs.coerceAtLeast(0))
        p.prepare()
        p.play()
        loadedDay = null
        ui.value = ui.value.copy(current = event, continuous = false, center = event.file.startMs + offsetMs)
        tick()
    }

    /** День, чей плейлист 24/7 сейчас загружен в плеер: переход внутри него — простой seek, без пересборки. */
    private var loadedDay: ArchiveDay? = null

    private fun dayOf(wallMs: Long) = ArchiveDay(RecArchive.dayStart(wallMs), snapshot.continuous, snapshot.events)

    private fun playContinuous(d: ArchiveDay, wallMs: Long, play: Boolean = true) {
        val p = player ?: return
        val (segment, offset) = d.locate(wallMs) ?: return
        val loaded = loadedDay
        if (ui.value.continuous && loaded != null && loaded.dayStartMs == d.dayStartMs) {
            p.seekTo(segment, offset)
        } else {
            p.setMediaItems(d.segments.map { MediaItem.fromUri(android.net.Uri.fromFile(it.path)) }, segment, offset)
            p.prepare()
            loadedDay = d
        }
        if (play) p.play()
        ui.value = ui.value.copy(current = null, continuous = true)
        tick()
    }

    /**
     * Встать на момент [wallMs] шкалы. Ведут пальцем ([done] = false) — быстрый переход к ключевому кадру, если
     * этот момент уже в плеере; отпустили — точно: круглосуточная запись, иначе ролик события, иначе ближайшая
     * следующая запись.
     */
    private fun goTo(wallMs: Long, done: Boolean) {
        val p = player ?: return
        val d = dayOf(wallMs)
        val inSegment = d.segments.any { wallMs >= it.startMs && wallMs < it.endMs }
        if (!done) {
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            val current = ui.value.current
            when {
                inSegment -> playContinuous(d, wallMs, play = false)
                current != null && wallMs >= current.file.startMs && wallMs < current.file.endMs -> p.seekTo(wallMs - current.file.startMs)
            }
            return
        }
        p.setSeekParameters(SeekParameters.EXACT)
        ui.value = ui.value.copy(center = wallMs)
        val day = RecArchive.dayStart(wallMs)
        if (day != ui.value.day && day in ui.value.days) selectDay(day)
        val event = snapshot.events.firstOrNull { wallMs >= it.file.startMs && wallMs < it.file.endMs }
        when {
            inSegment -> playContinuous(d, wallMs)
            event != null -> play(event, wallMs - event.file.startMs)
            else -> {
                // Пропуск в записи — к ближайшей следующей (24/7 или событие).
                val next = (snapshot.continuous.map { it.startMs } + snapshot.events.map { it.file.startMs }).filter { it > wallMs }.minOrNull()
                if (next != null) goTo(next, true) else toast("Дальше записей нет")
            }
        }
    }

    /** Момент из журнала звонков: ролик события звонка в HD, если он есть, иначе круглосуточная запись. */
    private fun openMoment(atMs: Long) {
        val event = ArchiveTimeline.eventNear(snapshot.events.filter { "visitor" in it.labels }, atMs, 60_000)
            ?: ArchiveTimeline.eventNear(snapshot.events, atMs, 30_000)
        if (event != null) play(event, (atMs - 5_000 - event.file.startMs).coerceAtLeast(0)) else goTo(atMs - 5_000, true)
    }

    private fun scrubClip(fraction: Float, done: Boolean) {
        val p = player ?: return
        val duration = knownDuration().takeIf { it > 0 } ?: return
        p.setSeekParameters(if (done) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Длительность текущего ролика: из плеера, а если файл ещё без оглавления — из имени файла. */
    private fun knownDuration(): Long = player?.duration?.takeIf { it > 0 } ?: ui.value.current?.file?.durationMs ?: 0L

    /** Текущее время на стене: для риски шкалы и подписи. */
    private fun wallNow(): Long? {
        val p = player ?: return null
        return if (ui.value.continuous) loadedDay?.wallTime(p.currentMediaItemIndex, p.currentPosition)
        else ui.value.current?.let { it.file.startMs + p.currentPosition }
    }

    private var ticking = false
    private fun tick() {
        if (ticking) return
        ticking = true
        lifecycleScope.launch {
            while (player != null) {
                val wall = wallNow()
                val p = player
                ui.value = ui.value.copy(positionWall = wall, center = wall ?: ui.value.center,
                    positionMs = p?.currentPosition ?: 0L, durationMs = knownDuration())
                delay(250)
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (ui.value.continuous) {
            wallNow()?.let { now -> loadedDay?.locate(now + deltaMs)?.let { (i, o) -> p.seekTo(i, o) } }
        } else {
            val duration = knownDuration()
            p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it })
        }
    }

    private fun frameStep() {
        val p = player ?: return
        p.pause()
        p.seekTo(p.currentMediaItemIndex, p.currentPosition + FRAME_MS)
    }

    private fun neighbour(forward: Boolean) {
        val events = snapshot.events.filter { ui.value.filter == null || ui.value.filter in it.labels }
        val at = wallNow() ?: ui.value.center ?: System.currentTimeMillis()
        val next = ArchiveTimeline.neighbour(events, at, ui.value.current, forward)
        if (next == null) toast(if (forward) "Дальше событий нет" else "Раньше событий нет") else play(next)
    }

    // ---- Действия с роликом ----

    private fun star(event: RecArchive.Event) {
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { archive.setStarred(event.file, !event.starred) }
            if (updated == null) { toast("Не удалось изменить отметку"); return@launch }
            replace(event, RecArchive.Event(updated, event.labels))
            // Сохранённый ролик — сразу и в папку копий (не ждать ночи: планшет может пропасть раньше).
            if (updated.starred) launch(Dispatchers.IO) {
                try { ArchiveCopy.run(applicationContext) } catch (_: Exception) {}
                withContext(Dispatchers.Main) { loadStorage() }
            }
        }
    }

    private fun replace(old: RecArchive.Event, new: RecArchive.Event?) {
        snapshot = snapshot.copy(events = snapshot.events.mapNotNull { if (it.file.startMs == old.file.startMs) new else it })
        val current = ui.value.current?.takeIf { it.file.startMs == old.file.startMs }?.let { new } ?: ui.value.current
        ui.value = ui.value.copy(current = if (new == null && ui.value.current?.file?.startMs == old.file.startMs) null else current,
            timeline = timelineOf(ui.value.filter))
        selectDay(ui.value.day)
    }

    private fun requestDelete(event: RecArchive.Event) {
        val pin = RecordingDevice(this).archivePin
        if (pin == RecordingDevice.ArchivePin.OFF) { ui.value = ui.value.copy(confirmDelete = event); return }
        PinSecurityUi.authorize(this, { ui.value = ui.value.copy(confirmDelete = event) })
    }

    private fun delete(event: RecArchive.Event) {
        ui.value = ui.value.copy(confirmDelete = null)
        if (ui.value.current?.file?.startMs == event.file.startMs) player?.stop()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { archive.delete(event.file) }
            if (!ok) { toast("Не удалось удалить ролик"); return@launch }
            thumbs.remove(event.file.path.name)
            replace(event, null)
            loadStorage()
        }
    }

    private fun share(event: RecArchive.Event) {
        lifecycleScope.launch {
            val copy = try { withContext(Dispatchers.IO) { archive.shareCopy(event.file, title) } } catch (_: Exception) {
                toast("Не удалось подготовить файл"); return@launch
            }
            send(copy, "video/mp4", "Поделиться роликом")
        }
    }

    private fun send(file: File, type: String, chooser: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(send, chooser)) } catch (_: Exception) { toast("Некуда отправить") }
    }

    // ---- Кадр и отрезок ----

    /** Кадр с экрана в разрешении записи (без увеличения пальцами). */
    private fun captureFrame() {
        val view = texture ?: return
        val size = player?.videoSize
        val w = size?.width?.takeIf { it > 0 } ?: view.width
        val h = size?.height?.takeIf { it > 0 } ?: view.height
        val frame = try { view.getBitmap(w, h) } catch (_: Exception) { null }
        if (frame == null) { toast("Кадр недоступен — запустите видео"); return }
        player?.pause()
        ui.value = ui.value.copy(frame = frame, frameAt = wallNow())
    }

    private fun frameAction(save: Boolean?) {
        val frame = ui.value.frame ?: return
        val at = ui.value.frameAt ?: System.currentTimeMillis()
        ui.value = ui.value.copy(frame = null)
        if (save == null) return
        val name = RecArchive.shareName(title, at).removeSuffix(".mp4") + "-" + SimpleDateFormat("ss", Locale.ROOT).format(Date(at)) + ".jpg"
        lifecycleScope.launch {
            if (save) {
                val ok = withContext(Dispatchers.IO) { saveToGallery(frame, name) }
                toast(if (ok) "Кадр сохранён в галерею (Pictures/Домофон)" else "Не удалось сохранить кадр")
            } else {
                val file = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(cacheDir, RecArchive.SHARE_DIR).apply { deleteRecursively(); mkdirs() }
                        File(dir, name).also { f -> f.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                    } catch (_: Exception) { null }
                }
                if (file == null) toast("Не удалось подготовить кадр") else send(file, "image/jpeg", "Поделиться кадром")
            }
        }
    }

    private fun saveToGallery(frame: Bitmap, name: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Домофон")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("нет места")
            contentResolver.openOutputStream(uri)?.use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: error("не открылся")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } else {
            // Android 9 и старше: без разрешения на память — в папку приложения (видна в «Файлах»).
            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Домофон").apply { mkdirs() }
            File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }
    } catch (_: Exception) { false }

    private fun mark(start: Boolean) {
        val at = wallNow() ?: run { toast("Сначала запустите запись"); return }
        ui.value = if (start) ui.value.copy(markIn = at, markOut = ui.value.markOut?.takeIf { it > at })
        else ui.value.copy(markOut = at, markIn = ui.value.markIn?.takeIf { it < at })
    }

    private fun shareSegment() {
        val from = ui.value.markIn ?: return
        val to = ui.value.markOut ?: return
        if (to - from > MAX_SEGMENT_MS) { toast("Отрезок длиннее 15 минут — сократите его"); return }
        val current = ui.value.current
        // Внутри ролика события — из него (HD); иначе — из круглосуточной записи.
        val sources = if (current != null && from >= current.file.startMs - 1_000 && to <= current.file.endMs + 1_000) listOf(current.file)
            else snapshot.continuous
        ui.value = ui.value.copy(busy = "Готовлю отрезок…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = File(cacheDir, RecArchive.SHARE_DIR).apply { deleteRecursively(); mkdirs() }
                    val out = File(dir, RecArchive.shareName(title, from).removeSuffix(".mp4") + " отрезок.mp4")
                    val ms = Mp4Cut.cut(sources, from, to, out)
                    if (ms > 0) out to ms else null
                } catch (e: Exception) {
                    android.util.Log.w("Archive", "Отрезок не вырезан: ${e.javaClass.simpleName}"); null
                }
            }
            ui.value = ui.value.copy(busy = null)
            if (result == null) { toast("В этом промежутке нет записи"); return@launch }
            if (result.second < to - from - 3_000) toast("Отрезок короче выбранного: ${durationText(result.second)} (дальше записи нет)")
            send(result.first, "video/mp4", "Поделиться отрезком")
        }
    }

    // ---- Превью над риской ----

    private val previewCache = object : LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > PREVIEW_CACHE
    }
    private val previewThread = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "archive-preview").apply { isDaemon = true } }
    @Volatile private var previewWanted = 0L
    private var previewLogged = 0

    /**
     * Кадр для момента [atMs]: ролик 24/7, иначе ролик события; шаг [PREVIEW_STEP_MS] (соседние моменты — тот же кадр).
     * Считается в своём потоке; пока считался, палец ушёл дальше — старый запрос пропускается.
     */
    private fun preview(atMs: Long) {
        val key = atMs / PREVIEW_STEP_MS * PREVIEW_STEP_MS
        previewCache[key]?.let { ui.value = ui.value.copy(preview = key to it); return }
        previewWanted = key
        val segment = snapshot.continuous.firstOrNull { key >= it.startMs && key < it.endMs }
            ?: snapshot.events.firstOrNull { key >= it.file.startMs && key < it.file.endMs }?.file ?: return
        previewThread.execute {
            if (previewWanted != key) return@execute
            val started = android.os.SystemClock.uptimeMillis()
            val frame = try { archive.frameAt(segment, key - segment.startMs) } catch (_: Exception) { null }
            if (frame == null) { android.util.Log.w("Archive", "Превью: кадр не получен"); return@execute }
            if (previewLogged++ < 3) android.util.Log.i("Archive", "Превью: ${frame.width}×${frame.height} за ${android.os.SystemClock.uptimeMillis() - started} мс")
            runOnUiThread {
                previewCache[key] = frame
                if (previewWanted == key) ui.value = ui.value.copy(preview = key to frame)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val actions = object : ArchiveActions {
        override fun back() = finish()
        override fun selectDay(day: Long) = this@ArchiveActivity.selectDay(day, recenter = true)
        override fun filter(label: String?) {
            ui.value = ui.value.copy(filter = label, events = filtered(ui.value.day, label, ui.value.week), timeline = timelineOf(label))
        }
        override fun play(event: RecArchive.Event) = this@ArchiveActivity.play(event)
        override fun timelineTap(atMs: Long, toleranceMs: Long) {
            // Касание прямо по ролику события (в пределах пальца) — ролик в HD; иначе — тот же момент 24/7.
            val events = snapshot.events.filter { ui.value.filter == null || ui.value.filter in it.labels }
            val near = ArchiveTimeline.eventNear(events, atMs, toleranceMs)
            if (near != null && (atMs < near.file.startMs || atMs >= near.file.endMs || snapshot.continuous.none { atMs >= it.startMs && atMs < it.endMs }))
                play(near)
            else if (near != null && near.file.endMs - near.file.startMs > 0 && atMs in near.file.startMs until near.file.endMs)
                play(near, atMs - near.file.startMs)
            else goTo(atMs, true)
        }
        override fun timelineScrub(atMs: Long, done: Boolean) = goTo(atMs, done)
        override fun timelineSpan(spanMs: Long) { ui.value = ui.value.copy(spanMs = ArchiveTimeline.clampSpan(spanMs)) }
        override fun toNow() {
            val latest = ArchiveTimeline.latest(snapshot.continuous, snapshot.events) ?: run { toast("Записей пока нет"); return }
            goTo(latest - 10_000, true)
        }
        override fun neighbour(forward: Boolean) = this@ArchiveActivity.neighbour(forward)
        override fun scrubClip(fraction: Float, done: Boolean) = this@ArchiveActivity.scrubClip(fraction, done)
        override fun togglePlay() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        override fun seekBy(deltaMs: Long) = this@ArchiveActivity.seekBy(deltaMs)
        override fun frameStep() = this@ArchiveActivity.frameStep()
        override fun speed(value: Float) { player?.setPlaybackSpeed(value); ui.value = ui.value.copy(speed = value) }
        override fun star(event: RecArchive.Event) = this@ArchiveActivity.star(event)
        override fun delete(event: RecArchive.Event) = requestDelete(event)
        override fun confirmDelete(event: RecArchive.Event?) {
            if (event == null) ui.value = ui.value.copy(confirmDelete = null) else this@ArchiveActivity.delete(event)
        }
        override fun share(event: RecArchive.Event) = this@ArchiveActivity.share(event)
        override fun thumbnail(file: RecFile) {
            val key = file.path.name
            if (key in thumbs) return
            thumbs[key] = null
            lifecycleScope.launch { thumbs[key] = withContext(Dispatchers.IO) { archive.thumbnail(file) } }
        }
        override fun captureFrame() = this@ArchiveActivity.captureFrame()
        override fun frameAction(save: Boolean?) = this@ArchiveActivity.frameAction(save)
        override fun mark(start: Boolean) = this@ArchiveActivity.mark(start)
        override fun clearMarks() { ui.value = ui.value.copy(markIn = null, markOut = null) }
        override fun shareSegment() = this@ArchiveActivity.shareSegment()
        override fun tab(tab: ArchiveUi.Tab) { ui.value = ui.value.copy(tab = tab) }
        override fun calendar(show: Boolean) { ui.value = ui.value.copy(calendar = show) }
        override fun ringThumb(ring: RingLog.Ring) {
            val key = ringKey(ring)
            if (key in thumbs) return
            thumbs[key] = null
            lifecycleScope.launch { thumbs[key] = withContext(Dispatchers.IO) { RingLog.photo(ring.photo, 320) } }
        }
        override fun ringPhoto(ring: RingLog.Ring?) {
            ui.value = ui.value.copy(ringPhoto = ring)
            if (ring != null) lifecycleScope.launch { thumbs[ringKey(ring) + "_big"] = withContext(Dispatchers.IO) { RingLog.photo(ring.photo, 1280) } }
        }
        override fun preview(atMs: Long) = this@ArchiveActivity.preview(atMs)
        override fun camera(tileId: String) = switchCamera(tileId)
        override fun selectWeek() {
            ui.value = ui.value.copy(week = true, events = filtered(ui.value.day, ui.value.filter, week = true))
        }
        override fun openRing(ring: RingLog.Ring) {
            ui.value = ui.value.copy(ringPhoto = null, tab = ArchiveUi.Tab.RECORDS)
            openMoment(ring.atMs)
        }
    }

    /** Видео: TextureView (в отличие от SurfaceView, её можно увеличивать пальцами). */
    @Composable
    private fun VideoSurface() {
        AndroidView(factory = { context -> TextureView(context).also { texture = it; player?.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { player?.clearVideoTextureView(it); if (texture === it) texture = null })
    }

    companion object {
        const val EXTRA_TILE = "tile_id"
        const val EXTRA_LATEST = "latest"
        const val EXTRA_CALLS = "calls"
        const val EXTRA_AT = "at"
        private const val FRAME_MS = 40L
        private const val MAX_SEGMENT_MS = 15 * 60_000L
        private const val PREVIEW_STEP_MS = 5_000L
        private const val PREVIEW_CACHE = 120

        internal fun ringKey(ring: RingLog.Ring) = "ring_${ring.atMs}_${ring.photo?.lastModified() ?: 0}"

        fun open(context: Context, tileId: String, latest: Boolean = false, calls: Boolean = false, atMs: Long? = null) {
            context.startActivity(Intent(context, ArchiveActivity::class.java)
                .putExtra(EXTRA_TILE, tileId).putExtra(EXTRA_LATEST, latest).putExtra(EXTRA_CALLS, calls)
                .apply { atMs?.let { putExtra(EXTRA_AT, it) } })
        }
    }
}

/** Состояние экрана архива (неизменяемое; для тестов и предпросмотра). */
internal data class ArchiveUi(
    val title: String = "",
    val loading: Boolean = true,
    val locked: Boolean = false,
    val days: List<Long> = emptyList(),
    val day: Long? = null,
    val filter: String? = null,
    val events: List<RecArchive.Event> = emptyList(),
    val timeline: Timeline? = null,
    /** Момент под риской шкалы и видимая ширина шкалы. */
    val center: Long? = null,
    val spanMs: Long = ArchiveTimeline.DEFAULT_SPAN,
    val current: RecArchive.Event? = null,
    val continuous: Boolean = false,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val positionWall: Long? = null,
    /** Позиция и длительность текущего ролика (для полосы перемотки события). */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoAspect: Float = 16f / 9f,
    val confirmDelete: RecArchive.Event? = null,
    /** Отрезок «Поделиться»: начало и конец (время на стене). */
    val markIn: Long? = null,
    val markOut: Long? = null,
    /** Идёт долгая операция (вырезка отрезка) — подпись. */
    val busy: String? = null,
    val frame: Bitmap? = null,
    val frameAt: Long? = null,
    val tab: Tab = Tab.RECORDS,
    val rings: List<RingLog.Ring> = emptyList(),
    val ringPhoto: RingLog.Ring? = null,
    val storage: String? = null,
    val calendar: Boolean = false,
    /** События сразу за 7 дней (кнопка «Неделя») вместо одного дня. */
    val week: Boolean = false,
    /** Кадр-превью над риской, пока ведут ленту: время на стене (округлённое) и кадр. */
    val preview: Pair<Long, Bitmap>? = null,
    /** Как копируются ролики со ★ (ночью в папку копий). */
    val copyStatus: String? = null,
    /** Камеры для переключения (номер плитки → название) и текущая. */
    val cameras: List<Pair<String, String>> = emptyList(),
    val camera: String? = null,
    /** Текущая камера — домофон (есть вкладка «Звонки»). */
    val doorbell: Boolean = true
) {
    enum class Tab { RECORDS, CALLS }
    /** Участки круглосуточной записи и ролики событий — время на стене, все дни. */
    data class Timeline(val coverage: List<LongRange>, val events: List<Mark>)
    data class Mark(val start: Long, val end: Long, val label: String)
}

internal interface ArchiveActions {
    fun back()
    fun selectDay(day: Long)
    fun filter(label: String?)
    fun play(event: RecArchive.Event)
    /** Касание шкалы в момент [atMs]; ролик события ближе [toleranceMs] (≈ палец) — ролик события. */
    fun timelineTap(atMs: Long, toleranceMs: Long)
    /** Ленту ведут пальцем: под риской [atMs]; [done] — палец отпущен. */
    fun timelineScrub(atMs: Long, done: Boolean)
    fun timelineSpan(spanMs: Long)
    /** «Сейчас» — к последней записи. */
    fun toNow()
    /** «◀ Событие» / «Событие ▶». */
    fun neighbour(forward: Boolean)
    /** Перемотка внутри ролика события. */
    fun scrubClip(fraction: Float, done: Boolean)
    fun togglePlay()
    fun seekBy(deltaMs: Long)
    fun frameStep()
    fun speed(value: Float)
    fun star(event: RecArchive.Event)
    fun delete(event: RecArchive.Event)
    fun confirmDelete(event: RecArchive.Event?)
    fun share(event: RecArchive.Event)
    fun thumbnail(file: RecFile)
    fun captureFrame()
    /** Кадр: true — в галерею, false — поделиться, null — закрыть. */
    fun frameAction(save: Boolean?)
    fun mark(start: Boolean)
    fun clearMarks()
    fun shareSegment()
    fun tab(tab: ArchiveUi.Tab)
    fun calendar(show: Boolean)
    fun ringThumb(ring: RingLog.Ring)
    fun ringPhoto(ring: RingLog.Ring?)
    fun openRing(ring: RingLog.Ring)
    /** Кадр-превью для момента [atMs] (пока ведут ленту). */
    fun preview(atMs: Long)
    /** «Неделя» — события за 7 дней. */
    fun selectWeek()
    /** Другая камера (плитка). */
    fun camera(tileId: String) {}
}

// ---- Оформление: палитра Modern (res/values/modern_colors.xml) ----

private object ArchiveColors {
    val background = Color(0xFF0A1014)
    val surface = Color(0xFF0E1E26)
    val border = Color(0x6E347880)
    val accent = Color(0xFF26C6DA)
    val accentSoft = Color(0x3C26C6DA)
    val text = Color.White
    val secondary = Color(0xFFC8D6DE)
    val hint = Color(0xFF788C96)
    val danger = Color(0xFFFF5252)
    val star = Color(0xFFFFCD78)
    val segment = Color(0x50FFCD78)

    fun label(key: String) = when (key) {
        "visitor" -> Color(0xFF34E89E)
        "person" -> Color(0xFFFFB74D)
        "vehicle" -> Color(0xFF64B5F6)
        "animal" -> Color(0xFFBA68C8)
        else -> Color(0xFF90A4AE)
    }
}

@Composable
private fun ArchiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = ArchiveColors.accent, onPrimary = Color(0xFF062A30), background = ArchiveColors.background,
        surface = ArchiveColors.surface, onSurface = ArchiveColors.text, onBackground = ArchiveColors.text,
        secondary = ArchiveColors.accent, secondaryContainer = ArchiveColors.accentSoft, onSecondaryContainer = ArchiveColors.text,
        onSurfaceVariant = ArchiveColors.secondary, outline = ArchiveColors.border
    ), content = content)
}

internal fun labelTitle(key: String) = RecordingConfig.TRIGGERS.firstOrNull { it.first == key }?.second ?: key

private val timeFormat get() = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private val shortTime get() = SimpleDateFormat("HH:mm", Locale.ROOT)
private val dayFormat get() = SimpleDateFormat("d MMM", Locale("ru"))

internal fun durationText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "$s с" else "${s / 60} мин ${"%02d".format(s % 60)} с"
}

@Composable
internal fun ArchiveScreen(ui: ArchiveUi, thumbs: Map<String, Bitmap?>, actions: ArchiveActions, video: @Composable () -> Unit) =
    ArchiveTheme { ArchiveContent(ui, thumbs, actions, video) }

@Composable
private fun ArchiveContent(ui: ArchiveUi, thumbs: Map<String, Bitmap?>, actions: ArchiveActions, video: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(ArchiveColors.background).testTag("archive_root")) {
        if (ui.locked) {
            Text("Архив защищён PIN", color = ArchiveColors.secondary, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.6f).fillMaxSize()) { PlayerPane(ui, actions, video, fill = true) }
                    Column(Modifier.weight(1f).fillMaxSize()) { ListPane(ui, thumbs, actions) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PlayerPane(ui, actions, video, fill = false)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f)) { ListPane(ui, thumbs, actions) }
                }
            }
        }
        ui.confirmDelete?.let { event ->
            AlertDialog(
                onDismissRequest = { actions.confirmDelete(null) },
                title = { Text("Удалить ролик?") },
                text = { Text("${timeFormat.format(Date(event.file.startMs))} · ${durationText(event.file.durationMs)}. Восстановить будет нельзя.") },
                confirmButton = { TextButton(onClick = { actions.confirmDelete(event) }, modifier = Modifier.testTag("archive_delete_confirm")) {
                    Text("Удалить", color = ArchiveColors.danger) } },
                dismissButton = { TextButton(onClick = { actions.confirmDelete(null) }) { Text("Отмена") } }
            )
        }
        ui.frame?.let { frame ->
            AlertDialog(
                onDismissRequest = { actions.frameAction(null) },
                title = { Text("Кадр ${ui.frameAt?.let { timeFormat.format(Date(it)) } ?: ""}") },
                text = { Image(frame.asImageBitmap(), contentDescription = "Кадр", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) },
                confirmButton = { TextButton(onClick = { actions.frameAction(true) }, modifier = Modifier.testTag("archive_frame_save")) {
                    Text("В галерею") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { actions.frameAction(false) }, modifier = Modifier.testTag("archive_frame_share")) { Text("Поделиться") }
                        TextButton(onClick = { actions.frameAction(null) }) { Text("Закрыть") }
                    }
                }
            )
        }
        ui.ringPhoto?.let { ring -> RingDialog(ring, thumbs, actions) }
        if (ui.calendar) CalendarDialog(ui, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(ui: ArchiveUi, actions: ArchiveActions) {
    // DatePicker работает с полуночью UTC; дни архива — местная полночь.
    val localDays = ui.days.map { localToUtcDate(it) }.toSet()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = ui.day?.let(::localToUtcDate),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in localDays
        })
    DatePickerDialog(
        onDismissRequest = { actions.calendar(false) },
        confirmButton = {
            TextButton(onClick = {
                actions.calendar(false)
                state.selectedDateMillis?.let { actions.selectDay(utcDateToLocal(it)) }
            }, modifier = Modifier.testTag("archive_calendar_ok")) { Text("Показать") }
        },
        dismissButton = { TextButton(onClick = { actions.calendar(false) }) { Text("Отмена") } }
    ) { DatePicker(state = state, showModeToggle = false) }
}

/** Местная полночь → полночь UTC того же числа (для DatePicker). */
internal fun localToUtcDate(localDayStart: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localDayStart }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

internal fun utcDateToLocal(utcDate: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDate }
    return Calendar.getInstance().apply {
        clear(); set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

@Composable
private fun RingDialog(ring: RingLog.Ring, thumbs: Map<String, Bitmap?>, actions: ArchiveActions) {
    AlertDialog(
        onDismissRequest = { actions.ringPhoto(null) },
        title = { Text("Звонок ${dayTitle(RecArchive.dayStart(ring.atMs))}, ${timeFormat.format(Date(ring.atMs))}") },
        text = {
            val big = thumbs[ArchiveActivity.ringKey(ring) + "_big"] ?: thumbs[ArchiveActivity.ringKey(ring)]
            if (big != null) Image(big.asImageBitmap(), contentDescription = "Снимок посетителя", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
            else Text(if (ring.photo == null) "Снимка нет: в момент звонка видео не шло" else "Загрузка…", color = ArchiveColors.hint)
        },
        confirmButton = { TextButton(onClick = { actions.openRing(ring) }, modifier = Modifier.testTag("archive_ring_open")) { Text("Запись звонка") } },
        dismissButton = { TextButton(onClick = { actions.ringPhoto(null) }) { Text("Закрыть") } }
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlayerPane(ui: ArchiveUi, actions: ArchiveActions,
                                                                     video: @Composable () -> Unit, fill: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = actions::back) { Text("‹ Назад", fontSize = 18.sp) }
        Text("Архив · ${ui.title}", color = ArchiveColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
        val now = ui.positionWall
        if (now != null) Text("${dayFormat.format(Date(now))}, ${timeFormat.format(Date(now))}" +
            if (ui.continuous) " · круглосуточно" else " · событие HD", color = ArchiveColors.secondary, fontSize = 16.sp,
            modifier = Modifier.testTag("archive_position"))
    }
    Spacer(Modifier.height(8.dp))
    // Альбом: видео занимает всё, что осталось от кнопок и шкалы (на 1280×800 dp иначе шкала уходит за экран).
    Box((if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        .clip(RoundedCornerShape(16.dp)).background(Color.Black)
        .border(1.dp, ArchiveColors.border, RoundedCornerShape(16.dp))) {
        if (ui.current != null || ui.continuous) ZoomableVideo(ui, video)
        else Text(if (ui.loading) "Загрузка…" else "Выберите событие или место на шкале", color = ArchiveColors.hint,
            fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        ui.busy?.let {
            Text(it, color = ArchiveColors.text, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xB0000000), RoundedCornerShape(10.dp)).padding(12.dp))
        }
    }
    if (ui.current != null) ClipBar(ui, actions)
    Spacer(Modifier.height(8.dp))
    Controls(ui, actions)
    Spacer(Modifier.height(8.dp))
    Timeline(ui, actions)
}

/**
 * Видео в своих пропорциях (без растяжения) с увеличением пальцами: два пальца — масштаб до 5×, один палец по
 * увеличенной картинке — сдвиг, двойное касание — увеличить/вернуть. Увеличение сбрасывается при смене ролика.
 */
@Composable
private fun ZoomableVideo(ui: ArchiveUi, video: @Composable () -> Unit) {
    val key = ui.current?.file?.startMs ?: -1L
    var zoom by remember(key) { mutableStateOf(VideoZoom()) }
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ui.videoAspect.coerceIn(0.3f, 4f)).fillMaxSize().testTag("archive_video")
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (pressed >= 2) {
                            val c = event.calculateCentroid()
                            val pan = event.calculatePan()
                            zoom = zoom.pinch(c.x, c.y, event.calculateZoom(), pan.x, pan.y, w, h)
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1 && zoom.zoomed) {
                            val change = event.changes.first { it.pressed }
                            val d = change.positionChange()
                            if (d != Offset.Zero) { zoom = zoom.pan(d.x, d.y, w, h); change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(key) {
                detectTapGestures(onDoubleTap = { zoom = zoom.toggle(it.x, it.y, size.width.toFloat(), size.height.toFloat()) })
            }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.x; translationY = zoom.y
            }) { video() }
        }
        Text(if (zoom.zoomed) "🔍 ${zoom.title} · двойное касание — обычный вид" else "", color = ArchiveColors.text, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).testTag("archive_zoom"))
    }
}

/** Полоса ролика события: позиция, перетаскивание с переходом по ходу движения, длительность. */
@Composable
private fun ClipBar(ui: ArchiveUi, actions: ArchiveActions) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var lastSeek by remember { mutableStateOf(0L) }
    val duration = ui.durationMs
    val position = drag?.let { (it * duration).toLong() } ?: ui.positionMs
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(clock(position), color = ArchiveColors.secondary, fontSize = 15.sp, modifier = Modifier.width(56.dp))
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { f ->
                drag = f
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastSeek > SCRUB_EVERY_MS) { lastSeek = now; actions.scrubClip(f, false) }
            },
            onValueChangeFinished = { drag?.let { actions.scrubClip(it, true) }; drag = null },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = ArchiveColors.text, activeTrackColor = ArchiveColors.accentSoft),
            modifier = Modifier.weight(1f).testTag("archive_clip_slider")
        )
        Text(clock(duration), color = ArchiveColors.secondary, fontSize = 15.sp, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Переход плеера при перетаскивании — не чаще (декодер HD не успевает за каждым движением пальца). */
private const val SCRUB_EVERY_MS = 150L

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(ui: ArchiveUi, actions: ArchiveActions) {
    val active = ui.current != null || ui.continuous
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlButton(if (ui.playing) "❚❚ Пауза" else "▶ Пуск", active, "archive_play") { actions.togglePlay() }
        ControlButton("−10 с", active, "archive_back10") { actions.seekBy(-10_000) }
        ControlButton("+10 с", active, "archive_fwd10") { actions.seekBy(10_000) }
        ControlButton("Кадр ›", active && !ui.playing, "archive_frame") { actions.frameStep() }
        listOf(1f, 2f, 4f, 8f).forEach { s ->
            FilterChip(selected = ui.speed == s, onClick = { actions.speed(s) }, enabled = active,
                label = { Text("${s.toInt()}×", fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_speed_${s.toInt()}"))
        }
    }
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlButton("◀ Событие", true, "archive_prev_event") { actions.neighbour(false) }
        ControlButton("Событие ▶", true, "archive_next_event") { actions.neighbour(true) }
        ControlButton("📷 Кадр", active, "archive_capture") { actions.captureFrame() }
        ControlButton(ui.markIn?.let { "[ ${timeFormat.format(Date(it))}" } ?: "[ Начало", active, "archive_mark_in") { actions.mark(true) }
        ControlButton(ui.markOut?.let { "${timeFormat.format(Date(it))} ]" } ?: "Конец ]", active, "archive_mark_out") { actions.mark(false) }
        if (ui.markIn != null && ui.markOut != null) {
            ControlButton("Поделиться отрезком", ui.busy == null, "archive_share_segment") { actions.shareSegment() }
        }
        if (ui.markIn != null || ui.markOut != null) {
            TextButton(onClick = actions::clearMarks, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_clear_marks")) { Text("✕", fontSize = 18.sp) }
        }
    }
    ui.current?.let { event ->
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(if (event.starred) "★ Сохранён" else "☆ Сохранить", true, "archive_star") { actions.star(event) }
            ControlButton("Поделиться", true, "archive_share") { actions.share(event) }
            OutlinedButton(onClick = { actions.delete(event) }, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_delete")) {
                Text("Удалить", color = ArchiveColors.danger, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ControlButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        colors = ButtonDefaults.buttonColors(containerColor = ArchiveColors.surface, contentColor = ArchiveColors.text)) {
        Text(text, fontSize = 16.sp)
    }
}

/**
 * Шкала времени как у Reolink: риска по центру неподвижна, лента под ней. Ведут пальцем — лента едет, видео
 * перематывается по ходу, отпустили — воспроизведение с момента под риской; касание — этот момент встаёт под риску
 * (по ролику события — ролик в HD); два пальца — масштаб от 10 минут до суток.
 */
@Composable
private fun Timeline(ui: ArchiveUi, actions: ArchiveActions) {
    val timeline = ui.timeline
    var dragCenter by remember { mutableStateOf<Long?>(null) }
    var pinchSpan by remember { mutableStateOf<Long?>(null) }
    val center = dragCenter ?: ui.center ?: System.currentTimeMillis()
    val span = pinchSpan ?: ui.spanMs
    val currentCenter by rememberUpdatedState(center)
    val currentSpan by rememberUpdatedState(span)
    val measurer = rememberTextMeasurer()
    // Кадр-превью над риской, пока ведут ленту (как у Reolink): рисуется поверх видео, место под шкалой не меняется.
    // Кадр готов с опозданием (декодер), а палец уходит дальше: показывается последний кадр, если он не дальше 1/15
    // видимого отрезка (найдено на планшете 19.11.2026: с допуском 10 с при быстром ведении превью не появлялось).
    val preview = ui.preview?.takeIf { p -> dragCenter?.let { kotlin.math.abs(it - p.first) <= maxOf(10_000L, span / 15) } == true }
    Box {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text((if (dragCenter != null) "→ " else "") + "${dayFormat.format(Date(center))}, ${timeFormat.format(Date(center))}",
                color = if (dragCenter != null) ArchiveColors.text else ArchiveColors.secondary, fontSize = 16.sp,
                modifier = Modifier.weight(1f).testTag("archive_scrub_time"))
            Text(spanTitle(span), color = ArchiveColors.hint, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp).testTag("archive_span"))
            TextButton(onClick = actions::toNow, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_now")) { Text("Сейчас ⏭", fontSize = 16.sp) }
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).background(ArchiveColors.surface)
            .testTag("archive_timeline")
            .semantics { contentDescription = "Шкала времени: ведите пальцем, два пальца — масштаб" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val startCenter = currentCenter
                    val startSpan = currentSpan
                    var mode = 0 // 0 — касание, 1 — ведут ленту, 2 — масштаб
                    var originX = down.position.x
                    var originCenter = startCenter
                    var pinchFrom = 0f
                    var pinchBase = startSpan
                    var lastSeek = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (mode != 2) { mode = 2; pinchFrom = d; pinchBase = pinchSpan ?: startSpan; dragCenter = null }
                            else if (d > 1f) pinchSpan = ArchiveTimeline.clampSpan((pinchBase * pinchFrom / d).toLong())
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == 2) continue // после масштаба оставшийся палец ленту не двигает
                        val change = pressed[0]
                        if (mode == 0 && kotlin.math.abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                            mode = 1; originX = change.position.x; originCenter = startCenter
                        }
                        if (mode == 1) {
                            val c = originCenter - ((change.position.x - originX) / w * startSpan).toLong()
                            dragCenter = c
                            if (change.uptimeMillis - lastSeek > SCRUB_EVERY_MS) {
                                lastSeek = change.uptimeMillis
                                actions.timelineScrub(c, false)
                                actions.preview(c)
                            }
                            change.consume()
                        }
                    }
                    when (mode) {
                        2 -> pinchSpan?.let { actions.timelineSpan(it) }
                        1 -> dragCenter?.let { actions.timelineScrub(it, true) }
                        else -> {
                            val at = startCenter - startSpan / 2 + (down.position.x / w * startSpan).toLong()
                            actions.timelineTap(at, (16.dp.toPx() / w * startSpan).toLong())
                        }
                    }
                    dragCenter = null
                    pinchSpan = null
                }
            }) {
            val w = size.width
            val h = size.height
            val left = center - span / 2
            val perMs = w / span.toFloat()
            fun x(t: Long) = (t - left) * perMs
            // Записано круглосуточно — полоса акцента; пропуски — фон.
            timeline?.coverage?.forEach { r ->
                if (r.last < left || r.first > left + span) return@forEach
                val x0 = x(r.first).coerceAtLeast(0f)
                val x1 = x(r.last + 1).coerceAtMost(w)
                drawRect(ArchiveColors.accentSoft, Offset(x0, h * 0.38f), Size((x1 - x0).coerceAtLeast(1f), h * 0.32f))
            }
            // Ролики событий — своей длины (на крупном масштабе — полосой, на мелком — засечкой).
            timeline?.events?.forEach { m ->
                if (m.end < left || m.start > left + span) return@forEach
                val x0 = x(m.start)
                val x1 = maxOf(x(m.end), x0 + 4f)
                drawRect(ArchiveColors.label(m.label).copy(alpha = 0.85f), Offset(x0, h * 0.30f), Size(x1 - x0, h * 0.48f))
            }
            // Отрезок «Поделиться».
            val a = ui.markIn
            val b = ui.markOut
            if (a != null || b != null) {
                val x0 = x(a ?: b!!)
                val x1 = x(b ?: a!!)
                drawRect(ArchiveColors.segment, Offset(minOf(x0, x1), 0f), Size(kotlin.math.abs(x1 - x0).coerceAtLeast(3f), h))
            }
            // Подписи времени: шаг — чтобы между ними было не меньше 64 dp.
            val step = ArchiveTimeline.step(span, w, 64.dp.toPx())
            val style = TextStyle(color = ArchiveColors.hint, fontSize = 12.sp)
            ArchiveTimeline.ticks(left, left + span, step).forEach { t ->
                val tx = x(t)
                val midnight = RecArchive.dayStart(t) == t
                drawLine(if (midnight) ArchiveColors.secondary else ArchiveColors.border, Offset(tx, if (midnight) 0f else h * 0.78f),
                    Offset(tx, h), strokeWidth = if (midnight) 2f else 1f)
                val label = if (midnight) dayFormat.format(Date(t)) else shortTime.format(Date(t))
                val layout = measurer.measure(label, style)
                if (tx + 4f + layout.size.width < w) drawText(layout, topLeft = Offset(tx + 4f, h * 0.02f))
            }
            // Риска по центру — неподвижна.
            val cx = w / 2f
            drawLine(Color.White, Offset(cx, 0f), Offset(cx, h), strokeWidth = 3f)
            drawPath(Path().apply { moveTo(cx - 9f, 0f); lineTo(cx + 9f, 0f); lineTo(cx, 12f); close() }, Color.White)
        }
        if (timeline != null && timeline.coverage.isEmpty())
            Text("Круглосуточной записи нет — на шкале только события", color = ArchiveColors.hint, fontSize = 14.sp)
    }
    preview?.let { (at, frame) ->
        Column(Modifier.align(Alignment.TopCenter).offset(y = (-128).dp).testTag("archive_preview"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(frame.asImageBitmap(), contentDescription = "Кадр в ${timeFormat.format(Date(at))}", contentScale = ContentScale.Crop,
                modifier = Modifier.width(176.dp).height(112.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp)))
        }
    }
    }
}

private fun spanTitle(span: Long): String = when {
    span >= 3_600_000L -> "видно ${span / 3_600_000L} ч" + if (span % 3_600_000L >= 1_800_000L) " 30 мин" else ""
    else -> "видно ${span / 60_000L} мин"
} + " · два пальца — масштаб"

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ListPane(ui: ArchiveUi, thumbs: Map<String, Bitmap?>, actions: ArchiveActions) {
    // Несколько камер (docs/camera-tz.md) — переключение одним касанием.
    if (ui.cameras.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            items(ui.cameras) { (id, name) ->
                FilterChip(selected = ui.camera == id, onClick = { actions.camera(id) },
                    label = { Text(name, fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_camera_$id"),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArchiveColors.accentSoft))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = ui.tab == ArchiveUi.Tab.RECORDS, onClick = { actions.tab(ArchiveUi.Tab.RECORDS) },
            label = { Text("Записи", fontSize = 18.sp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_tab_records"))
        val missed = ui.rings.count { !it.answered }
        // У камеры нет звонка — нет и вкладки «Звонки».
        if (ui.doorbell) FilterChip(selected = ui.tab == ArchiveUi.Tab.CALLS, onClick = { actions.tab(ArchiveUi.Tab.CALLS) },
            label = { Text("Звонки" + if (ui.rings.isNotEmpty()) " (${ui.rings.size})" else "", fontSize = 18.sp) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("archive_tab_calls").semantics {
                contentDescription = "Звонки, пропущенных $missed"
            })
    }
    Spacer(Modifier.height(4.dp))
    if (ui.tab == ArchiveUi.Tab.CALLS && ui.doorbell) { CallsList(ui, thumbs, actions); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            item {
                FilterChip(selected = ui.week, onClick = { actions.selectWeek() }, enabled = ui.days.isNotEmpty(),
                    label = { Text("Неделя", fontSize = 16.sp) }, modifier = Modifier.testTag("archive_week"),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArchiveColors.accentSoft))
            }
            items(ui.days) { d ->
                FilterChip(selected = !ui.week && ui.day == d, onClick = { actions.selectDay(d) },
                    label = { Text(dayTitle(d), fontSize = 16.sp) }, modifier = Modifier.testTag("archive_day_$d"),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArchiveColors.accentSoft))
            }
        }
        TextButton(onClick = { actions.calendar(true) }, enabled = ui.days.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("archive_calendar")) { Text("📅 Дата", fontSize = 16.sp) }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(selected = ui.filter == null, onClick = { actions.filter(null) }, label = { Text("Все", fontSize = 16.sp) },
                modifier = Modifier.testTag("archive_filter_all"))
        }
        items(RecordingConfig.TRIGGERS) { (key, title) ->
            FilterChip(selected = ui.filter == key, onClick = { actions.filter(key) }, label = { Text(title, fontSize = 16.sp) },
                modifier = Modifier.testTag("archive_filter_$key"),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArchiveColors.label(key).copy(alpha = 0.3f)))
        }
    }
    Spacer(Modifier.height(8.dp))
    if (ui.events.isEmpty() && !ui.loading) {
        Text(if (ui.days.isEmpty()) "Записей пока нет" else if (ui.week) "Событий за неделю нет" else "Событий за этот день нет",
            color = ArchiveColors.hint, fontSize = 18.sp,
            modifier = Modifier.padding(top = 24.dp).testTag("archive_empty"))
    }
    // Сменили день или «Неделя» — список с начала (иначе он держится за прежний первый ролик, и заголовок дня уходит
    // за верхний край — найдено на планшете 19.11.2026).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(ui.week, ui.day, ui.filter) { listState.scrollToItem(0) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).testTag("archive_list")) {
        if (ui.week) {
            // За неделю — с заголовком каждого дня (новые первыми).
            ui.events.groupBy { RecArchive.dayStart(it.file.startMs) }.forEach { (day, events) ->
                item(key = "day_$day") {
                    Text("${dayTitle(day)} · ${events.size}", color = ArchiveColors.secondary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp).testTag("archive_week_day_$day"))
                }
                items(events, key = { it.file.startMs }) { event -> EventRow(event, ui.current?.file?.startMs == event.file.startMs, thumbs, actions) }
            }
        } else {
            items(ui.events, key = { it.file.startMs }) { event -> EventRow(event, ui.current?.file?.startMs == event.file.startMs, thumbs, actions) }
        }
    }
    ui.storage?.let {
        Text(it, color = ArchiveColors.hint, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).testTag("archive_storage"))
    }
    ui.copyStatus?.let {
        Text(it, color = ArchiveColors.hint, fontSize = 14.sp, modifier = Modifier.testTag("archive_copy_status"))
    }
}

@Composable
private fun CallsList(ui: ArchiveUi, thumbs: Map<String, Bitmap?>, actions: ArchiveActions) {
    if (ui.rings.isEmpty()) {
        Text("Звонков пока не было", color = ArchiveColors.hint, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp).testTag("archive_calls_empty"))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().testTag("archive_calls")) {
        items(ui.rings, key = { it.atMs }) { ring ->
            val key = ArchiveActivity.ringKey(ring)
            LaunchedEffect(key) { actions.ringThumb(ring) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArchiveColors.surface)
                .clickable { actions.ringPhoto(ring) }.padding(8.dp).testTag("archive_ring_${ring.atMs}"),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(128.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                    thumbs[key]?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        ?: Text(if (ring.photo == null) "нет снимка" else "", color = ArchiveColors.hint, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(timeFormat.format(Date(ring.atMs)), color = ArchiveColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(dayTitle(RecArchive.dayStart(ring.atMs)), color = ArchiveColors.secondary, fontSize = 15.sp)
                }
                Text(if (ring.answered) "Ответили" else "Пропущен", color = if (ring.answered) ArchiveColors.label("visitor") else ArchiveColors.danger,
                    fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

private fun dayTitle(dayStart: Long): String {
    val today = RecArchive.dayStart(System.currentTimeMillis())
    return when (dayStart) {
        today -> "Сегодня"
        today - 86_400_000L -> "Вчера"
        else -> dayFormat.format(Date(dayStart))
    }
}

@Composable
private fun EventRow(event: RecArchive.Event, selected: Boolean, thumbs: Map<String, Bitmap?>, actions: ArchiveActions) {
    val key = event.file.path.name
    LaunchedEffect(key) { actions.thumbnail(event.file) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) ArchiveColors.accentSoft else ArchiveColors.surface)
        .clickable { actions.play(event) }.padding(8.dp).testTag("archive_event_${event.file.startMs}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            thumbs[key]?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(timeFormat.format(Date(event.file.startMs)), color = ArchiveColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(durationText(event.file.durationMs), color = ArchiveColors.secondary, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                event.labels.forEach { l ->
                    Text(labelTitle(l), color = ArchiveColors.label(l), fontSize = 14.sp,
                        modifier = Modifier.border(1.dp, ArchiveColors.label(l), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
                }
            }
        }
        Text(if (event.starred) "★" else "☆", color = if (event.starred) ArchiveColors.star else ArchiveColors.hint, fontSize = 28.sp,
            modifier = Modifier.size(48.dp).clickable { actions.star(event) }.padding(8.dp).testTag("archive_star_${event.file.startMs}"))
    }
}
