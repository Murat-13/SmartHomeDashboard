package com.example.smarthomedashboard

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
    const val WRONG_PASSWORD = "неверный пароль устройства"
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Один запрос — одна команда (аудит 25.11.2026, AUD-01): за перенаправлением не идём (контроллер, ответивший им на команду,
            // получил бы её ещё раз — до 21 раза в петле) и «занят, повторите» (503 с Retry-After) сами не повторяем.
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain -> chain.proceed(chain.request()).let { if (it.code == 503) it.newBuilder().removeHeader("Retry-After").build() else it } }
            // Каждое обращение — новое соединение: простые устройства (и стенд) закрывают его после ответа, а
            // повторное использование закрытого давало IOException на каждом опросе (найдено на планшете 22.11).
            // Повтор запроса не включаем: команда («импульс») не должна уйти дважды.
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .eventListener(object : EventListener() {
                override fun requestHeadersStart(call: Call) { call.request().tag(Sent::class.java)?.sent = true }
            })
            .build()
    }

    /** Метка запроса команды: ушла ли она устройству (соединение открыто, запрос отправляется). */
    private class Sent(val gateId: String? = null) { @Volatile var sent = false; @Volatile var call: okhttp3.Call? = null }

    /** Ключ пароля прохода в зашифрованном хранилище. */
    fun secretKey(gateId: String) = "gate_$gateId"

    /**
     * Пароль, вписанный прямо в адрес «своего HTTP» (`http://логин:пароль@…`), — отдельно от адреса: в настройках и копиях он
     * лежит открытым текстом (аудит 25.11.2026, SEC-02). Логин остаётся в адресе, команда идёт с тем же логином и паролем
     * ([call]). null — переносить нечего или нельзя: пароль есть не во всех адресах прохода или они разные — сохранённый один
     * на проход.
     */
    fun splitPassword(gate: Gate): Pair<Gate, String>? {
        val d = gate.direct?.takeIf { it.protocol == Gate.Direct.Protocol.HTTP } ?: return null
        val urls = listOf(d.openUrl, d.closeUrl).filter { it.isNotBlank() }
        val password = urls.map { it.toHttpUrlOrNull()?.password ?: return null }.distinct().singleOrNull()?.ifEmpty { null } ?: return null
        val clean = d.copy(openUrl = withoutPassword(d.openUrl) ?: return null, closeUrl = withoutPassword(d.closeUrl) ?: return null)
        return gate.copy(direct = clean) to password
    }

    /** Тот же адрес без пароля, посимвольно; null — не вышло без иных изменений. */
    private fun withoutPassword(url: String): String? {
        if (url.isBlank()) return url
        val start = url.indexOf("://") + 3
        val end = url.indexOfAny(charArrayOf('/', '\\', '?', '#'), start).let { if (it < 0) url.length else it }
        val at = url.lastIndexOf('@', end - 1)
        val colon = url.indexOf(':', start)
        if (at < start || colon !in start until at) return null
        val clean = url.removeRange(colon, at)
        return clean.takeIf { it.toHttpUrlOrNull() == url.toHttpUrlOrNull()?.newBuilder()?.password("")?.build() }
    }

    /** Итог команды: принята / отказ (с причиной для кнопки); [unclear] — ушла, а ответа нет: могла и выполниться. */
    data class Result(val ok: Boolean, val detail: String = "", val unclear: Boolean = false)

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

    /** Идущие команды по проходам — чтобы новая команда могла оборвать прежнюю ([cancel]). */
    private val running = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()

    fun cancel(gateId: String) { running[gateId]?.cancel() }

    fun send(gate: Gate, action: Gate.Action, password: String?): Result {
        val d = gate.direct ?: return Result(false, "связь напрямую не настроена")
        val sent = Sent(gate.id)
        return try {
            when (d.protocol) {
                Gate.Direct.Protocol.SHELLY -> {
                    val (method, params) = shellyCall(gate, action) ?: return Result(false, "устройство так не умеет")
                    rpc(d.host, method, params, password, sent).use { describe(it) }
                }
                Gate.Direct.Protocol.HTTP -> {
                    val url = if (action == Gate.Action.CLOSE) d.closeUrl else d.openUrl
                    if (url.isBlank()) return Result(false, "адрес команды не задан")
                    val request = Request.Builder().url(url).apply { if (d.post) post(ByteArray(0).toRequestBody()) }.build()
                    call(request, password, sent).use { describe(it) }
                }
            }
        } catch (e: IOException) {
            Result(false, "устройство не отвечает", unclear = sent.sent)
        } catch (e: IllegalArgumentException) {
            Result(false, "неверный адрес")
        } finally {
            sent.call?.let { running.remove(gate.id, it) }
        }
    }

    private fun describe(response: Response): Result = when {
        response.isSuccessful -> Result(true)
        response.code == 401 -> Result(false, WRONG_PASSWORD)
        // Команда дошла, а выполнена ли — неизвестно: повторять её панель не будет, человек проверит глазами.
        response.code in 300..399 -> Result(false, "устройство перенаправило запрос", unclear = true)
        response.code == 503 -> Result(false, "устройство занято", unclear = true)
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
        // У имени может быть несколько адресов (IPv6 и IPv4) — команда тоже пробует все.
        val reached = InetAddress.getAllByName(url.host).take(2).any { address ->
            try { Socket().use { it.connect(InetSocketAddress(address, url.port), PING_MS) }; true } catch (e: IOException) { false }
        }
        if (reached) DirectState(true) else null
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

    private fun rpc(host: String, method: String, params: Map<String, String>, password: String?, sent: Sent? = null): Response {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = base(host) + "/rpc/" + method + if (query.isEmpty()) "" else "?$query"
        return call(Request.Builder().url(url).build(), password, sent)
    }

    /** Запрос; на 401 с Digest — повтор с ответом на вызов (пароль не уходит по сети открытым). */
    private fun call(command: Request, password: String?, sent: Sent? = null): Response {
        val request = if (sent == null) command else command.newBuilder().tag(Sent::class.java, sent).build()
        fun run(r: Request): Response = http.newCall(r).also { c -> sent?.gateId?.let { running[it] = c; sent.call = c } }.execute()
        val first = run(request)
        // Логин и пароль в адресе своего запроса (http://логин:пароль@…) важнее сохранённого пароля Shelly.
        val user = request.url.username.ifEmpty { USER }
        val secret = request.url.password.ifEmpty { password.orEmpty() }
        if (first.code != 401 || secret.isEmpty()) return first
        // Лучший из предложенных способов: Digest раньше Basic, в каком бы порядке устройство их ни назвало (AUD-02). Незнакомый
        // способ — второго запроса нет.
        val auth = DigestAuth.choose(first.headers("WWW-Authenticate")) ?: return first
        first.close()
        sent?.sent = false // «нужен пароль» — команда не выполнена
        val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        // Basic — в прежней кодировке (ISO-8859-1): контроллеры с паролем не из латиницы работают как раньше.
        val header = if (auth.scheme == "Basic") okhttp3.Credentials.basic(user, secret) else auth.header(user, secret, request.method, uri)
        return run(request.newBuilder().header("Authorization", header).build())
    }

    /** Разбор заголовка Digest — для поддельных устройств в тестах; сам ответ на вызов считает [DigestAuth]. */
    internal object Digest {
        fun fields(challenge: String): Map<String, String> {
            if (!challenge.startsWith("Digest", ignoreCase = true)) return emptyMap()
            return Regex("""(\w+)=(?:"([^"]*)"|([^\s,]+))""").findAll(challenge.substring(6))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        }
    }
}
