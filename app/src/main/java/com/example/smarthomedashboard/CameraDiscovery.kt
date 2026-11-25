package com.example.smarthomedashboard

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Марка камеры — для подсказок («где включить ONVIF») и обычных ссылок RTSP. */
internal enum class CameraBrand(val title: String, private val words: List<String>) {
    REOLINK("Reolink", listOf("reolink")), HIKVISION("Hikvision", listOf("hikvision", "hiwatch")), DAHUA("Dahua", listOf("dahua")),
    TAPO("TP-Link Tapo", listOf("tapo", "tp-link", "tplink")), EZVIZ("EZVIZ", listOf("ezviz")), IMOU("Imou", listOf("imou")),
    UNIVIEW("Uniview", listOf("uniview", "unv")), AXIS("Axis", listOf("axis")), UBIQUITI("UniFi", listOf("ubiquiti", "unifi")),
    AMCREST("Amcrest", listOf("amcrest")), OTHER("", emptyList());

    companion object {
        fun of(vararg texts: String): CameraBrand {
            val text = texts.joinToString(" ").lowercase(Locale.ROOT)
            return entries.firstOrNull { b -> b.words.any { it in text } } ?: OTHER
        }
    }
}

/** Камера, найденная в сети. [host] — её адрес; [onvifPort] 0 — по ONVIF не ответила. */
internal data class CameraFound(
    val host: String, val name: String = "", val model: String = "", val brand: CameraBrand = CameraBrand.OTHER,
    val onvifPort: Int = 0, val sources: Set<Source> = emptySet(),
    /** Похожа на домофон (по модели) — только предположение: тип плитки выбирает человек. */
    val doorbell: Boolean = false,
    /** Устройство Home Assistant этой камеры ("" — Home Assistant её не знает). */
    val haDevice: String = ""
) {
    enum class Source { ONVIF, REOLINK, HOME_ASSISTANT }

    /** Заголовок строки: имя из Home Assistant или модель. */
    val title get() = name.ifBlank { listOf(brand.title.takeUnless { model.contains(it, true) }.orEmpty(), model).filter { it.isNotBlank() }.joinToString(" ") }
        .ifBlank { "Камера" }

    /** Та же камера из другого источника: имя — человеческое (Home Assistant), остальное — кто знает. */
    fun with(other: CameraFound): CameraFound {
        val named = if (Source.HOME_ASSISTANT in other.sources && other.name.isNotBlank()) other.name else name.ifBlank { other.name }
        return copy(name = named, model = model.ifBlank { other.model }, brand = if (brand == CameraBrand.OTHER) other.brand else brand,
            onvifPort = if (onvifPort > 0) onvifPort else other.onvifPort, sources = sources + other.sources, doorbell = doorbell || other.doorbell,
            haDevice = haDevice.ifEmpty { other.haDevice })
    }

    companion object {
        private val DOORBELL = listOf("doorbell", "vdb", "intercom", "door station", "doorstation", "домофон", "звонок")
        fun looksDoorbell(vararg texts: String) = texts.joinToString(" ").lowercase(Locale.ROOT).let { text -> DOORBELL.any { it in text } }
    }
}

/**
 * Поиск по WS-Discovery (ONVIF): запрос на групповой адрес, камеры отвечают сами. Только чтение, без пароля. Типизированный
 * и общий запрос, трижды: ответы теряются, а часть камер на типизированный молчит. Блокирующее.
 */
internal object WsDiscovery {
    private val GROUP = InetSocketAddress("239.255.255.250", 3702)
    private const val ROUNDS = 3
    private const val ROUND_MS = 700L
    private const val MAX_ANSWER = 64 * 1024

    fun probe(typed: Boolean, id: String = UUID.randomUUID().toString()): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
        "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header>" +
        "<a:Action s:mustUnderstand=\"1\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action><a:MessageID>uuid:$id</a:MessageID>" +
        "<a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>" +
        "<a:To s:mustUnderstand=\"1\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To></s:Header><s:Body><d:Probe>" +
        (if (typed) "<d:Types>dn:NetworkVideoTransmitter</d:Types>" else "") + "</d:Probe></s:Body></s:Envelope>"

