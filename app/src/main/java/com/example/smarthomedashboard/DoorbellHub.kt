package com.example.smarthomedashboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.smarthomedashboard.data.TileEntity
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Общее для всех плиток «Домофон» (docs/doorbell.md): события звонка и связи, звук, автосвет, окно звонка
 * поверх любого экрана приложения, пропущенные звонки, пароль камеры и доступ к HA для видео.
 *
 * Живёт, пока жива панель ([PanelAlerts] получает состояние HA и передаёт сюда). Окно звонка показывается
 * в том экране, который сейчас открыт: на панели — сразу (растёт из плитки), в настройках и редакторах —
 * сначала баннер «Звонят в калитку» с «Открыть»/«Позже» (черновик редактора не теряется: окно — слой поверх).
 * Только главный поток.
 */
internal object DoorbellHub {
    /** Действия панели (задаёт MainActivity; пока панели нет — null и команды не отправляются). */
    interface Actions {
        fun call(domain: String, service: String, entityId: String, data: Map<String, Any>,
                 onResult: (ServiceCommandResult) -> Unit): Boolean
        /** Экран панели проснуться (снять затемнение) и свернуть раскрытые карточки. */
        fun prepareForRing()
        /** Прямоугольник плитки в окне (для роста окна звонка из неё); null — плитка не на экране. */
        fun tileBounds(tileId: String): android.graphics.RectF?
        /** Окно звонка открыто: панель не затемняется (перезапуск таймера бездействия). */
        fun keepAwake() {}
        /** Видео плитки на экране — окно звонка начинает с него, пока подключается основной поток. */
        fun tileVideo(tileId: String): DoorbellVideoView? = null
    }

    var actions: Actions? = null
    /** Соединение панели с HA (задаёт MainActivity). */
    var connection: (() -> HomeAssistantWebSocket?)? = null

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var monitor = DoorbellMonitor()
    private var tileList: List<TileEntity> = emptyList()
    var readings: BatteryReadings? = null
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private val recheck = Runnable { readings?.let { onState(tileList, it) } }

    // ---- Подключение ----

    fun attach(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        app = application
        (application as? Application)?.registerActivityLifecycleCallbacks(activityTracker)
    }

