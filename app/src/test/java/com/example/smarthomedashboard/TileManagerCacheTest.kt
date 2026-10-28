package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TileManagerCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun tile(id: String, container: String = "grid", y: Int = 0, enabled: Boolean = true) =
        TileEntity(id, "sensor", container, id, 0, y, 1, 1, enabled = enabled)

    @Test fun unchangedPrefsReturnSameParsedList() {
        val manager = TileManager(context)
        manager.saveTiles(listOf(tile("a"), tile("b")))
        val first = manager.loadTiles()
        assertSame(first, manager.loadTiles())
        assertSame(manager.getAllTiles(), manager.getAllTiles())
        assertSame(manager.getTilesByContainer("grid"), manager.getTilesByContainer("grid"))
    }

    @Test fun writeFromAnotherInstanceIsVisibleImmediately() {
        val panel = TileManager(context)
        panel.saveTiles(listOf(tile("a")))
        val before = panel.getAllTiles()
        TileManager(context).saveTiles(listOf(tile("a"), tile("b")))
        assertEquals(listOf("a", "b"), panel.loadTiles().map { it.id })
        assertNotSame(before, panel.getAllTiles())
        assertEquals(listOf("a", "b"), panel.getAllTiles().map { it.id })
    }

    @Test fun directPrefsWriteFromSettingsImportIsVisible() {
        val panel = TileManager(context)
        panel.saveTiles(listOf(tile("old")))
        panel.loadTiles()
        // SettingsActivity.applyImportedConfig пишет строку напрямую, минуя TileManager.
        val imported = """[{"id":"new","type":"sensor","container":"grid","title":"N","x":1,"y":2,"width":1,"height":1,"enabled":true}]"""
        context.getSharedPreferences("tiles_prefs", 0).edit().putString("tiles", imported).commit()
        assertEquals(listOf("new"), panel.loadTiles().map { it.id })
        assertEquals(listOf("new"), panel.getTilesByContainer("grid").map { it.id })
    }

    @Test fun derivedListsKeepFilteringAndOrder() {
        val manager = TileManager(context)
        manager.saveTiles(listOf(
            tile("low", y = 50), tile("off", enabled = false), tile("top", y = 10),
            tile("btn", container = "bottom_panel", y = 0)
        ))
        assertEquals(listOf("top", "low"), manager.getTilesByContainer("grid").map { it.id })
        assertEquals(listOf("btn"), manager.getTilesByContainer("bottom_panel").map { it.id })
        assertEquals(listOf("btn", "top", "low"), manager.getAllTiles().map { it.id })
        assertEquals(4, manager.loadTiles().size)
    }

    @Test fun cachedListCannotBeMutatedByCallers() {
        val manager = TileManager(context)
        manager.saveTiles(listOf(tile("a")))
        listOf(manager.loadTiles(), manager.getAllTiles(), manager.getTilesByContainer("grid")).forEach { list ->
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (list as MutableList<TileEntity>).add(tile("x"))
            }
        }
        // Существующие вызовы копируют список перед изменением, и это продолжает работать.
        val copy = manager.loadTiles().toMutableList().apply { add(tile("b")) }
        manager.saveTiles(copy)
        assertEquals(listOf("a", "b"), manager.loadTiles().map { it.id })
    }
}
