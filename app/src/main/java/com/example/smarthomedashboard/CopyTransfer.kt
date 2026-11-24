package com.example.smarthomedashboard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.SocketFactory

/**
 * Код копирования (docs/setup-tz.md, часть 5): девять знаков из тех, что одинаково выглядят по-русски и по-английски, —
 * его можно набрать в любой раскладке. Из кода получается ключ; сам код по сети не передаётся никогда.
 */
internal object CopyCode {
    private const val ALPHABET = "ABCEHKMPTXY23456789"
    const val LENGTH = 9
    private const val GROUP = 3
    private val CYRILLIC = mapOf('А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'К' to 'K', 'М' to 'M', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X', 'У' to 'Y')

    fun create(random: SecureRandom = SecureRandom()) = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** «K7H-2MX-9AB». */
    fun pretty(code: String) = code.chunked(GROUP).joinToString("-")

    /** Набранное человеком → код; лишние знаки отброшены, русские буквы — в латинские. Не код — null. */
    fun parse(text: String): String? {
        val code = text.uppercase(java.util.Locale.ROOT).map { CYRILLIC[it] ?: it }.filter { it in ALPHABET }.joinToString("")
        val typed = text.count { it.isLetterOrDigit() }
        return code.takeIf { it.length == LENGTH && typed == LENGTH }
    }
}

/** Ключи из кода (PBKDF2-HMAC-SHA256) и шифрование AES-256-GCM: посторонний в сети не прочитает и не подменит настройки. */
internal object CopyCrypto {
    const val ROUNDS = 120_000
    private const val KEY = 32
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val HMAC = "HmacSHA256"

    class Keys(val auth: ByteArray, val data: ByteArray)

    fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC)); parts.forEach { update(it) }; doFinal()
    }

    /** Один блок PBKDF2 (32 байта) — своими руками: `PBKDF2WithHmacSHA256` есть только с Android 8. */
    internal fun pbkdf2(code: String, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), HMAC)) }
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out.copyOf(KEY)
    }

    fun keys(code: String, salt: ByteArray, rounds: Int): Keys {
        val base = pbkdf2(code, salt, rounds)
        return Keys(hmac(base, "auth".toByteArray()), hmac(base, "data".toByteArray()))
    }

    /** Доказательство, что код известен: сам код и ключ не передаются. */
    fun proof(keys: Keys, server: ByteArray, client: ByteArray) = hmac(keys.auth, server, client)

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun seal(keys: Keys, server: ByteArray, client: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = random(IV)
        return iv to cipher(Cipher.ENCRYPT_MODE, keys, iv, server, client).doFinal(plain)
    }

    /** Подмена или чужой ключ — [GeneralSecurityException]. */
    fun open(keys: Keys, server: ByteArray, client: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, keys, iv, server, client).doFinal(data)

    private fun cipher(mode: Int, keys: Keys, iv: ByteArray, server: ByteArray, client: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(keys.data, "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD(server); updateAAD(client)
    }

    fun text(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun bytes(text: String): ByteArray = try { Base64.decode(text, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { ByteArray(0) }
}

/** Что передаётся: файл настроек ([ConfigBackup] — без токена, паролей, ключей и PIN) и размер экрана старого планшета. */
internal class CopyPayload(val config: String, val screen: TileScale.Screen?, val name: String, val version: String) {
    fun toJson(): String = JSONObject().put("v", VERSION).put("name", name).put("app", version).put("config", config)
        .apply { screen?.let { put("screen", JSONObject().put("w", it.w).put("h", it.h).put("density", it.density.toDouble())) } }.toString()

    companion object {
        const val VERSION = 1

        fun of(context: Context, screen: TileScale.Screen): CopyPayload = CopyPayload(ConfigBackup.build(context).toString(), screen, deviceName(),
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" })

        fun deviceName() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

        fun parse(text: String): CopyPayload {
            val j = JSONObject(text)
            if (j.optInt("v") != VERSION) throw JSONException("Другая версия копирования")
            val s = j.optJSONObject("screen")?.let { TileScale.Screen(it.optInt("w"), it.optInt("h"), it.optDouble("density", 1.0).toFloat()) }
                ?.takeIf { it.w in 1..MAX_SIDE && it.h in 1..MAX_SIDE && it.density in 0.5f..8f }
            return CopyPayload(j.getString("config"), s, j.optString("name").take(MAX_NAME), j.optString("app").take(MAX_NAME))
        }

        private const val MAX_SIDE = 20_000
        internal const val MAX_NAME = 60
    }
}

/**
 * Старый планшет: одноразовая страница в домашней сети, пока открыт экран с кодом. Настройки отдаются только тому, кто доказал,
 * что знает код ([CopyCrypto.proof]), и зашифрованными; пять неверных кодов, удачная передача или [LIFETIME_MS] — страница
 * закрыта. [onEvent] — с рабочего потока.
 */
internal class CopyServer(
    address: InetAddress, val code: String, private val payload: String, private val name: String,
    private val lifetimeMs: Long = LIFETIME_MS, private val onEvent: (Event, Int) -> Unit
) : Closeable {
    enum class Event { WRONG, SENT, LOCKED, CLOSED }

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, 0)); soTimeout = ACCEPT_MS }
    private val pool = Executors.newFixedThreadPool(WORKERS) { Thread(it, "copy-server").apply { isDaemon = true } }
    private val admission = HookAdmission(MAX_PER_SECOND, MAX_CONCURRENT)
    private val salt = CopyCrypto.random(NONCE)
    private val nonce = CopyCrypto.random(NONCE)
    /** Ключ считается доли секунды — один раз и не в главном потоке. */
    private val keys by lazy { CopyCrypto.keys(code, salt, CopyCrypto.ROUNDS) }
    private val left = AtomicInteger(ATTEMPTS)
    private val sent = AtomicBoolean(false)
    @Volatile private var finished = false

    val port: Int get() = server.localPort
    val host: String = "${address.hostAddress}:$port"
    /** Что в QR: код — после «#», браузер его в сеть не отправляет. */
    val link = "http://$host/#$code"

    fun start() {
        val until = System.nanoTime() + lifetimeMs * 1_000_000
        Thread({
            keys
            while (!finished && System.nanoTime() < until) {
                val client = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                val from = client.inetAddress
                val neighbour = from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress
                if (!neighbour || !admission.enter(from)) { quietly(client); continue }
                try { pool.execute { try { serve(client) } finally { admission.leave(from) } } } catch (_: RuntimeException) { admission.leave(from); quietly(client) }
            }
            close()
        }, "copy-accept").apply { isDaemon = true; start() }
    }

    override fun close() {
        if (finished) return
        finished = true
        try { server.close() } catch (_: IOException) {}
        pool.shutdown()
        onEvent(Event.CLOSED, 0)
    }

    private fun quietly(s: Socket) = try { s.close() } catch (_: IOException) {}

    private fun serve(client: Socket) = client.use { s ->
        try {
            s.soTimeout = READ_MS
            val (line, body) = request(s.getInputStream()) ?: return@use
            when (line) {
                "GET /" -> page(s)
                "GET $HELLO" -> json(s, OK, JSONObject().put("v", CopyPayload.VERSION).put("name", name).put("salt", CopyCrypto.text(salt))
                    .put("nonce", CopyCrypto.text(nonce)).put("rounds", CopyCrypto.ROUNDS))
                "POST $TAKE" -> take(s, body)
                else -> json(s, NOT_FOUND, JSONObject())
            }
        } catch (_: IOException) {} catch (_: JSONException) {}
    }

    private fun take(s: Socket, body: String) {
        if (sent.get() || left.get() <= 0) { json(s, GONE, JSONObject()); return }
        val j = JSONObject(body)
        val client = CopyCrypto.bytes(j.optString("nonce"))
        val proof = CopyCrypto.bytes(j.optString("proof"))
        if (client.size != NONCE || !CopyCrypto.same(proof, CopyCrypto.proof(keys, nonce, client))) {
            val rest = left.decrementAndGet().coerceAtLeast(0)
            json(s, DENIED, JSONObject().put("left", rest))
            onEvent(if (rest > 0) Event.WRONG else Event.LOCKED, rest)
            if (rest <= 0) close()
            return
        }
        // Второй раз не отдаём; оборвалась сама передача — можно повторить.
        if (!sent.compareAndSet(false, true)) { json(s, GONE, JSONObject()); return }
        try {
            val (iv, data) = CopyCrypto.seal(keys, nonce, client, payload.toByteArray(Charsets.UTF_8))
            json(s, OK, JSONObject().put("iv", CopyCrypto.text(iv)).put("data", CopyCrypto.text(data)))
            Log.i(TAG, "Настройки переданы другому планшету")
            onEvent(Event.SENT, 0)
            close()
        } catch (e: IOException) {
            sent.set(false)
            throw e
        }
    }

    /** Страница из QR: открывает панель на новом планшете. Код остаётся в браузере (часть адреса после «#»). */
    private fun page(s: Socket) = answer(s, OK, "text/html; charset=utf-8", ("<!doctype html><html lang=ru><meta charset=utf-8>" +
        "<meta name=viewport content=\"width=device-width\"><title>Панель</title><body style=\"background:#10181f;color:#e8eef2;" +
        "font:18px/1.5 sans-serif;padding:32px\"><p>Откройте эту страницу на новом планшете, где установлена панель.</p>" +
        "<p><a id=a style=\"display:inline-block;padding:14px 24px;border-radius:10px;background:#22c3dc;color:#07222a;text-decoration:none\">" +
        "Открыть в панели</a></p><p>Или введите в панели код со старого планшета.</p><script>var c=location.hash.slice(1);" +
        "if(/^[A-Z0-9]{9}$/.test(c))document.getElementById('a').href='intent://copy/$host/'+c+'#Intent;scheme=$SCHEME;" +
        "package=com.example.smarthomedashboard;end'</script></body></html>").toByteArray())

    private fun json(s: Socket, code: Int, body: JSONObject) = answer(s, code, "application/json", body.toString().toByteArray())

    private fun answer(s: Socket, code: Int, type: String, body: ByteArray) = s.getOutputStream().run {
        write(("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        write(body)
        flush()
    }

    /** «МЕТОД /путь» и тело; больше [MAX_HEAD] / [MAX_BODY] — обрыв. */
    private fun request(input: InputStream): Pair<String, String>? {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0 || head.size() >= MAX_HEAD) return null
            head.write(b)
            tail = (tail shl 8) or b
        }
        val lines = head.toString(Charsets.UTF_8.name()).lines()
        val first = lines.first().split(' ').takeIf { it.size >= 2 } ?: return null
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return null; read += n }
        return "${first[0]} ${first[1].substringBefore('?')}" to String(body, Charsets.UTF_8)
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        const val SCHEME = "smarthomedashboard"
        const val HELLO = "/copy/v1/hello"
        const val TAKE = "/copy/v1/take"
        internal const val ATTEMPTS = 5
        internal const val NONCE = 16
        private const val TAG = "CopyServer"
        private const val ACCEPT_MS = 1_000
        private const val READ_MS = 5_000
        private const val WORKERS = 3
        private const val MAX_PER_SECOND = 10
        private const val MAX_CONCURRENT = 3
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 4 * 1024
        internal const val OK = 200
        internal const val DENIED = 403
        internal const val NOT_FOUND = 404
        internal const val GONE = 410
    }
}

