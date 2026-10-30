package com.example.smarthomedashboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Погодные значки в том же семействе контуров, что [ModernIcon] (24×24, линия 2), но с цветами
 * частей: солнце жёлтое, облако голубое, осадки синие. Рисуются на Canvas без ассетов.
 */
internal enum class WeatherGlyph(private vararg val parts: Pair<Int, Array<String>>) {
    SUN(SUN_COLOR to arrayOf("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2", "M12 20v2", "M4.93 4.93l1.41 1.41",
        "M17.66 17.66l1.41 1.41", "M2 12h2", "M20 12h2", "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41")),
    MOON(MOON_COLOR to arrayOf("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")),
    PARTLY_DAY(SUN_COLOR to arrayOf("M12 2v2", "M4.93 4.93l1.41 1.41", "M20 12h2", "M19.07 4.93l-1.41 1.41", "M15.95 12.65a4 4 0 0 0-5.93-4.13"),
        CLOUD_COLOR to arrayOf("M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6Z")),
    PARTLY_NIGHT(MOON_COLOR to arrayOf("M10.19 8.5A6 6 0 0 1 16 4a1 1 0 0 0 6 6 6 6 0 0 1-3 5.2"),
        CLOUD_COLOR to arrayOf("M13 16a3 3 0 1 1 0 6H7a5 5 0 1 1 4.9-6Z")),
    CLOUD(CLOUD_COLOR to arrayOf("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")),
    RAIN(CLOUD_COLOR to arrayOf(RAIN_CLOUD), RAIN_COLOR to arrayOf("M16 14v6", "M8 14v6", "M12 16v6")),
    DRIZZLE(CLOUD_COLOR to arrayOf(RAIN_CLOUD), RAIN_COLOR to arrayOf("M8 19v1", "M8 14v1", "M16 19v1", "M16 14v1", "M12 21v1", "M12 16v1")),
    SNOW(CLOUD_COLOR to arrayOf(RAIN_CLOUD), SNOW_COLOR to arrayOf("M8 15h.01", "M8 19h.01", "M12 17h.01", "M12 21h.01", "M16 15h.01", "M16 19h.01")),
    HAIL(CLOUD_COLOR to arrayOf(RAIN_CLOUD), SNOW_COLOR to arrayOf("M16 14v2", "M8 14v2", "M16 20h.01", "M8 20h.01", "M12 16v2", "M12 22h.01")),
    LIGHTNING(CLOUD_COLOR to arrayOf("M6 16.33A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.97"), SUN_COLOR to arrayOf("M13 12l-3 5h4l-3 5")),
    FOG(CLOUD_COLOR to arrayOf(RAIN_CLOUD), FOG_COLOR to arrayOf("M16 17H7", "M17 21H9")),
    WIND(CLOUD_COLOR to arrayOf("M12.8 19.6A2 2 0 1 0 14 16H2", "M17.5 8a2.5 2.5 0 1 1 2 4H2", "M9.8 4.4A2 2 0 1 1 11 8H2")),
    UNKNOWN(FOG_COLOR to arrayOf("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"));

    private val paths: List<Pair<Int, Path>> by lazy {
        parts.map { (color, data) -> color to Path().apply { data.forEach { addPath(PathParser.createPathFromPathData(it)) } } }
    }

