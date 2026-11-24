package com.example.smarthomedashboard

import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Вход в Home Assistant его собственной страницей (docs/setup-tz.md, часть 1): OAuth 2 с PKCE. Пароль вводится только
 * там; панель получает одноразовый код, меняет его на временный доступ, создаёт постоянный (тот же вид, что токен из
 * профиля) и отзывает временный. Подключение панели и хранилище токена не меняются. Блокирующее — рабочий поток.
 */
internal object HaLogin {
    /** Вход не удался; текст показывается человеку. */
    class LoginException(message: String) : IOException(message)

    /** Одна попытка входа: куда идти и что проверить в ответе. */
    class Attempt(val origin: String, val clientId: String, val redirect: String, val state: String, val verifier: String) {
        val url: String get() = "$origin/auth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    /** Что даёт вход. [token] — постоянный доступ; [external] — внешний адрес из настроек HA (https) или "". */
    class Granted(val token: String, val name: String, val external: String, val admin: Boolean) {
        override fun toString() = "Granted(admin=$admin)"
    }

    private val random = SecureRandom()
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    }

    fun secret(bytes: Int): String = Base64.encodeToString(ByteArray(bytes).also { random.nextBytes(it) }, FLAGS)
    fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), FLAGS)

    /** Вход на планшете: `client_id` — сам Home Assistant, возврат перехватывается до загрузки. */
    fun onTablet(origin: String): Attempt =
        Attempt(origin, "$origin/", "$origin/?panel_login=${secret(8)}", secret(16), secret(48))

    /** Вход с телефона: возврат на одноразовую страницу планшета [tablet] («http://адрес:порт»), путь [path]. */
    fun onPhone(origin: String, tablet: String, path: String): Attempt =
        Attempt(origin, "$tablet/", "$tablet/$path/done", secret(16), secret(48))

    /** [url] — возврат этой попытки (до проверки `state`). */
    fun isReturn(attempt: Attempt, url: String): Boolean =
        url.startsWith(attempt.redirect) && url.getOrNull(attempt.redirect.length).let { it == null || it == '&' || it == '?' }

    /** Код из возврата; чужой или повреждённый ответ — [LoginException]. */
    fun code(attempt: Attempt, url: String): String {
        val parsed = try { url.toHttpUrl() } catch (_: IllegalArgumentException) { throw LoginException(BROKEN) }
        return code(attempt, parsed.queryParameter("state"), parsed.queryParameter("code"))
    }

    fun code(attempt: Attempt, state: String?, code: String?): String {
        val same = state != null && MessageDigest.isEqual(state.toByteArray(), attempt.state.toByteArray())
        if (!same || code.isNullOrBlank() || code.length > MAX_CODE) throw LoginException(BROKEN)
        return code
    }

    /** Код → постоянный доступ с именем [name] (имя в Home Assistant не должно повторяться). */
    fun grant(attempt: Attempt, code: String, name: String): Granted {
        val (access, refresh) = exchange(attempt, code)
        try {
            return session(attempt.origin, access) { call ->
                val config = call("get_config", JSONObject())
                val user = runCatching { call("auth/current_user", JSONObject()) }.getOrNull()
                val token = try { create(call, name) } catch (_: LoginException) {
                    // Такое имя уже есть (два входа в одну секунду): ещё раз с другим.
                    create(call, "$name ${secret(3)}")
                }
                Granted(token, config.optString("location_name"),
                    config.optString("external_url").takeIf { it.startsWith("https://", true) && HaDiscovery.usableUrl(it) }.orEmpty(),
                    user?.optBoolean("is_admin") == true)
            }
        } finally {
            // Временный доступ больше не нужен; не отозвался — сам истечёт.
            runCatching { revoke(attempt.origin, refresh) }
        }
    }

    private fun create(call: (String, JSONObject) -> JSONObject, name: String): String =
        call("auth/long_lived_access_token", JSONObject().put("client_name", name).put("lifespan", LIFESPAN_DAYS)).optString(VALUE)
            .ifBlank { throw LoginException("Home Assistant не выдал доступ панели") }

    private fun exchange(attempt: Attempt, code: String): Pair<String, String> {
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("client_id", attempt.clientId).add("code_verifier", attempt.verifier).build()
        val request = Request.Builder().url("${attempt.origin}/auth/token").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> JSONObject(text).let { it.getString("access_token") to it.optString("refresh_token") }
                    403 -> throw LoginException("Этому пользователю Home Assistant вход не разрешён")
                    else -> throw LoginException("Home Assistant не принял вход. Попробуйте ещё раз")
                }
            }
        } catch (_: JSONException) {
            throw LoginException("Home Assistant ответил непонятно")
        } catch (e: LoginException) { throw e } catch (e: IOException) {
            throw LoginException(HomeAssistantAddress.errorMessage(e))
        }
    }

    private fun revoke(origin: String, refresh: String) {
        if (refresh.isBlank()) return
        client.newCall(Request.Builder().url("$origin/auth/revoke").post(FormBody.Builder().add("token", refresh).build()).build())
            .execute().close()
    }

    /** Одно соединение WebSocket: вход временным доступом и несколько команд по очереди. */
    private fun <T> session(origin: String, access: String, block: ((String, JSONObject) -> JSONObject) -> T): T {
        val url = HomeAssistantAddress.webSocketUrl(origin)
        val ready = CountDownLatch(1)
        var failure: String? = null
        var answer: JSONObject? = null
        var waiting = CountDownLatch(0)
        val lock = Any()
        val http = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        fun fail(text: String) { synchronized(lock) { if (failure == null) failure = text; ready.countDown(); waiting.countDown() } }
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (_: JSONException) { return }
                when (json.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", access).toString())
                    "auth_ok" -> ready.countDown()
                    "auth_invalid" -> fail("Home Assistant не принял вход. Попробуйте ещё раз")
                    "result" -> synchronized(lock) { answer = json; waiting.countDown() }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(HomeAssistantAddress.errorMessage(t))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail("Home Assistant закрыл соединение")
        })
        try {
            if (!ready.await(WAIT_S, TimeUnit.SECONDS)) throw LoginException("Home Assistant не отвечает")
            synchronized(lock) { failure }?.let { throw LoginException(it) }
            var id = 0
            return block { type, data ->
                val latch = CountDownLatch(1)
                synchronized(lock) { answer = null; waiting = latch }
                socket.send(data.put("id", ++id).put("type", type).toString())
                if (!latch.await(WAIT_S, TimeUnit.SECONDS)) throw LoginException("Home Assistant не отвечает")
                val json = synchronized(lock) { failure?.let { throw LoginException(it) }; answer } ?: throw LoginException(BROKEN)
                if (!json.optBoolean("success")) throw LoginException("Home Assistant не выдал доступ панели")
                when (val result = json.opt("result")) {
                    is JSONObject -> result
                    else -> JSONObject().put(VALUE, if (result is String) result else "")
                }
            }
        } finally {
            socket.close(1000, null)
            socket.cancel()
        }
    }

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    /** Как у токена из профиля Home Assistant — 10 лет. */
    private const val LIFESPAN_DAYS = 3650
    private const val MAX_CODE = 512
    private const val WAIT_S = 15L
    private const val VALUE = "value"
    private const val BROKEN = "Ответ входа не подошёл. Начните вход заново"
}
