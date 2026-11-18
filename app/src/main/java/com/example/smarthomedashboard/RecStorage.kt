package com.example.smarthomedashboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Запись с камер: настройки, раскладка файлов и очистка (docs/recording.md).
 *
 * Настройки делятся на две части. Настройки камеры ([RecordingConfig]) лежат в `tile.config["recording"]` и
 * переносятся резервной копией вместе с плиткой. Настройки этого планшета ([RecordingDevice]: «записывать здесь»,
 * лимит, остаток, место) — в своём SharedPreferences, в резервную копию не попадают: восстановление копии на
 * другом планшете не должно включать там запись.
 */
internal data class RecordingConfig(
    val mode: Mode = Mode.CONTINUOUS_EVENTS,
    /** События, запускающие HD-запись (этап 2). */
    val eventTriggers: Set<String> = DEFAULT_TRIGGERS,
    val preSeconds: Int = 10,
    val postSeconds: Int = 20,
    val maxClipMinutes: Int = 5,
    val audioContinuous: Boolean = true,
    val audioEvents: Boolean = true,
    val keepContinuousDays: Int = 7,
    val keepEventDays: Int = 30,
    /** Голосом об остановке записи (нет места, карта пропала). */
    val voiceProblems: Boolean = true
) {
    enum class Mode(val key: String, val title: String, val continuous: Boolean, val events: Boolean) {
        OFF("off", "Выключено", false, false),
        EVENTS("events", "Только события", false, true),
        CONTINUOUS("continuous", "Круглосуточно", true, false),
        CONTINUOUS_EVENTS("continuous_events", "Круглосуточно + события в HD", true, true);
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CONTINUOUS_EVENTS }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("mode", mode.key)
        put("triggers", org.json.JSONArray(eventTriggers.sorted()))
        put("pre_seconds", preSeconds); put("post_seconds", postSeconds); put("max_clip_minutes", maxClipMinutes)
        put("audio_continuous", audioContinuous); put("audio_events", audioEvents)
        put("keep_continuous_days", keepContinuousDays); put("keep_event_days", keepEventDays)
        put("voice_problems", voiceProblems)
    }

    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    companion object {
        const val KEY = "recording"
        const val VERSION = 1
        val TRIGGERS = listOf("visitor" to "Звонок", "person" to "Человек", "vehicle" to "Машина", "animal" to "Животное", "motion" to "Движение")
        val DEFAULT_TRIGGERS = setOf("visitor", "person", "vehicle", "animal")

        fun from(config: String): RecordingConfig {
            val json = try { JSONObject(config).optJSONObject(KEY) } catch (_: Exception) { null } ?: return RecordingConfig()
            val d = RecordingConfig()
            fun int(key: String, default: Int, range: IntRange) = json.optInt(key, default).takeIf { it in range } ?: default
            val triggers = json.optJSONArray("triggers")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { k -> TRIGGERS.any { t -> t.first == k } } }.toSet()
            } ?: d.eventTriggers
            return RecordingConfig(
                mode = Mode.of(json.optString("mode")),
                eventTriggers = triggers,
                preSeconds = int("pre_seconds", d.preSeconds, 0..30),
                postSeconds = int("post_seconds", d.postSeconds, 5..120),
                maxClipMinutes = int("max_clip_minutes", d.maxClipMinutes, 1..30),
                audioContinuous = json.optBoolean("audio_continuous", d.audioContinuous),
                audioEvents = json.optBoolean("audio_events", d.audioEvents),
                keepContinuousDays = int("keep_continuous_days", d.keepContinuousDays, 1..90),
                keepEventDays = int("keep_event_days", d.keepEventDays, 1..365),
                voiceProblems = json.optBoolean("voice_problems", d.voiceProblems)
            )
        }
    }
}

/** Настройки записи этого планшета. */
internal class RecordingDevice(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(tileId: String) = tileId in prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()

    fun setEnabled(tileId: String, on: Boolean) {
        val set = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toMutableSet()
        if (on) set += tileId else set -= tileId
        prefs.edit().putStringSet(KEY_ENABLED, set).apply()
    }

    val enabledTiles: Set<String> get() = prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()

    /** Лимит записей, ГБ; 0 — авто (меньшее из 20 ГБ и половины доступного места). */
    var limitGb: Int
        get() = prefs.getInt(KEY_LIMIT, 0).coerceIn(0, 4096)
        set(v) { prefs.edit().putInt(KEY_LIMIT, v.coerceIn(0, 4096)).apply() }

    /** Неприкосновенный остаток свободного места, ГБ. */
    var reserveGb: Int
        get() = prefs.getInt(KEY_RESERVE, 2).coerceIn(1, 10)
        set(v) { prefs.edit().putInt(KEY_RESERVE, v.coerceIn(1, 10)).apply() }

    var useSdCard: Boolean
        get() = prefs.getBoolean(KEY_SD, false)
        set(v) { prefs.edit().putBoolean(KEY_SD, v).apply() }

    companion object {
        const val PREFS = "recording_device"
        private const val KEY_ENABLED = "enabled_tiles"
        private const val KEY_LIMIT = "limit_gb"
        private const val KEY_RESERVE = "reserve_gb"
        private const val KEY_SD = "use_sd"
    }
}

