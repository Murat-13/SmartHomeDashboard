package com.example.smarthomedashboard

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Вход с телефона (docs/setup-tz.md, часть 1): пока открыт экран с QR, планшет показывает в домашней сети одну
 * одноразовую страницу. Телефон открывает её, уходит на страницу входа Home Assistant и возвращается с одноразовым
 * кодом. Адрес — только домашней сети планшета, порт и путь случайные (128 бит), живёт [LIFETIME_MS]. Код идёт от телефона к
 * планшету по домашней сети без шифрования. Home Assistant (проверено на 2026.11.3, аудит 25.11.2026) выдаёт доступ по коду и без
 * проверочного слова PKCE (с неверным словом — отказывает), поэтому перехваченный в сети код — это доступ: в чужой или гостевой сети
 * входить нужно на самом планшете. Экран входа об этом говорит.
 * [onCode] — на рабочем потоке, один раз.
 */
internal class PhoneLogin(
    origin: String,
    private val address: InetAddress,
    private val onCode: (HaLogin.Attempt, String) -> Unit,
    private val onClosed: () -> Unit = {},
    private val lifetimeMs: Long = LIFETIME_MS
) : Closeable {
    private val path = HaLogin.secret(16)
    private val server = ServerSocket().apply { reuseAddress = true }
    private val pool = Executors.newFixedThreadPool(WORKERS) { Thread(it, "phone-login").apply { isDaemon = true } }
    private val admission = HookAdmission(MAX_PER_SECOND, MAX_CONCURRENT)
    @Volatile private var finished = false
    private val used = java.util.concurrent.atomic.AtomicBoolean(false)

    private val base: String
    val attempt: HaLogin.Attempt
    /** Что зашито в QR. */
    val link: String

    init {
        server.bind(InetSocketAddress(address, 0))
        server.soTimeout = 1_000
        base = "http://${address.hostAddress}:${server.localPort}"
        attempt = HaLogin.onPhone(origin, base, path)
        link = "$base/$path"
    }

    fun start() {
        val until = System.nanoTime() + lifetimeMs * 1_000_000
        Thread({
            while (!finished && System.nanoTime() < until) {
                val client = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                val from = client.inetAddress
                val neighbour = from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress
                if (!neighbour || !admission.enter(from)) { quietly(client); continue }
                try { pool.execute { try { serve(client) } finally { admission.leave(from) } } } catch (_: RuntimeException) {
                    admission.leave(from); quietly(client)
                }
            }
            close()
        }, "phone-login-accept").apply { isDaemon = true; start() }
    }

    override fun close() {
        if (finished) return
        finished = true
        try { server.close() } catch (_: IOException) {}
        pool.shutdown()
        onClosed()
    }

    private fun quietly(s: Socket) = try { s.close() } catch (_: IOException) {}

    private fun serve(client: Socket) = client.use { s ->
        try {
            s.soTimeout = READ_MS
            val line = readRequestLine(s.getInputStream()) ?: return@use
            val parts = line.split(' ')
            val target = parts.getOrNull(1).orEmpty()
            if (parts.getOrNull(0) != "GET" || !target.startsWith("/")) { answer(s, 404, "Не найдено"); return@use }
            val url = try { (base + target).toHttpUrl() } catch (_: IllegalArgumentException) { answer(s, 404, "Не найдено"); return@use }
            when (url.encodedPath) {
                "/$path" -> if (used.get()) answer(s, 200, DONE) else redirect(s, attempt.url)
                "/$path/done" -> finish(s, url.queryParameter("state"), url.queryParameter("code"))
                else -> answer(s, 404, "Не найдено")
            }
        } catch (_: IOException) {}
    }

    private fun finish(s: Socket, state: String?, code: String?) {
        val value = try { HaLogin.code(attempt, state, code) } catch (_: HaLogin.LoginException) {
            answer(s, 400, "Ссылка устарела. Отсканируйте QR на планшете ещё раз")
            return
        }
        // Обновление страницы на телефоне не должно запускать вход второй раз.
        val first = used.compareAndSet(false, true)
        answer(s, 200, DONE)
        if (first) {
            Log.i(TAG, "Вход с телефона: код получен")
            onCode(attempt, value)
        }
    }

    private fun redirect(s: Socket, to: String) = s.getOutputStream().run {
        write(("HTTP/1.1 302 Found\r\nLocation: $to\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
        flush()
    }

    private fun answer(s: Socket, code: Int, text: String) {
        val body = ("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<title>Панель</title><body style=\"background:#10181f;color:#e8eef2;font:18px/1.5 sans-serif;padding:32px\">" +
            "<p>$text</p></body></html>").toByteArray()
        s.getOutputStream().run {
            write(("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(body)
            flush()
        }
    }

    /** Первая строка запроса; заголовки дочитываются (не больше [MAX_HEAD]), чтобы телефон получил ответ, а не обрыв. */
    private fun readRequestLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0 || out.size() >= MAX_HEAD) return null
            out.write(b)
            tail = (tail shl 8) or b
        }
        return out.toString(Charsets.UTF_8.name()).lineSequence().first().trim()
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        private const val TAG = "PhoneLogin"
        private const val WORKERS = 4
        private const val MAX_PER_SECOND = 20
        private const val MAX_CONCURRENT = 4
        private const val READ_MS = 5_000
        private const val MAX_HEAD = 16 * 1024
        private const val DONE = "Готово. Вернитесь к планшету — панель подключается."
    }
}
