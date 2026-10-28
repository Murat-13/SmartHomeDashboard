package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SensorValueFormatterTest {
    private fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun integerValueIsFormattedWithRequestedPrecision() = withLocale(Locale.US) {
        assertEquals("42", SensorValueFormatter.formatSensorValue("42", 0, "", "C"))
        assertEquals("42.0", SensorValueFormatter.formatSensorValue("42", 1, "", "C"))
        assertEquals("42.00", SensorValueFormatter.formatSensorValue("42", 2, "", "C"))
    }

    @Test
    fun fractionalValueIsRoundedAtZeroOneAndTwoDecimalPlaces() = withLocale(Locale.US) {
        assertEquals("12", SensorValueFormatter.formatSensorValue("12.375", 0, "", "C"))
        assertEquals("12.4", SensorValueFormatter.formatSensorValue("12.375", 1, "", "C"))
        assertEquals("12.38", SensorValueFormatter.formatSensorValue("12.375", 2, "", "C"))
    }

    @Test
    fun negativeValueIsFormattedWithFloatRounding() = withLocale(Locale.US) {
        assertEquals("-12", SensorValueFormatter.formatSensorValue("-12.375", 0, "", "C"))
        assertEquals("-12.4", SensorValueFormatter.formatSensorValue("-12.375", 1, "", "C"))
        assertEquals("-12.38", SensorValueFormatter.formatSensorValue("-12.375", 2, "", "C"))
    }

    @Test
    fun celsiusIsConvertedToFahrenheit() = withLocale(Locale.US) {
        assertEquals("32.0", SensorValueFormatter.formatSensorValue("0", 1, "°C", "F"))
        assertEquals("98.60", SensorValueFormatter.formatSensorValue("37", 2, "°C", "F"))
    }

    @Test
    fun fahrenheitIsConvertedToCelsius() = withLocale(Locale.US) {
        assertEquals("100.0", SensorValueFormatter.formatSensorValue("212", 1, "°F", "C"))
        assertEquals("-17.78", SensorValueFormatter.formatSensorValue("0", 2, "°F", "C"))
    }

    @Test
    fun sameTemperatureUnitsDoNotConvertValue() = withLocale(Locale.US) {
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "°C", "C"))
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "°F", "F"))
    }

    @Test
    fun missingUnitOrDegreeSymbolPreventsConversion() = withLocale(Locale.US) {
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "", "F"))
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "C", "F"))
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "F", "C"))
        assertEquals("12.5", SensorValueFormatter.formatSensorValue("12.5", 1, "V", "F"))
    }

    @Test
    fun unitChecksRemainCaseSensitiveContainsChecks() = withLocale(Locale.US) {
        assertEquals("32.0", SensorValueFormatter.formatSensorValue("0", 1, "temperature °C", "F"))
        assertEquals("0.0", SensorValueFormatter.formatSensorValue("0", 1, "°c", "F"))
        assertEquals("0.0", SensorValueFormatter.formatSensorValue("0", 1, "°C", "f"))
    }

    @Test
    fun nonNumericSensorValuesRemainUnchanged() = withLocale(Locale.US) {
        assertEquals("текст", SensorValueFormatter.formatSensorValue("текст", 2, "°C", "F"))
        assertEquals("", SensorValueFormatter.formatSensorValue("", 2, "°C", "F"))
        assertEquals("unknown", SensorValueFormatter.formatSensorValue("unknown", 2, "°C", "F"))
        assertEquals("unavailable", SensorValueFormatter.formatSensorValue("unavailable", 2, "°C", "F"))
        assertEquals("12 V", SensorValueFormatter.formatSensorValue("12 V", 2, "", "C"))
    }

    @Test
    fun simpleFormatterReturnsDashForNonNumericValues() = withLocale(Locale.US) {
        assertEquals("—", SensorValueFormatter.formatFloat("текст", 2))
        assertEquals("—", SensorValueFormatter.formatFloat("", 2))
        assertEquals("—", SensorValueFormatter.formatFloat("unknown", 2))
        assertEquals("—", SensorValueFormatter.formatFloat("unavailable", 2))
    }

    @Test
    fun simpleFormatterKeepsNumericPrecisionAndRounding() = withLocale(Locale.US) {
        assertEquals("42", SensorValueFormatter.formatFloat("42", 0))
        assertEquals("12.4", SensorValueFormatter.formatFloat("12.375", 1))
        assertEquals("-12.38", SensorValueFormatter.formatFloat("-12.375", 2))
    }

    @Test
    fun usLocaleUsesDecimalPointInBothMethods() = withLocale(Locale.US) {
        assertEquals("12.50", SensorValueFormatter.formatSensorValue("12.5", 2, "", "C"))
        assertEquals("12.50", SensorValueFormatter.formatFloat("12.5", 2))
    }

    @Test
    fun germanLocaleUsesDecimalCommaInBothMethods() = withLocale(Locale.GERMANY) {
        assertEquals("12,50", SensorValueFormatter.formatSensorValue("12.5", 2, "", "C"))
        assertEquals("12,50", SensorValueFormatter.formatFloat("12.5", 2))
        assertEquals("32,0", SensorValueFormatter.formatSensorValue("0", 1, "°C", "F"))
    }

    @Test
    fun commaInputIsNotParsedEvenWithCommaLocale() = withLocale(Locale.GERMANY) {
        assertEquals("12,5", SensorValueFormatter.formatSensorValue("12,5", 2, "", "C"))
        assertEquals("—", SensorValueFormatter.formatFloat("12,5", 2))
    }
}
