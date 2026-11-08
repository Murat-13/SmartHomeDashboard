package com.example.smarthomedashboard.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Безопасное чтение сохранённого списка плиток (формат не меняется — это тот же JSON, что пишет Gson).
 * Повреждённый JSON, `null`, не массив, `null`-элементы и плитки без `id`/`type` не роняют панель:
 * пригодные плитки возвращаются, о непригодных сообщает [Result.issue]. Необязательные поля, которых
 * нет в старых конфигурациях или которые имеют неверный тип, получают значения по умолчанию [TileEntity]
 * (Gson без конструктора оставил бы в них `null`, `0` или `enabled = false`).
 */
object TileParser {
    /** [unreadable] — весь список не прочитан; иначе [skipped] из [total] элементов пропущены. */
    data class Issue(val unreadable: Boolean, val skipped: Int, val total: Int)

    data class Result(val tiles: List<TileEntity>, val issue: Issue?)

    fun parse(json: String?): Result {
        if (json == null) return Result(emptyList(), null)
        val root = try { JsonParser.parseString(json) } catch (_: Exception) { null }
        if (root == null || !root.isJsonArray) return Result(emptyList(), Issue(unreadable = true, skipped = 0, total = 0))
        val array = root.asJsonArray
        // Порядок и повторы сохраняются как раньше (см. SettingsConfigTransferTest); пропускаются только непригодные.
        val tiles = array.mapNotNull { element -> (element as? JsonObject)?.let { runCatching { tile(it) }.getOrNull() } }
        val skipped = array.size() - tiles.size
        return Result(tiles, if (skipped > 0) Issue(unreadable = false, skipped = skipped, total = array.size()) else null)
    }

    private fun tile(o: JsonObject): TileEntity? {
        val id = o.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val type = o.string("type")?.takeIf { it.isNotBlank() } ?: return null
        return TileEntity(
            id = id,
            type = type,
            container = o.string("container") ?: "grid",
            title = o.string("title") ?: "",
            x = o.int("x") ?: 0,
            y = o.int("y") ?: 0,
            width = o.int("width")?.takeIf { it > 0 } ?: 1,
            height = o.int("height")?.takeIf { it > 0 } ?: 1,
            appearance = o.string("appearance") ?: "{}",
            conditions = o.string("conditions") ?: "{}",
            config = o.string("config") ?: "{}",
            enabled = o.bool("enabled") ?: true,
            collapsedSensorIds = o.string("collapsedSensorIds") ?: "[]",
            colorRules = o.string("colorRules") ?: "[]",
            fontSize = o.int("fontSize")?.takeIf { it > 0 } ?: 16,
            icon = o.string("icon") ?: "",
            sourceType = o.string("sourceType") ?: "auto",
            page = o.int("page")?.takeIf { it >= 0 } ?: 0,
            portrait = o.string("portrait") ?: "",
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.takeIf { it.isBoolean }?.asBoolean

    /** Только целое число в пределах Int; дробь, текст, бесконечность — как отсутствие значения. */
    private fun JsonObject.int(key: String): Int? {
        val value = primitive(key)?.takeIf { it.isNumber }?.asDouble ?: return null
        if (!value.isFinite() || value != Math.floor(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
        return value.toInt()
    }
}
