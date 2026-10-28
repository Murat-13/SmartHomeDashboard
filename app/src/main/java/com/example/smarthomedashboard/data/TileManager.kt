package com.example.smarthomedashboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val sourceType: String = "auto"
)

class TileManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Плитки читаются на каждое событие HA, а разбор JSON дорог. Кеш привязан к строке
    // из prefs: любая запись (другим экземпляром, импортом настроек) меняет строку и сбрасывает его.
    // Списки неизменяемые, поэтому повторное чтение без изменений возвращает тот же объект.
    private var cachedJson: String? = null
    private var cachedTiles: List<TileEntity> = emptyList()
    private var cachedEnabled: List<TileEntity>? = null
    private val cachedByContainer = mutableMapOf<String, List<TileEntity>>()

    fun saveTiles(tiles: List<TileEntity>) {
        val json = gson.toJson(tiles)
        prefs.edit { putString("tiles", json) }
    }

    @Synchronized
    fun loadTiles(): List<TileEntity> {
        val json = prefs.getString("tiles", "[]") ?: "[]"
        if (json != cachedJson) {
            val type = object : TypeToken<List<TileEntity>>() {}.type
            val parsed: List<TileEntity> = gson.fromJson(json, type)
            cachedTiles = java.util.Collections.unmodifiableList(ArrayList(parsed))
            cachedJson = json
            cachedEnabled = null
            cachedByContainer.clear()
        }
        return cachedTiles
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
}