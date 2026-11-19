package com.example.smarthomedashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Прямые события с камер (docs/direct-events-tz.md): типы событий, драйверы производителей и приёмник вебхуков
 * на планшете. Управление и связь со звонком — [DirectEvents].
 */
internal enum class CameraEventType { RING, PERSON, VEHICLE, ANIMAL, MOTION }

internal class CameraException(message: String, val auth: Boolean = false) : IOException(message)

/** Драйвер производителя: всё сетевое — с фонового потока. */
internal interface CameraEventDriver {
    data class Info(val model: String, val firmware: String)
    enum class Health { OK, MOVED, MISSING }

    fun detect(): Info
    /** Прописать вебхук [url] (свой слот ищется по [secret]); номер слота. */
    fun install(url: String, secret: String): Int
    fun verify(url: String, secret: String): Health
    fun uninstall(secret: String)
    /** События из тела вебхука; неизвестное — пусто. */
    fun parse(body: String): List<CameraEventType>
    /** Запасной опрос: кнопка звонка нажата сейчас. */
    fun ringActive(): Boolean
    fun close()
}

/**
 * Reolink (HTTP API `/cgi-bin/api.cgi`, проверено на Video Doorbell PoE v3.0.0.6460): 4 слота вебхука,
 * событие — `POST` JSON `{"alarm":{"type":"VISITOR"|"PEOPLE"|…}}`. Чужие слоты не трогаются.
 */
internal class ReolinkDriver(
    private val base: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient
) : CameraEventDriver {
    private var token: String? = null

    override fun detect(): CameraEventDriver.Info {
        val info = call("GetDevInfo", JSONObject()).optJSONObject("DevInfo") ?: JSONObject()
        return CameraEventDriver.Info(info.optString("model").ifBlank { "Reolink" }, info.optString("firmVer"))
    }

    override fun install(url: String, secret: String): Int {
        val hooks = hooks()
        val slot = hooks.firstOrNull { secret in it.optString("hookUrl") } ?: hooks.firstOrNull { it.optString("hookUrl").isBlank() }
            ?: throw CameraException("Все ${hooks.size} слота вебхука в камере заняты")
        val index = slot.optInt("index")
        write(index, url, enabled = true)
        val back = hooks().firstOrNull { it.optInt("index") == index }
        if (back?.optString("hookUrl") != url || back.optInt("indexEnable") != 1) throw CameraException("Камера не сохранила вебхук")
        return index
    }

    override fun verify(url: String, secret: String): CameraEventDriver.Health {
        val own = hooks().firstOrNull { secret in it.optString("hookUrl") } ?: return CameraEventDriver.Health.MISSING
        return if (own.optString("hookUrl") == url && own.optInt("indexEnable") == 1) CameraEventDriver.Health.OK
            else CameraEventDriver.Health.MOVED
    }

    override fun uninstall(secret: String) {
        hooks().filter { secret in it.optString("hookUrl") }.forEach { write(it.optInt("index"), "", enabled = false) }
    }

    override fun parse(body: String): List<CameraEventType> {
        val type = try { JSONObject(body).optJSONObject("alarm")?.optString("type") } catch (_: Exception) { null } ?: return emptyList()
        val key = type.uppercase(Locale.ROOT).removePrefix("AI_")
        val event = TYPES[key]
        when {
            event != null -> Unit
            key == "TEST" -> android.util.Log.i(TAG, "Тест вебхука из настроек камеры")
            else -> android.util.Log.i(TAG, "Незнакомый тип события камеры: «${type.take(40)}» — пропущен")
        }
        return listOfNotNull(event)
    }

    override fun ringActive(): Boolean =
        call("GetEvents", JSONObject().put("channel", 0)).optJSONObject("visitor")?.optInt("alarm_state") == 1

    /**
     * Прожектор камеры (docs/camera-tz.md, часть 3): включён ли; null — у модели прожектора нет (камера отказала
     * в GetWhiteLed).
     */
    fun floodlight(): Boolean? = try {
        call("GetWhiteLed", JSONObject().put("channel", 0)).optJSONObject("WhiteLed")?.let { it.optInt("state", 0) == 1 }
    } catch (e: CameraException) { if (e.auth) throw e; null }

    fun setFloodlight(on: Boolean) {
        call("SetWhiteLed", JSONObject().put("WhiteLed", JSONObject().put("channel", 0).put("state", if (on) 1 else 0)))
    }

    override fun close() {
        if (token == null) return
        try { call("Logout", JSONObject(), retry = false) } catch (_: Exception) {}
        token = null
    }

    private fun hooks(): List<JSONObject> {
        val array = call("GetWebHook", JSONObject().put("channel", 0)).optJSONArray("WebHook") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** Пустой слот у Reolink — `hookUrl ""`, `indexEnable -1` (так он выглядит с завода). */
    private fun write(index: Int, url: String, enabled: Boolean) {
        call("SetWebHook", JSONObject().put("WebHook", JSONObject().put("channel", 0).put("index", index).put("hookUrl", url)
            .put("bCustom", 0).put("hookBody", "").put("indexEnable", if (enabled) 1 else -1)))
    }

    private fun login() {
        val reply = post("Login", JSONObject().put("User", JSONObject().put("Version", "0").put("userName", user).put("password", password)))
        if (reply.optInt("code", -1) != 0) throw CameraException("Камера не приняла логин или пароль", auth = true)
        token = reply.optJSONObject("value")?.optJSONObject("Token")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw CameraException("Камера не выдала сессию")
    }

    private fun call(cmd: String, param: JSONObject, retry: Boolean = true): JSONObject {
        if (token == null) login()
        val reply = post(cmd, param)
        if (reply.optInt("code", -1) == 0) return reply.optJSONObject("value") ?: JSONObject()
        val error = reply.optJSONObject("error")
        // Сессия истекла или камера перезагрузилась — один повторный вход.
        if (retry && error?.optInt("rspCode") == LOGIN_FIRST) { token = null; return call(cmd, param, retry = false) }
        throw CameraException("$cmd: ${error?.optString("detail")?.ifBlank { null } ?: "отказ камеры"}")
    }

    private fun post(cmd: String, param: JSONObject): JSONObject {
        val url = "$base/cgi-bin/api.cgi?cmd=$cmd" + (token?.let { "&token=$it" } ?: "")
        val body = JSONArray().put(JSONObject().put("cmd", cmd).put("action", 0).put("param", param)).toString()
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CameraException("$cmd: HTTP ${response.code}")
            return JSONArray(response.body?.string().orEmpty()).getJSONObject(0)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val LOGIN_FIRST = -6
        private const val TAG = "DirectEvents"
        /**
         * Типы `alarm.type` вебхука Reolink. Прошивка v3.0.0.6460 звонка владельца шлёт `PEOPLE`, `VISITOR`; по отзывам
         * (форум Node-RED, 2024) другие прошивки — `AI_PEOPLE`, `AI_VEHICLE`, а также `VEHICLE`, `MD`, `TEST`. Приставка
         * `AI_` снимается; животных в вебхуке никто не видел — понимаем возможные варианты.
         */
        private val TYPES = mapOf(
            "VISITOR" to CameraEventType.RING, "PEOPLE" to CameraEventType.PERSON, "PERSON" to CameraEventType.PERSON,
            "VEHICLE" to CameraEventType.VEHICLE, "DOG_CAT" to CameraEventType.ANIMAL, "ANIMAL" to CameraEventType.ANIMAL,
            "PET" to CameraEventType.ANIMAL, "MD" to CameraEventType.MOTION, "MOTION" to CameraEventType.MOTION
        )

        /**
         * HTTPS камеры с самоподписанным сертификатом: доверие при первом подключении — отпечаток запоминается
         * ([onPin]) и дальше обязан совпадать; имя хоста — только адрес этой камеры.
         */
        fun client(host: String, pin: String?, onPin: (String) -> Unit): OkHttpClient {
            val trust = PinnedTrust(pin, onPin)
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory, trust)
                .hostnameVerifier { name, _ -> name.equals(host, ignoreCase = true) }
                .build()
        }
    }
}

