package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json в юнит-тестах доступен только через Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupChildrenTest {
    // Как у «Свет навес»: пятым по ошибке выбран update.* вместо выключателя.
    private fun naves() = GroupChildren.fromConfig(JSONObject("""{
        "entity_ids":["switch.naves1","switch.naves2","switch.naves3","switch.naves4","update.naves5"],
        "child_names":["1","2","3","4","Пятый"],"button_size":160}"""))

    private fun written(group: GroupChildren, base: String = "{}") = JSONObject(base).also { group.writeTo(it) }

    @Test fun replaceKeepsNameAndPosition() {
        val group = naves()
        assertEquals(GroupChildren.Change.DONE, group.replace(4, "switch.naves5"))
        val config = written(group)
        assertEquals("""["switch.naves1","switch.naves2","switch.naves3","switch.naves4","switch.naves5"]""",
            config.getJSONArray("entity_ids").toString())
        assertEquals("""["1","2","3","4","Пятый"]""", config.getJSONArray("child_names").toString())
    }

    @Test fun replaceWithDeviceAlreadyInGroupIsRejected() {
        val group = naves()
        assertEquals(GroupChildren.Change.DUPLICATE, group.replace(4, "switch.naves1"))
        assertEquals("update.naves5", group.items[4].entityId)
        // Замена на то же самое устройство — не дубль.
        assertEquals(GroupChildren.Change.DONE, group.replace(4, "update.naves5"))
        assertEquals(GroupChildren.Change.IGNORED, group.replace(9, "switch.x"))
        assertEquals(GroupChildren.Change.IGNORED, group.replace(0, " "))
    }

    @Test fun removingMiddleDeviceDoesNotShiftNames() {
        val group = GroupChildren.fromConfig(JSONObject("""{"entity_ids":["light.a","light.b","light.c"],
            "child_names":["Кухня","Коридор","Ванная"]}"""))
        assertTrue(group.remove(1))
        val config = written(group)
        assertEquals("""["light.a","light.c"]""", config.getJSONArray("entity_ids").toString())
        assertEquals("""["Кухня","Ванная"]""", config.getJSONArray("child_names").toString())
        assertFalse(group.remove(5))
    }

    @Test fun addSkipsDuplicatesAndUsesProvidedName() {
        val group = naves()
        val added = group.add(listOf("switch.naves1", "switch.naves5", "", "light.yard")) { if (it == "light.yard") "Двор" else "" }
        assertEquals(2, added)
        val config = written(group)
        assertEquals("""["1","2","3","4","Пятый","6","Двор"]""", config.getJSONArray("child_names").toString())
    }

    @Test fun moveCarriesNameWithDevice() {
        val group = naves()
        group.move(4, 3)
        val config = written(group)
        assertEquals("update.naves5", config.getJSONArray("entity_ids").getString(3))
        assertEquals("Пятый", config.getJSONArray("child_names").getString(3))
    }

    @Test fun writeKeepsOtherSettingsAndDropsSingleEntity() {
        val config = written(naves(), """{"entity_id":"switch.old","button_size":200}""")
        assertFalse(config.has("entity_id"))
        assertEquals(200, config.getInt("button_size"))
    }

    @Test fun readsFormerSingleButtonAndMissingNames() {
        assertEquals(listOf(ChildButtonData("switch.old", "1")),
            GroupChildren.fromConfig(JSONObject("""{"entity_id":"switch.old"}""")).items)
        assertEquals(listOf(ChildButtonData("light.a", "A"), ChildButtonData("light.b", "2")),
            GroupChildren.fromConfig(JSONObject("""{"entity_ids":["light.a","light.b"],"child_names":["A"]}""")).items)
        assertTrue(GroupChildren.fromConfig(JSONObject("{}")).items.isEmpty())
    }
}
