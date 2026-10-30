package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONObject

/** Только расчёт источников; отправку и согласование подписок выполняет владелец соединения. */
internal data class PanelSubscriptionPlan(
    val entityIds: List<String>,
    val forecastIds: Set<String>,
    val hourlyForecastIds: Set<String> = emptySet()
) {
    companion object {
        /** [tiles] — текущие включённые плитки в порядке, предоставленном TileManager. */
        fun create(tiles: List<TileEntity>, motionSensorId: String?, useYandex: Boolean): PanelSubscriptionPlan {
            val ids = mutableSetOf<String>()
            val weatherIds = mutableSetOf<String>()
            val hourlyIds = mutableSetOf<String>()
            motionSensorId?.let { ids.add(it) }
            tiles.forEach { tile ->
                if (tile.type == HomeClimateConfig.TYPE) {
                    // У плитки своя сущность HA; выбор Яндекса для прежней плитки погоды ей не мешает.
                    val config = HomeClimateConfig.from(tile.config)
                    val weather = config.weather
                    if (weather.entityId.startsWith("weather.")) {
                        ids.add(weather.entityId)
                        if (weather.showDaily) weatherIds.add(weather.entityId)
                        // Вероятность осадков берётся из ближайшего часа.
                        if (weather.showHourly || weather.showPrecipitation) hourlyIds.add(weather.entityId)
                    }
                    config.rooms.forEach { room ->
                        room.temperatureEntity.takeIf { it.isNotBlank() }?.let(ids::add)
                        room.humidityEntity.takeIf { it.isNotBlank() }?.let(ids::add)
                    }
                    return@forEach
                }
                if (tile.type == "weather" && !useYandex) {
                    val source = WeatherEntityResolver.resolve(tile.config)
                    if (source.startsWith("weather.")) weatherIds.add(source)
                }
                if (tile.type == "button" || tile.type == "group") {
                    // Повреждённый список не должен давать частичный набор источников для подписок.
                    val result = TileButtonConfig.subscriptionIds(tile.config)
                    if (result is ButtonConfigResult.Valid) {
                        result.value.forEach { ids.add(it) }
                    }
                    return@forEach
                }
                try {
                    val config = JSONObject(tile.config)
                    config.optString("entity_id", "").takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                    config.optJSONArray("entity_ids")?.let { array ->
                        for (i in 0 until array.length()) ids.add(array.getString(i))
                    }
                } catch (_: Exception) {
                    // Сохраняем прежнюю границу ошибки: уже прочитанные обычные источники остаются.
                }
            }
            // Это не фильтр state_changed: клиент получает общие события HA.
            // В отличие от PanelEntityIds, здесь не собираются строки оформления и расширенных конфигураций.
            return PanelSubscriptionPlan(ids.toList(), weatherIds.toSet(), hourlyIds.toSet())
        }
    }
}
