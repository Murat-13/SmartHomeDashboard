package com.example.smarthomedashboard

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Проход без Home Assistant (docs/gate-tz.md, этап 4): реле или привод Shelly Gen2 по их HTTP RPC в локальной сети
 * или свой HTTP-запрос (у многих контроллеров ворот и замков есть такой). Только сетевые вызовы — блокирующие,
 * вызывать с фонового потока ([GateHub] держит для этого свой поток).
 *
 * Пароль Shelly — пользователь `admin`, HTTP Digest (SHA-256, у старых прошивок MD5); хранится зашифрованным
 * ([CameraPasswords], ключ `gate_<id>`), в настройки и копии не попадает.
 */
internal object GateDirect {
    private const val USER = "admin"
    private const val PING_MS = 3_000
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Каждое обращение — новое соединение: простые устройства (и стенд) закрывают его после ответа, а
            // повторное использование закрытого давало IOException на каждом опросе (найдено на планшете 22.11).
            // Повтор запроса не включаем: команда («импульс») не должна уйти дважды.
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /** Ключ пароля прохода в зашифрованном хранилище. */
    fun secretKey(gateId: String) = "gate_$gateId"

    /** Итог команды: принята / отказ (с причиной для кнопки). */
    data class Result(val ok: Boolean, val detail: String = "")

    /** Что ответило устройство на «Найти устройство». */
    data class Info(val model: String, val id: String, val gen: Int, val authNeeded: Boolean, val authOk: Boolean)

