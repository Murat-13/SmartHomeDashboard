package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json в юнит-тестах доступен только через Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EntityDisplayNameTest {
    private fun name(entry: String, attrs: String = "{}") =
        displayName("switch.naves5", JSONObject(entry), JSONObject(attrs))

    @Test fun registryEntryWithoutUserNameUsesIntegrationName() {
        // Так config/entity_registry/list отдаёт большинство сущностей; раньше в списке было «null».
        assertEquals("Навес 5", name("""{"entity_id":"switch.naves5","name":null,"original_name":"Навес 5"}"""))
    }

    @Test fun userNameWinsOverIntegrationName() {
        assertEquals("Свет у ворот", name("""{"name":"Свет у ворот","original_name":"Навес 5"}"""))
    }

    @Test fun stateFriendlyNameWinsForGetStates() {
        assertEquals("Навес", name("""{"name":"Другое"}""", """{"friendly_name":"Навес"}"""))
    }

    @Test fun nullsAndBlanksFallBackToEntityId() {
        assertEquals("switch.naves5", name("""{"name":null,"original_name":null}""", """{"friendly_name":null}"""))
        assertEquals("switch.naves5", name("""{"name":"  ","original_name":""}"""))
        assertEquals("switch.naves5", name("{}"))
    }
}
