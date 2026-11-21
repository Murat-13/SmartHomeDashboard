package com.example.smarthomedashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.smarthomedashboard.data.TileManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Запись с камер на этом планшете (docs/recording.md): сервис переднего плана (`specialUse`, решение владельца
 * 18.11.2026), живёт независимо от экрана. Управление, очистка и чтение пароля — на своём потоке; каждая камера
 * пишет на своём потоке ([ContinuousRecorder]). Главный поток не участвует ни в сети, ни в записи на диск.
 *
 * [sync] вызывается панелью при запуске и после сохранения плитки: сервис сверяет нужные записи с идущими.
 * Нет ни одной включённой записи — сервис останавливается.
 */
class RecorderService : Service() {
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "rec-control").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val recorders = java.util.concurrent.ConcurrentHashMap<String, Running>()
    /** Файлы записей в памяти: полный обход диска — при запуске и раз в 6 часов. */
    private val files = ArrayList<RecFile>()
    private var lastScan = 0L
    @Volatile private var freeCache = Long.MAX_VALUE
    @Volatile private var freeCacheAt = 0L
    @Volatile private var paused = false
    private var started = false
    /** Последнее известное расположение камер (поток управления). */
    private val locality = RecLocality.Memory()
    /** Повторная сверка через минуту уже назначена (расположение камеры пока неизвестно). */
    private var retryScheduled = false
    /** Пишем в память планшета, хотя выбрана SD-карта (карту вынули или она сломалась). */
    private var sdMissing = false

    /** Изменилось что-то из ключа — запись перезапускается. Для HD сюда же предзапись, дозапись и предел ролика. */
    private data class Key(val url: String, val user: String, val passwordHash: Int, val audio: Boolean, val clip: List<Int> = emptyList())
    private class Running(val key: Key, val recorder: CameraRecorder, val config: RecordingConfig)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = notification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        // Раз в 10 минут: очистка и сверка (камера могла стать доступной только издалека, вернулась SD-карта).
        control.scheduleWithFixedDelay({ safe { sync(); retention(forceScan = false) } }, 10, 10, TimeUnit.MINUTES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        control.execute { safe { sync() } }
        return START_STICKY
    }

    override fun onDestroy() {
        // Остановка камер (закрытие отрезков, до 3 с на камеру) — на потоке управления: главный поток не ждёт.
        // После shutdown() поток дорабатывает уже поставленные задачи и завершается сам.
        control.execute { safe { stopAll() }; RecordingStatus.clear() }
        control.shutdown()
        synced = false
        super.onDestroy()
    }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "Запись: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Сверить нужные записи с идущими (поток управления). */
    private fun sync() {
        val device = RecordingDevice(this)
        val tiles = TileManager(this).loadTiles().filter { it.type in CameraSource.TYPES }
        // Ключ записи: id плитки — круглосуточная (субпоток), id + [HD] — события (основной поток).
        val wanted = HashMap<String, Pair<Key, RecordingConfig>>()
        // Пароль — свой у каждой камеры (docs/camera-tz.md); у домофона — прежний слот.
        val passwords = HashMap<String, String?>()
        val passwordFor = HashMap<String, String?>()
        for (tile in tiles) {
            if (!device.enabled(tile.id)) continue
            val rec = RecordingConfig.from(tile.config)
            val source = CameraSource.of(tile) ?: continue
            val sub = source.subUrl
            val hd = source.mainUrl
            if (sub.isBlank()) continue
            // Камера не в подсети планшета (VPN, интернет) — свой режим, по умолчанию выключен (трафик).
            // Неизвестно (нет DNS или сети) — последнее известное; не было и его — решим через минуту (A5).
            val local = if (rec.mode == rec.remoteMode) true else locality.resolve(sub, RecLocality.isLocal(sub))
            if (local == null) { retrySoon(); continue }
            val mode = rec.effectiveMode(local)
            if (!mode.continuous && !mode.events) continue
            val password = passwords.getOrPut(source.secretKey) { readPassword(source.secretKey) }
            passwordFor[tile.id] = password
            val hash = password?.hashCode() ?: 0
            if (mode.continuous) wanted[tile.id] = Key(sub, source.rtspUser, hash, rec.audioContinuous) to rec
            if (mode.events) wanted[tile.id + HD] = Key(hd, source.rtspUser, hash, rec.audioEvents,
                listOf(rec.preSeconds, rec.postSeconds, rec.maxClipMinutes)) to rec
        }
        // Остановить лишние и изменившиеся.
        for (id in recorders.keys.toList()) {
            val run = recorders.getValue(id)
            val want = wanted[id]
            if (want == null || want.first != run.key) {
                run.recorder.stop()
                recorders.remove(id)
                RecordingStatus.remove(id)
            } else if (want.second != run.config) {
                recorders[id] = Running(run.key, run.recorder, want.second)
            }
        }
        if (!started) {
            started = true
            // До запуска записей: все недописанные отрезки — от прошлого запуска.
            storageRoot()?.let { root ->
                val fixed = RecLayout.recover(root)
                if (fixed > 0) Log.i(TAG, "Запись: восстановлено отрезков после перезапуска: $fixed")
            }
            retention(forceScan = true)
        }
        for ((id, want) in wanted) {
            if (recorders.containsKey(id)) continue
            val tileId = id.removeSuffix(HD)
            val camera = RecLayout.cameraKey(tileId)
            val (key, config) = want
            val r: CameraRecorder = if (id.endsWith(HD)) EventRecorder(
                camera = camera, url = key.url, user = key.user, password = passwordFor[tileId], audio = key.audio,
                root = { storageRoot() }, canWrite = { canWrite() },
                events = { RecordingEvents.of(tileId) }, settings = { config },
                onClip = { file, labels -> control.execute { safe { clipClosed(file, labels) } } },
                onStatus = { _, status -> statusChanged(id, status) }
            ) else ContinuousRecorder(
                camera = camera, url = key.url, user = key.user, password = passwordFor[tileId],
                audio = key.audio, root = { storageRoot() }, canWrite = { canWrite() },
                onSegment = { file -> control.execute { safe { segmentClosed(file) } } },
                onStatus = { _, status -> statusChanged(id, status) }
            )
            recorders[id] = Running(key, r, config)
            r.start()
        }
        // Останавливаемся, только если запись не включена ни для одной плитки: камера, ставшая доступной лишь
        // издалека, вернётся в запись при следующей сверке (раз в 10 минут).
        if (recorders.isEmpty() && tiles.none { device.enabled(it.id) }) {
            Log.i(TAG, "Запись: включённых камер нет — сервис остановлен")
            main.post { stopSelf() }
        }
        // Потоки записей открыты (или стало ясно, что их не будет): живому видео больше нечего ждать ([starting]).
        synced = true
    }

    private fun retrySoon() {
        if (retryScheduled) return
        retryScheduled = true
        control.schedule({ retryScheduled = false; safe { sync() } }, 1, TimeUnit.MINUTES)
    }

    private fun stopAll() {
        recorders.values.forEach { it.recorder.stop() }
        recorders.clear()
    }

    private fun readPassword(secretKey: String): String? = try {
        CameraPasswords.blocking(applicationContext, secretKey)
    } catch (e: Exception) {
        Log.w(TAG, "Запись: пароль камеры недоступен: ${e.javaClass.simpleName}")
        null
    }

    /** Папка записей: SD-карта, если выбрана и вставлена, иначе память планшета. */
    private fun storageRoot(): File? {
        val dirs = try { getExternalFilesDirs(null) } catch (_: Exception) { emptyArray<File?>() }
        if (RecordingDevice(this).useSdCard) {
            val sd = dirs.getOrNull(1)
            if (sd != null && Environment.getExternalStorageState(sd) == Environment.MEDIA_MOUNTED) return sd
        }
        return dirs.getOrNull(0) ?: filesDir
    }

    /** SD-карта выбрана, но пишем в память планшета — лимит вдвое меньше (решение владельца 18.11). */
    private fun onFallback(): Boolean {
        if (!RecordingDevice(this).useSdCard) return false
        val sd = try { getExternalFilesDirs(null).getOrNull(1) } catch (_: Exception) { null }
        return sd == null || Environment.getExternalStorageState(sd) != Environment.MEDIA_MOUNTED
    }

    private fun freeBytes(root: File): Long = try { StatFs(root.path).availableBytes } catch (_: Exception) { 0L }

    /** Можно ли открыть новый отрезок: место сверх неприкосновенного остатка. Дёшево: кэш 5 с. */
    private fun canWrite(): Boolean {
        if (paused) return false
        val now = System.currentTimeMillis()
        if (now - freeCacheAt > 5_000) {
            freeCache = storageRoot()?.let { freeBytes(it) } ?: 0L
            freeCacheAt = now
        }
        return freeCache >= RecordingDevice(this).reserveGb * RecRetention.GB
    }

    /** SD-карта пропала или вернулась — сказать один раз (решение владельца 18.11), запись не прерывается. */
    private fun checkSdCard() {
        val missing = onFallback()
        if (missing == sdMissing) return
        sdMissing = missing
        if (missing) {
            Log.w(TAG, "Запись: SD-карта не найдена — пишем в память планшета")
            val voice = recorders.values.any { it.config.voiceProblems }
            if (voice) main.post { PanelAlerts.external(Alert(AlertEvent.RECORDING_STOPPED, SD_MISSING_PHRASE)) }
        } else {
            Log.i(TAG, "Запись: SD-карта снова доступна")
        }
        recorders.keys.forEach { id -> RecordingStatus.setSdMissing(id, missing) }
    }

    private fun segmentClosed(file: RecFile) {
        files += file
        retention(forceScan = false)
    }

    private val index by lazy { RecIndex(this) }

    private fun clipClosed(file: RecFile, labels: Set<String>) {
        try { index.add(file, labels) } catch (e: Exception) { Log.w(TAG, "Запись: индекс событий недоступен: ${e.javaClass.simpleName}") }
        Log.i(TAG, "Запись: событие ${labels.joinToString(",")} — ${file.durationMs / 1000} с")
        segmentClosed(file)
    }

    /** Очистка по сроку, лимиту и остатку (поток управления). */
    private fun retention(forceScan: Boolean) {
        checkSdCard()
        val root = storageRoot() ?: return
        val now = System.currentTimeMillis()
        if (forceScan || now - lastScan > 6 * 3_600_000L) {
            files.clear()
            files += RecLayout.scan(root)
            lastScan = now
            // Файлы — источник правды: строки удалённых роликов убираются из индекса.
            try { index.reconcile(files) } catch (e: Exception) { Log.w(TAG, "Запись: сверка индекса: ${e.javaClass.simpleName}") }
        }
        val device = RecordingDevice(this)
        val free = freeBytes(root)
        val used = files.sumOf { it.bytes }
        var limit = RecRetention.limit(device.limitGb, free, used)
        if (onFallback()) limit /= 2
        val configs = recorders.mapKeys { RecLayout.cameraKey(it.key.removeSuffix(HD)) }.mapValues { it.value.config }
        val plan = RecRetention.plan(files, { camera ->
            val c = configs[camera] ?: RecordingConfig()
            RecRetention.Rules(c.keepContinuousDays * 86_400_000L, c.keepEventDays * 86_400_000L)
        }, now, free, limit, device.reserveGb * RecRetention.GB)
        val deleted = plan.delete.filter { f -> (f.path.delete() || !f.path.exists()).also { if (it) files.remove(f) } }
        try { index.remove(deleted) } catch (e: Exception) { Log.w(TAG, "Запись: индекс событий: ${e.javaClass.simpleName}") }
        if (plan.delete.isNotEmpty()) {
            Log.i(TAG, "Запись: удалено отрезков ${plan.delete.size}")
            freeCacheAt = 0
            // Пустые папки дней не копятся.
            plan.delete.mapNotNull { it.path.parentFile }.toSet().forEach { dir -> if (dir.list()?.isEmpty() == true) dir.delete() }
        }
        paused = plan.pause
    }

    /** [key] — ключ записи: id плитки (круглосуточная) или id + [HD] (события). */
    private fun statusChanged(key: String, status: CameraRecorder.Status) {
        if (status.state == CameraRecorder.State.STOPPED) { RecordingStatus.remove(key); return }
        RecordingStatus.set(key, status)
        if (status.state == CameraRecorder.State.NO_SPACE || status.state == CameraRecorder.State.NO_STORAGE) {
            val voice = recorders[key]?.config?.voiceProblems ?: true
            if (voice) main.post { PanelAlerts.external(Alert(AlertEvent.RECORDING_STOPPED, status.detail.ifBlank { "Запись остановлена" })) }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Запись с камер", NotificationManager.IMPORTANCE_MIN))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Запись с камер")
            .setContentText("Идёт запись на этом планшете")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "Recording"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 4107
        const val SD_MISSING_PHRASE = "Карта памяти не найдена, запись идёт в память планшета"
        /** Суффикс ключа записи событий в HD (и её состояния в [RecordingStatus]). */
        const val HD = "#hd"

        /** Сервис уже сверил записи с настройками и открыл их потоки. */
        @Volatile private var synced = false
        private const val STARTING_MS = 15_000L

        /**
         * Запись на планшете включена, но сервис ещё не открыл потоки — первые секунды после запуска приложения. Живому
         * видео стоит подождать общий поток ([CameraHub.startingUp]), а не подключаться к камере вторым соединением.
         */
        fun starting(context: Context): Boolean = !synced &&
            android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < STARTING_MS &&
            RecordingDevice(context.applicationContext).enabledTiles.isNotEmpty()

        /**
         * Привести запись в соответствие с настройками. Безопасно вызывать часто: сервис запускается, только
         * если на этом планшете есть хоть одна включённая запись; иначе уже запущенный сам остановится.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val device = RecordingDevice(app)
            val any = device.enabledTiles.isNotEmpty() || RecordingStatus.active
            if (!any) return
            try {
                val intent = Intent(app, RecorderService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            } catch (e: Exception) {
                // Запуск сервиса переднего плана из фона запрещён (Android 12+): повторится при следующем запуске панели.
                Log.w(TAG, "Запись: сервис не запущен: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** Состояние записи по плиткам — для редактора и плитки. Слушатели вызываются на главном потоке. */
internal object RecordingStatus {
    private val map = java.util.concurrent.ConcurrentHashMap<String, CameraRecorder.Status>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val sd = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val active get() = map.isNotEmpty()
    /** Выбрана SD-карта, но её нет — пишем в память планшета. */
    fun sdMissing(tileId: String) = tileId in sd
    fun setSdMissing(tileId: String, missing: Boolean) { if (if (missing) sd.add(tileId) else sd.remove(tileId)) notifyListeners() }
    fun of(tileId: String): CameraRecorder.Status? = map[tileId]
    fun set(tileId: String, status: CameraRecorder.Status) { map[tileId] = status; notifyListeners() }
    fun remove(tileId: String) { if (map.remove(tileId) != null) notifyListeners() }
    fun clear() { sd.clear(); if (map.isNotEmpty()) { map.clear(); notifyListeners() } }
    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)
    private fun notifyListeners() = main.post { listeners.forEach { it() } }
}
