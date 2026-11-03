package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileParser
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Безопасное чтение сохранённых плиток (этап 0.9.2-1): повреждения не роняют панель, старые данные читаются. */
class TileParserTest {
    private fun parse(json: String?) = TileParser.parse(json)

    @Test fun brokenRootIsUnreadableWithoutException() {
        for (json in listOf("", "   ", "[{\"id\":", "null", "{}", "\"tiles\"", "42", "true", "{\"id\":\"a\",\"type\":\"clock\"}")) {
            val result = parse(json)
            assertTrue("для «$json»", result.tiles.isEmpty())
            assertEquals("для «$json»", TileParser.Issue(unreadable = true, skipped = 0, total = 0), result.issue)
        }
    }

    @Test fun missingPrefsValueIsEmptyWithoutIssue() {
        assertEquals(TileParser.Result(emptyList(), null), parse(null))
        assertEquals(TileParser.Result(emptyList(), null), parse("[]"))
    }

    @Test fun nullAndForeignElementsAndTilesWithoutIdOrTypeAreSkipped() {
        val json = """[null, 5, "x", [], {"id":"ok","type":"clock"},
            {"type":"clock"}, {"id":"","type":"clock"}, {"id":"no-type"}, {"id":7,"type":"clock"}, {"id":"t","type":null},
            {"id":"w","type":"weather"}]"""
        val result = parse(json)
        assertEquals(listOf("ok", "w"), result.tiles.map { it.id })
        assertEquals(listOf("clock", "weather"), result.tiles.map { it.type })
        assertEquals(TileParser.Issue(unreadable = false, skipped = 9, total = 11), result.issue)
    }

    @Test fun oldConfigWithoutOptionalFieldsGetsEntityDefaults() {
        val tile = parse("""[{"id":"a","type":"sensor","container":"grid","title":"Кухня","x":10,"y":20,"width":1,"height":1}]""")
            .also { assertNull(it.issue) }.tiles.single()
        assertEquals(TileEntity("a", "sensor", "grid", "Кухня", 10, 20, 1, 1), tile)
        assertEquals("{}", tile.appearance)
        assertEquals("{}", tile.conditions)
        assertEquals("{}", tile.config)
        assertEquals("[]", tile.collapsedSensorIds)
        assertEquals("[]", tile.colorRules)
        assertEquals("", tile.icon)
        assertEquals("auto", tile.sourceType)
        assertEquals(16, tile.fontSize)
        assertTrue("нет поля enabled — плитка видима (Gson дал бы false)", tile.enabled)
        assertEquals(0, tile.page)
    }

    @Test fun wrongTypesAndInvalidValuesBecomeDefaults() {
        val tile = parse("""[{"id":"a","type":"sensor","container":null,"title":3,"x":"1","y":1.5,"width":0,"height":-2,
            "appearance":null,"conditions":{},"config":[],"enabled":"yes","collapsedSensorIds":null,"colorRules":5,
            "fontSize":0,"icon":null,"sourceType":false,"page":-1}]""").tiles.single()
        assertEquals(TileEntity("a", "sensor", "grid", "", 0, 0, 1, 1), tile)
        assertEquals(16, parse("""[{"id":"a","type":"s","fontSize":-5}]""").tiles.single().fontSize)
        assertEquals(16, parse("""[{"id":"a","type":"s","fontSize":1e30}]""").tiles.single().fontSize)
    }

    @Test fun intactSavedListReadsExactlyAsWritten() {
        val tiles = listOf(
            TileEntity("a", "battery_modern", "grid", "АКБ", 100, 40, 1, 1, config = """{"button_size":300}""", page = 1),
            TileEntity("b", "button", "bottom_panel", "", -5, 0, 1, 1, enabled = false, fontSize = 22, icon = "lamp",
                sourceType = "", colorRules = """[{"color":"#fff"}]"""),
        )
        val result = parse(Gson().toJson(tiles))
        assertNull(result.issue)
        assertEquals("координаты, пустые строки и выключенные плитки не меняются", tiles, result.tiles)
    }

    @Test fun orderAndRepeatedIdsArePreservedAsBefore() {
        val result = parse("""[{"id":"a","type":"clock","title":"1"},{"id":"a","type":"clock","title":"2"}]""")
        assertEquals(listOf("1", "2"), result.tiles.map { it.title })
        assertNull(result.issue)
    }
}
