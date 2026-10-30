package com.example.smarthomedashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Готовые к показу данные плитки «Часы · Погода · Климат» на один момент. Строится из уже
 * полученных данных панели (без сети); карточки только отображают его.
 */
internal data class HomeClimateSnapshot(
    val title: String,
    val config: HomeClimateConfig,
    val current: HomeClimateData.Current,
    /** Все комнаты в порядке редактора (развёрнутый список). */
    val rooms: List<RoomLine>,
    /** Избранные (до 5) в своём порядке (компактный вид). */
    val favorites: List<RoomLine>,
    val hourly: Forecasts,
    val daily: Forecasts,
    /** Нет связи или синхронизация: показания и прогнозы могут быть неактуальны. */
    val offlineStatus: String?
) {
    data class RoomLine(val room: HomeClimateConfig.Room, val temperature: HomeClimateData.Temperature?, val humidity: String?)

    enum class ForecastState { HIDDEN, NOT_CONFIGURED, WAITING, UNSUPPORTED, EMPTY, READY }

    data class Forecasts(val state: ForecastState, val items: List<HomeClimateData.Forecast> = emptyList())

    companion object {
        fun build(
            title: String,
            config: HomeClimateConfig,
            readings: BatteryReadings,
            attributes: (String) -> JSONObject?,
            hourlyData: (String) -> JSONArray?,
            hourlyKnown: (String) -> Boolean,
            hourlyUnsupported: (String) -> Boolean,
            now: Long = System.currentTimeMillis(),
            dailyData: (String) -> JSONArray? = { attributes(it)?.optJSONArray("forecast") },
            dailyKnown: (String) -> Boolean = { attributes(it)?.has("forecast") == true },
            dailyUnsupported: (String) -> Boolean = { false }
        ): HomeClimateSnapshot {
            val weather = config.weather
            val entity = weather.entityId
            val sourceUnit = attributes(entity)?.optString("temperature_unit")?.takeIf { it.isNotBlank() }
            val configured = entity.startsWith("weather.")

            val hourlyItems = if (configured) HomeClimateData.hourly(hourlyData(entity), weather, sourceUnit, now) else emptyList()
            val hourly = when {
                !weather.showHourly -> Forecasts(ForecastState.HIDDEN)
                !configured -> Forecasts(ForecastState.NOT_CONFIGURED)
                hourlyUnsupported(entity) -> Forecasts(ForecastState.UNSUPPORTED)
                !hourlyKnown(entity) -> Forecasts(ForecastState.WAITING)
                hourlyItems.isEmpty() -> Forecasts(ForecastState.EMPTY)
                else -> Forecasts(ForecastState.READY, hourlyItems)
            }
            val dailyArray = dailyData(entity)
            val dailyItems = if (configured) HomeClimateData.daily(dailyArray, weather, sourceUnit,
                HomeClimateData.zone(config.clock), now) else emptyList()
            val daily = when {
                !weather.showDaily -> Forecasts(ForecastState.HIDDEN)
                !configured -> Forecasts(ForecastState.NOT_CONFIGURED)
                dailyUnsupported(entity) -> Forecasts(ForecastState.UNSUPPORTED)
                !dailyKnown(entity) -> Forecasts(ForecastState.WAITING)
                dailyItems.isEmpty() -> Forecasts(ForecastState.EMPTY)
                else -> Forecasts(ForecastState.READY, dailyItems)
            }

            fun line(room: HomeClimateConfig.Room) = RoomLine(room,
                HomeClimateData.temperature(readings, room.temperatureEntity, weather.unit),
                HomeClimateData.humidity(readings, room.humidityEntity))
            val lines = config.rooms.map(::line)
            val byId = lines.associateBy { it.room.id }
            val current = HomeClimateData.current(readings, weather, hourlyItems.takeIf { hourly.state == ForecastState.READY || !weather.showHourly })
            val forecastWarning = current.status?.takeIf { hourly.items.isNotEmpty() || daily.items.isNotEmpty() }
                ?.let { "Погодная сущность: $it — прогноз может быть устаревшим" }
            return HomeClimateSnapshot(
                title, config,
                current,
                lines,
                config.favoriteRooms.mapNotNull { byId[it.id] },
                hourly, daily, readings.status ?: forecastWarning
            )
        }

        fun from(title: String, config: HomeClimateConfig, session: HaSession, devices: DashboardDeviceState) = build(
            title, config, BatteryReadings.from(session, devices), devices.entityAttributes::get,
            devices.hourlyForecasts::get, devices.hourlyForecasts::containsKey, { it in devices.hourlyUnsupported },
            dailyData = devices.dailyForecasts::get, dailyKnown = devices.dailyForecasts::containsKey,
            dailyUnsupported = { it in devices.dailyUnsupported }
        )

        /** Предпросмотр редактора: тот же снимок текущих данных и прогнозов панели. */
        fun from(title: String, config: HomeClimateConfig, model: MainUiState) = build(
            title, config, BatteryReadings.from(model), model.entityAttributes::get, model.hourlyForecasts::get, model.hourlyForecasts::containsKey, { it in model.hourlyUnsupported },
            dailyData = model.dailyForecasts::get, dailyKnown = model.dailyForecasts::containsKey,
            dailyUnsupported = { it in model.dailyUnsupported }
        )
    }
}
