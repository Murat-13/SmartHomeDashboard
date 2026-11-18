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

    private data class Key(val url: String, val user: String, val passwordHash: Int, val audio: Boolean)
    private class Running(val key: Key, val recorder: ContinuousRecorder, val config: RecordingConfig)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = notification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        control.scheduleWithFixedDelay({ safe { retention(forceScan = false) } }, 10, 10, TimeUnit.MINUTES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        control.execute { safe { sync() } }
        return START_STICKY
    }

    override fun onDestroy() {
        control.execute { safe { stopAll() } }
        control.shutdown()
        try { control.awaitTermination(4, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        RecordingStatus.clear()
        super.onDestroy()
    }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "Запись: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Сверить нужные записи с идущими (поток управления). */
    private fun sync() {
        val device = RecordingDevice(this)
        val tiles = TileManager(this).loadTiles().filter { it.type == DoorbellConfig.TYPE }
        val wanted = HashMap<String, Pair<Key, RecordingConfig>>()
        var password: String? = null
        var passwordRead = false
        for (tile in tiles) {
            if (!device.enabled(tile.id)) continue
            val rec = RecordingConfig.from(tile.config)
            if (!rec.mode.continuous) continue
            val door = DoorbellConfig.from(tile.config)
            val url = door.rtspSub.ifBlank { door.rtspMain }
            if (url.isBlank()) continue
            if (!passwordRead) { password = readPassword(); passwordRead = true }
            wanted[tile.id] = Key(url, door.rtspUser, password?.hashCode() ?: 0, rec.audioContinuous) to rec
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
            val camera = RecLayout.cameraKey(id)
            val r = ContinuousRecorder(
                camera = camera, url = want.first.url, user = want.first.user, password = password,
                audio = want.first.audio, root = { storageRoot() }, canWrite = { canWrite() },
                onSegment = { file -> control.execute { safe { segmentClosed(file) } } },
                onStatus = { _, status -> statusChanged(id, status) }
            )
            recorders[id] = Running(want.first, r, want.second)
            r.start()
        }
        if (recorders.isEmpty()) {
            Log.i(TAG, "Запись: включённых камер нет — сервис остановлен")
            main.post { stopSelf() }
        }
    }

    private fun stopAll() {
        recorders.values.forEach { it.recorder.stop() }
        recorders.clear()
    }

    private fun readPassword(): String? = try {
        AndroidSecretStore.create(applicationContext).read(SecretStore.Slot.CAMERA_PASSWORD)
            .takeIf { it.status == SecretStore.Status.SUCCESS }?.value
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

    private fun segmentClosed(file: RecFile) {
        files += file
        retention(forceScan = false)
    }

    /** Очистка по сроку, лимиту и остатку (поток управления). */
    private fun retention(forceScan: Boolean) {
        val root = storageRoot() ?: return
        val now = System.currentTimeMillis()
        if (forceScan || now - lastScan > 6 * 3_600_000L) {
            files.clear()
            files += RecLayout.scan(root)
            lastScan = now
        }
        val device = RecordingDevice(this)
        val free = freeBytes(root)
        val used = files.sumOf { it.bytes }
        var limit = RecRetention.limit(device.limitGb, free, used)
        if (onFallback()) limit /= 2
        val configs = recorders.mapKeys { RecLayout.cameraKey(it.key) }.mapValues { it.value.config }
        val plan = RecRetention.plan(files, { camera ->
            val c = configs[camera] ?: RecordingConfig()
            RecRetention.Rules(c.keepContinuousDays * 86_400_000L, c.keepEventDays * 86_400_000L)
        }, now, free, limit, device.reserveGb * RecRetention.GB)
        for (f in plan.delete) {
            if (f.path.delete() || !f.path.exists()) files.remove(f)
        }
        if (plan.delete.isNotEmpty()) {
            Log.i(TAG, "Запись: удалено отрезков ${plan.delete.size}")
            freeCacheAt = 0
            // Пустые папки дней не копятся.
            plan.delete.mapNotNull { it.path.parentFile }.toSet().forEach { dir -> if (dir.list()?.isEmpty() == true) dir.delete() }
        }
        paused = plan.pause
    }

    private fun statusChanged(tileId: String, status: ContinuousRecorder.Status) {
        if (status.state == ContinuousRecorder.State.STOPPED) { RecordingStatus.remove(tileId); return }
        RecordingStatus.set(tileId, status)
        if (status.state == ContinuousRecorder.State.NO_SPACE || status.state == ContinuousRecorder.State.NO_STORAGE) {
            val voice = recorders[tileId]?.config?.voiceProblems ?: true
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
    private val map = java.util.concurrent.ConcurrentHashMap<String, ContinuousRecorder.Status>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val active get() = map.isNotEmpty()
    fun of(tileId: String): ContinuousRecorder.Status? = map[tileId]
    fun set(tileId: String, status: ContinuousRecorder.Status) { map[tileId] = status; notifyListeners() }
    fun remove(tileId: String) { if (map.remove(tileId) != null) notifyListeners() }
    fun clear() { if (map.isNotEmpty()) { map.clear(); notifyListeners() } }
    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)
    private fun notifyListeners() = main.post { listeners.forEach { it() } }
}
