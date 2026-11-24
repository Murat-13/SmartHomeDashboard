package com.example.smarthomedashboard

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * ONVIF — общий язык современных камер (docs/camera-tz.md): по адресу, логину и паролю узнать модель и ссылки RTSP
 * основного потока и субпотока. Только SOAP по HTTP в домашней сети, ответы ограничены по размеру, пароль не попадает
 * в журнал. Блокирующее — рабочий поток.
 */
internal object Onvif {
    /**
     * Поток профиля: ссылка RTSP и разрешение; для диагностики (docs/diagnostics-tz.md) — частота, битрейт и интервал
     * ключевых кадров из настроек кодировщика (0 — камера не сообщила).
     */
    data class Stream(val uri: String, val width: Int, val height: Int, val encoding: String, val profile: String,
                      val fps: Int = 0, val kbps: Int = 0, val gov: Int = 0, val options: EncoderOptions? = null)

    /**
     * Что камера разрешает поставить у потока (`GetVideoEncoderConfigurationOptions`): размеры, частота кадров, есть ли
     * H.264. Диагностика советует только то, что камера умеет (у домофона Reolink — 2560×1920 и не больше 20 к/с).
     */
    data class EncoderOptions(val resolutions: List<Pair<Int, Int>>, val fpsMin: Int, val fpsMax: Int, val h264: Boolean,
                              /** Частоты списком (Reolink: 20, 18, 16…); пусто — любая в [fpsMin]..[fpsMax]. */
                              val fpsList: List<Int> = emptyList()) {
        fun allows(fps: Int) = if (fpsList.isNotEmpty()) fps in fpsList else fps in fpsMin..fpsMax
    }

    data class Info(
        val manufacturer: String,
        val model: String,
        val firmware: String,
        val port: Int,
        val main: Stream?,
        val sub: Stream?,
        val profiles: List<Stream>,
        /** Адрес службы событий (часть 2: подписка PullPoint). */
        val eventsAddress: String?,
        /** Часы камеры минус часы планшета, мс; null — камера не сообщила время. */
        val clockOffsetMs: Long? = null
    ) {
        val title get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Камера ONVIF" }
    }

    class OnvifException(message: String, val auth: Boolean = false) : java.io.IOException(message)

    /** Порты ONVIF по очереди, если владелец не задал свой: Hikvision/Dahua — 80, Reolink — 8000, TP-Link — 2020. */
    val PORTS = listOf(80, 8000, 8080, 2020, 8899)
    private const val MAX_RESPONSE = 512 * 1024

    private const val DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl"
    private const val MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl"
    private const val SCHEMA_NS = "http://www.onvif.org/ver10/schema"

