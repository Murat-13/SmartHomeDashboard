package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.ColorRule
import com.example.smarthomedashboard.data.TileEntity
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

/** Черновик без Context/сети. Сохранение только по явному действию владельца экрана. */
internal class SensorTileDraft(val original: TileEntity) {
    data class Row(val value: SensorConfig, val raw: String? = null) {
        fun json() = (raw?.let(::JSONObject) ?: JSONObject()).apply {
            put("entity_id", value.entityId)
            put("display_name", value.displayName)
            put("decimals", value.decimals)
        }
    }
    data class Rule(val value: ColorRule, val raw: String? = null) {
        fun json(): JSONObject {
            val json = raw?.let(::JSONObject) ?: JSONObject()
            if (raw == null || ruleValue(json) != value) {
                json.put("entity_id", value.entityId)
                json.put("from", value.from ?: "")
                json.put("to", value.to ?: "")
                json.put("color_hex", value.colorHex)
            }
            return json
        }
    }

    val compact = mutableListOf<Row>()
    val detailed = mutableListOf<Row>()
    val rules = mutableListOf<Rule>()
    var followsCompact = true
    var fontSizeText = original.fontSize.toString()
    var loadError: String? = null
        private set
    private var initialCompact = emptyList<Row>()
    private var initialDetailed = emptyList<Row>()
    private var initialRules = emptyList<Rule>()
    private var initialFollows = true

    init {
        try {
            val config = JSONObject(original.config)
            // Не затираем непонятный/повреждённый формат результатом частичного чтения.
            listOf("collapsed_sensor_configs", "expanded_sensor_configs", "entity_ids").forEach {
                require(!config.has(it) || config.opt(it) is JSONArray)
            }
            val legacy = JSONArray(original.collapsedSensorIds)
            require((0 until legacy.length()).all { legacy.opt(it) is String })
            compact += rows(config, "collapsed_sensor_configs", false)
            detailed += rows(config, "expanded_sensor_configs", true)
            followsCompact = config.optJSONArray("expanded_sensor_configs").let { it == null || it.length() == 0 } &&
                compact.map { it.value } == detailed.map { it.value }
            val array = JSONArray(original.colorRules.ifBlank { "[]" })
            repeat(array.length()) {
                val obj = array.getJSONObject(it)
                rules += Rule(ruleValue(obj), obj.toString())
            }
        } catch (_: Exception) {
            loadError = "Не удалось прочитать настройки плитки. Сохранение заблокировано, чтобы не потерять данные."
        }
        initialCompact = compact.toList()
        initialDetailed = detailed.toList()
        initialRules = rules.toList()
        initialFollows = followsCompact
    }

    private fun rows(config: JSONObject, key: String, expanded: Boolean): List<Row> {
        val own = config.optJSONArray(key)
        if (own != null && own.length() > 0) return (0 until own.length()).map {
            val obj = own.getJSONObject(it)
            require(obj.opt("entity_id") is String)
            Row(SensorConfig(obj.getString("entity_id"), obj.optString("display_name", obj.getString("entity_id").substringAfterLast("_")), obj.optInt("decimals", 0)), obj.toString())
        }
        val ids = if (expanded) SensorConfigResolver.getExpandedSensorIds(original) else SensorConfigResolver.getCollapsedSensorIds(original)
        val formats = SensorConfigResolver.loadSensorConfigs(original, expanded)
        return ids.map { id -> Row(formats.firstOrNull { it.entityId == id } ?: SensorConfig(id, id.substringAfterLast("_"), defaultDecimals(id))) }
    }

    val isDirty get() = compact != initialCompact || detailed != initialDetailed || rules != initialRules ||
        followsCompact != initialFollows || fontSizeText != original.fontSize.toString()

    fun add(ids: List<String>, expanded: Boolean, name: (String) -> String) {
        val list = if (expanded) detailed else compact
        ids.filter { id -> list.none { it.value.entityId == id } }.distinct().forEach {
            list += Row(SensorConfig(it, name(it), defaultDecimals(it)))
        }
    }