    fun detach() {
        main.removeCallbacks(recheck)
        lightOffs.values.forEach(main::removeCallbacks)
        lightOffs.clear()
        autoLit.clear()
        RingUi.closeAll()
        (app as? Application)?.unregisterActivityLifecycleCallbacks(activityTracker)
        app = null
        readings = null
        // Новая панель начинает с чистого листа: первое состояние снова только запоминается.
        monitor = DoorbellMonitor()
        tileList = emptyList()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun tile(tileId: String): TileEntity? = tileList.firstOrNull { it.id == tileId }
    fun config(tileId: String): DoorbellConfig? = tile(tileId)?.let { DoorbellConfig.from(it.config) }
    fun offline(tileId: String) = monitor.offline(tileId)

    // ---- Состояние HA ----

    /** Новое состояние HA и список плиток (из [PanelAlerts]). */
    fun onState(tiles: List<TileEntity>, current: BatteryReadings) {
        tileList = tiles.filter { it.type == DoorbellConfig.TYPE }
        readings = current
        val now = SystemClock.uptimeMillis()
        val events = monitor.evaluate(tileList, current, now)
        main.removeCallbacks(recheck)
        monitor.nextCheckAt?.let { main.postDelayed(recheck, (it - now).coerceAtLeast(0) + 20) }
        events.forEach(::handle)
        RingUi.render()
        // Плитки панели перерисовываются по своим сущностям сами; здесь — только то, чего в HA нет
        // (пропущенные, «нет сигнала», окно звонка). Иначе каждое событие любой сущности будило бы все домофоны.
        if (events.isNotEmpty()) notifyListeners()
    }

    private fun handle(event: DoorbellMonitor.Event) {
        val tile = tile(event.tileId) ?: return
        val config = DoorbellConfig.from(tile.config)
        when (event) {
            is DoorbellMonitor.Event.Ring -> ring(tile, config)
            is DoorbellMonitor.Event.Offline -> if (config.offlineSound) {
                PanelAlerts.external(Alert(AlertEvent.DOORBELL_OFFLINE, "Домофон не в сети"))
            }
            is DoorbellMonitor.Event.Online -> Unit
            is DoorbellMonitor.Event.GateOpened -> RingUi.gateOpened(tile.id)
        }
    }

    // ---- Звонок ----

    private fun ring(tile: TileEntity, config: DoorbellConfig) {
        val context = app ?: return
        prefs(context).edit()
            .putInt(KEY_MISSED + tile.id, missed(tile.id) + 1)
            .putLong(KEY_LAST + tile.id, System.currentTimeMillis())
            .apply()
        playRing(context, tile.id, config)
        wakeScreen()
        autoLight(tile.id, config)
        RingUi.ring(tile.id)
    }

    /** Мелодия (своя или встроенная) и голос — сразу, без общего кулдауна: повторный звонок звучит снова. */
    private fun playRing(context: Context, tileId: String, config: DoorbellConfig) {
        val volume = config.ringVolumeAt(minuteOfDay())
        if (volume <= 0f) return
        val tone = config.ringTone.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: VoiceAnnouncer.resource(context, R.raw.doorbell_ding_dong)
        val phrase = if (config.ringVoice && AlertEvent.DOORBELL_RING in PanelAlerts.settings().enabled) RING_PHRASE else null
        PanelAlerts.sound(phrase, tone, volume, ringTag(tileId))
    }

    fun ringTag(tileId: String) = "doorbell_ring_$tileId"

    /**
     * Экран панели не гаснет (FLAG_KEEP_SCREEN_ON), а затемняется самой панелью — её и будим.
     * WAKE_LOCK намеренно не запрашивается (PanelWindowTest): поверх чужих приложений окно звонка не показывается.
     */
    private fun wakeScreen() {
        actions?.prepareForRing()
    }

    // ---- Пропущенные звонки ----

    fun missed(tileId: String): Int = app?.let { prefs(it).getInt(KEY_MISSED + tileId, 0) } ?: 0
    fun lastRing(tileId: String): Long? = app?.let { prefs(it).getLong(KEY_LAST + tileId, 0L).takeIf { v -> v > 0 } }

    /** Звонок увиден (касание окна, плитки, баннера «Открыть»): счётчик пропущенных обнуляется. */
    fun acknowledge(tileId: String) {
        val context = app ?: return
        if (missed(tileId) == 0) return
        prefs(context).edit().putInt(KEY_MISSED + tileId, 0).apply()
        notifyListeners()
    }

    // ---- Свет у входа ----

    private val autoLit = mutableSetOf<String>()
    private val lightOffs = mutableMapOf<String, Runnable>()

    /** После заката включить свет, если он выключен; включённый ранее свет не трогается. */
    private fun autoLight(tileId: String, config: DoorbellConfig) {
        lightOffs.remove(tileId)?.let(main::removeCallbacks)
        val current = readings ?: return
        if (!config.autoLight || config.light.isBlank()) return
        val snapshot = DoorbellModel.snapshot(config, current)
        if (!snapshot.sunDown || snapshot.lightOn != false) return
        val domain = config.light.substringBefore('.')
        if (actions?.call(domain, "turn_on", config.light, emptyMap()) {} == true) {
            autoLit += tileId
            // Окно могло так и не открыться («Позже», баннер ушёл сам): свет гаснет и без него.
            scheduleLightOff(tileId, DoorbellRingWindow.RING_MS + config.lightOffMinutes * 60_000L)
        }
    }

    /** Свет переключён вручную в окне звонка: дальше им управляет человек. */
    fun lightTouched(tileId: String) {
        autoLit -= tileId
        lightOffs.remove(tileId)?.let(main::removeCallbacks)
    }

    /** Окно звонка закрылось: включённый автоматически свет гаснет через заданное время. */
    fun windowClosed(tileId: String) {
        if (tileId !in autoLit) return
        val config = config(tileId) ?: return
        if (config.lightOffMinutes <= 0) { autoLit -= tileId; lightOffs.remove(tileId)?.let(main::removeCallbacks); return }
        scheduleLightOff(tileId, config.lightOffMinutes * 60_000L)
    }

    private fun scheduleLightOff(tileId: String, delay: Long) {
        if (config(tileId)?.lightOffMinutes?.let { it <= 0 } != false) return
        lightOffs.remove(tileId)?.let(main::removeCallbacks)
        val off = Runnable {
            lightOffs.remove(tileId)
            // Окно всё ещё открыто (человек смотрит) — погасим после его закрытия.
            if (RingUi.current?.let { it.tileId == tileId && it.isShowing } == true) return@Runnable
            autoLit -= tileId
            val latest = config(tileId) ?: return@Runnable
            val state = readings?.let { DoorbellModel.snapshot(latest, it).lightOn }
            if (state == true) actions?.call(latest.light.substringBefore('.'), "turn_off", latest.light, emptyMap()) {}
        }
        lightOffs[tileId] = off
        main.postDelayed(off, delay)
    }

    // ---- Видео: HA и пароль ----

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    fun httpBase(): String? = connection?.invoke()?.httpBase

    /** HLS-адрес потока HA (с токеном в пути — заголовки не нужны) или null. */
    fun streamUrl(camera: String, result: (String?) -> Unit) {
        val ws = connection?.invoke()
        val base = ws?.httpBase
        if (ws == null || base == null) { result(null); return }
        ws.read("camera/stream", org.json.JSONObject().put("entity_id", camera), 15_000L) { value ->
            val path = (value as? org.json.JSONObject)?.optString("url")?.takeIf { it.isNotBlank() }
            result(path?.let { if (it.startsWith("http")) it else base + it })
        }
    }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "doorbell-secret").apply { isDaemon = true } }
    @Volatile private var password: String? = null
    @Volatile private var passwordLoaded = false

    /** Пароль RTSP: читается из защищённого хранилища один раз в фоне, хранится в памяти процесса. */
    fun cameraPassword(context: Context, result: (String?) -> Unit) {
        if (passwordLoaded) { result(password); return }
        val application = context.applicationContext
        io.execute {
            val value = try {
                AndroidSecretStore.create(application).read(SecretStore.Slot.CAMERA_PASSWORD)
                    .takeIf { it.status == SecretStore.Status.SUCCESS }?.value
            } catch (e: Exception) { Log.w(TAG, "Пароль камеры недоступен: ${e.javaClass.simpleName}"); null }
            password = value
            passwordLoaded = true
            main.post { result(value) }
        }
    }

    /** Сохранить пароль RTSP (фон; результат — на главном потоке). Пустой — удалить. */
    fun savePassword(context: Context, value: String, done: (Boolean) -> Unit) {
        val application = context.applicationContext
        io.execute {
            val ok = try {
                val store = AndroidSecretStore.create(application)
                val status = if (value.isEmpty()) store.delete(SecretStore.Slot.CAMERA_PASSWORD)
                    else store.write(SecretStore.Slot.CAMERA_PASSWORD, value)
                status == SecretStore.Status.SUCCESS || (value.isEmpty() && status == SecretStore.Status.ABSENT)
            } catch (_: Exception) { false }
            passwordLoaded = false
            password = null
            main.post { done(ok) }
        }
    }

    // ---- Вспомогательное ----

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun minuteOfDay(): Int = java.util.Calendar.getInstance().let {
        it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
    }

    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = RingUi.resumed(activity)
        override fun onActivityPaused(activity: Activity) = RingUi.paused(activity)
        override fun onActivityDestroyed(activity: Activity) = RingUi.destroyed(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    const val RING_PHRASE = "Звонят в калитку"
    private const val TAG = "DoorbellHub"
    private const val KEY_MISSED = "doorbell_missed_"
    private const val KEY_LAST = "doorbell_last_"

    /**
     * Где показывать звонок: окно на панели, баннер в остальных экранах, отложенный звонок, пока экрана нет.
     * Отложенный звонок показывается, если приложение откроется в течение [DoorbellRingWindow.RING_MS].
     */
    internal object RingUi {
        private var resumed: WeakReference<Activity>? = null
        private var window: DoorbellRingWindow? = null
        private var banner: DoorbellBanner? = null
        private var pending: Pair<String, Long>? = null

        val current: DoorbellRingWindow? get() = window

        fun ring(tileId: String) {
            window?.let { if (it.tileId == tileId && it.isShowing) { it.extend(); return } }
            val activity = resumed?.get()
            pending = tileId to SystemClock.uptimeMillis()
            when {
                activity == null || activity.isFinishing -> Unit
                activity is MainActivity -> show(activity, tileId, null)
                else -> showBanner(activity, tileId)
            }
        }

        fun gateOpened(tileId: String) {
            // Калитку открыли (здесь или на другом планшете) — звонок обработан, не пропущен.
            acknowledge(tileId)
            if (pending?.first == tileId) pending = null
            banner?.takeIf { it.tileId == tileId }?.dismiss()
            window?.takeIf { it.tileId == tileId }?.gateOpened()
        }

        fun render() { window?.render() }

        private fun show(activity: Activity, tileId: String, from: android.graphics.RectF?) {
            pending = null
            banner?.dismiss()
            window?.closeNow()
            val origin = from ?: if (activity is MainActivity) actions?.tileBounds(tileId) else null
            window = DoorbellRingWindow.show(activity, tileId, origin) { closed ->
                if (window === closed) window = null
                windowClosed(tileId)
                notifyListeners()
            }
            notifyListeners()
        }

        private fun showBanner(activity: Activity, tileId: String) {
            banner?.dismiss()
            banner = DoorbellBanner.show(activity, tileId,
                onOpen = { rect -> banner = null; acknowledge(tileId); show(activity, tileId, rect) },
                onLater = { banner = null; pending = null },
                onGone = { b -> if (banner === b) banner = null })
        }

        fun resumed(activity: Activity) {
            resumed = WeakReference(activity)
            val (tileId, at) = pending ?: return
            if (SystemClock.uptimeMillis() - at > DoorbellRingWindow.RING_MS) { pending = null; return }
            if (window?.isShowing == true) return
            // Баннер прежнего экрана (настройки закрыли, вернулись на панель) — заменить окном или новым баннером.
            if (banner?.host === activity) return
            banner?.dismiss()
            if (activity is MainActivity) show(activity, tileId, null) else showBanner(activity, tileId)
        }

        fun paused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        fun destroyed(activity: Activity) {
            window?.takeIf { it.host === activity }?.closeNow()
            banner?.takeIf { it.host === activity }?.dismiss()
        }

        val windowOpen get() = window?.isShowing == true

        fun closeAll() {
            pending = null
            banner?.dismiss()
            window?.closeNow()
        }
    }
}
