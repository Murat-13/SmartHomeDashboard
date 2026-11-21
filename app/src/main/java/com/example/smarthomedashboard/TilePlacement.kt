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

    /** Место новой плитки. [overlap] — цена наложения на соседей (0 — место свободно). */
    data class Spot(val x: Int, val y: Int, val width: Int, val height: Int, val overlap: Long)

    /** Меньшие размеры для плитки с видео, если полный не помещается: пропорция та же, видео просто мельче. */
    val VIDEO_SCALES = listOf(1f, 0.85f, 0.7f, 0.6f, 0.5f)

    /** Первое место сверху слева, не перекрывающее плитки; свободного нет — место с наименьшим наложением. */
    fun findFreeSpot(tiles: List<TileEntity>, width: Int, height: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        place(tiles, "grid", width, height, screenW, screenH).let { it.x to it.y }

    /**
     * Место новой плитки [width]×[height] в контейнере [container]: первое свободное сверху слева. Свободного нет —
     * пробуются размеры меньше ([scales]: только для плиток, которые умеют уменьшаться), затем берётся место, где
     * плитка меньше всего накладывается на соседей.
     *
     * Наложение плитки сетки на кнопки нижнего ряда считается вчетверо дороже: кнопки рисуются поверх, и плитка
     * оказалась бы под ними. Раньше при нехватке места плитка всегда вставала в левый нижний угол — на планшете
     * новая «Камера» легла под ряд кнопок (21.11.2026, docs/camera-stand.md).
     */
    fun place(tiles: List<TileEntity>, container: String, width: Int, height: Int, screenW: Int, screenH: Int,
              scales: List<Float> = listOf(1f)): Spot {
        // Занято именно отображаемое место, как при восстановлении панели: x, y, ширина, высота, цена наложения.
        val occupied = tiles.map { tile ->
            val size = displaySize(tile.container, tile.config, screenW, screenH)
            val (x, y) = position(tile.x.toFloat(), tile.y.toFloat(), size.width, size.height, screenW, screenH)
            val above = container != "bottom_panel" && tile.container == "bottom_panel"
            intArrayOf(x.toInt(), y.toInt(), size.width, size.height, if (above) HIDDEN_COST else 1)
        } + listOf(
            // Место прежних кнопок «Настройки» и «+»: пока есть другое свободное, оно не занимается; само по себе
            // наложением не считается (кнопок на экране давно нет).
            intArrayOf(screenW - BUTTON_SIZE - 20, screenH - BUTTON_SIZE - 80, BUTTON_SIZE, BUTTON_SIZE, 0),
            intArrayOf(screenW - BUTTON_SIZE * 2 - 40, screenH - BUTTON_SIZE - 80, BUTTON_SIZE, BUTTON_SIZE, 0)
        )
        val step = 20
        var least: Spot? = null
        for (scale in scales.ifEmpty { listOf(1f) }) {
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            var leastHere: Spot? = null
            var y = 40
            while (y + h < screenH - 100) {
                var x = 20
                while (x + w < screenW - 20) {
                    var cost = 0L
                    var touching = false
                    for ((ox, oy, ow, oh, weight) in occupied) {
                        val dx = minOf(x + w, ox + ow) - maxOf(x, ox)
                        val dy = minOf(y + h, oy + oh) - maxOf(y, oy)
                        if (dx > 0 && dy > 0) { touching = true; cost += dx.toLong() * dy * weight }
                    }
                    if (!touching) return Spot(x, y, w, h, 0)
                    if (leastHere == null || cost < leastHere.overlap) leastHere = Spot(x, y, w, h, cost)
                    x += step
                }
                y += step
            }
            // Задето только место прежних кнопок — оно на деле свободно.
            if (leastHere?.overlap == 0L) return leastHere
            if (leastHere != null) least = leastHere
        }
        return least ?: Spot(20, (screenH - height - 80).coerceAtLeast(0), width, height, Long.MAX_VALUE)
    }

    /** Те же настройки плитки с другим размером (остальные ключи не трогаются). */
    fun withSize(config: String, width: Int, height: Int): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put("button_size", width).put("widget_height", height).toString()
    }

    private const val HIDDEN_COST = 4
}
