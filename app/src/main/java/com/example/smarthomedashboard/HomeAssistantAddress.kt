package com.example.smarthomedashboard

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object HomeAssistantAddress {
    /** Базовый http(s)-адрес HA для REST и потоков камеры (снимок, HLS): тот же сервер, что у WebSocket. */
    fun httpBase(address: String): String =
        webSocketUrl(address).replaceFirst(Regex("^ws"), "http").removeSuffix("/api/websocket")

    fun webSocketUrl(address: String): String {
        val value = address.trim()
        require(value.isNotEmpty()) { "Empty address" }
        val http = when {
            value.startsWith("wss://", true) -> "https://" + value.substring(6)
            value.startsWith("ws://", true) -> "http://" + value.substring(5)
            value.contains("://") -> value
            // Preserve legacy bare IP:8123 settings; bare public domains default to TLS.
            else -> {
                val hostname = value.substringBefore("/").substringBefore(":")
                val local = hostname == "localhost" || hostname.matches(Regex("[0-9.]+")) ||
                    value.startsWith("[") || !hostname.contains(".") || hostname.endsWith(".local")
                (if (local) "http://" else "https://") + value
            }
        }.toHttpUrl()
        require(http.username.isEmpty() && http.password.isEmpty()) { "Credentials in URL" }
        var path = http.encodedPath.trimEnd('/')
        while (path.endsWith("/api/websocket")) path = path.removeSuffix("/api/websocket").trimEnd('/')
        val url = http.newBuilder().encodedPath("$path/api/websocket").query(null).fragment(null).build()
        return (if (url.isHttps) "wss://" else "ws://") + url.toString().substringAfter("://")
    }

    /**
     * Без шифрования (http/ws) токен HA и все команды идут открытым текстом. В домашней сети это обычная
     * практика, а через интернет — нет: внешний адрес по http допускается только с явного разрешения владельца.
     */
    const val PREF_ALLOW_INSECURE_REMOTE = "ha_allow_insecure_remote"
    const val INSECURE_REMOTE_REFUSED = "Внешний адрес без шифрования (http): подключение не выполняется. " +
        "Используйте https:// или разрешите небезопасное подключение в настройках"

    /** Адрес будет открыт без шифрования (ws://). Некорректный адрес — false: его отклонит само подключение. */
    fun isCleartext(address: String): Boolean =
        try { address.isNotBlank() && webSocketUrl(address).startsWith("ws://") } catch (_: Exception) { false }

    /**
     * Хост в частной или домашней сети: частные и служебные диапазоны IPv4 (включая 100.64/10 — адреса
     * VPN вроде Tailscale), локальные IPv6, loopback, имена без точки и домашние зоны (.local, .lan, .home.arpa…).
     * Публичное имя, которое дома резолвится в частный адрес, сюда не попадает — для него нужно явное разрешение.
     */
    fun isPrivateNetwork(address: String): Boolean {
        val host = try {
            webSocketUrl(address).replaceFirst(Regex("^wss?://"), "http://").toHttpUrl().host.lowercase()
        } catch (_: Exception) { return false }
        if (host == "localhost" || !host.contains('.') && !host.contains(':')) return true
        if (LOCAL_ZONES.any { host.endsWith(it) }) return true
        if (host.contains(':')) {
            // IPv6: loopback, link-local fe80::/10, уникальные локальные fc00::/7.
            val first = host.substringBefore(':').toIntOrNull(16) ?: return host == "::1"
            return host == "::1" || (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
        }
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 || a == 192 && b == 168 || a == 172 && b in 16..31 ||
            a == 169 && b == 254 || a == 100 && b in 64..127
    }

    /** Подключение без шифрования к адресу вне домашней сети — требуется явное разрешение. */
    fun needsInsecureConsent(address: String): Boolean = isCleartext(address) && !isPrivateNetwork(address)

    /** Предупреждение для формы адресов: только про внешний адрес без шифрования; локальный http — норма. */
    fun transportWarning(addresses: Collection<String>): String? =
        if (addresses.any { it.isNotBlank() && needsInsecureConsent(it) })
            "Внешний адрес без шифрования: токен Home Assistant уйдёт через интернет открытым текстом, " +
                "его смогут перехватить. Используйте https:// — или разрешите такое подключение явно."
        else null

    private val LOCAL_ZONES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    fun retryDelay(attempt: Int): Long = (2_000L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(60_000L)

    fun errorMessage(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(10).toList()
        return when {
            causes.any { it is UnknownHostException } -> "Ошибка DNS: не удалось найти сервер"
            causes.any { it is SSLException } -> "Ошибка TLS: не удалось установить защищённое соединение"
            causes.any { it is SocketTimeoutException } -> "Тайм-аут подключения к серверу"
            causes.any { it is ConnectException } -> "Ошибка сети: сервер недоступен или отказал в подключении"
            else -> "Ошибка сети: соединение с Home Assistant потеряно"
        }
    }
}
