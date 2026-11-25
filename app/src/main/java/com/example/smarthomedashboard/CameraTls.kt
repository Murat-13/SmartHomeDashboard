package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * RTSP поверх TLS (`rtsps://`, так отдаёт видео UniFi Protect). Сертификат у камер свой, неподписанный: доверяем тому, что
 * увидели при первом подключении, и запоминаем его отпечаток на этом планшете — подменённый сертификат подключение рвёт
 * ([PinnedTrust], как у прямых событий Reolink). Отпечатки — в настройках этого планшета, не в копиях.
 */
internal object CameraTls {
    private const val PREFS = "camera_tls"
    private val pins = ConcurrentHashMap<String, String>()
    @Volatile private var store: SharedPreferences? = null

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        store = prefs
        prefs.all.forEach { (k, v) -> (v as? String)?.let { pins.putIfAbsent(k, it) } }
    }

    /** Забыть отпечаток камеры с адресом [url] (сменили камеру или сертификат — человек сохранил её заново). */
    fun forget(url: String) {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return
        if (!uri.scheme.equals("rtsps", ignoreCase = true) || uri.host.isNullOrBlank()) return
        val key = "${uri.host}:${if (uri.port > 0) uri.port else PORT}"
        pins.remove(key)
        store?.edit { remove(key) }
    }

    /** Порт RTSP поверх TLS, если в адресе его нет. */
    const val PORT = 322

    /** TLS поверх уже подключённого [socket]; отпечаток не совпал — [java.io.IOException]. */
    fun wrap(socket: Socket, host: String, port: Int): Socket {
        val key = "$host:$port"
        val trust = PinnedTrust(pins[key]) { pin ->
            if (pins.putIfAbsent(key, pin) == null) store?.edit { putString(key, pin) }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            .socketFactory.createSocket(socket, host, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    internal fun clearForTests() { pins.clear(); store = null }
}
