package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.SurfaceView
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Архив записей камеры (этап 3, docs/recording.md) — первый экран на Compose. Лента событий (новые первыми,
 * фильтр по типу и дню), шкала суток с непрерывной записью и событиями, плеер по локальным файлам (1×/2×/4×,
 * ±10 с, кадр вперёд на паузе), «Сохранить» (звёздочка), «Удалить» (по настройке PIN архива), «Поделиться».
 * Открывается из раскрытой плитки домофона и из окна звонка (сразу последний ролик).
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
    private var day: ArchiveDay? = null
    private var snapshot = RecArchive.Snapshot(emptyList(), emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileId = intent.getStringExtra(EXTRA_TILE).orEmpty()
        val tile = TileManager(this).loadTiles().firstOrNull { it.id == tileId }
        title = tile?.title?.ifBlank { null } ?: DoorbellConfig.DEFAULT_TITLE
        index = try { RecIndex(this) } catch (_: Exception) { null }
        archive = RecArchive(this, index = { index })
        ui.value = ui.value.copy(title = title)
        // HD-ролики событий (Reolink 2560×1920) аппаратный декодер Samsung SM-X216B не принимает — следующий декодер
        // (программный) берётся сам, как у живого видео домофона.
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { ui.value = ui.value.copy(playing = isPlaying) }
            })
        }
        setContent {
            ArchiveScreen(ui.value, thumbs, actions, video = { VideoSurface(player) })
        }
        // PIN архива «весь архив»: без PIN экран закрывается (тот же PIN, что у настроек).
        if (RecordingDevice(this).archivePin == RecordingDevice.ArchivePin.ALL) {
            ui.value = ui.value.copy(locked = true)
            PinSecurityUi.authorize(this, { ui.value = ui.value.copy(locked = false); reload(latest = true) }, { finish() })
        } else {
            reload(latest = intent.getBooleanExtra(EXTRA_LATEST, false))
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        index?.close()
        super.onDestroy()
    }

    /** Прочитать диск; [latest] — сразу открыть последний ролик (из окна звонка). */
    private fun reload(latest: Boolean = false, keepDay: Long? = ui.value.day) {
        ui.value = ui.value.copy(loading = true)
        lifecycleScope.launch {
            val camera = RecLayout.cameraKey(tileId)
            snapshot = withContext(Dispatchers.IO) { archive.load(camera) }
            val days = snapshot.days
            val selected = keepDay?.takeIf { it in days } ?: days.firstOrNull()
            selectDay(selected, days)
            if (latest) snapshot.events.firstOrNull()?.let { play(it) }
            // Ролики, записанные до появления оглавления (или если оно не успело записаться), — в очередь: после
            // этого в них работает перемотка. Сначала события (новые первыми), затем 24/7 выбранного дня.
            val files = snapshot.events.map { it.file } + snapshot.continuous.filter { RecArchive.dayStart(it.startMs) == selected }.reversed()
            withContext(Dispatchers.IO) { files.forEach { f -> if (Mp4Finalize.isFragmented(f.path)) Mp4Finalize.enqueue(f.path) } }
        }
    }

    private fun selectDay(selected: Long?, days: List<Long> = ui.value.days) {
        day = selected?.let { ArchiveDay(it, snapshot.continuous, snapshot.events) }
        ui.value = ui.value.copy(loading = false, days = days, day = selected, timeline = day?.let(::timelineOf),
            events = filtered(selected, ui.value.filter))
    }

    private fun filtered(dayStart: Long?, filter: String?) = snapshot.events.filter { e ->
        (dayStart == null || RecArchive.dayStart(e.file.startMs) == dayStart) && (filter == null || filter in e.labels)
    }

    private fun timelineOf(d: ArchiveDay) = ArchiveUi.Timeline(
        coverage = d.coverage.map { d.fraction(it.first) to d.fraction(it.last + 1) },
        events = d.events.map { d.fraction(it.file.startMs) to (it.labels.firstOrNull() ?: "") }
    )

    // ---- Плеер ----

    private fun play(event: RecArchive.Event) {
        val p = player ?: return
        p.setSeekParameters(SeekParameters.EXACT)
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(event.file.path)))
        p.prepare()
        p.play()
        loadedDay = null
        ui.value = ui.value.copy(current = event, continuous = false)
        tick()
    }

    /** День, чей плейлист 24/7 сейчас загружен в плеер: переход по шкале внутри него — простой seek, без пересборки. */
    private var loadedDay: ArchiveDay? = null

    private fun playContinuous(wallMs: Long, play: Boolean = true) {
        val d = day ?: return
        val p = player ?: return
        val (segment, offset) = d.locate(wallMs) ?: run { toast("За этот день круглосуточной записи нет"); return }
        if (ui.value.continuous && loadedDay === d) {
            p.seekTo(segment, offset)
        } else {
            p.setMediaItems(d.segments.map { MediaItem.fromUri(android.net.Uri.fromFile(it.path)) }, segment, offset)
            p.prepare()
            loadedDay = d
            if (play) p.play()
        }
        ui.value = ui.value.copy(current = null, continuous = true)
        tick()
    }

    /** Перемотка пальцем: пока ведут — быстрые переходы к ключевым кадрам, отпустили — точно в выбранный момент. */
    private fun scrubContinuous(fraction: Float, done: Boolean) {
        val d = day ?: return
        val p = player ?: return
        p.setSeekParameters(if (done) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        playContinuous(d.timeAt(fraction), play = done)
        if (done) p.play()
    }

    /** Длительность текущего ролика: из плеера, а если файл ещё без оглавления — из имени файла. */
    private fun knownDuration(): Long = player?.duration?.takeIf { it > 0 } ?: ui.value.current?.file?.durationMs ?: 0L

    private fun scrubClip(fraction: Float, done: Boolean) {
        val p = player ?: return
        val duration = knownDuration().takeIf { it > 0 } ?: return
        p.setSeekParameters(if (done) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Текущее время на стене: для курсора шкалы и подписи. */
    private fun wallNow(): Long? {
        val p = player ?: return null
        return if (ui.value.continuous) day?.wallTime(p.currentMediaItemIndex, p.currentPosition)
        else ui.value.current?.let { it.file.startMs + p.currentPosition }
    }

    private var ticking = false
    private fun tick() {
        if (ticking) return
        ticking = true
        lifecycleScope.launch {
            while (player != null) {
                val wall = wallNow()
                val d = day
                val p = player
                ui.value = ui.value.copy(positionWall = wall, cursor = if (wall != null && d != null) d.fraction(wall) else null,
                    positionMs = p?.currentPosition ?: 0L, durationMs = knownDuration())
                delay(250)
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (ui.value.continuous) {
            wallNow()?.let { now -> day?.locate(now + deltaMs)?.let { (i, o) -> p.seekTo(i, o) } }
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

    // ---- Действия с роликом ----

    private fun star(event: RecArchive.Event) {
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { archive.setStarred(event.file, !event.starred) }
            if (updated == null) { toast("Не удалось изменить отметку"); return@launch }
            replace(event, RecArchive.Event(updated, event.labels))
        }
    }

    private fun replace(old: RecArchive.Event, new: RecArchive.Event?) {
        snapshot = snapshot.copy(events = snapshot.events.mapNotNull { if (it.file.startMs == old.file.startMs) new else it })
        val current = ui.value.current?.takeIf { it.file.startMs == old.file.startMs }?.let { new } ?: ui.value.current
        ui.value = ui.value.copy(current = if (new == null && ui.value.current?.file?.startMs == old.file.startMs) null else current)
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
        }
    }

    private fun share(event: RecArchive.Event) {
        lifecycleScope.launch {
            val copy = try { withContext(Dispatchers.IO) { archive.shareCopy(event.file, title) } } catch (_: Exception) {
                toast("Не удалось подготовить файл"); return@launch
            }
            val uri = FileProvider.getUriForFile(this@ArchiveActivity, "$packageName.files", copy)
            val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(Intent.createChooser(send, "Поделиться роликом")) } catch (_: Exception) { toast("Некуда отправить") }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val actions = object : ArchiveActions {
        override fun back() = finish()
        override fun selectDay(day: Long) = this@ArchiveActivity.selectDay(day)
        override fun filter(label: String?) {
            ui.value = ui.value.copy(filter = label, events = filtered(ui.value.day, label))
        }
        override fun play(event: RecArchive.Event) = this@ArchiveActivity.play(event)
        override fun seekTimeline(fraction: Float, eventTolerance: Float) {
            val d = day ?: return
            val wall = d.timeAt(fraction)
            // Касание прямо по засечке события (в пределах пальца) — ролик в HD; иначе — тот же момент 24/7.
            val near = d.events.minByOrNull { kotlin.math.abs(d.fraction(it.file.startMs) - fraction) }
                ?.takeIf { kotlin.math.abs(d.fraction(it.file.startMs) - fraction) <= eventTolerance }
            player?.setSeekParameters(SeekParameters.EXACT)
            if (near != null) play(near) else playContinuous(wall)
        }
        override fun scrubTimeline(fraction: Float, done: Boolean) = scrubContinuous(fraction, done)
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
    }

    companion object {
        const val EXTRA_TILE = "tile_id"
        const val EXTRA_LATEST = "latest"
        private const val FRAME_MS = 40L

        fun open(context: Context, tileId: String, latest: Boolean = false) {
            context.startActivity(Intent(context, ArchiveActivity::class.java)
                .putExtra(EXTRA_TILE, tileId).putExtra(EXTRA_LATEST, latest))
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
    val current: RecArchive.Event? = null,
    val continuous: Boolean = false,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val positionWall: Long? = null,
    val cursor: Float? = null,
    /** Позиция и длительность текущего ролика (для полосы перемотки события). */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val confirmDelete: RecArchive.Event? = null
) {
    /** Участки записи и засечки событий в долях суток. */
    data class Timeline(val coverage: List<Pair<Float, Float>>, val events: List<Pair<Float, String>>)
}

internal interface ArchiveActions {
    fun back()
    fun selectDay(day: Long)
    fun filter(label: String?)
    fun play(event: RecArchive.Event)
    /** Касание шкалы; засечка события ближе [eventTolerance] (доля ширины ≈ палец) — ролик события. */
    fun seekTimeline(fraction: Float, eventTolerance: Float)
    /** Перемотка пальцем по шкале (только 24/7); [done] — палец отпущен. */
    fun scrubTimeline(fraction: Float, done: Boolean)
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
                    Column(Modifier.weight(1.6f).fillMaxSize()) { PlayerPane(ui, actions, video) }
                    Column(Modifier.weight(1f).fillMaxSize()) { ListPane(ui, thumbs, actions) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PlayerPane(ui, actions, video)
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
    }
}

@Composable
private fun PlayerPane(ui: ArchiveUi, actions: ArchiveActions, video: @Composable () -> Unit) {
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
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
        .border(1.dp, ArchiveColors.border, RoundedCornerShape(16.dp))) {
        if (ui.current != null || ui.continuous) video()
        else Text(if (ui.loading) "Загрузка…" else "Выберите событие или место на шкале", color = ArchiveColors.hint,
            fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
    }
    if (ui.current != null) ClipBar(ui, actions)
    Spacer(Modifier.height(8.dp))
    Controls(ui, actions)
    Spacer(Modifier.height(8.dp))
    Timeline(ui, actions)
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

@Composable
private fun Controls(ui: ArchiveUi, actions: ArchiveActions) {
    val active = ui.current != null || ui.continuous
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ControlButton(if (ui.playing) "❚❚ Пауза" else "▶ Пуск", active, "archive_play") { actions.togglePlay() }
        ControlButton("−10 с", active, "archive_back10") { actions.seekBy(-10_000) }
        ControlButton("+10 с", active, "archive_fwd10") { actions.seekBy(10_000) }
        ControlButton("Кадр ›", active && !ui.playing, "archive_frame") { actions.frameStep() }
        listOf(1f, 2f, 4f).forEach { s ->
            FilterChip(selected = ui.speed == s, onClick = { actions.speed(s) }, enabled = active,
                label = { Text("${s.toInt()}×", fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("archive_speed_${s.toInt()}"))
        }
    }
    ui.current?.let { event ->
        Spacer(Modifier.height(8.dp))
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

/** Шкала суток: участки круглосуточной записи, засечки событий, курсор; касание/перетаскивание — переход. */
@Composable
private fun Timeline(ui: ArchiveUi, actions: ArchiveActions) {
    val timeline = ui.timeline
    var drag by remember { mutableStateOf<Float?>(null) }
    Column {
        // Время под пальцем, пока ведут по шкале.
        val day = ui.day
        Text(drag?.let { f -> day?.let { "→ " + timeFormat.format(Date(it + (f * 86_400_000L).toLong())) } } ?: " ",
            color = ArchiveColors.text, fontSize = 16.sp, modifier = Modifier.testTag("archive_scrub_time"))
        Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(ArchiveColors.surface)
            .testTag("archive_timeline")
            .semantics { contentDescription = "Шкала суток" }
            .pointerInput(timeline) {
                // Касание — переход (по засечке — ролик события); ведение пальцем — перемотка 24/7 по ходу движения.
                val tolerance = 16.dp.toPx() / size.width
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startX = down.position.x
                    var dragging = false
                    var lastSeek = 0L
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!dragging && kotlin.math.abs(change.position.x - startX) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            drag = f
                            if (change.uptimeMillis - lastSeek > SCRUB_EVERY_MS) { lastSeek = change.uptimeMillis; actions.scrubTimeline(f, false) }
                            change.consume()
                        }
                    }
                    val end = drag
                    drag = null
                    if (dragging && end != null) actions.scrubTimeline(end, true)
                    else actions.seekTimeline((startX / size.width).coerceIn(0f, 1f), tolerance)
                }
            }) {
            val w = size.width
            val h = size.height
            // Пропуски — фон (серый); запись — полоса акцента.
            timeline?.coverage?.forEach { (a, b) ->
                drawRect(ArchiveColors.accentSoft, Offset(a * w, h * 0.25f), Size(((b - a) * w).coerceAtLeast(1f), h * 0.5f))
            }
            timeline?.events?.forEach { (at, label) ->
                drawRect(ArchiveColors.label(label), Offset(at * w - 1.5f, 0f), Size(3f, h))
            }
            for (hour in 1 until 24) {
                val x = hour / 24f * w
                drawLine(ArchiveColors.border, Offset(x, h * 0.85f), Offset(x, h), strokeWidth = if (hour % 6 == 0) 2f else 1f)
            }
            (drag ?: ui.cursor)?.let { c -> drawRect(Color.White, Offset(c * w - 1f, 0f), Size(2f, h)) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
                Text(t, color = ArchiveColors.hint, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    textAlign = if (i == 4) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
            }
        }
        if (timeline != null && timeline.coverage.isEmpty())
            Text("Круглосуточной записи за этот день нет", color = ArchiveColors.hint, fontSize = 14.sp)
    }
}

@Composable
private fun ListPane(ui: ArchiveUi, thumbs: Map<String, Bitmap?>, actions: ArchiveActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.days) { d ->
            FilterChip(selected = ui.day == d, onClick = { actions.selectDay(d) },
                label = { Text(dayTitle(d), fontSize = 16.sp) }, modifier = Modifier.testTag("archive_day_$d"),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArchiveColors.accentSoft))
        }
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
        Text(if (ui.days.isEmpty()) "Записей пока нет" else "Событий за этот день нет", color = ArchiveColors.hint, fontSize = 18.sp,
            modifier = Modifier.padding(top = 24.dp).testTag("archive_empty"))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("archive_list")) {
        items(ui.events, key = { it.file.startMs }) { event -> EventRow(event, ui.current?.file?.startMs == event.file.startMs, thumbs, actions) }
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

/** Видео: SurfaceView плеера (аппаратный вывод, как у живого видео домофона). */
@Composable
private fun VideoSurface(player: ExoPlayer?) {
    AndroidView(factory = { context -> SurfaceView(context).also { player?.setVideoSurfaceView(it) } },
        modifier = Modifier.fillMaxSize(),
        onRelease = { player?.clearVideoSurfaceView(it) })
}
