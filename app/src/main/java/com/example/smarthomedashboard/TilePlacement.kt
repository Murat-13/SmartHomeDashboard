package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/**
 * Координаты плиток — пиксели свободного размещения на панели MainActivity.
 * Общие размеры и границы используются и панелью, и поиском свободного места.
 */
internal object TilePlacement {
    const val WIDGET_WIDTH = 220
    const val WIDGET_HEIGHT = (WIDGET_WIDTH * 0.82).toInt()
    const val BUTTON_SIZE = 160

    data class Size(val width: Int, val height: Int)

    fun size(container: String, config: String): Size {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return if (container == "bottom_panel") {
            val side = json.optInt("button_size", BUTTON_SIZE).takeIf { it > 0 } ?: BUTTON_SIZE
            Size(side, side)
        } else {
            Size(json.optInt("button_size", WIDGET_WIDTH).takeIf { it > 0 } ?: WIDGET_WIDTH,
                json.optInt("widget_height", WIDGET_HEIGHT).takeIf { it > 0 } ?: WIDGET_HEIGHT)
        }
    }

    /** Нижние 60 px, как прежде, оставляем за пределами свободного перемещения плиток. */
    fun maxButtonSize(screenW: Int, screenH: Int): Int =
        minOf(screenW.coerceAtLeast(1), (screenH - 60).coerceAtLeast(1))

    /** Ограничиваем только отображение старых размеров; сохранённый config не изменяем. */
    fun displaySize(container: String, config: String, screenW: Int, screenH: Int): Size {
        val saved = size(container, config)
        return if (container == "bottom_panel") {
            val side = saved.width.coerceAtMost(maxButtonSize(screenW, screenH))
            Size(side, side)
        } else Size(saved.width.coerceAtMost(screenW.coerceAtLeast(1)),
            saved.height.coerceAtMost((screenH - 60).coerceAtLeast(1)))
    }

    /** Общие границы для первого размещения и drag, в том числе при узком окне. */
    fun position(x: Float, y: Float, width: Int, height: Int, screenW: Int, screenH: Int): Pair<Float, Float> =
        x.coerceIn(0f, (screenW.toFloat() - width).coerceAtLeast(0f)) to
            y.coerceIn(0f, (screenH.toFloat() - height - 60).coerceAtLeast(0f))

    /**
     * Отступ подсказки от нижнего края, чтобы она стояла над нижним рядом кнопок.
     * Кнопки расставлены свободно, поэтому «нижний ряд» — это кнопки, чей центр ниже
     * середины экрана; отступ считается от верхнего края самой высокой из них.
     * [buttons] — пары (top, height) в пикселях панели.
     */
    fun hintBottomMargin(buttons: List<Pair<Float, Int>>, panelHeight: Int, gap: Int, minMargin: Int): Int {
        val rowTop = buttons.filter { (top, height) -> top + height / 2f > panelHeight / 2f }
            .minOfOrNull { it.first } ?: return minMargin
        // Подсказка не уходит выше середины экрана даже при необычной раскладке.
        return (panelHeight - rowTop.toInt() + gap).coerceIn(minMargin, maxOf(minMargin, panelHeight / 2))
    }

    /** Первое место сверху слева, не перекрывающее плитки и кнопки режима редактирования. */
    fun findFreeSpot(tiles: List<TileEntity>, width: Int, height: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val occupied = tiles.map { tile ->
            // Занято именно отображаемое место, как при восстановлении панели.
            val size = displaySize(tile.container, tile.config, screenW, screenH)
            val (x, y) = position(tile.x.toFloat(), tile.y.toFloat(), size.width, size.height, screenW, screenH)
            intArrayOf(x.toInt(), y.toInt(), size.width, size.height)
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
