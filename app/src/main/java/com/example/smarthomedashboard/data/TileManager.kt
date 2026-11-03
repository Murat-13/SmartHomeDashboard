package com.example.smarthomedashboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TileEntity(
    val id: String,
    val type: String,
    val container: String,
    val title: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val appearance: String = "{}",
    val conditions: String = "{}",
    val config: String = "{}",
    val enabled: Boolean = true,
    // Новые поля для расширенных настроек
    val collapsedSensorIds: String = "[]",
    val colorRules: String = "[]",
    val fontSize: Int = 16,
    val icon: String = "",
    val sourceType: String = "auto",
    /** Страница панели: 0 — главная. В старых данных поля нет, Gson даёт 0 — плитки остаются на главной. */
    val page: Int = 0
)

class TileManager(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val prefs: SharedPreferences = context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    /** Повреждение последнего прочитанного списка; null — всё прочитано. */
    private var cachedIssue: TileParser.Issue? = null

    // Плитки читаются на каждое событие HA, а разбор JSON дорог. Кеш привязан к строке
    // из prefs: любая запись (другим экземпляром, импортом настроек) меняет строку и сбрасывает его.
    // Списки неизменяемые, поэтому повторное чтение без изменений возвращает тот же объект.
    private var cachedJson: String? = null
    private var cachedTiles: List<TileEntity> = emptyList()
    private var cachedEnabled: List<TileEntity>? = null
    private val cachedByContainer = mutableMapOf<String, List<TileEntity>>()

    /**
     * Запись списка. Если сохранённые сейчас данные повреждены, они сначала копируются в отдельный файл
     * ([preserveDamaged]); не удалось — запись не выполняется, чтобы не уничтожить единственный оригинал.
     */
    @Synchronized
    fun saveTiles(tiles: List<TileEntity>): Boolean {
        val current = prefs.getString("tiles", null)
        if (current != null && TileParser.parse(current).issue != null && !preserveDamaged(appContext, current)) return false
        val json = gson.toJson(tiles)
        prefs.edit { putString("tiles", json) }
        return true
    }

    /** Чтение без исключений: повреждения не роняют панель и не перезаписывают сохранённый JSON. */
    @Synchronized
    fun loadTiles(): List<TileEntity> {
        val json = prefs.getString("tiles", "[]") ?: "[]"
        if (json != cachedJson) {
            val result = TileParser.parse(json)
            if (result.issue != null) preserveDamaged(appContext, json)
            cachedTiles = java.util.Collections.unmodifiableList(ArrayList(result.tiles))
            cachedIssue = result.issue
            cachedJson = json
            cachedEnabled = null
            cachedByContainer.clear()
        }
        return cachedTiles
    }

    /** Повреждение сохранённого списка (после [loadTiles]); null — данные в порядке. */
    @Synchronized
    fun loadIssue(): TileParser.Issue? {
        loadTiles()
        return cachedIssue
    }

    fun addTile(tile: TileEntity) {
        val tiles = loadTiles().toMutableList()
        tiles.add(tile)
        saveTiles(tiles)
    }

    fun updateTile(tile: TileEntity) {
        val tiles = loadTiles().toMutableList()
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index >= 0) {
            tiles[index] = tile
            saveTiles(tiles)
        }
    }

    fun updateTilePosition(tileId: String, newX: Int, newY: Int) {
        val tiles = loadTiles().toMutableList()
        val index = tiles.indexOfFirst { it.id == tileId }
        if (index >= 0) {
            tiles[index] = tiles[index].copy(x = newX, y = newY)
            saveTiles(tiles)
        }
    }

    fun deleteTile(tileId: String) {
        val tiles = loadTiles().filter { it.id != tileId }
        saveTiles(tiles)
    }

    @Synchronized
    fun getTilesByContainer(container: String): List<TileEntity> {
        val tiles = loadTiles()
        return cachedByContainer.getOrPut(container) {
            java.util.Collections.unmodifiableList(tiles.filter { it.container == container && it.enabled }
                .sortedWith(compareBy({ it.y }, { it.x })))
        }
    }

    @Synchronized
    fun getAllTiles(): List<TileEntity> {
        val tiles = loadTiles()
        return cachedEnabled ?: java.util.Collections.unmodifiableList(tiles.filter { it.enabled }
            .sortedWith(compareBy({ it.container }, { it.y }, { it.x }))).also { cachedEnabled = it }
    }

    companion object {
        const val DAMAGED_PREFIX = "tiles_damaged_"
        const val DAMAGED_KEEP = 3

        /**
         * Исходная строка повреждённых плиток — во внутреннюю папку копий (`files/backups`, вне облачного
         * бэкапа Android и вне ротации автокопий). Та же строка повторно не пишется. true — оригинал сохранён.
         */
        fun preserveDamaged(context: Context, raw: String, now: Long = System.currentTimeMillis()): Boolean = runCatching {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val existing = dir.listFiles { file -> file.name.startsWith(DAMAGED_PREFIX) }.orEmpty().sortedBy { it.name }
            if (existing.any { it.readText(Charsets.UTF_8) == raw }) return@runCatching true
            val name = DAMAGED_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date(now)) + ".json"
            val file = File(dir, name)
            file.writeText(raw, Charsets.UTF_8)
            if (file.readText(Charsets.UTF_8) != raw) return@runCatching false
            (existing + file).sortedBy { it.name }.dropLast(DAMAGED_KEEP).forEach { it.delete() }
            true
        }.getOrDefault(false)
    }
}