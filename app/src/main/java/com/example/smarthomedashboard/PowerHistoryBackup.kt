package com.example.smarthomedashboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Отдельный бэкап истории мощности (docs/power-graph.md), независимый от копий настроек: своё имя файла,
 * своё расписание (~04:00), свой статус. Место — та же выбранная владельцем папка (SAF), иначе внутренняя.
 *
 * Формат — сжатый построчный текст (`.txt.gz`), пишется и читается потоком:
 * ```
 * SMARTHOME-POWER-HISTORY 1
 * created <ms>
 * source <instance> <entity_id>
 * r <ts> <ватты | ->          (− — разрыв)
 * m <start> <min> <max> <avg> <covered_ms> <count>
 * end <sha256 всех строк выше>
 * ```
 * Токенов, PIN и адресов в файле нет (сервер HA — только необратимый ключ). Новая копия засчитывается после
 * полного чтения с проверкой суммы; лишние старые удаляются только потом. Восстановление вставляет записи без
 * перезаписи имеющихся (более новые локальные данные не теряются).
 */
internal object PowerHistoryBackup {
    const val PREFIX = "smarthome_power_"
    const val SUFFIX = ".txt.gz"
    const val KEEP = 3
    const val HEADER = "SMARTHOME-POWER-HISTORY 1"
    const val PREF_LAST_TIME = "power_backup_last_time"
    const val PREF_LAST_RESULT = "power_backup_last_result"
    const val PREF_LAST_FP = "power_backup_last_fingerprint"
    private const val WORK = "daily_power_history_backup"

    enum class Outcome { CREATED, UNCHANGED, EMPTY, FAILED }

