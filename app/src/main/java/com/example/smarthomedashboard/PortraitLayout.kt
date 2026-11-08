package com.example.smarthomedashboard

import kotlin.math.roundToInt

/**
 * Вертикальная раскладка панели, построенная из горизонтальной (docs/adaptive-layout.md).
 *
 * Плитки читаются как текст — ряды сверху вниз, в ряду слева направо — и в узком экране переносятся на новые строки
 * в том же порядке, без наложений. Две зоны, как в горизонтали: виджеты (`grid`) — сверху вниз от верхнего поля,
 * кнопки (`bottom_panel`) — снизу вверх: нижний ряд полный (первые кнопки по порядку), остаток — рядом выше.
 * Не влезло — все плитки равномерно уменьшаются до [MIN_SCALE]; не влезло и так — лишние виджеты уходят на
 * экраны-продолжения (part 1, 2…), кнопки повторяются на каждом экране страницы.
 *
 * Чистая функция без Android: один и тот же вход — одна и та же раскладка.
 */
internal object PortraitLayout {
    const val MIN_SCALE = 0.75f

    data class Item(val id: String, val bottom: Boolean, val x: Int, val y: Int, val w: Int, val h: Int)

    data class Place(val x: Int, val y: Int, val w: Int, val h: Int, val part: Int = 0) {
        fun json(): String = """{"x":$x,"y":$y,"w":$w,"h":$h,"part":$part}"""

        companion object {
            /** Сохранённое место из `TileEntity.portrait`; пустое или повреждённое — null. */
            fun parse(json: String?): Place? {
                if (json.isNullOrBlank()) return null
                return try {
                    val o = org.json.JSONObject(json)
                    val w = o.optInt("w", 0)
                    val h = o.optInt("h", 0)
                    if (!o.has("x") || !o.has("y") || w <= 0 || h <= 0) null
                    else Place(o.optInt("x"), o.optInt("y"), w, h, o.optInt("part", 0).coerceIn(ALL_PARTS, MAX_PARTS - 1))
                } catch (_: org.json.JSONException) {
                    null
                }
            }
        }
    }

    data class Result(val places: Map<String, Place>, val parts: Int, val scale: Float)

    /**
     * [width]×[height] — панель в портрете (px); [reserveBottom] — нижняя полоса, куда плитки не ставятся
     * (как `TilePlacement.position`); [gap] и [margin] — зазор между плитками и поле у краёв (px).
     */
    fun auto(items: List<Item>, width: Int, height: Int, gap: Int, margin: Int, reserveBottom: Int = 60): Result {
        if (items.isEmpty() || width <= 0 || height <= 0) return Result(emptyMap(), 1, 1f)
        val top = readingOrder(items.filter { !it.bottom })
        val bottom = readingOrder(items.filter { it.bottom })
        val usableW = (width - 2 * margin).coerceAtLeast(1)
        val floor = height - reserveBottom
        var scale = 1f
        while (true) {
            // Кнопки занимают не больше половины высоты: при очень большом числе — уменьшаются сильнее остальных.
            var buttonScale = scale
            var buttons = flow(bottom, buttonScale, usableW, gap, Int.MAX_VALUE)
            while (buttons.height > floor / 2 && buttonScale > 0.2f) {
                buttonScale *= 0.9f
                buttons = flow(bottom, buttonScale, usableW, gap, Int.MAX_VALUE)
            }
            val buttonsTop = floor - buttons.height
            val available = (buttonsTop - (if (buttons.rows.isEmpty()) 0 else gap) - margin).coerceAtLeast(1)
            val widgets = flow(top, scale, usableW, gap, available)
            if (widgets.height <= available || scale <= MIN_SCALE) {
                return place(widgets, buttons, width, margin, buttonsTop, gap, available, scale)
            }
            scale = (scale - 0.05f).coerceAtLeast(MIN_SCALE)
        }
    }

