package com.example.smarthomedashboard

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.annotation.VisibleForTesting
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
    val voiceProblems: Boolean = true,
    /**
     * Режим, когда камера не в сети этого планшета (видна через VPN/интернет): та же запись тратит трафик,
     * поэтому настраивается отдельно и по умолчанию выключена (решение владельца 18.11.2026).
     */
    val remoteMode: Mode = Mode.OFF
) {
    /** Что писать сейчас: камера в сети планшета — [mode], иначе — [remoteMode]. */
    fun effectiveMode(local: Boolean) = if (local) mode else remoteMode

    enum class Mode(val key: String, val title: String, val continuous: Boolean, val events: Boolean) {
        OFF("off", "Выключено", false, false),
        EVENTS("events", "Только события", false, true),
        CONTINUOUS("continuous", "Круглосуточно", true, false),
        CONTINUOUS_EVENTS("continuous_events", "Круглосуточно + события в HD", true, true);
        companion object { fun of(key: String?, default: Mode = CONTINUOUS_EVENTS) = entries.firstOrNull { it.key == key } ?: default }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("mode", mode.key)
        put("triggers", org.json.JSONArray(eventTriggers.sorted()))
        put("pre_seconds", preSeconds); put("post_seconds", postSeconds); put("max_clip_minutes", maxClipMinutes)
        put("audio_continuous", audioContinuous); put("audio_events", audioEvents)
        put("keep_continuous_days", keepContinuousDays); put("keep_event_days", keepEventDays)
        put("voice_problems", voiceProblems)
        put("remote_mode", remoteMode.key)
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
                voiceProblems = json.optBoolean("voice_problems", d.voiceProblems),
                remoteMode = Mode.of(json.optString("remote_mode"), d.remoteMode)
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

    /** PIN архива (ТЗ): тот же PIN, что у настроек. По умолчанию выключен. */
    var archivePin: ArchivePin
        get() = ArchivePin.of(prefs.getString(KEY_ARCHIVE_PIN, null))
        set(v) { prefs.edit().putString(KEY_ARCHIVE_PIN, v.key).apply() }

    enum class ArchivePin(val key: String, val title: String) {
        OFF("off", "Выключен"), DELETE("delete", "Только удаление"), ALL("all", "Весь архив");
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OFF }
    }

    companion object {
        const val PREFS = "recording_device"
        private const val KEY_ENABLED = "enabled_tiles"
        private const val KEY_LIMIT = "limit_gb"
        private const val KEY_RESERVE = "reserve_gb"
        private const val KEY_SD = "use_sd"
        private const val KEY_ARCHIVE_PIN = "archive_pin"
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
    private const val REMUX = ".remux"
    private val NAME = Regex("^([ce])_(\\d{10,15})(?:_(\\d{1,10}))?(_s)?(\\.part)?\\.mp4$")

    fun cameraKey(tileId: String) = tileId.replace(SAFE, "_").take(64).ifEmpty { "camera" }

    fun dayDir(root: File, camera: String, startMs: Long): File = File(File(File(root, ROOT), camera), day(startMs))

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun openName(kind: RecFile.Kind, startMs: Long) = "${kind.prefix}_$startMs.part.mp4"

    /**
     * Время начала нового файла, чтобы его имя не совпало с файлом, который ещё дописывается или закрывается в потоке
     * записи ([RecIo]): новый ролик события может начаться с того же ключевого кадра, что и прежний (ревью 24.11.2026).
     * Раньше поток камеры в этом случае ждал, пока прежний закроется. Сдвиг — на миллисекунды.
     */
    fun freeStart(dir: File, kind: RecFile.Kind, startMs: Long): Long {
        var start = startMs
        val busy = RecIo.busyFiles()
        while (File(dir, openName(kind, start)).path in busy) start++
        return start
    }

    fun closedName(kind: RecFile.Kind, startMs: Long, durationMs: Long, starred: Boolean = false) =
        "${kind.prefix}_${startMs}_${durationMs.coerceAtLeast(0)}${if (starred) "_s" else ""}.mp4"

    /** [sized] = false — без обращения к файлу (только имя): тысячи обращений на SD-карте — секунды; размер тогда 0. */
    fun parse(file: File, camera: String, sized: Boolean = true): RecFile? {
        val m = NAME.matchEntire(file.name) ?: return null
        val kind = if (m.groupValues[1] == "e") RecFile.Kind.EVENT else RecFile.Kind.CONTINUOUS
        val start = m.groupValues[2].toLongOrNull() ?: return null
        val open = m.groupValues[5].isNotEmpty()
        val duration = m.groupValues[3].toLongOrNull() ?: 0L
        return RecFile(file, camera, kind, start, duration, if (sized) file.length() else 0L, m.groupValues[4].isNotEmpty(), open)
    }

    /**
     * Закрыть идущий файл: дописать, обрезать по последнему целому фрагменту и переименовать в закрытое имя. У записи с
     * камеры обрезка и переименование — когда файл дописан на носитель, в потоке записи [RecIo]: поток камеры их не
     * ждёт. [done] — там же; у тестов и вырезки (файл пишется сразу) — сразу. [contentMs] — от первого до последнего
     * кадра; последний кадр длится примерно 1/25 с. Файл без единого фрагмента (обрыв сразу после ключевого кадра)
     * удаляется — [done] не вызывается.
     */
    fun finishLater(writer: Mp4SegmentWriter, file: File?, camera: String, kind: RecFile.Kind, startMs: Long, contentMs: Long,
                    done: (RecFile) -> Unit) {
        writer.closeThen { closed(file, camera, kind, startMs, contentMs)?.let(done) }
    }

    /** Закрытый файл: обрезать по последнему целому фрагменту и переименовать в закрытое имя. */
    private fun closed(file: File?, camera: String, kind: RecFile.Kind, startMs: Long, contentMs: Long): RecFile? {
        if (file == null) return null
        val ok = try { Mp4Repair.truncateToLastFragment(file) > 0 } catch (_: Exception) { false }
        if (!ok) { file.delete(); return null }
        val duration = contentMs.coerceAtLeast(0) + 40
        val done = File(file.parentFile, closedName(kind, startMs, duration))
        if (!file.renameTo(done)) return null
        // Оглавление для перемотки в архиве — в фоне, запись следующего отрезка не ждёт.
        Mp4Finalize.enqueue(done)
        return RecFile(done, camera, kind, startMs, duration, done.length())
    }

    /** Все файлы записей под [root] (по всем камерам и дням). */
    fun scan(root: File, sized: Boolean = true): List<RecFile> {
        val base = File(root, ROOT)
        val out = mutableListOf<RecFile>()
        base.listFiles()?.forEach { camDir ->
            if (!camDir.isDirectory) return@forEach
            camDir.listFiles()?.forEach { dayDir ->
                if (!dayDir.isDirectory) return@forEach
                dayDir.listFiles()?.forEach { f -> parse(f, camDir.name, sized)?.let { out += it } }
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** Папки дней камеры под [root] (имя — день UTC), без обращения к файлам. */
    fun dayDirs(root: File, camera: String): List<File> =
        File(File(root, ROOT), camera).listFiles()?.filter { DAY.matches(it.name) }.orEmpty()
    private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Записи папки дня — по одним именам. */
    fun names(dayDir: File, camera: String): List<RecFile> = dayDir.listFiles()?.mapNotNull { parse(it, camera, sized = false) }.orEmpty()

    /**
     * Недописанные отрезки после падения: обрезать по последнему целому фрагменту и переименовать в закрытые.
     * Длительность — по времени последнего изменения файла. Непригодный файл удаляется.
     */
    fun recover(root: File, skip: Set<String> = emptySet()): Int {
        // Процесс убит во время перекладки в обычный MP4 (Mp4Finalize): временный файл очистке не виден и занимал
        // место навсегда (аудит 20.11.2026). Сам отрезок цел — перекладка повторится при открытии архива.
        File(root, ROOT).listFiles()?.forEach { cam -> cam.listFiles()?.forEach { day ->
            day.listFiles { f -> f.name.endsWith(REMUX) }?.forEach { tmp ->
                if (!Mp4Finalize.pending(File(tmp.parentFile, tmp.name.removeSuffix(REMUX)))) tmp.delete()
            }
        } }
        var fixed = 0
        for (f in scan(root, sized = false)) {
            if (!f.open || f.path.path in skip) continue
            // Время последней записи — до обрезки: обрезка сама меняет время изменения файла.
            val modified = f.path.lastModified()
            val ok = try { Mp4Repair.truncateToLastFragment(f.path) > 0 } catch (_: Exception) { false }
            if (!ok) { f.path.delete(); continue }
            val duration = (modified - f.startMs).coerceIn(0, 24 * 3_600_000L)
            val done = File(f.path.parentFile, closedName(f.kind, f.startMs, duration))
            if (f.path.renameTo(done)) { fixed++; Mp4Finalize.enqueue(done) }
        }
        return fixed
    }
}

/**
 * Камера в сети этого планшета или видна издалека (VPN, интернет). Локальная — адрес в подсети одного из
 * обычных сетевых интерфейсов планшета (Wi-Fi, Ethernet). Туннели VPN на самом планшете (tun, ppp, wg, ipsec)
 * локальными не считаются: через них камера — издалека.
 */
internal object RecLocality {
    data class Iface(val name: String, val address: java.net.InetAddress, val prefix: Int)

    /**
     * null — решить нельзя: у планшета сейчас нет обычного интерфейса того же семейства адресов (Wi-Fi ещё не
     * поднялся, только VPN). Раньше это считалось «издалека» — запись локальной камеры останавливалась до
     * следующей сверки (аудит 20.11.2026, A5).
     */
    fun isLocal(camera: java.net.InetAddress, interfaces: List<Iface>): Boolean? {
        if (camera.isLoopbackAddress) return true
        val ordinary = interfaces.filter { i ->
            TUNNELS.none { i.name.startsWith(it) } && !i.address.isLoopbackAddress && i.address.javaClass == camera.javaClass
        }
        if (ordinary.isEmpty()) return null
        return ordinary.any { sameSubnet(camera, it.address, it.prefix) }
    }

    /**
     * Последнее известное расположение камеры по адресу потока: сбой DNS или сети на минуту не переключает режим
     * записи (не останавливает запись локальной камеры и не включает запись удалённой). null — ещё ни разу не
     * определялось.
     */
    class Memory {
        private val last = HashMap<String, Boolean>()
        fun resolve(url: String, now: Boolean?): Boolean? {
            if (now != null) { last[url] = now; return now }
            return last[url]
        }
    }

    fun sameSubnet(a: java.net.InetAddress, b: java.net.InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        val bits = prefix.coerceAtMost(x.size * 8)
        for (i in 0 until bits) {
            val mask = 0x80 ushr (i % 8)
            if ((x[i / 8].toInt() and mask) != (y[i / 8].toInt() and mask)) return false
        }
        return true
    }

    /** По rtsp-адресу; null — адрес непонятен или имя не разрешилось. Блокирующее — не с главного потока. */
    fun isLocal(rtspUrl: String): Boolean? {
        val host = try { java.net.URI(rtspUrl.trim()).host } catch (_: Exception) { null } ?: return null
        val address = try { java.net.InetAddress.getByName(host) } catch (_: Exception) { return null }
        return isLocal(address, interfaces())
    }

    fun interfaces(): List<Iface> = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.flatMap { ni ->
            ni.interfaceAddresses.mapNotNull { a -> a.address?.let { Iface(ni.name, it, a.networkPrefixLength.toInt()) } }
        }
    } catch (_: Exception) { emptyList() }

    private val TUNNELS = listOf("tun", "ppp", "wg", "ipsec", "utun", "tap")
}

/**
 * Можно ли сейчас удалять записи по сроку (аудит 25.11.2026, REC-03). Часы планшета прыгнули (сбой, неверное время при включении) —
 * «старше недели» по ним ничего не значит: сутки по сроку не удаляется ничего, только по месту. То же, если при запуске последняя
 * запись старше двух суток: либо планшет долго был выключен, либо часы врут, — сутки подождать не жалко. Чистая логика.
 */
internal class ClockGuard {
    private var offsetMs: Long? = null
    private var trustedFromMs = 0L

    /** [wallMs] — часы, [elapsedMs] — время с включения планшета (не прыгает), [newestMs] — конец последней записи. */
    fun trusted(wallMs: Long, elapsedMs: Long, newestMs: Long?): Boolean {
        val offset = wallMs - elapsedMs
        val before = offsetMs
        offsetMs = offset
        val suspicious = if (before == null) newestMs != null && wallMs - newestMs > GAP_MS else kotlin.math.abs(offset - before) > JUMP_MS
        if (suspicious) trustedFromMs = elapsedMs + SETTLE_MS
        return elapsedMs >= trustedFromMs
    }

    companion object {
        const val JUMP_MS = 3_600_000L
        const val GAP_MS = 2 * 86_400_000L
        const val SETTLE_MS = 86_400_000L
    }
}

/** Что удалить и можно ли писать дальше. Чистая логика: тестируется без файлов. */
internal object RecRetention {
    data class Rules(val keepContinuousMs: Long, val keepEventMs: Long)
    data class Plan(val delete: List<RecFile>, val pause: Boolean)

    const val GB = 1024L * 1024 * 1024

    const val AUTO_LIMIT = 20 * GB

    /**
     * Лимит: заданный или авто — меньшее из 20 ГБ и половины (свободного + уже занятого записями). [fallback] — выбрана
     * SD-карта, а запись идёт в память планшета: лимит вдвое меньше (решение владельца 18.11).
     */
    fun limit(limitGb: Int, freeBytes: Long, usedBytes: Long, fallback: Boolean = false): Long =
        (if (limitGb > 0) limitGb * GB else minOf(AUTO_LIMIT, (freeBytes + usedBytes) / 2)) / (if (fallback) 2 else 1)

    /**
     * Порядок: 1) старше срока своей дорожки (24/7 и события — каждая по своему); 2) самые старые по времени —
     * круглосуточная запись и события вместе, кроме помеченных ★, — пока не уложимся в лимит и остаток (решение
     * владельца 22.11.2026: раньше сначала уходила вся круглосуточная запись, а события держались до своего срока, и
     * при частых событиях круглосуточной записи оставалось всё меньше); 3) места всё ещё меньше остатка — пауза.
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
        // Одного времени начала у отрезка 24/7 и ролика события — сначала отрезок (ролик подробнее).
        for (f in files.filter { !it.open && !it.starred && it !in delete }.sortedWith(compareBy({ it.startMs }, { it.kind != RecFile.Kind.CONTINUOUS }))) {
            if (!over()) break
            delete += f
            used -= f.bytes
            free += f.bytes
        }
        return Plan(delete.toList(), free < reserveBytes)
    }

    private const val DAY_MS = 86_400_000L

    /**
     * Место под записи — одна оценка для редактора камеры и архива (ревью 22.11.2026, docs/review-2026-11-22.md,
     * пункт 5). Лимит общий для всех камер и для роликов событий, поэтому и объём в сутки — всех записей носителя, куда
     * идёт запись, а не одной камеры и не одной круглосуточной.
     */
    data class Outlook(
        val usedBytes: Long,
        val limitBytes: Long,
        /** Место заполнено (лимит или неприкосновенный остаток): старое уже удаляется по месту. */
        val full: Boolean,
        /** Заполнено не лимитом, а самим носителем: свободно меньше неприкосновенного остатка. */
        val lowFree: Boolean,
        /** Начало самой старой круглосуточной записи (★ не в счёт); null — её нет. */
        val continuousSinceMs: Long?,
        /** Пишется в сутки всего (24/7 и события) и из них событий; null — записано меньше получаса. */
        val bytesPerDay: Long?,
        val eventBytesPerDay: Long?,
        /** Записано меньше суток: события за день неравномерны — оценка грубая. */
        val preliminary: Boolean,
        /** Лимит без роликов со ★ — они не удаляются и место под остальное уменьшают. */
        val rotatingLimitBytes: Long,
        val now: Long
    ) {
        /** Сколько дней хранится круглосуточная запись: место заполнено — по факту, иначе — прогноз при нынешнем объёме. */
        val days: Double? get() = if (full) continuousSinceMs?.let { (now - it).toDouble() / DAY_MS }
            else bytesPerDay?.takeIf { it > 0 }?.let { rotatingLimitBytes.toDouble() / it }
    }

    /**
     * [files] — записи носителя, куда идёт запись. Объём в сутки — по последней неделе (или по всей истории, если она
     * короче): так в него входят и дни с событиями, и дни без них.
     */
    fun outlook(files: List<RecFile>, limitBytes: Long, freeBytes: Long, reserveBytes: Long, now: Long): Outlook {
        val used = files.sumOf { it.bytes }
        val rotating = files.filter { !it.starred }
        // Начало окна — самая старая запись за последнюю неделю (забытый старый файл окно не растягивает).
        val from = rotating.filter { it.startMs >= now - 7 * DAY_MS }.minOfOrNull { it.startMs } ?: now
        val window = now - from
        fun perDay(kind: RecFile.Kind?): Long? = if (window < 30 * 60_000L) null
            else rotating.filter { it.startMs >= from && (kind == null || it.kind == kind) }.sumOf { it.bytes } * DAY_MS / window
        return Outlook(
            usedBytes = used,
            limitBytes = limitBytes,
            full = used >= limitBytes / 100 * 95 || freeBytes < reserveBytes,
            lowFree = freeBytes < reserveBytes,
            continuousSinceMs = rotating.filter { it.kind == RecFile.Kind.CONTINUOUS }.minOfOrNull { it.startMs },
            bytesPerDay = perDay(null),
            eventBytesPerDay = perDay(RecFile.Kind.EVENT),
            preliminary = window < DAY_MS,
            rotatingLimitBytes = (limitBytes - files.filter { it.starred }.sumOf { it.bytes }).coerceAtLeast(0),
            now = now
        )
    }

    private val RU = Locale("ru")

    /** «Записи: 3,2 из 20 ГБ» (единица один раз, если одна и та же). */
    fun usedOfLimit(o: Outlook): String {
        val used = size(o.usedBytes)
        val limit = size(o.limitBytes)
        return if (used.substringAfter(' ') == limit.substringAfter(' ')) "${used.substringBefore(' ')} из $limit" else "$used из $limit"
    }

    /** «20 ГБ», «3,2 ГБ», «512 МБ». */
    fun size(bytes: Long): String = if (bytes >= GB) String.format(RU, "%.1f", bytes / GB.toDouble()).removeSuffix(",0") + " ГБ"
        else "${bytes / (1024 * 1024)} МБ"

    /** «5 ч», «2,8 дн.», «85 дн.». */
    fun daysText(days: Double): String = when {
        days < 1 -> "${(days * 24).toInt().coerceAtLeast(1)} ч"
        days < 10 -> String.format(RU, "%.1f", days).removeSuffix(",0") + " дн."
        else -> "${days.toInt()} дн."
    }

    /**
     * Оценка словами — одна для редактора камеры и архива: место заполнено — сколько круглосуточной записи есть на деле,
     * иначе — на сколько хватит места при нынешнем объёме; и сколько пишется в сутки, из них событий. Строка архива
     * узкая (на Huawei 1000×600 dp — три строки текста), поэтому коротко. [keepDays] — срок хранения 24/7 камеры.
     */
    private fun outlookBody(o: Outlook, keepDays: Int): String {
        val perDay = o.bytesPerDay
        val rate = perDay?.let { p ->
            val events = o.eventBytesPerDay?.takeIf { it > 0 }?.let { "из них события — ${size(it)}" } ?: "событий нет"
            "В сутки ≈ ${size(p)}, $events"
        }
        val days = o.days
        val short = if (days != null && days < keepDays) " — меньше срока $keepDays дн." else ""
        return if (o.full) listOfNotNull(
            o.continuousSinceMs?.let { "Круглосуточная запись есть за ${daysText(days ?: 0.0)}$short".withPeriod() },
            rate?.let { "$it." }
        ).joinToString(" ")
        else if (rate == null || days == null) "Оценка появится после получаса записи."
        else "$rate: места хватит примерно на ${daysText(days)}$short".withPeriod() +
            if (o.preliminary) " Оценка грубая: записано меньше суток." else ""
    }

    private fun String.withPeriod() = if (endsWith(".")) this else "$this."

    /** Для редактора камеры: «Место заполнено. Круглосуточная запись есть за 2,8 дн. …». */
    fun outlookText(o: Outlook, keepDays: Int): String {
        val state = when { o.lowFree -> "Свободного места на планшете мало, старое удаляется."; o.full -> "Место заполнено."; else -> "" }
        return listOf(state, outlookBody(o, keepDays)).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Для строки внизу архива: «Записи: 20 из 20 ГБ, заполнено. Круглосуточная запись есть за 2,8 дн. …». */
    fun archiveLine(o: Outlook, keepDays: Int): String {
        val state = when { o.lowFree -> ", мало свободного места"; o.full -> ", заполнено"; else -> "" }
        return "Записи: ${usedOfLimit(o)}$state. ${outlookBody(o, keepDays)}".trim()
    }
}

/**
 * Носители записей: память планшета и SD-карта. Куда идёт запись — одно правило для службы записи и экранов, чтобы
 * оценка места считалась по тому же носителю и лимиту, по которым удаляет очистка.
 */
internal object RecVolumes {
    /** Для тестов: папки приложения на носителях вместо `getExternalFilesDirs` (память планшета и SD-карта). */
    @VisibleForTesting @Volatile var dirsOverride: ((Context) -> Array<File?>)? = null

    /** Куда идёт запись; [fallback] — выбрана SD-карта, а её нет: пишем в память планшета. */
    class Target(val root: File?, val fallback: Boolean)

    /** Папка SD-карты, которая перестала писать (до её извлечения и возврата или перезапуска приложения); null — всё в порядке. */
    @Volatile var brokenCard: String? = null

    /** Папки приложения на носителях: [0] — память планшета, [1] — SD-карта (null или нет — карты нет). */
    fun dirs(context: Context): Array<File?> =
        try { (dirsOverride ?: { c -> c.getExternalFilesDirs(null) })(context) } catch (_: Exception) { emptyArray() }

    fun mounted(dir: File?): Boolean = dir != null && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED

    /** SD-карта, если выбрана, вставлена и пишет, иначе память планшета. */
    fun target(context: Context, useSd: Boolean): Target {
        val dirs = dirs(context)
        val sd = dirs.getOrNull(1)
        if (useSd && mounted(sd) && sd?.path != brokenCard) return Target(sd, fallback = false)
        return Target(dirs.getOrNull(0) ?: context.filesDir, fallback = useSd)
    }

    fun freeBytes(root: File): Long = try { StatFs(root.path).availableBytes } catch (_: Exception) { 0L }

    /** Записи и свободное место носителя, куда идёт запись (обход папок — не на главном потоке). */
    class Snapshot(val files: List<RecFile>, val freeBytes: Long, val fallback: Boolean)

    fun snapshot(context: Context, useSd: Boolean): Snapshot {
        val target = target(context, useSd)
        val root = target.root ?: return Snapshot(emptyList(), 0, target.fallback)
        return Snapshot(RecCatalog.files(root) ?: RecLayout.scan(root), freeBytes(root), target.fallback)
    }

    /** Оценка места при лимите [limitGb] и остатке [reserveGb] (настройки этого планшета или ещё не сохранённые). */
    fun outlook(s: Snapshot, limitGb: Int, reserveGb: Int, now: Long = System.currentTimeMillis()): RecRetention.Outlook {
        val limit = RecRetention.limit(limitGb, s.freeBytes, s.files.sumOf { it.bytes }, s.fallback)
        return RecRetention.outlook(s.files, limit, s.freeBytes, reserveGb * RecRetention.GB, now)
    }
}
