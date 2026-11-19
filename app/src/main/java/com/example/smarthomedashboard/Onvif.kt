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
    /** Поток профиля: ссылка RTSP и разрешение. */
    data class Stream(val uri: String, val width: Int, val height: Int, val encoding: String, val profile: String)

    data class Info(
        val manufacturer: String,
        val model: String,
        val firmware: String,
        val port: Int,
        val main: Stream?,
        val sub: Stream?,
        val profiles: List<Stream>,
        /** Адрес службы событий (часть 2: подписка PullPoint). */
        val eventsAddress: String?
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
        val offset = runCatching { cameraClockOffset(device) }.getOrDefault(0L)
        val info = call(device, user, password, offset, "<GetDeviceInformation xmlns=\"$DEVICE_NS\"/>")
        val caps = call(device, user, password, offset, "<GetCapabilities xmlns=\"$DEVICE_NS\"><Category>All</Category></GetCapabilities>")
        val media = caps.find("Media")?.find("XAddr")?.text?.takeIf { it.startsWith("http") }?.let { sameHost(it, host, port) } ?: device
        val events = caps.find("Events")?.find("XAddr")?.text?.takeIf { it.startsWith("http") }?.let { sameHost(it, host, port) }
        val profiles = call(media, user, password, offset, "<GetProfiles xmlns=\"$MEDIA_NS\"/>").all("Profiles").mapNotNull { p ->
            val token = p.attrs["token"] ?: return@mapNotNull null
            val video = p.find("VideoEncoderConfiguration")
            val w = video?.find("Width")?.text?.toIntOrNull() ?: 0
            val h = video?.find("Height")?.text?.toIntOrNull() ?: 0
            Triple(token, w to h, video?.find("Encoding")?.text.orEmpty())
        }
        if (profiles.isEmpty()) throw OnvifException("Камера не сообщила видеопотоки (профили ONVIF)")
        val streams = profiles.mapNotNull { (token, size, encoding) ->
            val body = "<GetStreamUri xmlns=\"$MEDIA_NS\"><StreamSetup><Stream xmlns=\"$SCHEMA_NS\">RTP-Unicast</Stream>" +
                "<Transport xmlns=\"$SCHEMA_NS\"><Protocol>RTSP</Protocol></Transport></StreamSetup>" +
                "<ProfileToken>${escape(token)}</ProfileToken></GetStreamUri>"
            val uri = runCatching { call(media, user, password, offset, body).find("Uri")?.text }.getOrNull()
                ?.takeIf { it.startsWith("rtsp://") } ?: return@mapNotNull null
            Stream(withoutCredentials(uri), size.first, size.second, encoding, token)
        }.distinctBy { it.uri }
        if (streams.isEmpty()) throw OnvifException("Камера не дала ссылки RTSP — введите их вручную")
        val (main, sub) = pick(streams)
        return Info(info.find("Manufacturer")?.text.orEmpty(), info.find("Model")?.text.orEmpty(),
            info.find("FirmwareVersion")?.text.orEmpty(), port, main, sub, streams, events)
    }

    /** Основной поток — наибольшее разрешение, субпоток — наименьшее (если поток один — он и то, и другое). */
    fun pick(streams: List<Stream>): Pair<Stream?, Stream?> {
        val sorted = streams.sortedByDescending { it.width.toLong() * it.height }
        val main = sorted.firstOrNull()
        val sub = sorted.lastOrNull()?.takeIf { it != main } ?: main
        return main to sub
    }

    private fun cameraClockOffset(device: String): Long {
        val (code, text) = transport(device, envelope(null, "<GetSystemDateAndTime xmlns=\"$DEVICE_NS\"/>"))
        if (code != 200) return 0
        val utc = parse(text).find("UTCDateTime") ?: return 0
        val date = utc.find("Date"); val time = utc.find("Time")
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(date?.find("Year")?.text?.toIntOrNull() ?: return 0, (date.find("Month")?.text?.toIntOrNull() ?: return 0) - 1,
                date.find("Day")?.text?.toIntOrNull() ?: return 0, time?.find("Hour")?.text?.toIntOrNull() ?: 0,
                time?.find("Minute")?.text?.toIntOrNull() ?: 0, time?.find("Second")?.text?.toIntOrNull() ?: 0)
        }
        return cal.timeInMillis - now()
    }

    private fun call(url: String, user: String, password: String, offset: Long, body: String): Node {
        val (code, text) = transport(url, envelope(security(user, password, offset), body))
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
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
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
