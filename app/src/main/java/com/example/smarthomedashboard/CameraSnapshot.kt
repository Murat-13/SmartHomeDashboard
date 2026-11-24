package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Прямой снимок камеры по HTTP (docs/cameras.md, «Камеры только со снимком»): камера без RTSP или снимок, когда у Home
 * Assistant его нет. Только домашняя сеть; авторизация — [DigestAuth]. Адрес даёт сама камера (ONVIF `GetSnapshotUri`) или
 * вводит человек.
 */
internal object CameraSnapshot {
    /** Раскрытая карточка и плитка камеры, у которой кроме снимка ничего нет. */
    const val EXPANDED_MS = 1_000L
    const val TILE_MS = 5_000L
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TARGET_W = 960
    private val main = Handler(Looper.getMainLooper())

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false).build()
    }

    /** Адрес, по которому можно спрашивать: http(s) в домашней сети; логин и пароль в адресе не нужны. Иначе null. */
    fun usable(url: String): String? = url.trim().toHttpUrlOrNull()?.takeIf { HomeAssistantAddress.isPrivateNetwork(it.host) }
        ?.newBuilder()?.username("")?.password("")?.build()?.toString()

    /** Снимок в фоне; [done] — на главном потоке, null — не вышло. */
    fun load(url: String, user: String, password: String, done: (Bitmap?) -> Unit) {
        val address = usable(url) ?: run { main.post { done(null) }; return }
        http.newBuilder().authenticator(auth(user, password)).build().newCall(Request.Builder().url(address).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { main.post { done(null) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bitmap = try { response.use { r -> if (r.isSuccessful) bytes(r)?.let(::decode) else null } } catch (_: IOException) { null }
                main.post { done(bitmap) }
            }
        })
    }

    private fun bytes(r: okhttp3.Response): ByteArray? {
        val source = r.body?.source() ?: return null
        source.request(MAX_BYTES + 1)
        return if (source.buffer.size > MAX_BYTES) null else source.buffer.readByteArray()
    }

    /** Ответ на 401: один раз. Пароль открытым текстом — только если камера другого не умеет ([DigestAuth.choose]). */
    internal fun auth(user: String, password: String) = Authenticator { _, response ->
        if (response.request.header("Authorization") != null || user.isBlank()) return@Authenticator null
        val url = response.request.url
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        DigestAuth.choose(response.headers("WWW-Authenticate"))?.let {
            response.request.newBuilder().header("Authorization", it.header(user, password, response.request.method, path)).build()
        }
    }

    /** Компактной плитке не нужен полный кадр 2560×1920: уменьшаем при декодировании. */
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
