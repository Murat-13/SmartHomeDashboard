package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json в юнит-тестах доступен только через Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TilePlacementTest {
    private val w = 1920
    private val h = 1200
    private fun sensor(x: Int, y: Int, config: String = "{}") = TileEntity("s$x-$y", "sensor", "grid", "S", x, y, 1, 1, config = config)
    private fun button(x: Int, y: Int, size: Int = 160) = TileEntity("b$x-$y", "button", "bottom_panel", "B", x, y, 1, 1,
        config = """{"button_size":$size}""")
    private fun overlaps(x: Int, y: Int, width: Int, height: Int, tile: TileEntity): Boolean {
        val size = TilePlacement.size(tile.container, tile.config)
        return x < tile.x + size.width && x + width > tile.x && y < tile.y + size.height && y + height > tile.y
    }

    @Test fun displaySizesClampWithoutChangingTheRequestedSizeContract() {
        assertEquals(TilePlacement.Size(1140, 1140), TilePlacement.displaySize("bottom_panel",
            """{"button_size":2147483647}""", w, h))
        assertEquals(TilePlacement.Size(400, 300), TilePlacement.displaySize("grid",
            """{"button_size":400,"widget_height":300}""", w, h))
        assertEquals(TilePlacement.Size(100, 20), TilePlacement.displaySize("grid", "{}", 100, 80))
        assertEquals(TilePlacement.Size(1, 1), TilePlacement.displaySize("bottom_panel", "{}", 0, 0))
    }

    @Test fun nonpositiveDimensionsArePixelFallbacksNotAndroidLayoutConstants() {
        assertEquals(TilePlacement.Size(160, 160), TilePlacement.size("bottom_panel", """{"button_size":-1}"""))
        assertEquals(TilePlacement.Size(220, 180), TilePlacement.size("grid", """{"button_size":0,"widget_height":-2}"""))
        assertEquals(TilePlacement.Size(1, 2), TilePlacement.size("grid", """{"button_size":1,"widget_height":2}"""))
    }

    @Test fun positionsUseActualSizeAndNeverAnEmptyRange() {
        assertEquals(1520f to 840f, TilePlacement.position(1700f, 1000f, 400, 300, w, h))
        assertEquals(0f to 0f, TilePlacement.position(20f, -10f, Int.MAX_VALUE, Int.MAX_VALUE, 100, 80))
        assertEquals(20f to 30f, TilePlacement.position(20f, 30f, 220, 180, w, h))
        assertEquals(0f to 0f, TilePlacement.position(-10f, -20f, 160, 160, w, h))
        assertEquals(1760f to 980f, TilePlacement.position(1760f, 980f, 160, 160, w, h))
    }

    @Test fun emptyPanelStartsAtTopLeftMargin() {
        assertEquals(20 to 40, TilePlacement.findFreeSpot(emptyList(), 220, 180, w, h))
    }

    @Test fun sizesFollowPanelRendering() {
        assertEquals(TilePlacement.Size(220, 180), TilePlacement.size("grid", "{}"))
        assertEquals(TilePlacement.Size(300, 150), TilePlacement.size("grid", """{"button_size":300,"widget_height":150}"""))
        assertEquals(TilePlacement.Size(160, 160), TilePlacement.size("bottom_panel", "{}"))
        assertEquals(TilePlacement.Size(240, 240), TilePlacement.size("bottom_panel", """{"button_size":240}"""))
        assertEquals(TilePlacement.Size(220, 180), TilePlacement.size("grid", "{broken"))
    }

    @Test fun freeSpotUsesVisibleSizeOfOversizedLegacyButton() {
        val old = button(0, 0, Int.MAX_VALUE)
        // На панели эта кнопка ограничена квадратом 1140 px; справа есть свободное место.
        assertEquals(1140 to 40, TilePlacement.findFreeSpot(listOf(old), 220, 180, w, h))
    }

    @Test fun newTilesDoNotStackOnEachOther() {
        val tiles = mutableListOf<TileEntity>()
        repeat(6) {
            val (x, y) = TilePlacement.findFreeSpot(tiles, 220, 180, w, h)
            tiles.forEach { assertFalse("Плитка $it перекрыта", overlaps(x, y, 220, 180, it)) }
            tiles += sensor(x, y)
        }
        assertEquals(6, tiles.map { it.x to it.y }.toSet().size)
    }

    @Test fun avoidsLegacyCornerTilesAndLargeWidgets() {
        // Так раньше сохранялись новые плитки: номер ячейки вместо пикселей.
        val legacy = listOf(sensor(0, 0), sensor(1, 0), sensor(2, 0))
        val big = sensor(260, 40, """{"button_size":600,"widget_height":400}""")
        val (x, y) = TilePlacement.findFreeSpot(legacy + big, 220, 180, w, h)
        (legacy + big).forEach { assertFalse(overlaps(x, y, 220, 180, it)) }
    }

    @Test fun avoidsEditModeControlButtons() {
        // Свободен только правый нижний участок (x ≥ 1520, y ≥ 840). Любая кнопка 160×160
        // там задела бы «Настройки» (1740, 960) или «+» (1560, 960), поэтому нужен запасной ряд.
        val top = sensor(0, 0, """{"button_size":1920,"widget_height":840}""")
        val left = sensor(0, 840, """{"button_size":1520,"widget_height":360}""")
        assertEquals(20 to h - 160 - 80, TilePlacement.findFreeSpot(listOf(top, left), 160, 160, w, h))
    }

    @Test fun fullPanelFallsBackToVisibleBottomRow() {
        val tiles = mutableListOf<TileEntity>()
        var y = 0
        while (y < h) { var x = 0; while (x < w) { tiles += button(x, y, 200); x += 200 }; y += 200 }
        assertEquals(20 to h - 180 - 80, TilePlacement.findFreeSpot(tiles, 220, 180, w, h))
    }
}
