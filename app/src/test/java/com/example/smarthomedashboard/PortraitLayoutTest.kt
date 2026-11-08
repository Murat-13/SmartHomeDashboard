package com.example.smarthomedashboard

import com.example.smarthomedashboard.PortraitLayout.Item
import com.example.smarthomedashboard.PortraitLayout.Place
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PortraitLayoutTest {
    /** Раскладка, похожая на Huawei владельца (2000×1200): ряд виджетов сверху, ряд из десяти кнопок внизу. */
    private val owner = listOf(
        Item("grid1", false, 20, 20, 300, 260), Item("grid2", false, 340, 20, 300, 260),
        Item("bms", false, 660, 20, 560, 260), Item("climate", false, 1240, 20, 640, 320),
    ) + (0 until 10).map { Item("b$it", true, 20 + it * 180, 980, 160, 160) }

    private fun overlap(a: Place, b: Place) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun check(items: List<Item>, w: Int, h: Int, r: PortraitLayout.Result, gap: Int = 16) {
        assertEquals("Каждая плитка размещена", items.map { it.id }.toSet(), r.places.keys)
        for (part in 0 until r.parts) {
            val shown = r.places.values.filter { PortraitLayout.visible(it, part) }
            shown.forEach { p ->
                assertTrue("Внутри экрана: $p", p.x >= 0 && p.y >= 0 && p.x + p.w <= w && p.y + p.h <= h - 60)
            }
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                assertFalse("Нет наложений: ${shown[i]} / ${shown[j]}", overlap(shown[i], shown[j]))
            }
        }
        // Порядок чтения сохраняется: виджеты идут по частям, строкам и слева направо.
        val widgets = PortraitLayout.readingOrder(items.filter { !it.bottom }).map { r.places.getValue(it.id) }
        widgets.zipWithNext().forEach { (a, b) ->
            assertTrue("Порядок: $a → $b", a.part < b.part || (a.part == b.part && (b.y > a.y || (b.y == a.y && b.x > a.x))))
        }
    }

    @Test fun ownerLayoutTurnsIntoRowsWithoutOverlaps() {
        val r = PortraitLayout.auto(owner, 1200, 2000, gap = 16, margin = 16)
        check(owner, 1200, 2000, r)
        assertEquals("Всё на одном экране", 1, r.parts)
        assertEquals("Размер не уменьшен — места хватает", 1f, r.scale)
        val p = r.places
        // Виджеты: «Сеть», «Инвертор» и «АКБ» в первой строке не помещаются вместе с «Климатом» — перенос.
        assertEquals(p.getValue("grid1").y, p.getValue("grid2").y)
        assertTrue(p.getValue("climate").y > p.getValue("grid1").y)
        // Кнопки: нижний ряд полный (первые по порядку), остаток — выше.
        val buttons = (0 until 10).map { p.getValue("b$it") }
        val bottomY = buttons.maxOf { it.y }
        val bottomRow = buttons.filter { it.y == bottomY }
        assertEquals("Первые кнопки — в нижнем ряду", buttons.take(bottomRow.size), bottomRow)
        assertTrue("Нижний ряд заполнен: ${bottomRow.size}", bottomRow.size >= 6)
        assertTrue(buttons.drop(bottomRow.size).all { it.y < bottomY })
        assertTrue("Кнопки у нижнего края", bottomY + 160 >= 2000 - 60 - 1)
    }

    @Test fun sameInputSameLayout() {
        val a = PortraitLayout.auto(owner, 1200, 2000, 16, 16)
        val b = PortraitLayout.auto(owner.shuffled(Random(3)), 1200, 2000, 16, 16)
        assertEquals(a, b)
    }

    @Test fun tooManyTilesShrinkThenContinueOnNextScreen() {
        val many = (0 until 40).map { Item("w$it", false, (it % 6) * 320, (it / 6) * 300, 300, 280) } +
            (0 until 4).map { Item("b$it", true, it * 180, 1000, 160, 160) }
        val r = PortraitLayout.auto(many, 1200, 2000, 16, 16)
        check(many, 1200, 2000, r)
        assertEquals(PortraitLayout.MIN_SCALE, r.scale, 0.001f)
        assertTrue("Есть продолжение", r.parts > 1)
        assertTrue("Кнопки — на каждом экране", (0 until 4).all { r.places.getValue("b$it").part == PortraitLayout.ALL_PARTS })
    }

    @Test fun slightlyTooTallShrinksInsteadOfSplitting() {
        val items = (0 until 12).map { Item("w$it", false, (it % 4) * 400, (it / 4) * 420, 380, 400) }
        val r = PortraitLayout.auto(items, 1200, 1900, 16, 16)
        check(items, 1200, 1900, r)
        assertEquals(1, r.parts)
        assertTrue(r.scale < 1f && r.scale >= PortraitLayout.MIN_SCALE)
    }

    @Test fun tileWiderThanScreenIsNarrowedKeepingAspect() {
        val r = PortraitLayout.auto(listOf(Item("wide", false, 0, 0, 1800, 600)), 1080, 1920, 16, 16)
        val p = r.places.getValue("wide")
        assertEquals(1080 - 32, p.w)
        assertEquals((600 * (1048 / 1800f)).toInt().toFloat(), p.h.toFloat(), 1f)
    }

    @Test fun randomLayoutsOnManyScreensNeverOverlap() {
        val screens = listOf(720 to 1280, 800 to 1280, 1080 to 1920, 1080 to 2400, 1200 to 1920, 1200 to 2000, 1600 to 2560, 480 to 800)
        repeat(300) { seed ->
            val rnd = Random(seed)
            val items = (0 until rnd.nextInt(0, 30)).map {
                val bottom = rnd.nextInt(3) == 0
                val w = if (bottom) rnd.nextInt(80, 260) else rnd.nextInt(80, 1900)
                Item("t$it", bottom, rnd.nextInt(0, 1800), rnd.nextInt(0, 1100), w, if (bottom) w else rnd.nextInt(80, 900))
            }
            val (w, h) = screens[seed % screens.size]
            val r = PortraitLayout.auto(items, w, h, 12, 12)
            check(items, w, h, r, 12)
        }
    }

    @Test fun storedPlaceRoundTripsAndRejectsDamage() {
        val place = Place(10, 20, 300, 200, 1)
        assertEquals(place, Place.parse(place.json()))
        assertEquals(PortraitLayout.ALL_PARTS, Place.parse(Place(1, 2, 3, 4, PortraitLayout.ALL_PARTS).json())!!.part)
        listOf(null, "", "{", "[]", """{"x":1}""", """{"x":1,"y":2,"w":0,"h":5}""").forEach { assertNull(it, Place.parse(it)) }
    }
}
