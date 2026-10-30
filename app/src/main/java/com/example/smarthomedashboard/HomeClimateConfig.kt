package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Настройки плитки «Часы · Погода · Климат дома» (`home_climate_modern`). Хранятся в
 * `tile.config.home_climate`; размеры (`button_size`, `widget_height`) и прочие ключи config не трогаются.
 * Комнаты привязаны к устойчивому [Room.id], а не к индексу: порядок и избранное переживают
 * перестановки и удаление соседей.
 */
internal data class HomeClimateConfig(
    val clock: Clock = Clock(),
    val weather: Weather = Weather(),
    val rooms: List<Room> = emptyList(),
    /** Порядок избранных комнат компактного вида (id, не больше [MAX_FAVORITES]). */
    val favorites: List<String> = emptyList()
) {
    data class Clock(
        /** null — как в системе Android; true — 24 ч; false — 12 ч. */
        val use24h: Boolean? = null,
        val dateFormat: String = DATE_FULL,
        val seconds: Boolean = false,
        /** null — часовой пояс системы. */
        val timeZone: String? = null
    )

    data class Weather(
        val entityId: String = "",
        /** [UNIT_AUTO] — единицы Home Assistant; [UNIT_C] / [UNIT_F] — пересчёт. */
        val unit: String = UNIT_AUTO,
        val showFeelsLike: Boolean = true,
        val showHumidity: Boolean = true,
        val showWind: Boolean = true,
        val showPrecipitation: Boolean = true,
        val showPressure: Boolean = true,
        val showHourly: Boolean = true,
        val showDaily: Boolean = true,
        val source: String = SOURCE_HA
    )

    data class Room(
        val id: String,
        val name: String,
        val icon: String? = null,
        val temperatureEntity: String = "",
        val humidityEntity: String = ""
    ) {
        val modernIcon get() = ModernIcon.fromKey(icon)
    }

    /** Избранные в заданном порядке; удалённые комнаты пропускаются. */
    val favoriteRooms: List<Room> get() = favorites.mapNotNull { id -> rooms.firstOrNull { it.id == id } }.take(MAX_FAVORITES)

    fun isFavorite(id: String) = id in favorites

    fun room(id: String) = rooms.firstOrNull { it.id == id }

    fun withRoom(room: Room): HomeClimateConfig =
        if (rooms.any { it.id == room.id }) copy(rooms = rooms.map { if (it.id == room.id) room else it })
        else copy(rooms = rooms + room)

    fun withoutRoom(id: String) = copy(rooms = rooms.filter { it.id != id }, favorites = favorites - id)

    fun moveRoom(id: String, delta: Int) = copy(rooms = move(rooms.map { it.id }, id, delta).mapNotNull(::room))

    fun moveFavorite(id: String, delta: Int) = copy(favorites = move(favorites, id, delta))

    /** Результат отметки избранного: шестая отметка не вытесняет другую комнату. */
    enum class FavoriteChange { ADDED, REMOVED, LIMIT, UNKNOWN }

    fun toggleFavorite(id: String): Pair<HomeClimateConfig, FavoriteChange> = when {
        room(id) == null -> this to FavoriteChange.UNKNOWN
        id in favorites -> copy(favorites = favorites - id) to FavoriteChange.REMOVED
        favorites.size >= MAX_FAVORITES -> this to FavoriteChange.LIMIT
        else -> copy(favorites = favorites + id) to FavoriteChange.ADDED
    }

    /** Ошибка, блокирующая сохранение, или null. */
    fun validationError(): String? {
        rooms.forEach { room ->
            if (room.name.isBlank()) return "У каждой комнаты должно быть название"
            if (!validEntity(room.temperatureEntity, "sensor")) return "Выберите sensor.* температуры для комнаты «${room.name}»"
            if (room.humidityEntity.isNotBlank() && !validEntity(room.humidityEntity, "sensor")) return "Выберите sensor.* влажности для комнаты «${room.name}»"
        }
        if (weather.source == SOURCE_HA && weather.entityId.isNotBlank() && !validEntity(weather.entityId, "weather")) return "Погода: нужна сущность weather.*"
        if (favorites.size > MAX_FAVORITES) return "Избранных комнат может быть не больше $MAX_FAVORITES"
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("clock", JSONObject().apply {
            clock.use24h?.let { put("use_24h", it) }
            put("date_format", clock.dateFormat)
            put("seconds", clock.seconds)
            clock.timeZone?.let { put("time_zone", it) }
        })
        put("weather", JSONObject().apply {
            put("source", weather.source)
            put("entity_id", weather.entityId)
            put("unit", weather.unit)
            put("feels_like", weather.showFeelsLike)
            put("humidity", weather.showHumidity)
            put("wind", weather.showWind)
            put("precipitation", weather.showPrecipitation)
            put("pressure", weather.showPressure)
            put("hourly", weather.showHourly)
            put("daily", weather.showDaily)
        })
        put("rooms", JSONArray().apply {
            rooms.forEach { room ->
                put(JSONObject().apply {
                    put("id", room.id)
                    put("name", room.name)
                    room.icon?.let { put("icon", it) }
                    put("temperature", room.temperatureEntity)
                    if (room.humidityEntity.isNotBlank()) put("humidity", room.humidityEntity)
                })
            }
        })
        put("favorites", JSONArray(favorites))
    }

    /** Записывает себя в config плитки; остальные ключи (размер, стиль, неизвестные) сохраняются. */
    fun writeTo(config: String): String {
        val json = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
        return json.put(KEY, toJson()).toString()
    }

    companion object {
        fun validEntity(id: String, domain: String) = id.matches(Regex("$domain\\.[a-z0-9_]+"))
        fun roomUnitError(entityId: String, attributes: JSONObject?, humidity: Boolean = false): String? {
            if (entityId.isBlank() || attributes == null) return null
            val unit = attributes.optString("unit_of_measurement")
            val valid = if (humidity) unit == "%" else HomeClimateData.isTemperatureUnit(unit)
            return if (valid) null else "У $entityId нужна единица ${if (humidity) "%" else "°C или °F"}"
        }

        const val TYPE = "home_climate_modern"
        const val KEY = "home_climate"
        const val VERSION = 1
        const val MAX_FAVORITES = 5
        /** Пропорция свёрнутой плитки: ширина : высота. */
        const val ASPECT = 2f
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 320

        const val DATE_FULL = "full"      // Пятница, 30 октября
        const val DATE_SHORT = "short"    // Пт, 30 окт.
        const val DATE_NUMERIC = "numeric" // 30.10.2026
        val DATE_FORMATS = listOf(DATE_FULL to "Пятница, 30 октября", DATE_SHORT to "Пт, 30 окт.", DATE_NUMERIC to "30.10.2026")

        const val SOURCE_HA = "ha"
        const val SOURCE_YANDEX = "yandex"
        const val UNIT_AUTO = "auto"
        const val UNIT_C = "C"
        const val UNIT_F = "F"

        fun from(config: String): HomeClimateConfig = try {
            JSONObject(config).optJSONObject(KEY)?.let(::fromJson) ?: HomeClimateConfig()
        } catch (_: Exception) {
            HomeClimateConfig()
        }

        fun fromJson(json: JSONObject): HomeClimateConfig {
            val clock = json.optJSONObject("clock")
            val weather = json.optJSONObject("weather")
            val rooms = mutableListOf<Room>()
            val seen = HashSet<String>()
            json.optJSONArray("rooms")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    // Повреждённая или повторная запись получает новый id, но не теряет данных.
                    var id = obj.optString("id").takeIf { it.isNotBlank() } ?: newRoomId()
                    if (!seen.add(id)) id = newRoomId().also { seen.add(it) }
                    rooms += Room(id, obj.optString("name"), obj.optString("icon").takeIf { it.isNotBlank() },
                        obj.optString("temperature"), obj.optString("humidity"))
                }
            }
            val ids = rooms.mapTo(HashSet()) { it.id }
            val favorites = mutableListOf<String>()
            json.optJSONArray("favorites")?.let { array ->
                for (i in 0 until array.length()) {
                    val id = array.optString(i)
                    if (id in ids && id !in favorites && favorites.size < MAX_FAVORITES) favorites += id
                }
            }
            return HomeClimateConfig(
                Clock(
                    use24h = if (clock?.has("use_24h") == true) clock.optBoolean("use_24h") else null,
                    dateFormat = clock?.optString("date_format")?.takeIf { f -> DATE_FORMATS.any { it.first == f } } ?: DATE_FULL,
                    seconds = clock?.optBoolean("seconds", false) ?: false,
                    timeZone = clock?.optString("time_zone")?.takeIf { it.isNotBlank() }
                ),
                Weather(
                    entityId = weather?.optString("entity_id").orEmpty(),
                    unit = weather?.optString("unit")?.takeIf { it == UNIT_C || it == UNIT_F } ?: UNIT_AUTO,
                    showFeelsLike = weather?.optBoolean("feels_like", true) ?: true,
                    showHumidity = weather?.optBoolean("humidity", true) ?: true,
                    showWind = weather?.optBoolean("wind", true) ?: true,
                    showPrecipitation = weather?.optBoolean("precipitation", true) ?: true,
                    showPressure = weather?.optBoolean("pressure", true) ?: true,
                    showHourly = weather?.optBoolean("hourly", true) ?: true,
                    showDaily = weather?.optBoolean("daily", true) ?: true,
                    source = weather?.optString("source")?.takeIf { it == SOURCE_YANDEX } ?: SOURCE_HA
                ),
                rooms, favorites
            )
        }

        fun newRoomId(): String = "room_" + UUID.randomUUID().toString().take(8)

        /** Новая плитка: размер 2:1 и пустой шаблон без выдуманных источников; существующие ключи не меняются. */
        fun newTileConfig(existing: String = "{}"): String {
            val json = try { JSONObject(existing) } catch (_: Exception) { JSONObject() }
            if (!json.has("button_size")) json.put("button_size", DEFAULT_WIDTH)
            if (!json.has("widget_height")) json.put("widget_height", DEFAULT_HEIGHT)
            if (!json.has(KEY)) json.put(KEY, HomeClimateConfig().toJson())
            return json.toString()
        }

        /**
         * Отображаемый размер строго 2:1 внутри сохранённого/доступного прямоугольника: старый или
         * повреждённый размер не растягивает плитку в ленту. Сохранённый config не меняется.
         */
        fun fit(width: Int, height: Int): Pair<Int, Int> {
            val w = width.coerceAtLeast(MIN_HEIGHT * 2)
            val h = height.coerceAtLeast(MIN_HEIGHT)
            return if (w >= h * ASPECT) (h * ASPECT).toInt() to h else ((w / ASPECT).toInt() * 2) to (w / ASPECT).toInt()
        }

        const val MIN_HEIGHT = 120

        private fun <T> move(list: List<T>, item: T, delta: Int): List<T> {
            val index = list.indexOf(item)
            val target = index + delta
            if (index < 0 || target !in list.indices) return list
            return list.toMutableList().apply { add(target, removeAt(index)) }
        }
    }
}
