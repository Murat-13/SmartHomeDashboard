package com.example.smarthomedashboard

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Выпуск приложения на GitHub Releases: один APK и его SHA-256. */
internal data class AppRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Хэш из поля `digest` GitHub; null — искать в файле [sha256Url]. */
    val sha256: String?,
    /** Необязательный файл `<apk>.sha256` в том же выпуске. */
    val sha256Url: String?,
    val notes: String,
)

/**
 * Канал обновлений. Тестовый видит все выпуски, стабильный — только без флажка «Pre-release»
 * (решение владельца 02.11.2026: в стабильные выпуск переводится снятием флажка на GitHub).
 */
internal enum class UpdateChannel(val pref: String, val label: String) {
    STABLE("stable", "стабильный"),
    TEST("test", "тестовый");

    companion object {
        /** Нет выбора — стабильный (решение владельца). */
        fun from(value: String?) = entries.firstOrNull { it.pref == value } ?: STABLE
    }
}

internal object AppReleases {
    private val VERSION = Regex("""v?(\d{1,3})\.(\d{1,2})\.(\d{1,2})""")
    private val HEX = Regex("[0-9a-f]{64}")

    /** Та же формула, что в app/build.gradle.kts: мажор·10000 + минор·100 + патч; иное — null. */
    fun versionCode(name: String): Long? = VERSION.matchEntire(name.trim())?.destructured?.let { (major, minor, patch) ->
        major.toLong() * 10000 + minor.toLong() * 100 + patch.toLong()
    }

    /**
     * Ответ `GET /repos/{owner}/{repo}/releases`. Пропускаются черновики, теги не вида vX.Y.Z и выпуски,
     * где не ровно один APK. Неразборчивый ответ — пустой список.
     */
    fun parse(json: String): List<AppRelease> {
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { release(it.asJsonObject) }.getOrNull() }
    }

    private fun release(o: JsonObject): AppRelease? {
        if (o.bool("draft")) return null
        val tag = o.str("tag_name") ?: return null
        val code = versionCode(tag) ?: return null
        val assets = o.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject }.orEmpty()
            .filter { (it.str("state") ?: "uploaded") == "uploaded" }
        val apk = assets.filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }.singleOrNull() ?: return null
        val name = apk.str("name") ?: return null
        val digest = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        return AppRelease(
            tag = tag,
            versionName = tag.trim().removePrefix("v"),
            versionCode = code,
            prerelease = o.bool("prerelease"),
            apkName = name,
            apkUrl = apk.str("browser_download_url") ?: return null,
            apkSize = apk.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L,
            sha256 = digest?.takeIf { HEX.matches(it) },
            sha256Url = assets.firstOrNull { it.str("name").equals("$name.sha256", ignoreCase = true) }?.str("browser_download_url"),
            notes = o.str("body").orEmpty().take(2000),
        )
    }

    /** Самый новый выпуск канала, который новее установленного; null — обновлять нечего. */
    fun pick(releases: List<AppRelease>, channel: UpdateChannel, installedCode: Long): AppRelease? =
        releases.filter { channel == UpdateChannel.TEST || !it.prerelease }
            .filter { it.versionCode > installedCode }
            .maxByOrNull { it.versionCode }

    /** Содержимое файла `.sha256` (формат `sha256sum`: «хэш  имя»): первый токен, если это SHA-256. */
    fun parseSha256File(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { HEX.matches(it) }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean ?: false
}
