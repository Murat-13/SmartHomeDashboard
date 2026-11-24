package com.example.smarthomedashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Home Assistant, найденный в сети. [address] — чем подключаться («http://узел:порт»), [external] — внешний https или "". */
internal data class HaFound(
    val address: String,
    val name: String = "",
    val version: String = "",
    val uuid: String = "",
    val external: String = "",
    /** Сам Home Assistant ещё не настроен (страница первой настройки не пройдена). */
    val onboarding: Boolean = false
) {
    val title get() = name.ifBlank { "Home Assistant" }
}

/**
 * Что отвечает по адресу (docs/setup-tz.md, часть 1): `/auth/providers` есть только у Home Assistant, входом не
 * считается и пароля не требует. Блокирующее — рабочий поток.
 */
internal object HaServer {
    enum class State { READY, ONBOARDING, NOT_HA, UNREACHABLE, TLS }
    class Check(val state: State, val origin: String, val message: String)

    private const val MAX_BODY = 64 * 1024L
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

    /** «схема://узел[:порт]» адреса подключения. Некорректный адрес — [IllegalArgumentException]. */
    fun origin(address: String): String {
        val url = HomeAssistantAddress.httpBase(address).toHttpUrl()
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val default = if (url.isHttps) 443 else 80
        return "${url.scheme}://$host" + (if (url.port == default) "" else ":${url.port}")
    }

    fun check(address: String): Check {
        val origin = try { origin(address) } catch (_: IllegalArgumentException) {
            return Check(State.UNREACHABLE, "", "Адрес указан неверно")
        }
        return try {
            client.newCall(Request.Builder().url("$origin/auth/providers").build()).execute().use { response ->
                // Перенаправление на https (обратный прокси): дальше работаем с тем адресом, который ответил.
                val final = response.request.url
                val landed = if (final.encodedPath == "/auth/providers") origin(final.toString().removeSuffix("/auth/providers")) else origin
                // Чужой узел без шифрования — не прокси: страницу входа там не открываем.
                if (landed != origin && !final.isHttps && final.host != origin.toHttpUrl().host) {
                    return Check(State.NOT_HA, origin, "Адрес перенаправляет на другой сервер без шифрования")
                }
                val body = response.body?.source()?.let { s -> s.request(MAX_BODY); s.buffer.snapshot(minOf(s.buffer.size, MAX_BODY).toInt()).utf8() }.orEmpty()
                when {
                    response.code == 200 && providers(body) -> Check(State.READY, landed, "Home Assistant отвечает")
                    response.code == 400 && body.contains("onboarding_required") -> Check(State.ONBOARDING, landed,
                        "Этот Home Assistant ещё не настроен. Откройте его в браузере и закончите первую настройку")
                    else -> Check(State.NOT_HA, landed, "По этому адресу отвечает не Home Assistant")
                }
            }
        } catch (e: java.io.IOException) {
            val text = HomeAssistantAddress.errorMessage(e)
            if (text.startsWith("Ошибка TLS")) Check(State.TLS, origin,
                "Сертификат этого адреса не прошёл проверку Android. Укажите адрес с действующим сертификатом или http:// в домашней сети")
            else Check(State.UNREACHABLE, origin, text)
        }
    }

    /** Ответ со списком способов входа: новый вид — объект, прежний — список. */
    private fun providers(body: String): Boolean = try {
        when (val value = JSONTokener(body).nextValue()) {
            is JSONObject -> value.optJSONArray("providers") != null
            is JSONArray -> value.length() > 0 && value.optJSONObject(0)?.has("type") == true
            else -> false
        }
    } catch (_: org.json.JSONException) { false }
}

/** Служба mDNS «Home Assistant»: имя службы, адрес, порт и поля TXT. */
internal class HaService(val instance: String, val host: String, val port: Int, val txt: Map<String, String>)

/** Источник служб mDNS (в тестах — подмена). Вызовы — с любого потока. */
internal interface HaBrowser {
    fun start(onService: (HaService) -> Unit)
    fun stop()
}

/** mDNS Android (`NsdManager`). Разбор служб — по одной: старые Android отвечают «уже занят» на вторую. */
internal class NsdBrowser(context: Context) : HaBrowser {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onService: ((HaService) -> Unit)? = null

    override fun start(onService: (HaService) -> Unit) {
        handler.post {
            if (discovery != null) return@post
            this.onService = onService
            // Без блокировки часть планшетов (до Android 13) не слышит ответы mDNS.
            lock = try {
                app.getSystemService(WifiManager::class.java)?.createMulticastLock("ha-discovery")?.apply {
                    setReferenceCounted(false); acquire()
                }
            } catch (_: RuntimeException) { null }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "Поиск Home Assistant: Android отказал (код $code)")
                    handler.post { if (discovery === this) discovery = null }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onServiceFound(info: NsdServiceInfo) { handler.post { queue.addLast(info); next() } }
                override fun onServiceLost(info: NsdServiceInfo) {}
            }
            try {
                nsd?.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                discovery = listener
            } catch (_: RuntimeException) { release() }
        }
    }

    override fun stop() {
        handler.post {
            discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
            discovery = null
            queue.clear()
            onService = null
            release()
        }
    }

    private fun release() {
        runCatching { lock?.release() }
        lock = null
    }

    @Suppress("DEPRECATION") // resolveService и host — единственный способ, одинаковый на Android 7–16
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                handler.post { resolving = false; next() }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                handler.post {
                    resolving = false
                    service(resolved)?.let { s -> onService?.invoke(s) }
                    next()
                }
            }
        }
        try { nsd?.resolveService(info, listener) } catch (_: RuntimeException) { resolving = false }
    }

    @Suppress("DEPRECATION")
    private fun service(info: NsdServiceInfo): HaService? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress ?: return null
        val txt = info.attributes.orEmpty().mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) }.orEmpty() }
        return HaService(info.serviceName.orEmpty(), host.substringBefore('%'), info.port, txt)
    }

    private companion object {
        const val TYPE = "_home-assistant._tcp"
        const val TAG = "HaDiscovery"
    }
}

