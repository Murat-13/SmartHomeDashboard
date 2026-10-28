package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Координаты плиток — пиксели свободного размещения на панели MainActivity.
 * Размеры повторяют createSensorView/getButtonSize, отступы — findFreeSpot.
 */
internal object TilePlacement {
    const val WIDGET_WIDTH = 220
    const val WIDGET_HEIGHT = (WIDGET_WIDTH * 0.82).toInt()
    const val BUTTON_SIZE = 160

    data class Size(val width: Int, val height: Int)

    fun size(container: String, config: String): Size {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return if (container == "bottom_panel") {
            val side = json.optInt("button_size", BUTTON_SIZE)
            Size(side, side)
        } else {
            Size(json.optInt("button_size", WIDGET_WIDTH), json.optInt("widget_height", WIDGET_HEIGHT))
        }
    }

    /** Первое место сверху слева, не перекрывающее плитки и кнопки режима редактирования. */
    fun findFreeSpot(tiles: List<TileEntity>, width: Int, height: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val occupied = tiles.map { tile ->
            val size = size(tile.container, tile.config)
            intArrayOf(tile.x, tile.y, size.width, size.height)
        } + listOf(
            // Кнопки «Настройки» и «+», которые видны, пока пользователь добавляет плитки.
            intArrayOf(screenW - BUTTON_SIZE - 20, screenH - BUTTON_SIZE - 80, BUTTON_SIZE, BUTTON_SIZE),
            intArrayOf(screenW - BUTTON_SIZE * 2 - 40, screenH - BUTTON_SIZE - 80, BUTTON_SIZE, BUTTON_SIZE)
        )
        fun free(x: Int, y: Int) = occupied.none { (ox, oy, ow, oh) ->
            x < ox + ow && x + width > ox && y < oy + oh && y + height > oy
        }
        val step = 20
        var y = 40
        while (y + height < screenH - 100) {
            var x = 20
            while (x + width < screenW - 20) {
                if (free(x, y)) return Pair(x, y)
                x += step
            }
            y += step
        }
        return Pair(20, (screenH - height - 80).coerceAtLeast(0))
    }
}