/** Отпечаток сертификата камеры: первый запоминается, следующие должны совпасть (камеру не подменили). */
internal class PinnedTrust(@Volatile private var pin: String?, private val onPin: (String) -> Unit) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Камера не прислала сертификат")
        val print = fingerprint(leaf.encoded)
        val known = pin
        if (known == null) { pin = print; onPin(print); return }
        if (known != print) throw CertificateException("Сертификат камеры изменился")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Клиентские сертификаты не принимаются")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Приёмник вебхуков на планшете: только `POST`, заголовки ≤ 8 КБ, тело ≤ 16 КБ, не больше [MAX_PER_SECOND] запросов
 * в секунду с одного адреса. [route] решает, принять ли запрос (секрет пути и адрес камеры), и вызывается на потоке
 * приёмника; ответ камере — сразу после него.
 */
internal class CameraHookServer(
    private val port: Int,
    private val route: (path: String, from: InetAddress, body: String) -> Boolean
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "camera-hook").apply { isDaemon = true } }
    private val recent = HashMap<InetAddress, ArrayDeque<Long>>()

    val running get() = server?.isClosed == false
    val localPort get() = server?.localPort ?: port

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                try { pool.execute { serve(client) } } catch (_: Exception) { client.close() }
            }
        }, "camera-hook-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        pool.shutdownNow()
    }

    private fun serve(client: Socket) = client.use { s ->
        try {
            s.soTimeout = 5_000
            val from = s.inetAddress
            if (!allow(from)) return@use
            val input = s.getInputStream()
            val head = readHead(input) ?: return@use
            val lines = head.split("\r\n")
            val (method, path) = lines.first().split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val length = lines.drop(1).firstOrNull { it.substringBefore(':').trim().equals("content-length", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val ok = method == "POST" && length in 0..MAX_BODY &&
                route(path, from, String(readBody(input, length), Charsets.UTF_8))
            val status = if (ok) "200 OK" else "404 Not Found"
            s.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun allow(from: InetAddress): Boolean {
        val now = System.nanoTime() / 1_000_000
        val times = recent.getOrPut(from) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 1_000) times.removeFirst()
        if (times.size >= MAX_PER_SECOND) return false
        times.addLast(now)
        if (recent.size > 64) recent.keys.retainAll { it == from }
        return true
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0 || out.size() >= MAX_HEAD) return null
            out.write(b)
            tail = (tail shl 8) or b
        }
        return out.toString(Charsets.UTF_8.name()).trimEnd()
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) throw IOException("Тело оборвалось")
            read += n
        }
        return out
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 16 * 1024
        private const val MAX_PER_SECOND = 20
    }
}