/** Новый планшет: найти старый по адресу, доказать код, расшифровать. Блокирующее — рабочий поток. */
internal class CopyClient(private val host: String, sockets: SocketFactory? = null) {
    /** Не вышло; [left] — сколько попыток осталось (−1 — не о коде). Текст показывается человеку. */
    class Failed(message: String, val left: Int = -1) : IOException(message)

    private val http = OkHttpClient.Builder().connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(READ_S, TimeUnit.SECONDS)
        .followRedirects(false).apply { if (sockets != null) socketFactory(sockets) }.build()

    class Hello(val name: String, val salt: ByteArray, val nonce: ByteArray, val rounds: Int)

    fun hello(): Hello {
        val (code, j) = call(Request.Builder().url("http://$host${CopyServer.HELLO}").build())
        val salt = CopyCrypto.bytes(j.optString("salt"))
        val nonce = CopyCrypto.bytes(j.optString("nonce"))
        val rounds = j.optInt("rounds")
        if (code != CopyServer.OK || salt.size != CopyServer.NONCE || nonce.size != CopyServer.NONCE) throw Failed("По этому адресу отвечает не панель")
        if (j.optInt("v") != CopyPayload.VERSION) throw Failed("На старом планшете другая версия панели — обновите обе")
        // Чужой сервер не заставит считать слишком дешёвый или бесконечный ключ.
        if (rounds !in MIN_ROUNDS..MAX_ROUNDS) throw Failed("По этому адресу отвечает не панель")
        return Hello(j.optString("name").take(CopyPayload.MAX_NAME), salt, nonce, rounds)
    }

