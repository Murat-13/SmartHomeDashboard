package com.example.smarthomedashboard

import com.example.smarthomedashboard.PortraitLayout.Place
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Где стоит каждая плитка на текущем экране (docs/adaptive-layout.md).
 *
 * - Горизонтально — как всегда: сохранённые `x/y` и размеры; [plan] возвращает null, панель работает по-старому.
 * - Вертикально — своя раскладка владельца (поле `portrait`, если у страницы оно есть хотя бы у одной плитки) или
 *   автоматическая из горизонтальной ([PortraitLayout]). Страница может занять несколько экранов-продолжений.
 *
 * Данные плиток не меняются: панель строит плитку по «действующей» копии ([effective]) с местом и размером экрана.
 */
internal class PanelScreens(private val density: Float) {
    class Plan(val places: Map<String, Place>, val parts: Int, val custom: Boolean)

    private var cachedTiles: List<TileEntity>? = null
    private var cachedSize = 0L
    private val cache = mutableMapOf<Int, Plan>()

    val gap get() = (12 * density).roundToInt()
    val margin get() = (12 * density).roundToInt()

    /** null — горизонтальная ориентация (раскладка по сохранённым x/y). [all] — включённые плитки всех страниц. */
    fun plan(all: List<TileEntity>, page: Int, width: Int, height: Int): Plan? {
        if (height <= width || width <= 0) return null
        val size = (width.toLong() shl 32) or height.toLong()
        // Список плиток TileManager неизменяем и не пересоздаётся без записи — сравнение по ссылке.
        if (all !== cachedTiles || size != cachedSize) {
            cache.clear()
            cachedTiles = all
            cachedSize = size
        }
        return cache.getOrPut(page) { build(all.filter { it.page == page && it.container in CONTAINERS }, width, height) }
    }

    private fun build(tiles: List<TileEntity>, width: Int, height: Int): Plan {
        val stored = tiles.associate { it.id to Place.parse(it.portrait) }
        if (stored.values.none { it != null }) {
            val items = tiles.map { tile ->
                val size = TilePlacement.size(tile.container, tile.config)
                PortraitLayout.Item(tile.id, tile.container == BOTTOM, tile.x, tile.y, size.width, size.height)
            }
            val result = PortraitLayout.auto(items, width, height, gap, margin)
            return Plan(result.places, result.parts, custom = false)
        }
        // Своя раскладка: сохранённые места; плитки, добавленные после неё, — на первое свободное место.
        val places = stored.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        tiles.filter { places[it.id] == null }.forEach { tile ->
            val size = TilePlacement.size(tile.container, tile.config)
            val w = size.width.coerceAtMost(width - 2 * margin).coerceAtLeast(1)
            val h = size.height.coerceAtMost(height - 60 - 2 * margin).coerceAtLeast(1)
            places[tile.id] = freeSpot(places.values, w, h, width, height, if (tile.container == BOTTOM) PortraitLayout.ALL_PARTS else null)
        }
        val parts = (places.values.maxOfOrNull { it.part } ?: 0) + 1
        return Plan(places, parts.coerceAtLeast(1), custom = true)
    }

    /** Первое место сверху слева без наложений; [fixedPart] — для кнопок (видны на всех экранах страницы). */
    private fun freeSpot(taken: Collection<Place>, w: Int, h: Int, width: Int, height: Int, fixedPart: Int?): Place {
        val step = (8 * density).roundToInt().coerceAtLeast(4)
        var part = 0
        while (part < 64) {
            val target = fixedPart ?: part
            val busy = if (fixedPart != null) taken.toList() else taken.filter { PortraitLayout.visible(it, target) }
            var y = margin
            while (y + h <= height - 60) {
                var x = margin
                while (x + w <= width - margin) {
                    if (busy.none { x < it.x + it.w + gap && it.x < x + w + gap && y < it.y + it.h + gap && it.y < y + h + gap }) {
                        return Place(x, y, w, h, target)
                    }
                    x += step
                }
                y += step
            }
            if (fixedPart != null) break
            part++
        }
        return Place(margin, margin, w, h, fixedPart ?: part)
    }

    /** Копия плитки с местом и размером экрана: панель строит её как обычно, сохранённые данные не меняются. */
    fun effective(tile: TileEntity, place: Place): TileEntity {
        val config = try { JSONObject(tile.config) } catch (_: JSONException) { return tile.copy(x = place.x, y = place.y) }
        config.put("button_size", place.w)
        if (tile.container != BOTTOM) config.put("widget_height", place.h)
        return tile.copy(x = place.x, y = place.y, config = config.toString())
    }

    companion object {
        const val BOTTOM = "bottom_panel"
        private val CONTAINERS = setOf("grid", BOTTOM)
    }
}
