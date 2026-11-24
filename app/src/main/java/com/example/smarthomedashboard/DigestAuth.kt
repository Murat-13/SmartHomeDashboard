package com.example.smarthomedashboard

import java.security.MessageDigest
import java.util.Locale

/**
 * Авторизация камер (RFC 2617, RFC 7616): Digest MD5 и SHA-256, с `-sess` и без, Basic. Одна на RTSP ([RtspSession]), разговор
 * по HTTP ([HttpTalk]) и прямой снимок. Камеры присылают несколько вариантов сразу (Hikvision — MD5 и SHA-256, Axis и
 * Reolink — Digest и Basic), одной строкой или несколькими. Пароль открытым текстом уходит только в Basic — когда камера
 * ничего другого не умеет.
 */
internal class DigestAuth private constructor(
    val scheme: String, private val realm: String, private val nonce: String, private val qop: String?, private val opaque: String?,
    private val algorithm: String?,
    /** Отказ только из-за устаревшего nonce: логин и пароль верны. */
    val stale: Boolean = false
) {
    private var count = 0
    private val sha get() = algorithm?.startsWith("SHA-256", ignoreCase = true) == true
    private val sess get() = algorithm?.endsWith("-sess", ignoreCase = true) == true

    /** Значение заголовка `Authorization` для запроса [method] [uri] (RTSP — полный адрес, HTTP — путь с параметрами). */
    fun header(user: String, password: String, method: String, uri: String): String {
        if (scheme == BASIC) return "Basic " + RecBase64.encode("$user:$password".toByteArray(Charsets.UTF_8))
        val nc = "%08x".format(Locale.ROOT, ++count)
        val cnonce = hash(System.nanoTime().toString() + count).take(CNONCE)
        val ha1 = hash("$user:$realm:$password").let { if (sess) hash("$it:$nonce:$cnonce") else it }
        val ha2 = hash("$method:$uri")
        val parts = mutableListOf("username=\"${quoted(user)}\"", "realm=\"$realm\"", "nonce=\"$nonce\"", "uri=\"$uri\"")
        if (qop != null) {
            parts += "response=\"${hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\""
            parts += "qop=$qop"; parts += "nc=$nc"; parts += "cnonce=\"$cnonce\""
        } else {
            parts += "response=\"${hash("$ha1:$nonce:$ha2")}\""
            if (sess) parts += "cnonce=\"$cnonce\""
        }
        algorithm?.let { parts += "algorithm=$it" }
        opaque?.let { parts += "opaque=\"$it\"" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun quoted(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun hash(text: String): String = MessageDigest.getInstance(if (sha) "SHA-256" else "MD5")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        private const val BASIC = "Basic"
        private const val CNONCE = 16
        private val START = Regex("(?i)(?:^|,\\s*)(Digest|Basic)\\s+")
        private val PARAM = Regex("(\\w+)\\s*=\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,\\s]+)")

        /**
         * Лучший вариант из заголовков `WWW-Authenticate` ([values]); ни одного знакомого — null. Сначала Digest MD5 (его
         * понимают все), затем SHA-256, последним — Basic.
         */
        fun choose(values: List<String>): DigestAuth? = values.flatMap(::parse).minByOrNull { c ->
            when {
                c.scheme == BASIC -> 9
                c.algorithm == null || c.algorithm.equals("MD5", true) -> 0
                c.algorithm.equals("MD5-sess", true) -> 1
                else -> 2
            }
        }

        private fun parse(value: String): List<DigestAuth> {
            val starts = START.findAll(value).toList()
            return starts.mapIndexedNotNull { i, m ->
                val body = value.substring(m.range.last + 1, if (i + 1 < starts.size) starts[i + 1].range.first else value.length)
                val p = PARAM.findAll(body).associate { it.groupValues[1].lowercase(Locale.ROOT) to (it.groups[3]?.value ?: it.groupValues[2]) }
                if (m.groupValues[1].equals(BASIC, true)) return@mapIndexedNotNull DigestAuth(BASIC, p["realm"].orEmpty(), "", null, null, null)
                val algorithm = p["algorithm"]?.trim()
                val known = algorithm == null || listOf("MD5", "MD5-sess", "SHA-256", "SHA-256-sess").any { it.equals(algorithm, true) }
                val qops = p["qop"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }
                // Только `auth-int` (подпись тела) мы не умеем — такой вариант не подходит.
                if (!known || qops != null && "auth" !in qops && qops.any { it.isNotEmpty() }) null
                else DigestAuth("Digest", p["realm"].orEmpty(), p["nonce"].orEmpty(), qops?.firstOrNull { it == "auth" }, p["opaque"], algorithm,
                    p["stale"].equals("true", true))
            }
        }
    }
}
