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

    @Test fun reservedCornerOfFormerControlButtonsIsUsedOnlyWhenNothingElseIsFree() {
        // Свободен только правый нижний участок (x ≥ 1520, y ≥ 840) — место прежних кнопок «Настройки» и «+».
        // Пока есть другое свободное место, оно не занимается (тесты выше); другого нет — плитка встаёт сюда, а не
        // поверх соседей: самих кнопок на экране давно нет.
        val top = sensor(0, 0, """{"button_size":1920,"widget_height":840}""")
        val left = sensor(0, 840, """{"button_size":1520,"widget_height":360}""")
        assertEquals(1520 to 840, TilePlacement.findFreeSpot(listOf(top, left), 160, 160, w, h))
        assertEquals(0L, TilePlacement.place(listOf(top, left), "bottom_panel", 160, 160, w, h).overlap)
    }

    /** Площадь, которую прямоугольник закрывает у плиток (как их рисует панель). */
    private fun covered(x: Int, y: Int, width: Int, height: Int, tiles: List<TileEntity>): Long = tiles.sumOf { tile ->
        val size = TilePlacement.displaySize(tile.container, tile.config, w, h)
        val (tx, ty) = TilePlacement.position(tile.x.toFloat(), tile.y.toFloat(), size.width, size.height, w, h)
        val dx = minOf(x + width, tx.toInt() + size.width) - maxOf(x, tx.toInt())
        val dy = minOf(y + height, ty.toInt() + size.height) - maxOf(y, ty.toInt())
        if (dx > 0 && dy > 0) dx.toLong() * dy else 0L
    }

    @Test fun fullPanelPicksPlaceWithLeastOverlap() {
        val tiles = mutableListOf<TileEntity>()
        var y = 0
        while (y < h) { var x = 0; while (x < w) { tiles += button(x, y, 200); x += 200 }; y += 200 }
        val spot = TilePlacement.place(tiles, "grid", 220, 180, w, h)
        assertTrue("Свободного места нет — наложение честно сообщается", spot.overlap > 0)
        assertTrue("В пределах панели", spot.x >= 0 && spot.y >= 0 && spot.x + 220 <= w && spot.y + 180 <= h - 60)
        assertTrue("Не хуже прежнего левого нижнего угла",
            covered(spot.x, spot.y, 220, 180, tiles) <= covered(20, h - 180 - 80, 220, 180, tiles))
        assertEquals("Выбор один и тот же при повторе", spot, TilePlacement.place(tiles, "grid", 220, 180, w, h))
    }

    /** Раскладка как на планшете 21.11.2026: сверху ряд карточек, домофон, инвертор, маленькая камера, снизу кнопки. */
    private fun crowdedPage(): List<TileEntity> = listOf(
        sensor(0, 0, """{"button_size":1920,"widget_height":360}"""),
        sensor(615, 369, """{"button_size":596,"widget_height":447}"""),
        sensor(1402, 375, """{"button_size":500,"widget_height":480}"""),
        sensor(20, 380, """{"button_size":228,"widget_height":171}""")
    ) + (0 until 8).map { button(15 + it * 203, 915, 187) }

    @Test fun crowdedPageDoesNotPutTileUnderBottomButtons() {
        val tiles = crowdedPage()
        val buttons = tiles.filter { it.container == "bottom_panel" }
        // Плитке 480×360 не хватает нескольких пикселей над рядом кнопок: раньше она вставала в левый нижний угол,
        // под кнопки (на 205 px в высоту), теперь — туда, где наложение наименьшее.
        val spot = TilePlacement.place(tiles, "grid", 480, 360, w, h)
        assertTrue(spot.overlap > 0)
        val before = covered(20, h - 360 - 80, 480, 360, buttons)
        val now = covered(spot.x, spot.y, 480, 360, buttons)
        assertTrue("Под кнопками было $before px², стало $now", now * 20 < before)
        assertTrue("Сосед задет не больше чем полоской в 12 px", covered(spot.x, spot.y, 480, 360, tiles) <= 480L * 12)
    }

    @Test fun videoTileShrinksToFitInsteadOfOverlapping() {
        val tiles = crowdedPage()
        val spot = TilePlacement.place(tiles, "grid", 480, 360, w, h, TilePlacement.VIDEO_SCALES)
        assertEquals("Нашлось свободное место под меньший размер", 0L, spot.overlap)
        assertEquals("Наибольший из помещающихся размеров, пропорция 4:3", 408 to 306, spot.width to spot.height)
        assertEquals(0L, covered(spot.x, spot.y, spot.width, spot.height, tiles))
        // На свободной странице размер полный.
        assertEquals(480 to 360, TilePlacement.place(emptyList(), "grid", 480, 360, w, h, TilePlacement.VIDEO_SCALES)
            .let { it.width to it.height })
    }

    @Test fun gridTilePrefersCoveringGridNeighbourToHidingUnderButtons() {
        // Свободного места нет: слева кнопки нижнего ряда (рисуются поверх плиток сетки), справа плитка сетки.
        val buttons = (0 until 5).flatMap { row -> (0 until 5).map { button(it * 200, row * 220, 220) } }
        val grid = sensor(1000, 0, """{"button_size":920,"widget_height":1140}""")
        val spot = TilePlacement.place(buttons + grid, "grid", 220, 180, w, h)
        assertEquals("Плитка не уходит под кнопки", 0L, covered(spot.x, spot.y, 220, 180, buttons))
        // Новой кнопке всё равно: она рисуется поверх и тех, и других — берётся первое место с наименьшим наложением.
        assertTrue(TilePlacement.place(buttons + grid, "bottom_panel", 220, 180, w, h).overlap > 0)
    }

    @Test fun newSizeKeepsOtherSettingsOfTile() {
        val json = org.json.JSONObject(TilePlacement.withSize("""{"button_size":480,"widget_height":360,"doorbell":{"talk":true}}""", 336, 252))
        assertEquals(336, json.getInt("button_size"))
        assertEquals(252, json.getInt("widget_height"))
        assertTrue(json.getJSONObject("doorbell").getBoolean("talk"))
        assertEquals(TilePlacement.Size(336, 252), TilePlacement.size("grid", json.toString()))
    }
}