/** Файл записи. Имя — источник правды: `c_<начало UTC мс>_<длительность мс>.mp4`, идущий — `c_<начало>.part.mp4`. */
internal data class RecFile(
    val path: File,
    val camera: String,
    val kind: Kind,
    val startMs: Long,
    val durationMs: Long,
    val bytes: Long,
    val starred: Boolean = false,
    val open: Boolean = false
) {
    enum class Kind(val prefix: String) { CONTINUOUS("c"), EVENT("e") }
    val endMs get() = startMs + durationMs
}

internal object RecLayout {
    const val ROOT = "records"
    private val SAFE = Regex("[^A-Za-z0-9_-]")
    private val NAME = Regex("^([ce])_(\\d{10,15})(?:_(\\d{1,10}))?(_s)?(\\.part)?\\.mp4$")

    fun cameraKey(tileId: String) = tileId.replace(SAFE, "_").take(64).ifEmpty { "camera" }

    fun dayDir(root: File, camera: String, startMs: Long): File = File(File(File(root, ROOT), camera), day(startMs))

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun openName(kind: RecFile.Kind, startMs: Long) = "${kind.prefix}_$startMs.part.mp4"
    fun closedName(kind: RecFile.Kind, startMs: Long, durationMs: Long, starred: Boolean = false) =
        "${kind.prefix}_${startMs}_${durationMs.coerceAtLeast(0)}${if (starred) "_s" else ""}.mp4"

    fun parse(file: File, camera: String): RecFile? {
        val m = NAME.matchEntire(file.name) ?: return null
        val kind = if (m.groupValues[1] == "e") RecFile.Kind.EVENT else RecFile.Kind.CONTINUOUS
        val start = m.groupValues[2].toLongOrNull() ?: return null
        val open = m.groupValues[5].isNotEmpty()
        val duration = m.groupValues[3].toLongOrNull() ?: 0L
        return RecFile(file, camera, kind, start, duration, file.length(), m.groupValues[4].isNotEmpty(), open)
    }

    /** Все файлы записей под [root] (по всем камерам и дням). */
    fun scan(root: File): List<RecFile> {
        val base = File(root, ROOT)
        val out = mutableListOf<RecFile>()
        base.listFiles()?.forEach { camDir ->
            if (!camDir.isDirectory) return@forEach
            camDir.listFiles()?.forEach { dayDir ->
                if (!dayDir.isDirectory) return@forEach
                dayDir.listFiles()?.forEach { f -> parse(f, camDir.name)?.let { out += it } }
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Недописанные отрезки после падения: обрезать по последнему целому фрагменту и переименовать в закрытые.
     * Длительность — по времени последнего изменения файла. Непригодный файл удаляется.
     */
    fun recover(root: File, skip: Set<File> = emptySet()): Int {
        var fixed = 0
        for (f in scan(root)) {
            if (!f.open || f.path in skip) continue
            // Время последней записи — до обрезки: обрезка сама меняет время изменения файла.
            val modified = f.path.lastModified()
            val ok = try { Mp4Repair.truncateToLastFragment(f.path) > 0 } catch (_: Exception) { false }
            if (!ok) { f.path.delete(); continue }
            val duration = (modified - f.startMs).coerceIn(0, 24 * 3_600_000L)
            if (f.path.renameTo(File(f.path.parentFile, closedName(f.kind, f.startMs, duration)))) fixed++
        }
        return fixed
    }
}

/** Что удалить и можно ли писать дальше. Чистая логика: тестируется без файлов. */
internal object RecRetention {
    data class Rules(val keepContinuousMs: Long, val keepEventMs: Long)
    data class Plan(val delete: List<RecFile>, val pause: Boolean)

    const val GB = 1024L * 1024 * 1024
    const val AUTO_LIMIT = 20 * GB

    /** Лимит: заданный или авто — меньшее из 20 ГБ и половины (свободного + уже занятого записями). */
    fun limit(limitGb: Int, freeBytes: Long, usedBytes: Long): Long =
        if (limitGb > 0) limitGb * GB else minOf(AUTO_LIMIT, (freeBytes + usedBytes) / 2)

    /**
     * Порядок: 1) старше срока своей дорожки; 2) самые старые отрезки 24/7, пока не уложимся в лимит и остаток;
     * 3) самые старые события, кроме помеченных; 4) места всё ещё меньше остатка — пауза.
     * Открытые (идущие) файлы не трогаются, но учитываются в объёме.
     */
    fun plan(files: List<RecFile>, rules: (String) -> Rules, now: Long, freeBytes: Long, limitBytes: Long, reserveBytes: Long): Plan {
        val delete = LinkedHashSet<RecFile>()
        for (f in files) {
            if (f.open || f.starred) continue
            val r = rules(f.camera)
            val keep = if (f.kind == RecFile.Kind.CONTINUOUS) r.keepContinuousMs else r.keepEventMs
            if (f.endMs < now - keep) delete += f
        }
        var used = files.sumOf { it.bytes } - delete.sumOf { it.bytes }
        var free = freeBytes + delete.sumOf { it.bytes }
        fun over() = used > limitBytes || free < reserveBytes
        for (kind in listOf(RecFile.Kind.CONTINUOUS, RecFile.Kind.EVENT)) {
            if (!over()) break
            for (f in files.filter { it.kind == kind && !it.open && !it.starred && it !in delete }.sortedBy { it.startMs }) {
                if (!over()) break
                delete += f
                used -= f.bytes
                free += f.bytes
            }
        }
        return Plan(delete.toList(), free < reserveBytes)
    }
}
