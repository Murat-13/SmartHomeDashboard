package com.example.smarthomedashboard

import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SensorBackgroundRendererTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }
    private val states = linkedMapOf<String, String>()

    private fun tile(rules: String = "[]", title: String = "Датчики", type: String = "sensor") =
        TileEntity("tile", type, "grid", title, 0, 0, 1, 1, colorRules = rules)

    private fun rule(
        entity: String = "sensor.value",
        from: String = "10",
        to: String = "20",
        color: String = "#802196F3"
    ) = JSONObject().apply {
        put("entity_id", entity)
        put("from", from)
        put("to", to)
        put("color_hex", color)
    }

    private fun rules(vararg values: Any): String = JSONArray(values.toList()).toString()

    private fun card() = FrameLayout(RuntimeEnvironment.getApplication()).apply {
        alpha = 0.85f
        tag = "tile"
        setBackgroundColor(0xFFFF00FF.toInt())
    }

    // Те же проверки выполняются до переноса на исходном методе, затем на его делегировании.
    @Suppress("UNCHECKED_CAST")
    private fun render(tile: TileEntity, card: FrameLayout) {
        ArchitectureTestAccess.field("singleStates").apply {
            isAccessible = true
            (get(activity) as MutableMap<String, String>).apply {
                clear()
                putAll(states)
            }
        }
        ArchitectureTestAccess.method(activity,
            "updateNormalSensorWidget", TileEntity::class.java, FrameLayout::class.java
        ).apply {
            isAccessible = true
            invoke(activity, tile, card)
        }
    }

    private fun assertColor(expected: Long, tile: TileEntity, card: FrameLayout = card()) {
        render(tile, card)
        assertEquals(expected.toInt(), (card.background as ColorDrawable).color)
    }

    @Test fun absentRulesUseDefaultBackground() {
        assertColor(0x80333333, tile())
        assertColor(0x80333333, tile(""))
    }

    @Test fun matchingRuleAppliesItsColorIncludingAlpha() {
        states["sensor.value"] = "15"
        val card = card()
        assertColor(0x802196F3, tile(rules(rule())), card)
        assertEquals(0.85f, card.alpha, 0f)
        assertEquals("tile", card.tag)
    }

    @Test fun nonmatchingRuleUsesDefaultBackground() {
        states["sensor.value"] = "21"
        assertColor(0x80333333, tile(rules(rule())))
    }

    @Test fun lastMatchingRuleWinsAndLaterNonmatchDoesNotReplaceIt() {
        states["sensor.value"] = "15"
        assertColor(0x80F44336, tile(rules(
            rule(), rule(color = "#80F44336"), rule(from = "30", to = "40", color = "#804CAF50")
        )))
    }

    @Test fun eachRuleReadsItsOwnEntityState() {
        states["sensor.value"] = "100"
        states["sensor.other"] = "15"
        assertColor(0x804CAF50, tile(rules(rule(), rule(entity = "sensor.other", color = "#804CAF50"))))
        states["sensor.value"] = "15"
        states["sensor.other"] = "100"
        assertColor(0x802196F3, tile(rules(rule(), rule(entity = "sensor.other", color = "#804CAF50"))))
    }

    @Test fun numericValueWithUnitUsesExistingMatcher() {
        states["sensor.value"] = "15.5 °C"
        assertColor(0x802196F3, tile(rules(rule(from = "15", to = "16"))))
    }

    @Test fun textValueUsesExistingCaseInsensitiveMatch() {
        states["sensor.value"] = "ON"
        assertColor(0x802196F3, tile(rules(rule(from = "on", to = "on"))))
    }

    @Test fun absentAndEmptyStateDoNotMatchEvenOpenBounds() {
        val tile = tile(rules(rule(from = "", to = "")))
        assertColor(0x80333333, tile)
        states["sensor.value"] = ""
        assertColor(0x80333333, tile)
    }

    @Test fun emptyEntityOrColorDoesNotOverridePreviousMatch() {
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile(rules(rule(), rule(entity = ""), rule(color = ""))))
    }

    @Test fun omittedBoundsAreOpenForPresentValue() {
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile(rules(JSONObject().apply {
            put("entity_id", "sensor.value")
            put("color_hex", "#802196F3")
        })))
    }

    @Test fun omittedEntityAndColorAreIgnored() {
        states["sensor.value"] = "15"
        assertColor(0x80333333, tile(rules(JSONObject())))
        assertColor(0x80333333, tile(rules(rule().apply { remove("color_hex") })))
        assertColor(0x80333333, tile(rules(rule().apply { remove("entity_id") })))
    }

    @Test fun malformedJsonAndNonArrayUseGrayEvenForNetwork() {
        assertColor(0x80333333, tile("[", "Сеть"))
        assertColor(0x80333333, tile("{}", "Сеть"))
    }

    @Test fun malformedElementAbortsWholeRuleListIncludingPreviousMatch() {
        states["sensor.value"] = "15"
        assertColor(0x80333333, tile(rules(rule(), "broken", rule(color = "#804CAF50")), "Сеть"))
    }

    @Test fun invalidWinningColorUsesGrayEvenForNetwork() {
        states["sensor.value"] = "15"
        assertColor(0x80333333, tile(rules(rule(), rule(color = "invalid")), "Сеть"))
    }

    @Test fun invalidNonwinningColorIsNotParsed() {
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile(rules(rule(color = "invalid"), rule())))
        assertColor(0x80333333, tile(rules(rule(from = "30", color = "invalid"))))
    }

    @Test fun titleDoesNotChangeBackground() {
        // Раньше плитка со словом «сеть» красилась по личному PZEM-датчику напряжения.
        states["sensor.pzem_energy_monitor_pzem_voltage"] = "230"
        for (title in listOf("Сеть", "ЭлектроСЕТЬ дома", "Электричество")) {
            assertColor(0x80333333, tile(title = title))
        }
    }

    @Test fun userRuleColorsAnyTitle() {
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile(rules(rule()), "Сеть"))
    }

    @Test fun repeatedUpdateReturnsToDefaultWhenMatchEnds() {
        val card = card()
        val tile = tile(rules(rule()))
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile, card)
        states["sensor.value"] = "30"
        assertColor(0x80333333, tile, card)
        assertColor(0x80333333, tile(), card)
    }

    @Test fun repeatedUpdateOnAnyTitleReturnsToDefault() {
        val card = card()
        val tile = tile(rules(rule()), "Сеть")
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile, card)
        states.clear()
        assertColor(0x80333333, tile, card)
    }

    @Test fun twoTilesKeepIndependentBackgrounds() {
        states["sensor.value"] = "15"
        val first = card()
        val second = card()
        assertColor(0x802196F3, tile(rules(rule())), first)
        assertColor(0x80333333, tile(), second)
        assertEquals(0x802196F3.toInt(), (first.background as ColorDrawable).color)
        assertColor(0x80F44336, tile(rules(rule(color = "#80F44336"))), second)
        assertEquals(0x802196F3.toInt(), (first.background as ColorDrawable).color)
    }

    @Test fun weatherTileRetainsTheSameBackgroundPath() {
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile(rules(rule()), type = "weather"))
    }

    @Test fun invalidNumericStateDoesNotKeepNormalReadingColor() {
        val card = card()
        val tile = tile(rules(rule(from = "10", to = "")))
        states["sensor.value"] = "15"
        assertColor(0x802196F3, tile, card)
        for (value in listOf("unknown", "unavailable", "NaN", "Infinity", "bad")) {
            states["sensor.value"] = value
            assertColor(0x80333333, tile, card)
        }
    }

    @Test fun explicitUnavailableTextRuleSurvivesLaterNumericRule() {
        states["sensor.value"] = "unavailable"
        assertColor(0x80FFCC00, tile(rules(
            rule(from = "10", to = ""),
            rule(from = "unavailable", to = "unavailable", color = "#80FFCC00"),
            rule(from = "20", to = "", color = "#8033CC33")
        )))
    }

    @Test fun lastValidMatchWinsAcrossSensorsButInvalidReadingCannotOverrideIt() {
        val tile = tile(rules(rule(), rule(entity = "sensor.other", from = "10", to = "", color = "#80F44336")))
        val card = card()
        states["sensor.value"] = "15"
        for (value in listOf("unknown", "NaN", "Infinity", "1 2", "bad25")) {
            states["sensor.other"] = value
            assertColor(0x802196F3, tile, card)
        }
        states["sensor.other"] = "15 V"
        assertColor(0x80F44336, tile, card)
    }
}
