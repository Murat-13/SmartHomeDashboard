package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Тексты Яндекс Погоды, которые видит владелец: отказ сервера по-русски, нет значения — прочерк.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YandexWeatherTextTest {
    @Test fun httpErrorsAreRussianAndKeepCode() {
        assertEquals("Яндекс Погода отклонила ключ (HTTP 403)", YandexWeatherManager.httpError(403))
        assertEquals("Яндекс Погода отклонила ключ (HTTP 401)", YandexWeatherManager.httpError(401))
        assertEquals("Яндекс Погода: превышен лимит запросов (HTTP 429)", YandexWeatherManager.httpError(429))
        assertEquals("Сервер Яндекс Погоды недоступен (HTTP 503)", YandexWeatherManager.httpError(503))
        assertEquals("Яндекс Погода не ответила данными (HTTP 404)", YandexWeatherManager.httpError(404))
        // На плитке климата ошибка идёт вместе с пометкой о кэше.
        val climate = YandexClimateWeather(updatedAt = 1, error = YandexWeatherManager.httpError(403))
        assertEquals("Яндекс Погода отклонила ключ (HTTP 403) — показан кэш Яндекса", climate.status(2))
    }

    @Test fun missingFactValuesAreDashesNotZero() {
        val fact = JSONObject().put("temp", -3).put("humidity", "81").put("pressure_mm", 742.6)
        assertEquals("-3", YandexWeatherManager.whole(fact, "temp"))
        assertEquals("81", YandexWeatherManager.whole(fact, "humidity"))
        assertEquals("743", YandexWeatherManager.whole(fact, "pressure_mm"))
        assertEquals("—", YandexWeatherManager.whole(fact, "feels_like"))
        assertEquals("—", YandexWeatherManager.whole(JSONObject().put("temp", "n/a"), "temp"))
    }
}
