package com.example.smarthomedashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.smarthomedashboard.data.TileEntity
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Прямые события с камер на этом планшете (docs/direct-events-tz.md, этап 1 — звонок). Включается в редакторе
 * «Домофона» для каждой плитки; флаг, секрет, слот и отпечаток сертификата камеры — только на этом планшете
 * (`camera_events.xml`, вне резервных копий и экспорта).
 *
 * Камере прописывается вебхук `http://<адрес планшета>:<порт>/cam/<секрет>`; приёмник ([CameraHookServer]) принимает
 * его только с адреса этой камеры. Звонок уходит в [DoorbellHub.directRing] — там он сливается со звонком из HA.
 * Самопроверка — при запуске, раз в 15 мин, при смене сети и по кнопке; вебхук нездоров — запасной опрос камеры
 * раз в секунду. Сеть — на своём фоновом потоке, состояние и колбэки — на главном.
 */
internal object DirectEvents {
    enum class Level { OFF, WORKING, OK, WARN, ERROR }
    data class Status(val level: Level, val text: String)

    private const val TAG = "DirectEvents"
    const val PREFS = "camera_events"
    private const val CHECK_EVERY_MS = 15 * 60_000L
    private const val NETWORK_SETTLE_MS = 5_000L
    private const val POLL_MS = 1_000L
    private const val REMOVED_CONFIRM_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-events").apply { isDaemon = true } }
    private var app: Context? = null
    @Volatile private var server: CameraHookServer? = null
    private val statuses = HashMap<String, Status>()
    private val listeners = LinkedHashSet<() -> Unit>()
    /** Секрет → маршрут; читает поток приёмника. */
    private val routes = ConcurrentHashMap<String, Route>()
    private val polls = ConcurrentHashMap<String, Thread>()
    private val missingSince = HashMap<String, Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private data class Route(val tileId: String, val addresses: Set<InetAddress>, val driver: CameraEventDriver)

    /** Создание драйвера (в тестах — поддельный). */
    internal var driverFactory: (host: String, user: String, password: String, pin: String?, onPin: (String) -> Unit) -> CameraEventDriver =
        { host, user, password, pin, onPin -> ReolinkDriver("https://$host", user, password, ReolinkDriver.client(host, pin, onPin)) }
    /** Пароль камеры (в тестах — задан). */
    internal var passwordSource: (Context, (String?) -> Unit) -> Unit = { context, result -> DoorbellHub.cameraPassword(context, result) }
    /** Пароль камеры плитки: у «Камеры» — свой ([CameraPasswords]), у домофона — [passwordSource]. */
    private fun passwordFor(context: Context, tileId: String, result: (String?) -> Unit) {
        val key = prefs(context).getString("$tileId.secret_key", "").orEmpty()
        if (key.isEmpty()) passwordSource(context, result) else CameraPasswords.read(context, key, result)
    }
    /** Плитка «Камера» (не домофон): звонка нет, запасной опрос звонка не нужен. */
    private fun isCamera(context: Context, tileId: String) = !prefs(context).getString("$tileId.secret_key", "").isNullOrEmpty()
    /** Куда уходит звонок (в тестах — запись). */
    internal var onRing: (tileId: String, source: String) -> Unit = { tileId, source -> DoorbellHub.directRing(tileId, source) }
    /** События для записи (в тестах — запись вызовов). */
    internal var onRecordingEvents: (tileId: String, keys: List<String>) -> Unit = { tileId, keys ->
        DoorbellHub.storedTile(tileId)?.let { tile -> RecordingEvents.direct(tileId, keys, RecordingConfig.from(tile.config).eventTriggers) }
    }
    /** Ключи событий записи ([RecordingConfig.TRIGGERS]). */
    private val RECORDING_KEYS = mapOf(
        CameraEventType.RING to "visitor", CameraEventType.PERSON to "person", CameraEventType.VEHICLE to "vehicle",
        CameraEventType.ANIMAL to "animal", CameraEventType.MOTION to "motion"
    )
    /** Адрес планшета, с которого видна камера (в тестах — задан). */
    internal var localAddress: (cameraHost: String) -> String? = ::localAddressFor

