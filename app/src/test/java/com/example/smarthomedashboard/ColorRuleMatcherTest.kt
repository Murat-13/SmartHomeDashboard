package com.example.smarthomedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorRuleMatcherTest {
    @Test
    fun emptyValueNeverMatches() {
        assertFalse(ColorRuleMatcher.matches("", "", ""))
        assertFalse(ColorRuleMatcher.matches("", "0", "10"))
    }

    @Test
    fun numericRangeIncludesInteriorAndRejectsOutsideValues() {
        assertTrue(ColorRuleMatcher.matches("5", "0", "10"))
        assertFalse(ColorRuleMatcher.matches("-1", "0", "10"))
        assertFalse(ColorRuleMatcher.matches("11", "0", "10"))
    }

    @Test
    fun bothNumericBoundariesAreInclusive() {
        assertTrue(ColorRuleMatcher.matches("0", "0", "10"))
        assertTrue(ColorRuleMatcher.matches("10", "0", "10"))
        assertTrue(ColorRuleMatcher.matches("5", "5", "5"))
    }

    @Test
    fun negativeNumbersUseNumericComparison() {
        assertTrue(ColorRuleMatcher.matches("-5", "-10", "-1"))
        assertFalse(ColorRuleMatcher.matches("-11", "-10", "-1"))
        assertFalse(ColorRuleMatcher.matches("0", "-10", "-1"))
    }

    @Test
    fun fractionalNumbersUseNumericComparison() {
        assertTrue(ColorRuleMatcher.matches("-1.25", "-1.5", "-1.0"))
        assertTrue(ColorRuleMatcher.matches("1.25", "1.2", "1.3"))
        assertFalse(ColorRuleMatcher.matches("1.35", "1.2", "1.3"))
    }

    @Test
    fun unitsAndOtherNonNumericCharactersAreRemovedFromValue() {
        assertTrue(ColorRuleMatcher.matches("230 V", "220", "240"))
        assertTrue(ColorRuleMatcher.matches("Температура: -12.5 °C", "-13", "-12"))
        assertFalse(ColorRuleMatcher.matches("250 V", "220", "240"))
    }

    @Test
    fun emptyLowerBoundaryLeavesOnlyUpperLimit() {
        assertTrue(ColorRuleMatcher.matches("-20", "", "10"))
        assertTrue(ColorRuleMatcher.matches("10", "", "10"))
        assertFalse(ColorRuleMatcher.matches("11", "", "10"))
    }

    @Test
    fun emptyUpperBoundaryLeavesOnlyLowerLimit() {
        assertTrue(ColorRuleMatcher.matches("10", "10", ""))
        assertTrue(ColorRuleMatcher.matches("20", "10", ""))
        assertFalse(ColorRuleMatcher.matches("9", "10", ""))
    }

    @Test
    fun bothEmptyBoundariesMatchAnyNonEmptyValue() {
        assertTrue(ColorRuleMatcher.matches("5", "", ""))
        assertTrue(ColorRuleMatcher.matches("unknown", "", ""))
        assertTrue(ColorRuleMatcher.matches(" ", "", ""))
    }

    @Test
    fun numericValueWithOneNumericAndOneTextBoundaryDoesNotMatch() {
        assertFalse(ColorRuleMatcher.matches("5", "0", "high"))
        assertFalse(ColorRuleMatcher.matches("5", "low", "10"))
        assertFalse(ColorRuleMatcher.matches("5 V", "0", "high"))
    }

    @Test
    fun numericValueWithTwoTextBoundariesFallsBackToOriginalStringComparison() {
        assertTrue(ColorRuleMatcher.matches("5", "4a", "6z"))
        assertFalse(ColorRuleMatcher.matches("5 V", "4a", "5"))
    }

    @Test
    fun nonNumericValueWithNumericBoundaryNeverFallsBackToStringComparison() {
        assertFalse(ColorRuleMatcher.matches("on", "1", "z"))
        assertFalse(ColorRuleMatcher.matches("on", "1", "9"))
    }

    @Test
    fun stringEqualityIgnoresCaseForEitherBoundary() {
        assertTrue(ColorRuleMatcher.matches("ON", "on", "on"))
        assertTrue(ColorRuleMatcher.matches("ON", "on", ""))
        assertTrue(ColorRuleMatcher.matches("on", "", "ON"))
    }

    @Test
    fun stringRangesUseExistingCaseSensitiveLexicographicOrdering() {
        assertTrue(ColorRuleMatcher.matches("beta", "alpha", "gamma"))
        assertFalse(ColorRuleMatcher.matches("aardvark", "alpha", "gamma"))
        assertFalse(ColorRuleMatcher.matches("zeta", "alpha", "gamma"))
        assertFalse(ColorRuleMatcher.matches("Beta", "alpha", "gamma"))
        assertTrue(ColorRuleMatcher.matches("beta", "", "gamma"))
        assertTrue(ColorRuleMatcher.matches("beta", "alpha", ""))
    }

    @Test fun unavailableValuesNeverMatchNumericOrMixedBounds() {
        for (value in listOf("unknown", "unavailable", "bad", "NaN", "Infinity")) {
            assertFalse(value, ColorRuleMatcher.matches(value, "20", ""))
            assertFalse(value, ColorRuleMatcher.matches(value, "1", "z"))
        }
    }

    @Test fun signedNumbersUnitsAndWhitespaceUseFiniteNumericComparison() {
        assertTrue(ColorRuleMatcher.matches(" 230 V ", " 220 ", "240 "))
        assertTrue(ColorRuleMatcher.matches("-5.5 °C", "-10", "0"))
        assertTrue(ColorRuleMatcher.matches("+.5 A", "0", "1"))
        assertTrue(ColorRuleMatcher.matches("2.3e2 V", "220", "240"))
        assertTrue(ColorRuleMatcher.matches("20", " 20 ", "  "))
        assertTrue(ColorRuleMatcher.matches("20", "  ", " 20 "))
        assertFalse(ColorRuleMatcher.matches(" ", "20", ""))
        assertTrue(ColorRuleMatcher.matches("value", "  ", "  "))
    }

    @Test fun nonFiniteValuesIncludingOverflowNeverMatchNumericRules() {
        for (value in listOf("NaN", "Infinity", "+Infinity", "-Infinity", "1e309", "1e309 V", "NaN V")) {
            assertFalse(value, ColorRuleMatcher.matches(value, "", "2000"))
            assertFalse(value, ColorRuleMatcher.matches(value, "-2000", ""))
            assertFalse(value, ColorRuleMatcher.matches(value, "1200", "1400"))
        }
    }

    @Test fun nonFiniteBoundsAreInvalidRatherThanTextOrUnboundedLimits() {
        for (bound in listOf("NaN", "Infinity", "-Infinity", "1e309")) {
            assertFalse(bound, ColorRuleMatcher.matches("25", bound, ""))
            assertFalse(bound, ColorRuleMatcher.matches("25", "", bound))
            assertFalse(bound, ColorRuleMatcher.matches(bound, bound, bound))
        }
    }

    @Test fun mixedBoundsNeverMatchEitherNumericOrTextReadings() {
        for (value in listOf("25", "unknown", "on", "z", "25 V")) {
            assertFalse(value, ColorRuleMatcher.matches(value, "20", "z"))
            assertFalse(value, ColorRuleMatcher.matches(value, "a", "30"))
        }
    }

    @Test fun malformedReadingsDoNotJoinDigitsFromUnrelatedText() {
        for (value in listOf("--5", "5..5", "1,5", "1 2", "bad25", "1e", "1e+", ".", "5 V 5")) {
            assertFalse(value, ColorRuleMatcher.matches(value, "-100", "100"))
        }
    }

    @Test fun textStatesAndUserDefinedTextRangesKeepExistingSemantics() {
        for (value in listOf("on", "off", "open", "closed", "пользовательское значение")) {
            assertTrue(value, ColorRuleMatcher.matches(value.uppercase(), value, value))
        }
        assertTrue(ColorRuleMatcher.matches("beta", "alpha", "gamma"))
        assertFalse(ColorRuleMatcher.matches(" on ", "on", "on"))
        assertTrue(ColorRuleMatcher.matches(" on ", " on ", " on "))
    }
}