    /** Адрес вида `192.168.1.50` или `http://host:8080` → `http://host:port` без хвостового `/`. */
    fun base(host: String): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
    }

    /** Команда Shelly для действия (метод RPC и параметры) — null, если такого действия у устройства нет. */
    fun shellyCall(gate: Gate, action: Gate.Action): Pair<String, Map<String, String>>? {
        val d = gate.direct ?: return null
        val id = mapOf("id" to d.channel.toString())
        return when (gate.kind) {
            Gate.Kind.RELAY, Gate.Kind.STEP -> if (action == Gate.Action.OPEN || action == Gate.Action.STEP) {
                // Импульс: реле само выключится через pulseSeconds (toggle_after) — даже если панель пропадёт.
                "Switch.Set" to id + mapOf("on" to "true", "toggle_after" to d.pulseSeconds.coerceIn(Gate.Direct.PULSE_RANGE).toString())
            } else null
            Gate.Kind.COVER -> when (action) {
                Gate.Action.OPEN -> "Cover.Open" to id
                Gate.Action.CLOSE -> "Cover.Close" to id
                Gate.Action.STOP -> "Cover.Stop" to id
                else -> null
            }
            else -> null
        }
    }

    fun send(gate: Gate, action: Gate.Action, password: String?): Result {
        val d = gate.direct ?: return Result(false, "связь напрямую не настроена")
        return try {
            when (d.protocol) {
                Gate.Direct.Protocol.SHELLY -> {
                    val (method, params) = shellyCall(gate, action) ?: return Result(false, "устройство так не умеет")
                    rpc(d.host, method, params, password).use { describe(it) }
                }
                Gate.Direct.Protocol.HTTP -> {
                    val url = if (action == Gate.Action.CLOSE) d.closeUrl else d.openUrl
                    if (url.isBlank()) return Result(false, "адрес команды не задан")
                    val request = Request.Builder().url(url).apply { if (d.post) post(ByteArray(0).toRequestBody()) }.build()
                    call(request, password).use { describe(it) }
                }
            }
        } catch (e: IOException) {
            Result(false, "устройство не отвечает")
        } catch (e: IllegalArgumentException) {
            Result(false, "неверный адрес")
        }
    }

    private fun describe(response: Response): Result = when {
        response.isSuccessful -> Result(true)
        response.code == 401 -> Result(false, "неверный пароль устройства")
        else -> Result(false, "устройство отказало (${response.code})")
    }

    /** Опрос положения. null — ответа нет (сеть, пароль); тогда [GateHub] после двух неудач подряд пишет «не отвечает». */
    fun poll(gate: Gate, password: String?): DirectState? {
        val d = gate.direct ?: return null
        if (d.protocol != Gate.Direct.Protocol.SHELLY) return pingHttp(d)
        return try {
            val id = mapOf("id" to d.channel.toString())
            var state = when (gate.kind) {
                Gate.Kind.COVER -> json(rpc(d.host, "Cover.GetStatus", id, password))?.let {
                    DirectState(true, cover = it.optString("state").ifBlank { null },
                        percent = if (it.has("current_pos") && !it.isNull("current_pos")) it.optInt("current_pos") else null)
                }
                else -> json(rpc(d.host, "Switch.GetStatus", id, password))?.let {
                    DirectState(true, switchOn = if (it.has("output")) it.optBoolean("output") else null)
                }
            } ?: return null
            if (d.input >= 0) {
                val input = json(rpc(d.host, "Input.GetStatus", mapOf("id" to d.input.toString()), password)) ?: return null
                if (input.has("state") && !input.isNull("state")) state = state.copy(input = input.optBoolean("state"))
            }
            state
        } catch (e: IOException) {
            android.util.Log.d("Gates", "Опрос: ${e.javaClass.simpleName}"); null
        } catch (e: IllegalArgumentException) {
            android.util.Log.d("Gates", "Опрос: неверный адрес"); null
        }
    }

    /**
     * У своего HTTP положения нет — только «на связи ли»: соединение с устройством открывается и сразу закрывается, без
     * единого запроса. К адресу команды обращаться нельзя: простые контроллеры выполняют её и на HEAD, и калитка открылась бы
     * сама (аудит 25.11.2026, GATE-01).
     */
    private fun pingHttp(d: Gate.Direct): DirectState? = try {
        val url = d.openUrl.toHttpUrl()
        Socket().use { it.connect(InetSocketAddress(url.host, url.port), PING_MS) }
        DirectState(true)
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** «Найти устройство» в редакторе: что за Shelly и подходит ли пароль. null — не ответило как Shelly Gen2. */
    fun probe(host: String, password: String?): Info? = try {
        val info = http.newCall(Request.Builder().url(base(host) + "/shelly").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let(::JSONObject)
        } ?: return null
        val gen = info.optInt("gen", 0)
        val auth = info.optBoolean("auth_en", false)
        // Пароль подошёл — значит, устройство не ответило «401» (сам ответ на чтение сведений не важен).
        val authOk = !auth || rpc(host, "Shelly.GetDeviceInfo", emptyMap(), password).use { it.code != 401 }
        Info(info.optString("model"), info.optString("id"), gen, auth, authOk)
    } catch (e: IOException) {
        null
    } catch (e: org.json.JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun json(response: Response): JSONObject? = response.use { r ->
        if (!r.isSuccessful) { android.util.Log.d("Gates", "Опрос: ответ ${r.code}"); return@use null }
        r.body?.string()?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun rpc(host: String, method: String, params: Map<String, String>, password: String?): Response {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = base(host) + "/rpc/" + method + if (query.isEmpty()) "" else "?$query"
        return call(Request.Builder().url(url).build(), password)
    }

    /** Запрос; на 401 с Digest — повтор с ответом на вызов (пароль не уходит по сети открытым). */
    private fun call(request: Request, password: String?): Response {
        val first = http.newCall(request).execute()
        // Логин и пароль в адресе своего запроса (http://логин:пароль@…) важнее сохранённого пароля Shelly.
        val user = request.url.username.ifEmpty { USER }
        val secret = request.url.password.ifEmpty { password.orEmpty() }
        if (first.code != 401 || secret.isEmpty()) return first
        val challenge = first.header("WWW-Authenticate").orEmpty()
        first.close()
        val header = if (challenge.startsWith("Basic", ignoreCase = true)) okhttp3.Credentials.basic(user, secret)
        else Digest.authorization(challenge, request.method, request.url.encodedPath +
            (request.url.encodedQuery?.let { "?$it" } ?: ""), user, secret) ?: return http.newCall(request).execute()
        return http.newCall(request.newBuilder().header("Authorization", header).build()).execute()
    }

    /** HTTP Digest (RFC 7616): SHA-256 или MD5, qop=auth. */
    internal object Digest {
        private val random = SecureRandom()

        fun fields(challenge: String): Map<String, String> {
            if (!challenge.startsWith("Digest", ignoreCase = true)) return emptyMap()
            return Regex("""(\w+)=(?:"([^"]*)"|([^\s,]+))""").findAll(challenge.substring(6))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        }

        fun authorization(challenge: String, method: String, uri: String, user: String, password: String,
                          cnonce: String = newCnonce()): String? {
            val f = fields(challenge)
            val realm = f["realm"] ?: return null
            val nonce = f["nonce"] ?: return null
            val algorithm = f["algorithm"] ?: "MD5"
            val digest = when (algorithm.uppercase()) { "SHA-256" -> "SHA-256"; "MD5" -> "MD5"; else -> return null }
            fun h(s: String) = MessageDigest.getInstance(digest).digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val ha1 = h("$user:$realm:$password")
            val ha2 = h("$method:$uri")
            val qop = f["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            val nc = "00000001"
            val response = if (qop != null) h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else h("$ha1:$nonce:$ha2")
            return buildString {
                append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", algorithm=$algorithm, response=\"$response\"")
                if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                f["opaque"]?.let { append(", opaque=\"$it\"") }
            }
        }

        private fun newCnonce() = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