    data class Summary(val sources: List<Pair<String, String>>, val raw: Int, val minutes: Int, val from: Long?, val to: Long?)

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun run(context: Context, store: AutoBackup.Store = AutoBackup.store(context), db: PowerHistoryStore = PowerHistoryRecorder.get(context).store,
            now: Long = System.currentTimeMillis()): Outcome {
        val prefs = prefs(context)
        val sources = db.sources()
        if (sources.isEmpty()) return Outcome.EMPTY
        val fingerprint = db.fingerprint()
        if (fingerprint == prefs.getString(PREF_LAST_FP, null) && store.names().any { it.startsWith(PREFIX) }) return Outcome.UNCHANGED
        val name = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + SUFFIX
        val written = runCatching {
            val out = store.output(name) ?: return@runCatching false
            val digest = MessageDigest.getInstance("SHA-256")
            OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8).buffered().use { writer ->
                fun line(text: String) {
                    writer.write(text); writer.write("\n")
                    digest.update(text.toByteArray(Charsets.UTF_8)); digest.update(NEWLINE)
                }
                line(HEADER)
                line("created $now")
                sources.forEach { source ->
                    line("source ${source.instance} ${source.entityId}")
                    db.forEachMinute(source.id) { b -> line("m ${b.start} ${b.min} ${b.max} ${b.avg} ${b.coveredMs} ${b.count}") }
                    db.forEachRaw(source.id) { ts, value -> line("r $ts ${value ?: "-"}") }
                }
                writer.write("end ${hex(digest.digest())}\n")
            }
            true
        }.getOrDefault(false)
        // Засчитывается только копия, которая читается целиком с совпавшей суммой.
        if (!written || summary(store, name) == null) {
            store.delete(name)
            prefs.edit().putString(PREF_LAST_RESULT, "error").apply()
            return Outcome.FAILED
        }
        store.names().filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sorted().dropLast(KEEP).forEach { store.delete(it) }
        prefs.edit().putString(PREF_LAST_FP, fingerprint).putLong(PREF_LAST_TIME, now).putString(PREF_LAST_RESULT, "ok").apply()
        return Outcome.CREATED
    }

    /** Разбор с проверкой формата и суммы; [apply] получает каждую запись. null — файл повреждён/чужой. */
    private fun read(store: AutoBackup.Store, name: String, apply: ((Record) -> Unit)?): Summary? = runCatching {
        val input = store.input(name) ?: return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        var ok = false
        val sources = ArrayList<Pair<String, String>>()
        var raw = 0
        var minutes = 0
        var from: Long? = null
        var to: Long? = null
        fun span(ts: Long) { from = minOf(from ?: ts, ts); to = maxOf(to ?: ts, ts) }
        BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { reader ->
            var first = true
            var current: Pair<String, String>? = null
            while (true) {
                val text = reader.readLine() ?: break
                if (text.startsWith("end ")) {
                    ok = text.removePrefix("end ") == hex(digest.digest()) && reader.readLine() == null
                    break
                }
                digest.update(text.toByteArray(Charsets.UTF_8)); digest.update(NEWLINE)
                if (first) { if (text != HEADER) return@runCatching null; first = false; continue }
                val parts = text.split(' ')
                when (parts[0]) {
                    "created" -> {}
                    "source" -> {
                        if (parts.size != 3) return@runCatching null
                        current = parts[1] to parts[2]
                        sources += current!!
                        apply?.invoke(Record.Source(parts[1], parts[2]))
                    }
                    "r" -> {
                        if (current == null || parts.size != 3) return@runCatching null
                        val ts = parts[1].toLong()
                        val value = if (parts[2] == "-") null else parts[2].toDouble().takeIf { it.isFinite() } ?: return@runCatching null
                        raw++; span(ts)
                        apply?.invoke(Record.Raw(PowerSample(ts, value)))
                    }
                    "m" -> {
                        if (current == null || parts.size != 7) return@runCatching null
                        val b = PowerBucket(parts[1].toLong(), parts[2].toDouble(), parts[3].toDouble(), parts[4].toDouble(), parts[5].toLong(), parts[6].toInt())
                        minutes++; span(b.start)
                        apply?.invoke(Record.Minute(b))
                    }
                    else -> return@runCatching null
                }
            }
        }
        if (ok) Summary(sources, raw, minutes, from, to) else null
    }.getOrNull()

    private sealed interface Record {
        data class Source(val instance: String, val entityId: String) : Record
        data class Raw(val sample: PowerSample) : Record
        data class Minute(val bucket: PowerBucket) : Record
    }

    /** Проверка копии без записи в базу: датчики, объём и период — для подтверждения владельцем. */
    fun summary(store: AutoBackup.Store, name: String): Summary? = read(store, name, null)

    /**
     * Восстановление: сначала полная проверка файла, затем вставка без перезаписи имеющихся записей
     * (более новые локальные данные остаются). Возвращает число добавленных записей или null при ошибке.
     */
    fun restore(store: AutoBackup.Store, name: String, db: PowerHistoryStore): Int? {
        summary(store, name) ?: return null
        var added = 0
        var source: Long? = null
        val raw = ArrayList<PowerSample>()
        val minutes = ArrayList<PowerBucket>()
        fun flush() {
            val id = source ?: return
            added += db.insert(id, raw) + db.insertMinutes(id, minutes)
            raw.clear(); minutes.clear()
        }
        read(store, name) { record ->
            when (record) {
                is Record.Source -> { flush(); source = db.sourceId(record.instance, record.entityId) }
                is Record.Raw -> { raw += record.sample; if (raw.size >= BATCH) flush() }
                is Record.Minute -> { minutes += record.bucket; if (minutes.size >= BATCH) flush() }
            }
        } ?: return null
        flush()
        return added
    }

    /** Копии истории, новые сверху. */
    fun copies(store: AutoBackup.Store): List<String> =
        runCatching { store.names() }.getOrDefault(emptyList()).filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sortedDescending()

    fun label(name: String): String {
        val stamp = name.removePrefix(PREFIX).take(15)
        val time = runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse(stamp)?.time }.getOrNull() ?: return name
        return "История мощности · " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(time))
    }

    fun statusText(context: Context): String {
        val prefs = prefs(context)
        val time = prefs.getLong(PREF_LAST_TIME, 0L)
        val format = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        return when {
            prefs.getString(PREF_LAST_RESULT, null) == "error" ->
                "История мощности: ошибка записи копии" + if (time > 0) " (прошлая — ${format.format(Date(time))})" else ""
            time > 0 -> "История мощности: копия ${format.format(Date(time))}"
            else -> "История мощности: копии ещё нет"
        }
    }

    /** Раз в сутки около 04:00 — отдельно от копии настроек (03:00) и ночной установки (03:30). */
    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (_: IllegalStateException) {
            // WorkManager не инициализирован (тесты без манифеста): копии не планируются.
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            run(applicationContext)
            return Result.success()
        }
    }

    private const val BATCH = 5_000
    private val NEWLINE = byteArrayOf('\n'.code.toByte())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