    fun take(code: String): CopyPayload {
        val hello = hello()
        val keys = CopyCrypto.keys(code, hello.salt, hello.rounds)
        val mine = CopyCrypto.random(CopyServer.NONCE)
        val body = JSONObject().put("nonce", CopyCrypto.text(mine)).put("proof", CopyCrypto.text(CopyCrypto.proof(keys, hello.nonce, mine)))
        val (status, j) = call(Request.Builder().url("http://$host${CopyServer.TAKE}").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        when (status) {
            CopyServer.OK -> {}
            CopyServer.DENIED -> j.optInt("left", 0).let { left -> throw Failed(if (left > 0) "Код не подошёл, осталось попыток: $left" else LOCKED, left) }
            CopyServer.GONE -> throw Failed("Копирование на старом планшете уже закончено. Откройте его там заново")
            else -> throw Failed("Старый планшет ответил ошибкой")
        }
        return try {
            CopyPayload.parse(String(CopyCrypto.open(keys, hello.nonce, mine, CopyCrypto.bytes(j.optString("iv")), CopyCrypto.bytes(j.optString("data"))), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) { throw Failed("Настройки пришли повреждёнными — попробуйте ещё раз")
        } catch (_: JSONException) { throw Failed("На старом планшете другая версия панели — обновите обе") }
    }

    private fun call(request: Request): Pair<Int, JSONObject> = try {
        http.newCall(request).execute().use { response ->
            val source = response.body?.source()
            source?.request(MAX_BYTES + 1)
            if (source != null && source.buffer.size > MAX_BYTES) throw Failed("Старый планшет ответил ошибкой")
            val text = source?.buffer?.readUtf8().orEmpty()
            response.code to (try { JSONObject(text) } catch (_: JSONException) { JSONObject() })
        }
    } catch (e: Failed) { throw e
    } catch (_: IllegalArgumentException) { throw Failed("Адрес указан неверно")
    } catch (_: IOException) { throw Failed("Старый планшет не отвечает. Проверьте, что оба планшета в одной сети и экран с кодом открыт") }

    private companion object {
        const val LOCKED = "Слишком много неверных кодов. На старом планшете откройте копирование заново"
        const val CONNECT_S = 5L
        const val READ_S = 20L
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MIN_ROUNDS = 100_000
        const val MAX_ROUNDS = 1_000_000
    }
}

/** Старый планшет виден новому в сети (mDNS), пока открыт экран с кодом. */
internal class CopyAdvert(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(port: Int, name: String) {
        if (listener != null) return
        // На части планшетов (до Android 13) запросы mDNS без блокировки не слышны.
        lock = try {
            app.getSystemService(WifiManager::class.java)?.createMulticastLock("copy-advert")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: RuntimeException) { null }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "Планшет не виден в сети (код $code)") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
        }
        try {
            // Имя у каждого показа своё: прежнюю запись (с прежним портом) соседи ещё пару минут помнят. Человеку имя не показывается.
            val tag = "%04x".format(SecureRandom().nextInt(0x10000))
            nsd?.registerService(NsdServiceInfo().apply { serviceName = "${name.take(MAX_NAME)} $tag"; serviceType = TYPE; setPort(port) }, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: RuntimeException) { Log.w(TAG, "Планшет не виден в сети: ${e.javaClass.simpleName}") }
    }

    fun stop() {
        listener?.let { runCatching { nsd?.unregisterService(it) } }
        listener = null
        runCatching { lock?.release() }
        lock = null
    }

    companion object {
        const val TYPE = "_smarthomepanel._tcp"
        private const val MAX_NAME = 40
        private const val TAG = "CopyAdvert"
    }
}
