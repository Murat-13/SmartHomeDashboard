package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Подгонка плиток под экран другого размера (docs/setup-tz.md, часть 5): место и размеры плиток — пиксели, поэтому на другом
 * экране они стоят иначе. Всё умножается на одно число — раскладка остаётся той же, плитки не накладываются. Чистая функция.
 */
internal object TileScale {
    /** Панель в пикселях и плотность экрана; положение планшета не важно. */
    data class Screen(val w: Int, val h: Int, val density: Float) {
        val long get() = max(w, h)
        val short get() = min(w, h)
    }

    /** Нижняя полоса, куда плитки не ставятся ([TilePlacement]). */
    private const val RESERVE = 60
    private const val SAME = 0.03f
    private const val MAX_FONT = 200

    /** Во сколько раз изменить горизонтальную раскладку: вся помещается и занимает экран. */
    fun landscape(from: Screen, to: Screen) = factor(from.long, from.short, to.long, to.short)
    fun portrait(from: Screen, to: Screen) = factor(from.short, from.long, to.short, to.long)

    private fun factor(w: Int, h: Int, newW: Int, newH: Int) =
        min(newW.toFloat() / w.coerceAtLeast(1), (newH - RESERVE).coerceAtLeast(1).toFloat() / (h - RESERVE).coerceAtLeast(1))

    /** Экран заметно другой — есть что предложить. */
    fun needed(from: Screen, to: Screen) = abs(landscape(from, to) - 1f) > SAME

    /** Список плиток (`tiles_json`) под экран [to]. Неизвестные поля и испорченные плитки — как были. */
    fun apply(tiles: String, from: Screen, to: Screen): String {
        val list = try { JSONArray(tiles) } catch (_: JSONException) { return tiles }
        val s = landscape(from, to)
        val p = portrait(from, to)
        // Размер текста классических плиток — sp: на другой плотности он сам становится другим.
        val font = s * from.density / to.density
        for (i in 0 until list.length()) {
            val tile = list.optJSONObject(i) ?: continue
            tile.put("x", down(tile.optInt("x"), s)).put("y", down(tile.optInt("y"), s))
            size(tile, s)
            if (tile.has("fontSize")) tile.put("fontSize", (tile.optInt("fontSize") * font).roundToInt().coerceIn(1, MAX_FONT))
            PortraitLayout.Place.parse(tile.optString("portrait"))?.let { place ->
                tile.put("portrait", PortraitLayout.Place(down(place.x, p), down(place.y, p), down(place.w, p).coerceAtLeast(1),
                    down(place.h, p).coerceAtLeast(1), place.part).json())
            }
        }
        return list.toString()
    }

    private fun size(tile: JSONObject, s: Float) {
        val text = tile.optString("config", "{}")
        val config = try { JSONObject(text) } catch (_: JSONException) { return }
        val container = tile.optString("container")
        val old = TilePlacement.size(container, text)
        // «Климат» и другие плитки с точной пропорцией 2:1 её не теряют.
        val half = container != BOTTOM && old.width == old.height * 2
        val width = down(old.width, s).coerceAtLeast(1).let { if (half) (it / 2 * 2).coerceAtLeast(2) else it }
        config.put("button_size", width)
        if (container != BOTTOM) config.put("widget_height", if (half) width / 2 else down(old.height, s).coerceAtLeast(1))
        tile.put("config", config.toString())
    }

    private fun down(value: Int, s: Float) = floor(value * s + EPSILON).toInt()
    private const val EPSILON = 0.001f
    private const val BOTTOM = "bottom_panel"
}