    /** [dim] — приглушить цвета (нет актуальных данных). */
    fun draw(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint, dim: Boolean = false) {
        val scale = size / 24f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = (2f).coerceAtLeast(1.2f / scale)
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        paths.forEach { (color, path) ->
            paint.color = if (dim) FOG_COLOR else color
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    companion object {
        /** Состояния погоды Home Assistant; [daytime] = false — ночной вариант ясно/переменной облачности. */
        fun of(condition: String?, daytime: Boolean = true): WeatherGlyph = when (condition) {
            "sunny" -> if (daytime) SUN else MOON
            "clear-night" -> MOON
            "partlycloudy" -> if (daytime) PARTLY_DAY else PARTLY_NIGHT
            "cloudy" -> CLOUD
            "rainy" -> DRIZZLE
            "pouring" -> RAIN
            "snowy", "snowy-rainy" -> SNOW
            "hail" -> HAIL
            "lightning", "lightning-rainy" -> LIGHTNING
            "fog" -> FOG
            "windy", "windy-variant" -> WIND
            else -> UNKNOWN
        }
    }
}

private const val SUN_COLOR = 0xFFFFC94D.toInt()
private const val MOON_COLOR = 0xFFFFE6A8.toInt()
private const val CLOUD_COLOR = 0xFFA9DBFF.toInt()
private const val RAIN_COLOR = 0xFF5DB8FF.toInt()
private const val SNOW_COLOR = 0xFFE8F6FF.toInt()
private const val FOG_COLOR = 0xFF8FA3B8.toInt()
private const val RAIN_CLOUD = "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24"

/** Чтение и форматирование данных плитки. Только реальные значения HA: нет данных — «—» или скрытый блок. */
internal object HomeClimateData {
    const val DASH = "—"

    fun conditionText(condition: String?): String = when (condition) {
        "sunny" -> "Ясно"
        "clear-night" -> "Ясно"
        "partlycloudy" -> "Переменная облачность"
        "cloudy" -> "Пасмурно"
        "rainy" -> "Дождь"
        "pouring" -> "Ливень"
        "snowy" -> "Снег"
        "snowy-rainy" -> "Снег с дождём"
        "hail" -> "Град"
        "lightning" -> "Гроза"
        "lightning-rainy" -> "Гроза с дождём"
        "fog" -> "Туман"
        "windy", "windy-variant" -> "Ветрено"
        "exceptional" -> "Необычная погода"
        null, "", "unknown", "unavailable" -> "Нет данных"
        else -> condition
    }

    /** °C или °F, в которых показывать значение с исходной единицей [source]. */
    fun isTemperatureUnit(unit: String?) = unit in setOf("°C", "°F", "C", "F", "℃", "℉")

    fun targetUnit(setting: String, source: String?): String = when (setting) {
        HomeClimateConfig.UNIT_C -> "°C"
        HomeClimateConfig.UNIT_F -> "°F"
        else -> if ((source?.contains("F", ignoreCase = true) == true || source == "℉")) "°F" else "°C"
    }

    /** Пересчёт между °C и °F; неизвестная исходная единица считается °C (как в HA по умолчанию). */
    fun convert(value: Double, source: String?, target: String): Double {
        val fromF = (source?.contains("F", ignoreCase = true) == true || source == "℉")
        val toF = target == "°F"
        return when {
            fromF == toF -> value
            toF -> value * 9.0 / 5.0 + 32.0
            else -> (value - 32.0) * 5.0 / 9.0
        }
    }

    /** До [decimals] знаков без лишних нулей, десятичная запятая, без «−0». */
    fun number(value: Double, decimals: Int): String {
        val rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = if (rounded.signum() == 0) "0" else rounded.toPlainString()
        return plain.replace('.', ',').replace("-", "−")
    }

    data class Temperature(val value: Double, val unit: String) {
        fun compact() = number(value, 0) + "°"
        fun full() = number(value, 1) + " " + unit
        fun short() = number(value, 0) + "°"
    }

    /** Температура сенсора комнаты в выбранных единицах или null (нет данных, не число, нет связи). */
    fun temperature(readings: BatteryReadings, entityId: String, setting: String): Temperature? {
        val reading = readings.of(entityId) as? BatteryReading.Value ?: return null
        val number = reading.number?.takeIf { it.isFinite() } ?: return null
        val source = reading.attributes?.optString("unit_of_measurement")
        if (!isTemperatureUnit(source)) return null
        val target = targetUnit(setting, source)
        return convert(number, source, target).takeIf { it.isFinite() }?.let { Temperature(it, target) }
    }

    /** Влажность «48 %»; без сенсора — null (не «0 %»), сенсор недоступен — «—». */
    fun humidity(readings: BatteryReadings, entityId: String): String? {
        if (entityId.isBlank()) return null
        val reading = readings.of(entityId) as? BatteryReading.Value ?: return DASH
        if (reading.attributes?.optString("unit_of_measurement") != "%") return DASH
        val number = reading.number?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return DASH
        return number(number, 0) + " %"
    }

    data class Current(
        val available: Boolean,
        val status: String?,
        val condition: String?,
        val glyph: WeatherGlyph,
        val temperature: Temperature?,
        val feelsLike: Temperature?,
        val humidity: String?,
        val wind: String?,
        val windDirection: String?,
        val precipitation: String?,
        val pressure: String?,
        val pressureUnit: String?
    )

    fun current(readings: BatteryReadings, weather: HomeClimateConfig.Weather, hourly: List<Forecast>?): Current {
        val entity = weather.entityId
        val reading = readings.of(entity)
        val status = when (reading) {
            BatteryReading.NotConfigured -> "Погода не выбрана"
            BatteryReading.Disconnected -> "Нет связи"
            BatteryReading.Syncing -> "Синхронизация…"
            BatteryReading.Missing -> "Сущность не найдена"
            BatteryReading.Unconfirmed -> "Данные устарели"
            is BatteryReading.Unavailable -> "Недоступно"
            is BatteryReading.Value -> null
        }
        val value = reading as? BatteryReading.Value
        val attrs = value?.attributes ?: JSONObject()
        val sourceUnit = attrs.optString("temperature_unit").takeIf { it.isNotBlank() }
        val target = targetUnit(weather.unit, sourceUnit)
        fun temp(key: String) = attrs.optDoubleOrNull(key)?.takeIf { isTemperatureUnit(sourceUnit) }?.let { convert(it, sourceUnit, target).takeIf { value -> value.isFinite() }?.let { value -> Temperature(value, target) } }
        val windSpeed = attrs.optDoubleOrNull("wind_speed")
        val windUnit = attrs.optString("wind_speed_unit").takeIf { it.isNotBlank() }?.let(::unitText) ?: ""
        val bearing = attrs.opt("wind_bearing")
        val direction = when (bearing) {
            is Number -> compass(bearing.toDouble())
            is String -> bearing.toDoubleOrNull()?.let(::compass) ?: bearing.takeIf { it.isNotBlank() }
            else -> null
        }
        // Осадки: вероятность ближайшего часа, иначе количество из текущего состояния. Не выдумываем.
        val probability = hourly?.firstOrNull()?.precipitationProbability
        val amount = attrs.optDoubleOrNull("precipitation")
        val precipitation = when {
            probability != null -> number(probability, 0) + " %"
            amount != null -> number(amount, 1) + " " + (attrs.optString("precipitation_unit").takeIf { it.isNotBlank() }?.let(::unitText) ?: "")
            else -> null
        }
        val pressure = attrs.optDoubleOrNull("pressure")
        return Current(
            available = value != null,
            status = status,
            condition = value?.raw,
            glyph = if (value != null) WeatherGlyph.of(value.raw) else WeatherGlyph.UNKNOWN,
            temperature = if (value != null) temp("temperature") else null,
            feelsLike = if (value != null) temp("apparent_temperature") else null,
            humidity = if (value != null) attrs.optDoubleOrNull("humidity")?.let { number(it, 0) + " %" } else null,
            wind = if (value != null) windSpeed?.let { number(it, 1) + if (windUnit.isEmpty()) "" else " $windUnit" } else null,
            windDirection = if (value != null) direction else null,
            precipitation = if (value != null) precipitation else null,
            pressure = if (value != null) pressure?.let { number(it, 0) } else null,
            pressureUnit = attrs.optString("pressure_unit").takeIf { it.isNotBlank() }?.let(::unitText)
        )
    }

    fun unitText(unit: String): String = when (unit) {
        "km/h" -> "км/ч"
        "m/s" -> "м/с"
        "mph" -> "миль/ч"
        "kn" -> "уз"
        "hPa" -> "гПа"
        "mbar" -> "мбар"
        "mmHg" -> "мм рт. ст."
        "inHg" -> "дюйм рт. ст."
        "mm" -> "мм"
        "in" -> "дюйм"
        else -> unit
    }

    fun compass(degrees: Double): String {
        val names = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val index = (((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
        return names[index]
    }

    data class Forecast(
        val time: Date,
        val glyph: WeatherGlyph,
        val temperature: Temperature?,
        val low: Temperature?,
        val precipitationProbability: Double?
    )

    /**
     * Почасовой прогноз от ближайшего доступного интервала: часы, закончившиеся раньше текущего, пропускаются.
     * Столько элементов, сколько отдаёт источник.
     */
    fun hourly(array: JSONArray?, weather: HomeClimateConfig.Weather, sourceUnit: String?, now: Long = System.currentTimeMillis()): List<Forecast> =
        parse(array, weather, sourceUnit).filter { it.time.time > now - 3_600_000L }

    /** До 5 ближайших дней начиная с сегодняшнего (по часовому поясу [zone]); дни не выдумываются. */
    fun daily(array: JSONArray?, weather: HomeClimateConfig.Weather, sourceUnit: String?, zone: TimeZone,
              now: Long = System.currentTimeMillis()): List<Forecast> {
        val today = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
        return parse(array, weather, sourceUnit).filter { dayKey(it.time, zone) >= dayKey(today, zone) }
            .distinctBy { dayKey(it.time, zone) }.take(5)
    }

    private fun dayKey(date: Date, zone: TimeZone): Int {
        val calendar = Calendar.getInstance(zone).apply { time = date }
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun parse(array: JSONArray?, weather: HomeClimateConfig.Weather, sourceUnit: String?): List<Forecast> {
        if (array == null) return emptyList()
        val target = targetUnit(weather.unit, sourceUnit)
        val result = ArrayList<Forecast>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val time = parseTime(item.optString("datetime")) ?: continue
            val daytime = if (item.has("is_daytime")) item.optBoolean("is_daytime", true) else true
            result += Forecast(
                time, WeatherGlyph.of(item.optString("condition").takeIf { it.isNotBlank() }, daytime),
                item.optDoubleOrNull("temperature")?.takeIf { isTemperatureUnit(sourceUnit) }?.let { convert(it, sourceUnit, target).takeIf { value -> value.isFinite() }?.let { value -> Temperature(value, target) } },
                item.optDoubleOrNull("templow")?.takeIf { isTemperatureUnit(sourceUnit) }?.let { convert(it, sourceUnit, target).takeIf { value -> value.isFinite() }?.let { value -> Temperature(value, target) } },
                item.optDoubleOrNull("precipitation_probability")
            )
        }
        return result.sortedBy { it.time }
    }

    /** ISO 8601 из HA: «2026-10-30T15:00:00+00:00», «…Z», с долями секунд или без. */
    fun parseTime(text: String): Date? {
        if (text.isBlank()) return null
        val normalized = text.trim().replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.ROOT).apply {
                    if (!pattern.contains("XXX")) timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                val position = java.text.ParsePosition(0)
                val parsed = parser.parse(normalized, position)
                if (parsed != null && position.index == normalized.length) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> BatteryFormat.number(value)?.takeIf { it.isFinite() }
            else -> null
        }
    }

    // ---- Часы: системное время Android, формат и часовой пояс из настроек плитки. ----

    fun zone(clock: HomeClimateConfig.Clock): TimeZone =
        clock.timeZone?.let { id -> TimeZone.getTimeZone(id).takeIf { it.id == id } } ?: TimeZone.getDefault()

    fun time(clock: HomeClimateConfig.Clock, system24h: Boolean, now: Date, seconds: Boolean = clock.seconds): String {
        val use24 = clock.use24h ?: system24h
        val pattern = (if (use24) "HH:mm" else "h:mm") + (if (seconds) ":ss" else "")
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone(clock) }.format(now)
    }

    /** «AM»/«PM» для 12-часового формата, иначе null. */
    fun amPm(clock: HomeClimateConfig.Clock, system24h: Boolean, now: Date): String? {
        if (clock.use24h ?: system24h) return null
        return SimpleDateFormat("a", Locale.getDefault()).apply { timeZone = zone(clock) }.format(now)
    }

    fun date(clock: HomeClimateConfig.Clock, now: Date): String {
        val pattern = when (clock.dateFormat) {
            HomeClimateConfig.DATE_SHORT -> "EE, d MMM"
            HomeClimateConfig.DATE_NUMERIC -> "dd.MM.yyyy"
            else -> "EEEE, d MMMM"
        }
        val text = SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone(clock) }.format(now)
        return text.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun hourLabel(date: Date, clock: HomeClimateConfig.Clock, system24h: Boolean): String =
        SimpleDateFormat(if (clock.use24h ?: system24h) "HH:mm" else "h a", Locale.getDefault())
            .apply { timeZone = zone(clock) }.format(date)

    fun dayLabel(date: Date, clock: HomeClimateConfig.Clock, now: Date = Date()): String {
        val zone = zone(clock)
        if (dayKey(date, zone) == dayKey(now, zone)) return "Сегодня"
        return SimpleDateFormat("EE", Locale.getDefault()).apply { timeZone = zone }.format(date)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