    // ---- Жизненный цикл (из DoorbellHub) ----

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        listenNetwork()
        scheduleCheck(0)
    }

    fun detach() {
        main.removeCallbacks(periodic)
        main.removeCallbacks(networkSettled)
        unlistenNetwork()
        polls.keys.toList().forEach(::stopPoll)
        stopServer()
        routes.clear()
        statuses.clear()
        detected.clear()
        missingSince.clear()
        app = null
    }

    /** Дождаться фоновых задач (тесты: задачи прошлого теста не должны доходить до следующего). */
    internal fun drain() { io.submit {}.get() }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun enabled(context: Context, tileId: String) = prefs(context).getBoolean("$tileId.enabled", false)

    fun port(context: Context) = prefs(context).getInt("port", CameraHookServer.DEFAULT_PORT)

    fun setPort(context: Context, value: Int) {
        if (value !in 1024..65535 || value == port(context)) return
        prefs(context).edit().putInt("port", value).apply()
        stopServer()
        scheduleCheck(0)
    }

    fun status(context: Context, tileId: String): Status =
        statuses[tileId] ?: if (enabled(context, tileId)) Status(Level.WORKING, "Проверка…") else Status(Level.OFF, "Выключено")

    /** Включить или выключить на этом планшете (из «Сохранить» редактора). */
    fun setEnabled(context: Context, tileId: String, config: DoorbellConfig, on: Boolean, secretKey: String = "") {
        val application = context.applicationContext
        if (app == null) attach(application)
        val p = prefs(application)
        if (!on) {
            if (!p.getBoolean("$tileId.enabled", false)) return
            disable(application, tileId)
            return
        }
        val host = cameraHost(config)
        if (host == null || config.rtspUser.isBlank()) {
            setStatus(tileId, Status(Level.ERROR, "Нужен прямой поток (RTSP) с адресом камеры и логин"))
            return
        }
        val oldHost = p.getString("$tileId.host", null)
        val changedCamera = oldHost != host
        // Камеру в плитке сменили: у прежней камеры свой вебхук убирается (её отпечатком сертификата), иначе он
        // остаётся в её слоте навсегда (аудит 20.11.2026, A6). Чужие слоты не трогаются — только слот с нашим секретом.
        if (changedCamera && oldHost != null && p.getBoolean("$tileId.enabled", false)) {
            val oldUser = p.getString("$tileId.user", null)
            val oldSecret = p.getString("$tileId.secret", null)
            if (oldUser != null && oldSecret != null) {
                removeFromCamera(application, oldHost, oldUser, oldSecret, p.getString("$tileId.secret_key", "").orEmpty(),
                    p.getString("$tileId.pin", null))
            }
        }
        p.edit().putBoolean("$tileId.enabled", true).putString("$tileId.host", host).putString("$tileId.user", config.rtspUser)
            .putString("$tileId.secret_key", secretKey)
            .apply { if (changedCamera) remove("$tileId.pin").remove("$tileId.slot") }
            .apply { if (p.getString("$tileId.secret", null) == null) putString("$tileId.secret", newSecret()) }
            .apply()
        check(tileId)
    }

    /** Самопроверка сейчас (кнопка «Проверить»). */
    fun check(tileId: String) {
        val context = app ?: return
        if (!enabled(context, tileId)) return
        setStatus(tileId, Status(Level.WORKING, "Проверка камеры…"))
        passwordFor(context, tileId) { password ->
            io.execute { checkNow(context, tileId, password) }
        }
    }

    /** Плитки панели (из [DoorbellHub.onState]): удалённая плитка — свой вебхук из камеры убирается. */
    fun onTiles(all: List<TileEntity>, now: Long) {
        val context = app ?: return
        if (all.isEmpty()) return
        val ids = all.mapTo(HashSet()) { it.id }
        enabledTiles(context).forEach { id ->
            if (id in ids) { missingSince.remove(id); return@forEach }
            val since = missingSince.getOrPut(id) { now }
            if (now - since >= REMOVED_CONFIRM_MS) { missingSince.remove(id); disable(context, id) }
        }
    }

    // ---- Приём вебхука (поток приёмника) ----

    internal fun route(path: String, from: InetAddress, body: String): Boolean {
        val secret = path.removePrefix("/cam/").substringBefore('?').takeIf { path.startsWith("/cam/") && it.length >= 16 } ?: return false
        val route = routes[secret] ?: return false
        if (from !in route.addresses) {
            Log.w(TAG, "Вебхук с чужого адреса отклонён")
            return false
        }
        val events = route.driver.parse(body)
        main.post { onEvents(route.tileId, events, "вебхук") }
        return true
    }

    /** События с камеры для значков на плитке: тип → до какого времени (мс, стена) показывать. */
    private val detected = ConcurrentHashMap<String, ConcurrentHashMap<CameraEventType, Long>>()
    /** Камера сообщает только начало события — значок горит столько же, сколько событие считается для записи. */
    private const val SHOW_MS = RecordingEvents.DIRECT_HOLD_MS
    private val DETECTIONS = mapOf(
        CameraEventType.PERSON to DoorbellConfig.Detection.PERSON, CameraEventType.VEHICLE to DoorbellConfig.Detection.VEHICLE,
        CameraEventType.ANIMAL to DoorbellConfig.Detection.ANIMAL, CameraEventType.MOTION to DoorbellConfig.Detection.MOTION
    )

    /** Что камера сообщила за последние [SHOW_MS] (значки плитки и окна звонка). */
    fun activeDetections(tileId: String?): Set<DoorbellConfig.Detection> {
        val map = tileId?.let { detected[it] } ?: return emptySet()
        val now = System.currentTimeMillis()
        return map.filterValues { it > now }.keys.mapNotNullTo(LinkedHashSet()) { DETECTIONS[it] }
    }

    /** События из другого источника (ONVIF у камер, docs/camera-tz.md): значки, запись, охрана — как у вебхука. */
    fun external(tileId: String, events: List<CameraEventType>, source: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) onEvents(tileId, events, source) else main.post { onEvents(tileId, events, source) }
    }

    private fun onEvents(tileId: String, events: List<CameraEventType>, source: String) {
        val context = app ?: return
        if (events.isEmpty()) return
        val shown = events.filter { it in DETECTIONS }
        if (shown.isNotEmpty()) {
            val until = System.currentTimeMillis() + SHOW_MS
            val map = detected.getOrPut(tileId) { ConcurrentHashMap() }
            shown.forEach { map[it] = until }
            DoorbellHub.refreshCards()
            main.postDelayed({ DoorbellHub.refreshCards() }, SHOW_MS + 100)
        }
        val type = events.first()
        prefs(context).edit().putLong("$tileId.last_at", System.currentTimeMillis()).putString("$tileId.last_type", type.name).apply()
        // Вебхук дошёл — путь здоров: запасной опрос не нужен.
        if (source == "вебхук") { stopPoll(tileId); okStatus(context, tileId) }
        else if (statuses[tileId]?.level == Level.OK) okStatus(context, tileId)
        if (CameraEventType.RING in events && !isCamera(context, tileId)) onRing(tileId, source)
        Log.i(TAG, "Событие с камеры ($source): ${events.joinToString { it.name }}")
        // Этап 2: события камеры — в запуск HD-записи наравне с датчиками HA.
        onRecordingEvents(tileId, events.map { RECORDING_KEYS.getValue(it) })
    }

    // ---- Работа с камерой (фоновый поток) ----

    private fun checkNow(context: Context, tileId: String, password: String?) {
        val p = prefs(context)
        if (!p.getBoolean("$tileId.enabled", false)) return
        val host = p.getString("$tileId.host", null) ?: return
        val user = p.getString("$tileId.user", null) ?: return
        val secret = p.getString("$tileId.secret", null) ?: return
        if (password.isNullOrEmpty()) { post(tileId, Status(Level.ERROR, "Пароль камеры не задан")); return }
        if (!ensureServer(context)) {
            post(tileId, Status(Level.WARN, "Порт ${port(context)} на планшете занят — звонок идёт через Home Assistant и опрос камеры"))
            startPoll(context, tileId, password)
            return
        }
        val driver = driver(context, tileId, host, user, password)
        try {
            val addresses = InetAddress.getAllByName(host).toSet()
            val local = localAddress(host) ?: throw CameraException("Планшет не видит камеру в своей сети")
            val url = "http://$local:${server?.localPort ?: port(context)}/cam/$secret"
            when (driver.verify(url, secret)) {
                CameraEventDriver.Health.OK -> Unit
                CameraEventDriver.Health.MOVED, CameraEventDriver.Health.MISSING -> {
                    val info = driver.detect()
                    val slot = driver.install(url, secret)
                    p.edit().putInt("$tileId.slot", slot).putString("$tileId.model", "${info.model} ${info.firmware}".trim()).apply()
                    Log.i(TAG, "Вебхук прописан в камере (слот $slot)")
                }
            }
            routes.entries.removeIf { it.value.tileId == tileId }
            // Драйвер в маршруте только разбирает тела вебхуков — сессию API камеры закрываем ниже.
            routes[secret] = Route(tileId, addresses, driver)
            stopPoll(tileId)
            main.post { okStatus(context, tileId) }
        } catch (e: CameraException) {
            val reason = reason(e)
            Log.w(TAG, "Проверка не прошла: $reason")
            if (e.auth) post(tileId, Status(Level.ERROR, "$reason. Проверьте логин и пароль камеры"))
            else fallBack(context, tileId, password, reason)
        } catch (e: Exception) {
            val reason = reason(e)
            Log.w(TAG, "Проверка не прошла: $reason")
            fallBack(context, tileId, password, reason)
        } finally {
            driver.close()
        }
    }

    private fun disable(context: Context, tileId: String) {
        val p = prefs(context)
        val host = p.getString("$tileId.host", null)
        val user = p.getString("$tileId.user", null)
        val secret = p.getString("$tileId.secret", null)
        val secretKey = p.getString("$tileId.secret_key", "").orEmpty()
        val pin = p.getString("$tileId.pin", null)
        stopPoll(tileId)
        routes.entries.removeIf { it.value.tileId == tileId }
        p.edit().apply { listOf("enabled", "host", "user", "secret", "slot", "pin", "model", "last_at", "last_type", "secret_key").forEach { remove("$tileId.$it") } }.apply()
        setStatus(tileId, Status(Level.OFF, "Выключено"))
        if (routes.isEmpty()) stopServer()
        if (host == null || user == null || secret == null) return
        removeFromCamera(context, host, user, secret, secretKey, pin)
    }

    /**
     * Убрать свой вебхук (слот с [secret]) из камеры [host]. Сертификат — запомненный [pin]: пароль камеры уходит
     * только той камере, что была при включении (раньше — любому, кто ответит по этому адресу; A7).
     */
    private fun removeFromCamera(context: Context, host: String, user: String, secret: String, secretKey: String, pin: String?) {
        val read: (Context, (String?) -> Unit) -> Unit = if (secretKey.isEmpty()) passwordSource else { c, r -> CameraPasswords.read(c, secretKey, r) }
        read(context) { password ->
            if (password.isNullOrEmpty()) { Log.w(TAG, "Вебхук из камеры не убран: пароль камеры не задан"); return@read }
            io.execute {
                val driver = driverFactory(host, user, password, pin) {}
                try { driver.uninstall(secret); Log.i(TAG, "Вебхук убран из камеры") }
                catch (e: Exception) { Log.w(TAG, "Вебхук из камеры не убран: ${reason(e)}") }
                finally { driver.close() }
            }
        }
    }

    private fun driver(context: Context, tileId: String, host: String, user: String, password: String): CameraEventDriver {
        val p = prefs(context)
        return driverFactory(host, user, password, p.getString("$tileId.pin", null)) { print ->
            p.edit().putString("$tileId.pin", print).apply()
        }
    }

    // ---- Запасной опрос ----

    /** Вебхук не настроен: звонок идёт через HA и запасной опрос камеры. */
    private fun fallBack(context: Context, tileId: String, password: String, reason: String) {
        // У камеры звонка нет — опрашивать нечего; события остаются по ONVIF.
        if (isCamera(context, tileId)) { post(tileId, Status(Level.WARN, "$reason — события идут по ONVIF")); return }
        post(tileId, Status(Level.WARN, "$reason — звонок идёт через Home Assistant и опрос камеры"))
        startPoll(context, tileId, password)
    }

    private fun startPoll(context: Context, tileId: String, password: String) {
        if (polls.containsKey(tileId)) return
        val p = prefs(context)
        val host = p.getString("$tileId.host", null) ?: return
        val user = p.getString("$tileId.user", null) ?: return
        val thread = Thread({
            val driver = driver(context, tileId, host, user, password)
            var active: Boolean? = null
            try {
                while (polls[tileId] === Thread.currentThread()) {
                    val now = try { driver.ringActive() } catch (e: CameraException) {
                        if (e.auth) break
                        null
                    } catch (_: Exception) { null }
                    if (now == true && active == false) main.post { onEvents(tileId, listOf(CameraEventType.RING), "опрос") }
                    if (now != null) active = now
                    Thread.sleep(if (now == null) POLL_MS * 5 else POLL_MS)
                }
            } catch (_: InterruptedException) {
            } finally {
                driver.close()
            }
        }, "camera-poll").apply { isDaemon = true }
        polls[tileId] = thread
        thread.start()
    }

    private fun stopPoll(tileId: String) { polls.remove(tileId)?.interrupt() }

    // ---- Приёмник ----

    private fun ensureServer(context: Context): Boolean {
        server?.takeIf { it.running }?.let { return true }
        val s = CameraHookServer(port(context), ::route)
        return try { s.start(); server = s; true } catch (e: Exception) {
            Log.w(TAG, "Приёмник не открылся: ${e.javaClass.simpleName}")
            s.stop()
            false
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    // ---- Самопроверка ----

    private val periodic = Runnable { checkAll(); scheduleCheck(CHECK_EVERY_MS) }
    private val networkSettled = Runnable { checkAll() }

    private fun scheduleCheck(delay: Long) {
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, delay)
    }

    private fun checkAll() {
        val context = app ?: return
        enabledTiles(context).forEach(::check)
    }

    private fun listenNetwork() {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = settle()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = settle()
            private fun settle() { main.removeCallbacks(networkSettled); main.postDelayed(networkSettled, NETWORK_SETTLE_MS) }
        }
        try { cm.registerDefaultNetworkCallback(callback); networkCallback = callback } catch (_: Exception) {}
    }

    private fun unlistenNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try { app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    // ---- Вспомогательное ----

    private fun okStatus(context: Context, tileId: String) {
        val p = prefs(context)
        val model = p.getString("$tileId.model", null)?.let { " ($it)" }.orEmpty()
        val at = p.getLong("$tileId.last_at", 0L)
        val last = if (at > 0) {
            val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date(at))
            val what = when (p.getString("$tileId.last_type", null)) {
                CameraEventType.RING.name -> "звонок"; CameraEventType.PERSON.name -> "человек"; CameraEventType.VEHICLE.name -> "машина"
                CameraEventType.ANIMAL.name -> "животное"; CameraEventType.MOTION.name -> "движение"; else -> "событие"
            }
            " · последнее событие $time — $what"
        } else " · событий ещё не было"
        setStatus(tileId, Status(Level.OK, "Подключено$model$last"))
    }

    private fun post(tileId: String, status: Status) = main.post { setStatus(tileId, status) }

    private fun setStatus(tileId: String, status: Status) {
        if (statuses[tileId] == status) return
        statuses[tileId] = status
        listeners.toList().forEach { it() }
    }

    private fun enabledTiles(context: Context): List<String> =
        prefs(context).all.keys.filter { it.endsWith(".enabled") }.map { it.removeSuffix(".enabled") }
            .filter { enabled(context, it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newSecret(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun cameraHost(config: DoorbellConfig): String? =
        listOf(config.rtspMain, config.rtspSub).firstNotNullOfOrNull { url ->
            try { URI(url.trim()).host?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        }

    /** Адрес планшета на пути к камере: UDP-«подключение» ничего не отправляет, но выбирает интерфейс. */
    private fun localAddressFor(cameraHost: String): String? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(cameraHost), 443)
            (s.localAddress as? Inet4Address)?.takeIf { !it.isAnyLocalAddress && !it.isLoopbackAddress }?.hostAddress
        }
    } catch (_: Exception) { null }

    private fun reason(e: Exception): String = when {
        e is CameraException -> e.message.orEmpty()
        e is java.net.UnknownHostException -> "Адрес камеры не найден"
        e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "Камера не отвечает"
        e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException ->
            "Сертификат камеры изменился — выключите и снова включите прямые события"
        else -> e.javaClass.simpleName
    }
}