    /** Порядок чтения: ряды по перекрытию по вертикали (центр плитки внутри полосы ряда), в ряду — слева направо. */
    fun readingOrder(items: List<Item>): List<Item> {
        val rows = mutableListOf<MutableList<Item>>()
        var rowTop = 0
        var rowBottom = 0
        items.sortedWith(compareBy({ it.y }, { it.x }, { it.id })).forEach { item ->
            val center = item.y + item.h / 2
            if (rows.isNotEmpty() && center in rowTop..rowBottom) {
                rows.last() += item
                rowBottom = maxOf(rowBottom, item.y + item.h)
            } else {
                rows += mutableListOf(item)
                rowTop = item.y
                rowBottom = item.y + item.h
            }
        }
        return rows.flatMap { row -> row.sortedWith(compareBy({ it.x }, { it.id })) }
    }

    private class Row(val items: List<Pair<Item, Pair<Int, Int>>>, val width: Int, val height: Int)
    private class Flow(val rows: List<Row>, val height: Int)

    /** Размер с масштабом; шире экрана или выше свободной высоты — уменьшается с сохранением пропорций. */
    private fun sized(item: Item, scale: Float, usableW: Int, maxH: Int): Pair<Int, Int> {
        var w = (item.w * scale).roundToInt().coerceAtLeast(1)
        var h = (item.h * scale).roundToInt().coerceAtLeast(1)
        if (w > usableW) { h = (h * usableW / w.toFloat()).roundToInt().coerceAtLeast(1); w = usableW }
        if (h > maxH) { w = (w * maxH / h.toFloat()).roundToInt().coerceAtLeast(1); h = maxH }
        return w to h
    }

    /** Перенос по словам: в строку, пока помещается по ширине. */
    private fun rows(items: List<Item>, scale: Float, usableW: Int, gap: Int, maxH: Int): List<Row> {
        val rows = mutableListOf<Row>()
        var current = mutableListOf<Pair<Item, Pair<Int, Int>>>()
        var used = 0
        fun close() {
            if (current.isEmpty()) return
            rows += Row(current, used, current.maxOf { it.second.second })
            current = mutableListOf()
            used = 0
        }
        items.forEach { item ->
            val size = sized(item, scale, usableW, maxH)
            val next = if (current.isEmpty()) size.first else used + gap + size.first
            if (current.isNotEmpty() && next > usableW) close()
            used = if (current.isEmpty()) size.first else used + gap + size.first
            current += item to size
        }
        close()
        return rows
    }

    private fun flow(items: List<Item>, scale: Float, usableW: Int, gap: Int, maxH: Int): Flow {
        val rows = rows(items, scale, usableW, gap, maxH)
        return Flow(rows, rows.sumOf { it.height } + gap * (rows.size - 1).coerceAtLeast(0))
    }

    private fun place(widgets: Flow, buttons: Flow, width: Int, margin: Int, buttonsTop: Int, gap: Int,
                      available: Int, scale: Float): Result {
        val places = mutableMapOf<String, Place>()
        fun left(rows: List<Row>): Int {
            // Блок строк — по центру экрана, строки внутри — по левому краю блока.
            val block = rows.maxOfOrNull { it.width } ?: 0
            return ((width - block) / 2).coerceAtLeast(margin)
        }
        // Виджеты: сверху вниз; не влезшая строка начинает экран-продолжение.
        var part = 0
        var y = margin
        val wx = left(widgets.rows)
        widgets.rows.forEach { row ->
            if (y > margin && y + row.height > margin + available) {
                part++
                y = margin
            }
            var x = wx
            row.items.forEach { (item, size) ->
                places[item.id] = Place(x, y, size.first, size.second, part)
                x += size.first + gap
            }
            y += row.height + gap
        }
        // Кнопки: нижний ряд — первая строка потока, выше — следующие.
        val bx = left(buttons.rows)
        var bottomEdge = buttonsTop + buttons.height
        buttons.rows.forEach { row ->
            val rowTop = bottomEdge - row.height
            var x = bx
            row.items.forEach { (item, size) ->
                places[item.id] = Place(x, rowTop + (row.height - size.second), size.first, size.second, ALL_PARTS)
                x += size.first + gap
            }
            bottomEdge = rowTop - gap
        }
        return Result(places, part + 1, scale)
    }

    /** Кнопки нижней зоны видны на каждом экране страницы. */
    const val ALL_PARTS = -1
    private const val MAX_PARTS = 64

    /** Место плитки виджетов на экране части [part] (кнопки — на любой). */
    fun visible(place: Place, part: Int) = place.part == ALL_PARTS || place.part == part
}
