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
    fun nonNumericValueWithNumericBoundaryFallsBackToStringComparison() {
        assertTrue(ColorRuleMatcher.matches("on", "1", "z"))
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
}
