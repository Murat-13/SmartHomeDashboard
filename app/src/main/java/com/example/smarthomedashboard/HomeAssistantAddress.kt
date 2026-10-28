package com.example.smarthomedashboard

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object HomeAssistantAddress {
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

    fun transportWarning(addresses:Collection<String>):String? {
        val cleartext=addresses.any { address ->
            try { address.isNotBlank() && webSocketUrl(address).startsWith("ws://") }
            catch(_:Exception){false}
        }
        return if(cleartext) "HTTP/WS не шифруют соединение и токен. По возможности используйте HTTPS/WSS. Адрес автоматически не меняется." else null
    }

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