    /** [prepare] — привязать сокет к домашней сети; [target] в тестах — свой адрес. */
    fun search(local: InetAddress?, durationMs: Long, cancelled: () -> Boolean = { false }, target: InetSocketAddress = GROUP,
               prepare: (DatagramSocket) -> Unit = {}, found: (CameraFound) -> Unit) {
        MulticastSocket(InetSocketAddress(local, 0)).use { socket ->
            prepare(socket)
            local?.let { runCatching { NetworkInterface.getByInetAddress(it)?.let { i -> socket.networkInterface = i } } }
            runCatching { socket.timeToLive = 2 }
            socket.soTimeout = 200
            val start = System.nanoTime()
            fun passed() = (System.nanoTime() - start) / 1_000_000
            var sent = 0
            val buffer = ByteArray(MAX_ANSWER)
            while (passed() < durationMs && !cancelled()) {
                if (sent < ROUNDS && passed() >= sent * ROUND_MS) {
                    for (typed in listOf(true, false)) {
                        val bytes = probe(typed).toByteArray()
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target)) }
                    }
                    sent++
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parse(text, packet.address.hostAddress.orEmpty()).forEach(found)
            }
        }
    }

    /** Ответ камеры; [from] — откуда пришёл: у камер с двумя сетями XAddrs бывает чужим. Не камеры (принтеры) — пусто. */
    fun parse(text: String, from: String): List<CameraFound> {
        if (!shallow(text)) return emptyList()
        val root = try { Onvif.parse(text) } catch (_: Exception) { return emptyList() }
        return root.all("ProbeMatch").mapNotNull { match ->
            val scopes = match.find("Scopes")?.text.orEmpty().split(Regex("\\s+")).filter { it.startsWith("onvif://www.onvif.org/", true) }
            val types = match.find("Types")?.text.orEmpty()
            if (scopes.isEmpty() && !types.contains("NetworkVideoTransmitter")) return@mapNotNull null
            fun scope(name: String) = scopes.firstOrNull { it.startsWith("onvif://www.onvif.org/$name/", true) }?.substringAfter("/$name/", "")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.trim().orEmpty()
            val addresses = match.find("XAddrs")?.text.orEmpty().split(Regex("\\s+")).mapNotNull { x ->
                try { x.toHttpUrl().let { it.host to it.port } } catch (_: IllegalArgumentException) { null } }
            val port = (addresses.firstOrNull { it.first == from } ?: addresses.firstOrNull())?.second ?: 80
            val hardware = scope("hardware").take(MAX_NAME)
            val name = scope("name").take(MAX_NAME)
            val model = hardware.ifBlank { name }
            CameraFound(from, "", model, CameraBrand.of(hardware, name, scope("mfr"), scope("manufacturer")), port, setOf(CameraFound.Source.ONVIF),
                CameraFound.looksDoorbell(hardware, name))
        }.distinctBy { it.host }
    }

    /** Чужое устройство в сети могло прислать что угодно: слишком глубокая вложенность не разбирается. */
    private fun shallow(text: String): Boolean = try {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(text))
        var ok = true
        while (ok && parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) ok = parser.depth <= MAX_DEPTH
        ok
    } catch (_: org.xmlpull.v1.XmlPullParserException) { false } catch (_: java.io.IOException) { false
    } catch (_: RuntimeException) { false } // разборщик Android на части испорченных документов бросает и такое (фаззинг 26.11.2026)

    private const val MAX_NAME = 60
    private const val MAX_DEPTH = 32
}

/**
 * Поиск камер Reolink их собственным запросом (4 байта на UDP 2000, ответ на UDP 3000): находит и камеры с выключенным
 * ONVIF. Официального описания нет; проверено на домофоне Reolink 24.11.2026. Только чтение, без пароля. Блокирующее.
 */
internal object ReolinkSearch {
    private val REQUEST = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0, 0)
    private const val ASK_PORT = 2000
    private const val ANSWER_PORT = 3000
    private const val ROUNDS = 3
    private const val ROUND_MS = 1_000L
    private const val MIN_ANSWER = 200

    fun search(broadcasts: List<InetAddress>, durationMs: Long, cancelled: () -> Boolean = { false }, askPort: Int = ASK_PORT,
               answerPort: Int = ANSWER_PORT, prepare: (DatagramSocket) -> Unit = {}, found: (CameraFound) -> Unit) {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            prepare(socket)
            socket.bind(InetSocketAddress(answerPort))
            socket.soTimeout = 200
            val start = System.nanoTime()
            fun passed() = (System.nanoTime() - start) / 1_000_000
            var sent = 0
            val buffer = ByteArray(2048)
            while (passed() < durationMs && !cancelled()) {
                if (sent < ROUNDS && passed() >= sent * ROUND_MS) {
                    broadcasts.forEach { runCatching { socket.send(DatagramPacket(REQUEST, REQUEST.size, it, askPort)) } }
                    sent++
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                parse(packet.data.copyOf(packet.length), packet.address.hostAddress.orEmpty())?.let(found)
            }
        }
    }

    /** Ответ: адрес камеры в нём должен совпасть с тем, откуда он пришёл, — иначе это не ответ камеры. */
    fun parse(data: ByteArray, from: String): CameraFound? {
        if (data.size < MIN_ANSWER) return null
        fun text(at: Int, size: Int): String = String(data, at, minOf(size, data.size - at), Charsets.UTF_8).substringBefore('\u0000').trim()
        if (text(IP_AT, 16) != from) return null
        val name = text(NAME_AT, 32).filter { !it.isISOControl() }
        return CameraFound(from, "", name.ifBlank { "Reolink" }, CameraBrand.REOLINK, 0, setOf(CameraFound.Source.REOLINK), CameraFound.looksDoorbell(name))
    }

    private const val IP_AT = 108
    private const val NAME_AT = 132
}

