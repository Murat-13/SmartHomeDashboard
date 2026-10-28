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

    fun saveTiles(tiles: List<TileEntity>) {
        val json = gson.toJson(tiles)
        prefs.edit { putString("tiles", json) }
    }

    fun loadTiles(): List<TileEntity> {
        val json = prefs.getString("tiles", "[]") ?: "[]"
        val type = object : TypeToken<List<TileEntity>>() {}.type
        return gson.fromJson(json, type)
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

    fun getTilesByContainer(container: String): List<TileEntity> {
        return loadTiles().filter { it.container == container && it.enabled }
            .sortedWith(compareBy({ it.y }, { it.x }))
    }

    fun getAllTiles(): List<TileEntity> {
        return loadTiles().filter { it.enabled }
            .sortedWith(compareBy({ it.container }, { it.y }, { it.x }))
    }
}