    /** Сетевой вызов SOAP: адрес и тело конверта → текст ответа и код. Подменяется в тестах. */
    internal var transport: (String, String) -> Pair<Int, String> = { url, body -> post(url, body) }
    internal var now: () -> Long = { System.currentTimeMillis() }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/soap+xml; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_RESPONSE) throw OnvifException("Слишком большой ответ камеры")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return response.code to String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * Узнать камеру: [port] 0 — перебрать [PORTS]. Ошибка — [OnvifException] с понятным текстом (нет ONVIF, не тот
     * пароль, камера не отвечает).
     */
    fun probe(host: String, port: Int, user: String, password: String): Info {
        val ports = if (port > 0) listOf(port) else PORTS
        var last: Exception? = null
        for (p in ports) {
            try { return probePort(host.trim(), p, user, password) } catch (e: OnvifException) {
                if (e.auth) throw e
                last = e
            } catch (e: Exception) { last = e }
        }
        throw (last as? OnvifException) ?: OnvifException(
            "Камера не ответила по ONVIF" + (if (port > 0) " на порту $port" else " (порты ${PORTS.joinToString()})") +
                ". Включите ONVIF в настройках камеры или введите ссылки RTSP вручную")
    }

    private fun probePort(host: String, port: Int, user: String, password: String): Info {
        val device = "http://$host:$port/onvif/device_service"
        // Часы камеры: подпись WS-Security действительна, только если время близко к часам камеры.
        val clock = runCatching { clockOffset(device) }.getOrNull()
        val offset = clock ?: 0L
        val info = call(device, user, password, offset, "<GetDeviceInformation xmlns=\"$DEVICE_NS\"/>")
        val caps = call(device, user, password, offset, "<GetCapabilities xmlns=\"$DEVICE_NS\"><Category>All</Category></GetCapabilities>")
        val media = caps.find("Media")?.find("XAddr")?.text?.takeIf { it.startsWith("http") }?.let { sameHost(it, host, port) } ?: device
        val events = caps.find("Events")?.find("XAddr")?.text?.takeIf { it.startsWith("http") }?.let { sameHost(it, host, port) }
        val profiles = call(media, user, password, offset, "<GetProfiles xmlns=\"$MEDIA_NS\"/>").all("Profiles").mapNotNull { p ->
            val token = p.attrs["token"] ?: return@mapNotNull null
            val video = p.find("VideoEncoderConfiguration")
            val w = video?.find("Width")?.text?.toIntOrNull() ?: 0
            val h = video?.find("Height")?.text?.toIntOrNull() ?: 0
            val rate = video?.find("RateControl")
            val extra = Triple(rate?.find("FrameRateLimit")?.text?.toIntOrNull() ?: 0, rate?.find("BitrateLimit")?.text?.toIntOrNull() ?: 0,
                video?.find("GovLength")?.text?.toIntOrNull() ?: 0)
            Triple(token, w to h, video?.find("Encoding")?.text.orEmpty() to extra)
        }
        if (profiles.isEmpty()) throw OnvifException("Камера не сообщила видеопотоки (профили ONVIF)")
        val streams = profiles.mapNotNull { (token, size, codec) ->
            val (encoding, extra) = codec
            val body = "<GetStreamUri xmlns=\"$MEDIA_NS\"><StreamSetup><Stream xmlns=\"$SCHEMA_NS\">RTP-Unicast</Stream>" +
                "<Transport xmlns=\"$SCHEMA_NS\"><Protocol>RTSP</Protocol></Transport></StreamSetup>" +
                "<ProfileToken>${escape(token)}</ProfileToken></GetStreamUri>"
            val uri = runCatching { call(media, user, password, offset, body).find("Uri")?.text }.getOrNull()
                ?.takeIf { it.startsWith("rtsp://") } ?: return@mapNotNull null
            Stream(withoutCredentials(uri), size.first, size.second, encoding, token, extra.first, extra.second, extra.third)
        }.distinctBy { it.uri }
        if (streams.isEmpty()) throw OnvifException("Камера не дала ссылки RTSP — введите их вручную")
        val (pickedMain, pickedSub) = pick(streams)
        // Возможности кодировщика — только для двух потоков плитки; камера не ответила — без них (советы осторожнее).
        fun withOptions(s: Stream?) = s?.copy(options = runCatching { options(media, user, password, offset, s.profile) }.getOrNull())
        val main = withOptions(pickedMain)
        val sub = if (pickedSub == pickedMain) main else withOptions(pickedSub)
        return Info(info.find("Manufacturer")?.text.orEmpty(), info.find("Model")?.text.orEmpty(),
            info.find("FirmwareVersion")?.text.orEmpty(), port, main, sub, streams, events, clock)
    }

    // ---- События (часть 2, docs/camera-tz.md): подписка PullPoint ----

    private const val EVENTS_NS = "http://www.onvif.org/ver10/events/wsdl"
    private const val WSN_NS = "http://docs.oasis-open.org/wsn/b-2"

    /** Подписка на события: адрес подписки и поправка часов камеры. */
    data class Subscription(val address: String, val offset: Long, val user: String, val password: String)

    /** Найти службу событий камеры и подписаться (PullPoint, срок [ttlSeconds]). */
    fun subscribe(host: String, port: Int, user: String, password: String, ttlSeconds: Int = 120): Subscription {
        val ports = if (port > 0) listOf(port) else PORTS
        var last: Exception? = null
        for (p in ports) {
            try {
                val device = "http://$host:$p/onvif/device_service"
                val offset = runCatching { clockOffset(device) }.getOrNull() ?: 0L
                val caps = call(device, user, password, offset, "<GetCapabilities xmlns=\"$DEVICE_NS\"><Category>Events</Category></GetCapabilities>")
                val events = caps.find("Events")?.find("XAddr")?.text?.takeIf { it.startsWith("http") }?.let { sameHost(it, host, p) }
                    ?: throw OnvifException("Камера не сообщила службу событий ONVIF")
                val created = call(events, user, password, offset,
                    "<CreatePullPointSubscription xmlns=\"$EVENTS_NS\"><InitialTerminationTime>PT${ttlSeconds}S</InitialTerminationTime>" +
                        "</CreatePullPointSubscription>",
                    action = "$EVENTS_NS/EventPortType/CreatePullPointSubscriptionRequest")
                val address = created.find("SubscriptionReference")?.find("Address")?.text?.takeIf { it.startsWith("http") }
                    ?.let { sameHost(it, host, p) } ?: throw OnvifException("Камера не дала адрес подписки на события")
                return Subscription(address, offset, user, password)
            } catch (e: OnvifException) { if (e.auth) throw e; last = e } catch (e: Exception) { last = e }
        }
        throw (last as? OnvifException) ?: OnvifException("Камера не ответила по ONVIF")
    }

    /** Событие: тема (как у камеры) и состояние (true — началось, false — кончилось, null — разовое). */
    data class Event(val topic: String, val state: Boolean?)

    /** Забрать накопившиеся события (камера держит запрос до [waitSeconds] с, если событий нет). */
    fun pull(sub: Subscription, waitSeconds: Int = 4): List<Event> {
        val root = call(sub.address, sub.user, sub.password, sub.offset,
            "<PullMessages xmlns=\"$EVENTS_NS\"><Timeout>PT${waitSeconds}S</Timeout><MessageLimit>32</MessageLimit></PullMessages>",
            action = "$EVENTS_NS/PullPointSubscription/PullMessagesRequest")
        return events(root)
    }

    /** Продлить подписку. */
    fun renew(sub: Subscription, ttlSeconds: Int = 120) {
        call(sub.address, sub.user, sub.password, sub.offset,
            "<Renew xmlns=\"$WSN_NS\"><TerminationTime>PT${ttlSeconds}S</TerminationTime></Renew>",
            action = "$WSN_NS/SubscriptionManager/RenewRequest")
    }

    fun unsubscribe(sub: Subscription) {
        runCatching { call(sub.address, sub.user, sub.password, sub.offset, "<Unsubscribe xmlns=\"$WSN_NS\"/>",
            action = "$WSN_NS/SubscriptionManager/UnsubscribeRequest") }
    }

    /** Разбор ответа PullMessages: тема и булево значение из SimpleItem (State, IsMotion, IsPeople, …). */
    fun events(root: Node): List<Event> = root.all("NotificationMessage").map { m ->
        val topic = m.find("Topic")?.text.orEmpty()
        val items = m.find("Data")?.all("SimpleItem").orEmpty()
        val state = items.firstNotNullOfOrNull { item -> item.attrs["Value"]?.lowercase()?.let { v ->
            when (v) { "true", "1" -> true; "false", "0" -> false; else -> null } } }
        Event(topic, state)
    }

    /** Тема события → тип: люди, машины, животные, звонок, движение (у разных производителей — свои названия тем). */
    fun typeOf(topic: String): CameraEventType? {
        val t = topic.lowercase()
        return when {
            listOf("people", "person", "human", "face").any { it in t } -> CameraEventType.PERSON
            listOf("vehicle", "car").any { it in t } -> CameraEventType.VEHICLE
            listOf("dog", "cat", "animal", "pet").any { it in t } -> CameraEventType.ANIMAL
            listOf("visitor", "doorbell").any { it in t } -> CameraEventType.RING
            "motion" in t -> CameraEventType.MOTION
            else -> null
        }
    }

    private fun options(media: String, user: String, password: String, offset: Long, profile: String): EncoderOptions? {
        val root = call(media, user, password, offset,
            "<GetVideoEncoderConfigurationOptions xmlns=\"$MEDIA_NS\"><ProfileToken>${escape(profile)}</ProfileToken></GetVideoEncoderConfigurationOptions>")
        return parseOptions(root)
    }

    /** Разбор ответа `GetVideoEncoderConfigurationOptions` (Media 1): H.264 — размеры и частота; без них — JPEG. */
    internal fun parseOptions(root: Node): EncoderOptions? {
        val options = root.find("Options") ?: return null
        val h264 = options.children.firstOrNull { it.name == "H264" }
        val codec = h264 ?: options.children.firstOrNull { it.name == "JPEG" } ?: return null
        val sizes = codec.all("ResolutionsAvailable").mapNotNull { r ->
            val w = r.find("Width")?.text?.toIntOrNull() ?: return@mapNotNull null
            val h = r.find("Height")?.text?.toIntOrNull() ?: return@mapNotNull null
            w to h
        }.distinct()
        val range = codec.find("FrameRateRange")
        val min = range?.find("Min")?.text?.toIntOrNull() ?: 0
        val max = range?.find("Max")?.text?.toIntOrNull() ?: 0
        if (sizes.isEmpty() && max == 0) return null
        return EncoderOptions(sizes, min, max, h264 != null)
    }

    /** Основной поток — наибольшее разрешение, субпоток — наименьшее (если поток один — он и то, и другое). */
    fun pick(streams: List<Stream>): Pair<Stream?, Stream?> {
        val sorted = streams.sortedByDescending { it.width.toLong() * it.height }
        val main = sorted.firstOrNull()
        val sub = sorted.lastOrNull()?.takeIf { it != main } ?: main
        return main to sub
    }

    /**
     * Часы камеры минус часы планшета, мс (точность — секунда); без пароля. null — камера не ответила или не сообщила
     * время. Нужно для подписи WS-Security и для диагностики (К6).
     */
    fun clock(host: String, port: Int): Long? =
        (if (port > 0) listOf(port) else PORTS).firstNotNullOfOrNull { runCatching { clockOffset("http://$host:$it/onvif/device_service") }.getOrNull() }

    private fun clockOffset(device: String): Long? {
        val (code, text) = transport(device, envelope(null, "<GetSystemDateAndTime xmlns=\"$DEVICE_NS\"/>"))
        if (code != 200) return null
        val utc = parse(text).find("UTCDateTime") ?: return null
        val date = utc.find("Date"); val time = utc.find("Time")
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(date?.find("Year")?.text?.toIntOrNull() ?: return null, (date.find("Month")?.text?.toIntOrNull() ?: return null) - 1,
                date.find("Day")?.text?.toIntOrNull() ?: return null, time?.find("Hour")?.text?.toIntOrNull() ?: 0,
                time?.find("Minute")?.text?.toIntOrNull() ?: 0, time?.find("Second")?.text?.toIntOrNull() ?: 0)
        }
        return cal.timeInMillis - now()
    }

    private fun call(url: String, user: String, password: String, offset: Long, body: String, action: String? = null): Node {
        // WS-Addressing (Action, To) — службе событий он нужен (PullPoint), остальным не мешает.
        val addressing = action?.let {
            "<a:Action s:mustUnderstand=\"1\">$it</a:Action><a:MessageID>urn:uuid:${java.util.UUID.randomUUID()}</a:MessageID>" +
                "<a:To s:mustUnderstand=\"1\">${escape(url)}</a:To>"
        }.orEmpty()
        val (code, text) = transport(url, envelope(addressing + security(user, password, offset), body))
        val root = runCatching { parse(text) }.getOrNull()
        val fault = root?.find("Fault")
        if (code == 401 || fault?.let { f -> f.texts().any { it.contains("NotAuthorized") || it.contains("ailedAuthentication") || it.contains("Sender not Authorized") } } == true) {
            throw OnvifException("Камера не приняла логин или пароль (ONVIF)", auth = true)
        }
        if (code !in 200..299 || root == null || fault != null) throw OnvifException("Камера ответила ошибкой ONVIF ($code)")
        return root
    }

    // ---- WS-Security ----

    internal fun security(user: String, password: String, offset: Long, nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now() + offset))
        val digest = digest(nonce, created, password)
        return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
            "<UsernameToken><Username>${escape(user)}</Username>" +
            "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</Password>" +
            "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" +
            Base64.encodeToString(nonce, Base64.NO_WRAP) + "</Nonce>" +
            "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created>" +
            "</UsernameToken></Security>"
    }

    /** PasswordDigest = Base64(SHA-1(nonce + created + password)). */
    internal fun digest(nonce: ByteArray, created: String, password: String): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray(Charsets.UTF_8)); sha.update(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
    }

    private fun envelope(header: String?, body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:a=\"http://www.w3.org/2005/08/addressing\">" +
            (header?.let { "<s:Header>$it</s:Header>" } ?: "") + "<s:Body>$body</s:Body></s:Envelope>"

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Ссылка без логина и пароля (их подставляет плеер из настроек плитки). */
    fun withoutCredentials(uri: String): String = uri.replace(Regex("^(rtsp://)[^/@]*@"), "$1")

    /**
     * Камеры за NAT и с «внутренним» адресом в ответе (0.0.0.0, другой IP) — адрес службы приводится к тому, по которому
     * камеру нашли.
     */
    private fun sameHost(xaddr: String, host: String, port: Int): String {
        val path = xaddr.substringAfter("://").substringAfter('/', "")
        return "http://$host:$port/$path"
    }

    // ---- Разбор XML (без учёта пространств имён) ----

    class Node(val name: String, val attrs: Map<String, String>) {
        val children = mutableListOf<Node>()
        var text = ""
        fun find(local: String): Node? = children.firstOrNull { it.name == local } ?: children.asSequence().mapNotNull { it.find(local) }.firstOrNull()
        fun all(local: String): List<Node> = children.flatMap { c -> if (c.name == local) listOf(c) else c.all(local) }
        fun texts(): List<String> = listOf(text) + children.flatMap { it.texts() }
    }

    fun parse(xml: String): Node {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(java.io.StringReader(xml))
        val root = Node("#root", emptyMap())
        val stack = ArrayDeque<Node>().apply { add(root) }
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    val node = Node(parser.name, attrs)
                    stack.last().children += node
                    stack.addLast(node)
                }
                XmlPullParser.TEXT -> stack.last().text += parser.text.trim()
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.END_DOCUMENT -> return root
            }
        }
    }
}