/**
 * Поиск Home Assistant в домашней сети (docs/setup-tz.md, часть 1): mDNS сразу, обычные имена через 3 с, порт 8123 своей
 * подсети — по кнопке. Только соединения и `/auth/providers`, ничего не отправляется. [onChange] — на главном потоке.
 */
internal class HaDiscovery(
    context: Context,
    private val onChange: (List<HaFound>) -> Unit,
    private val browser: HaBrowser = NsdBrowser(context),
    private val check: (String) -> HaServer.Check = HaServer::check,
    private val names: List<String> = listOf("http://homeassistant.local:8123", "http://homeassistant:8123"),
    private val network: () -> Pair<InetAddress, Int>? = { HaScan.network(context.applicationContext) }
) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(THREADS) { Thread(it, "ha-discovery").apply { isDaemon = true } }
    private val found = LinkedHashMap<String, HaFound>()
    @Volatile private var stopped = false
    private var started = false

    fun start() {
        if (started || stopped) return
        started = true
        browser.start { service -> submit { fromService(service) } }
        main.postDelayed(tryNames, NAMES_DELAY_MS)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        browser.stop()
        io.shutdownNow()
    }

    private val tryNames = Runnable { names.forEach { name -> submit { byAddress(name) } } }

    /** Порт 8123 у соседей по сети; [done] — закончено (сколько адресов проверено). */
    fun scan(done: (Int) -> Unit) {
        submit {
            val hosts = network()?.let { (address, prefix) -> HaScan.hosts(address, prefix) }.orEmpty()
            val left = java.util.concurrent.CountDownLatch(hosts.size)
            hosts.forEach { host ->
                submit {
                    try { if (HaScan.open(host, PORT)) byAddress("http://$host:$PORT") } finally { left.countDown() }
                }
            }
            runCatching { left.await(SCAN_LIMIT_S, TimeUnit.SECONDS) }
            main.post { if (!stopped) done(hosts.size) }
        }
    }

    private fun submit(block: () -> Unit) {
        if (stopped) return
        try { io.execute { try { block() } catch (_: Exception) {} } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun fromService(service: HaService) {
        val direct = direct(service)
        val internal = service.txt["internal_url"].orEmpty().trim()
        // Имя «….local» Android до 12 не понимает, а адрес из mDNS уже известен.
        val candidates = listOf(internal.takeIf { usableUrl(it) && !hostOf(it).endsWith(".local") }, direct).filterNotNull().distinct()
        val result = candidates.asSequence().map(check)
            .firstOrNull { it.state == HaServer.State.READY || it.state == HaServer.State.ONBOARDING } ?: return
        add(HaFound(result.origin, service.txt["location_name"].orEmpty().ifBlank { service.instance },
            service.txt["version"].orEmpty().takeUnless { it == "0000.0.0" }.orEmpty(), service.txt["uuid"].orEmpty(),
            service.txt["external_url"].orEmpty().trim().takeIf { it.startsWith("https://", true) && usableUrl(it) }.orEmpty(),
            result.state == HaServer.State.ONBOARDING))
    }

    private fun byAddress(address: String) {
        val result = check(address)
        if (result.state == HaServer.State.READY || result.state == HaServer.State.ONBOARDING) {
            add(HaFound(result.origin, onboarding = result.state == HaServer.State.ONBOARDING))
        }
    }

    private fun add(item: HaFound) {
        val key = key(item)
        main.post {
            if (stopped) return@post
            // Тот же сервер уже найден по mDNS (с названием) — запись без названия его не заменяет.
            val same = found.entries.firstOrNull { it.key == key || item.uuid.isNotEmpty() && it.value.uuid == item.uuid }
            if (same != null) {
                if (item.name.isBlank() || same.value == item) return@post
                found.remove(same.key)
            }
            found[key] = item
            onChange(found.values.toList())
        }
    }

    /** Адрес без имени: имена приводятся к IP, чтобы «homeassistant» и его IP не были двумя строками. */
    private fun key(item: HaFound): String = try {
        val url = HomeAssistantAddress.httpBase(item.address).toHttpUrl()
        val host = runCatching { InetAddress.getByName(url.host).hostAddress }.getOrNull() ?: url.host
        "$host:${url.port}"
    } catch (_: IllegalArgumentException) { item.address }

    companion object {
        const val PORT = 8123
        private const val THREADS = 32
        private const val NAMES_DELAY_MS = 3_000L
        private const val SCAN_LIMIT_S = 20L

        fun direct(service: HaService): String =
            "http://" + (if (service.host.contains(':')) "[${service.host}]" else service.host) + ":${service.port}"

        fun usableUrl(url: String) = try { url.toHttpUrl().username.isEmpty() } catch (_: IllegalArgumentException) { false }
        private fun hostOf(url: String) = try { url.toHttpUrl().host } catch (_: IllegalArgumentException) { "" }
    }
}

/** Соседи по сети для «Искать дальше»: не больше 254 адресов (своя /24), только проверка открытого порта. */
internal object HaScan {
    private const val TIMEOUT_MS = 500

    fun hosts(address: InetAddress, prefix: Int): List<String> {
        val own = (address as? Inet4Address)?.address ?: return emptyList()
        val ip = own.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        val bits = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - bits)
        val base = ip and mask
        return (1 until (1 shl (32 - bits)) - 1).map { base or it }.filter { it != ip }
            .map { v -> listOf(24, 16, 8, 0).joinToString(".") { ((v ushr it) and 0xff).toString() } }
    }

    fun open(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS); true }
    } catch (_: java.io.IOException) { false }

    /**
     * Адрес планшета в домашней сети (Wi-Fi или провод, не VPN) и длина префикса; null — домашней сети нет.
     */
    @Suppress("DEPRECATION") // allNetworks: синхронно и одинаково на Android 7–16
    fun network(context: Context): Pair<InetAddress, Int>? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            manager.allNetworks.filter { n ->
                val c = manager.getNetworkCapabilities(n) ?: return@filter false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }?.let { it.address to it.prefixLength }
        } catch (_: RuntimeException) { null }
    }
}