/**
 * Поиск камер в домашней сети (docs/setup-tz.md, часть 3): ONVIF, Reolink и устройства Home Assistant одновременно, только
 * чтение, без паролей. Одна камера из нескольких источников — одна строка. [onChange] и [done] — на главном потоке.
 */
internal class CameraDiscovery(
    context: Context,
    private val onChange: (List<CameraFound>) -> Unit,
    private val done: () -> Unit = {},
    /** Прочитать дом Home Assistant (рабочий поток); null — не подключён. */
    private val home: (() -> HaHome)? = null,
    private val durationMs: Long = DURATION_MS,
    private val onvif: ((CameraFound) -> Unit, () -> Boolean) -> Unit = { found, cancelled -> Lan.of(context)?.onvif(durationMs, cancelled, found) },
    private val reolink: ((CameraFound) -> Unit, () -> Boolean) -> Unit = { found, cancelled -> Lan.of(context)?.reolink(durationMs, cancelled, found) }
) {
    /** Домашняя сеть для сокетов поиска. */
    private class Lan(private val lan: HaScan.Lan) {
        fun onvif(ms: Long, cancelled: () -> Boolean, found: (CameraFound) -> Unit) =
            WsDiscovery.search(lan.address, ms, cancelled, prepare = { runCatching { lan.network.bindSocket(it) } }, found = found)

        fun reolink(ms: Long, cancelled: () -> Boolean, found: (CameraFound) -> Unit) =
            ReolinkSearch.search(listOf(InetAddress.getByName("255.255.255.255"), broadcast(lan.address, lan.prefix)), ms, cancelled,
                prepare = { runCatching { lan.network.bindSocket(it) } }, found = found)

        companion object { fun of(context: Context) = HaScan.lan(context)?.let { Lan(it) } }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3) { Thread(it, "camera-discovery").apply { isDaemon = true } }
    private val found = LinkedHashMap<String, CameraFound>()
    @Volatile private var stopped = false
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        lock = try {
            app.getSystemService(WifiManager::class.java)?.createMulticastLock("camera-discovery")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: RuntimeException) { null }
        val left = AtomicInteger(3)
        fun run(what: String, block: () -> Unit) = io.execute {
            try { block() } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "Поиск камер ($what): ${e.javaClass.simpleName}")
            } finally { if (left.decrementAndGet() == 0) main.post { if (!stopped) { release(); done() } } }
        }
        run("ONVIF") { onvif(::add) { stopped } }
        run("Reolink") { reolink(::add) { stopped } }
        run("Home Assistant") { home?.let { read -> fromHome(read()).forEach(::add) } }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        release()
    }

    private fun release() {
        runCatching { lock?.release() }
        lock = null
    }

    private fun add(item: CameraFound) = main.post {
        if (stopped) return@post
        val next = found[item.host]?.with(item) ?: item
        if (found[item.host] == next) return@post
        found[item.host] = next
        onChange(found.values.sortedWith(compareBy({ !it.doorbell }, { it.title.lowercase(Locale.ROOT) }, { it.host })))
    }

    companion object {
        const val DURATION_MS = 5_000L
        private const val TAG = "CameraDiscovery"

        /** Адрес рассылки своей подсети (запрос Reolink часть точек доступа пропускает только на него). */
        fun broadcast(address: InetAddress, prefix: Int): InetAddress {
            val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val bits = prefix.coerceIn(8, 30)
            val all = ip or (-1 ushr bits)
            return InetAddress.getByAddress(byteArrayOf((all ushr 24).toByte(), (all ushr 16).toByte(), (all ushr 8).toByte(), all.toByte()))
        }

        /** Камеры, которые уже есть в Home Assistant: адрес — из «адреса настройки» устройства. Видит и другую подсеть. */
        fun fromHome(home: HaHome, resolve: (String) -> String? = ::resolve): List<CameraFound> {
            val cameras = home.entities.filter { it.domain == "camera" && it.device.isNotEmpty() }.mapTo(LinkedHashSet()) { it.device }
            return cameras.mapNotNull { id ->
                val device = home.devices[id] ?: return@mapNotNull null
                val host = hostOf(device)?.let(resolve) ?: return@mapNotNull null
                CameraFound(host, device.name, listOf(device.model).filter { it.isNotBlank() }.joinToString(" "),
                    CameraBrand.of(device.manufacturer, device.model, device.name), 0, setOf(CameraFound.Source.HOME_ASSISTANT),
                    CameraFound.looksDoorbell(device.model, device.name), id)
            }
        }

        /** Узел «адреса настройки» устройства; null — не адрес в сети (например, страница самого Home Assistant). */
        fun hostOf(device: HaHome.Device): String? = try {
            device.configUrl.takeIf { it.startsWith("http", true) }?.toHttpUrl()?.host
        } catch (_: IllegalArgumentException) { null }

        private fun resolve(host: String): String? = try {
            InetAddress.getByName(host).takeIf { it.isSiteLocalAddress }?.hostAddress
        } catch (_: java.io.IOException) { null }
    }
}