    fun applyTo(tile: TileEntity): TileEntity {
        check(loadError == null) { requireNotNull(loadError) }
        require(tile.id == original.id)
        val font = fontSizeText.toIntOrNull()
        require(font != null && (fontSizeText == original.fontSize.toString() || font in 1..200)) { "Размер текста: от 1 до 200" }
        val config = JSONObject(tile.config)
        val compactChanged = compact != initialCompact
        val detailChanged = detailed != initialDetailed || followsCompact != initialFollows
        if (compactChanged) config.put("collapsed_sensor_configs", rowArray(compact))
        if (followsCompact && (compactChanged || detailChanged)) {
            config.put("expanded_sensor_configs", JSONArray())
            config.put("entity_ids", JSONArray(compact.map { it.value.entityId }))
            // Явный пустой набор не должен восстановиться из устаревшего поля.
        } else if (!followsCompact && detailChanged) {
            config.put("expanded_sensor_configs", rowArray(detailed))
            config.put("entity_ids", JSONArray(detailed.map { it.value.entityId }))
        }
        val rowsChanged = compactChanged || detailChanged
        return tile.copy(
            config = if (rowsChanged) config.toString() else tile.config,
            collapsedSensorIds = if (compactChanged) "[]" else tile.collapsedSensorIds,
            fontSize = font,
            colorRules = if (rules == initialRules) tile.colorRules else JSONArray().also { a -> rules.forEach { a.put(it.json()) } }.toString()
        )
    }

    /** Состояние UI сохраняется отдельно от prefs, включая несохранённые имена/порядок/правила. */
    fun saveState(): String = JSONObject().put("original", Gson().toJson(original))
        .put("compact", rowArray(compact)).put("detailed", rowArray(detailed))
        .put("rules", JSONArray().also { a -> rules.forEach { a.put(it.json()) } })
        .put("follows", followsCompact).put("font", fontSizeText).toString()

    companion object {
        private fun ruleValue(obj: JSONObject) = ColorRule(obj.optString("entity_id"), obj.optString("from", ""), obj.optString("to", ""), obj.optString("color_hex"))
        private fun rowArray(rows: List<Row>) = JSONArray().also { a -> rows.forEach { a.put(it.json()) } }
        private fun defaultDecimals(id: String) = when {
            "temp" in id || "freq" in id -> 1
            "current" in id || "energy" in id -> 2
            else -> 0
        }
        fun restore(text: String): SensorTileDraft {
            val state = JSONObject(text)
            val draft = SensorTileDraft(Gson().fromJson(state.getString("original"), TileEntity::class.java))
            fun read(key: String) = state.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            fun row(o: JSONObject) = Row(SensorConfig(o.getString("entity_id"), o.getString("display_name"), o.getInt("decimals")), o.toString())
            // При одинаковых значениях сохраняем исходные raw, чтобы восстановление не стало изменением.
            fun restoreRows(saved: List<Row>, initial: List<Row>) = saved.mapIndexed { i, r -> initial.getOrNull(i)?.takeIf { it.value == r.value && it.json().toString() == r.raw } ?: r }
            draft.compact.apply { clear(); addAll(restoreRows(read("compact").map(::row), draft.initialCompact)) }
            draft.detailed.apply { clear(); addAll(restoreRows(read("detailed").map(::row), draft.initialDetailed)) }
            draft.rules.apply {
                clear()
                addAll(read("rules").mapIndexed { i, o -> draft.initialRules.getOrNull(i)?.takeIf { it.value == ruleValue(o) && it.json().toString() == o.toString() } ?: Rule(ruleValue(o), o.toString()) })
            }
            draft.followsCompact = state.getBoolean("follows")
            draft.fontSizeText = state.getString("font")
            return draft
        }
    }
}
