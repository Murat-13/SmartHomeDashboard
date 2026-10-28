package com.example.smarthomedashboard

import android.content.ContextWrapper

import android.app.Activity
import android.view.View
import android.widget.*
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Обновление содержимого карточек. Не запускает сеть и не управляет жизненным циклом Activity. */
internal class DashboardContentController(
    private val activity: Activity,
    private val devices: DashboardDeviceState,
    private val sources: Sources,
    private val expanded: () -> View?,
    private val touch: () -> Unit
) : ContextWrapper(activity) {
    interface Sources {
        val panel: FrameLayout
        val tiles: TileManager
        val weather: YandexWeatherManager
        val temperatureUnit: String
    }
    private val bottomPanel get() = sources.panel
    private val tileManager get() = sources.tiles
    private val yandexWeatherManager get() = sources.weather
    private val tempUnit get() = sources.temperatureUnit
    private val singleStates get() = devices.singleStates
    private val entityAttributes get() = devices.entityAttributes
    private val expandedSensorView get() = expanded()
    val weatherForecastScroll = WeatherForecastScrollState()
    private fun resetSensorCollapseTimer() = touch()

    fun updateClockViews() {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            v.findViewWithTag<TextView>("clock_text")?.text = time
            v.findViewWithTag<TextView>("clock_date")?.text = date
        }
        // Тик часов не приносит новых данных подробного прогноза.
        updateWeatherViews(refreshExpanded = false)
    }

    fun updateWeatherViews() {
        updateWeatherViews(refreshExpanded = true)
    }

    fun updateWeatherViews(refreshExpanded: Boolean) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)

        for (i in 0 until bottomPanel.childCount) {
            val v = bottomPanel.getChildAt(i)
            val tag = v.tag as? String ?: continue
            val tile = tileManager.getAllTiles().find { it.id == tag } ?: continue
            if (tile.type == "weather") {
                val container = v.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && expandedSensorView == v) {
                    if (refreshExpanded) buildWeatherDetailedContent(tile, container)
                    continue
                }

                if (useYandex) {
                    CompactWeatherRenderer.renderYandex(v, yandexWeatherManager.currentTemp,
                        yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState),
                        yandexWeatherManager.translateCondition(yandexWeatherManager.currentState))
                } else {
                    CompactWeatherRenderer.renderHomeAssistant(v, tile, singleStates::get, entityAttributes::get)
                }
            }
        }
    }

    fun buildWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        val useYandex = getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        val source = if (useYandex) "yandex" else "ha:${WeatherEntityResolver.resolve(tile.config)}"
        weatherForecastScroll.rebuild(container, tile.id, source) {
            if (useYandex) {
                buildYandexWeatherDetailedContent(tile, container)
            } else {
                HomeAssistantWeatherDetailsRenderer.render(
                    activity,
                    tile,
                    container,
                    entityAttributes::get,
                    singleStates::get,
                    ::resetSensorCollapseTimer
                )
            }
        }
    }

    fun buildYandexWeatherDetailedContent(tile: TileEntity, container: LinearLayout) {
        YandexWeatherDetailsRenderer.render(
            activity,
            container,
            yandexWeatherManager,
            ::resetSensorCollapseTimer
        )
    }

    fun updateSensorDisplay() {
        val sensorTiles = tileManager.getTilesByContainer("grid")
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val tile = sensorTiles.find { it.id == tag } ?: continue

            if (child is FrameLayout) {
                val isExpanded = (expandedSensorView == child)

                val idsToShow = if (isExpanded) {
                    getExpandedSensorIds(tile)
                } else {
                    getCollapsedSensorIds(tile)
                }

                // Погодное дерево обновляется отдельно, обычные строки не должны его перестраивать.
                val container = child.findViewWithTag<LinearLayout>("sensor_text_container")
                if (container != null && tile.type != "clock" && tile.type != "weather") {
                    buildSensorContent(tile, idsToShow, isExpanded, container)
                }

                if (tile.type == "clock") {
                    updateClockViews()
                } else if (tile.type == "battery") {
                    updateBatteryWidget(tile, child)
                } else {
                    updateNormalSensorWidget(tile, child)
                }
            }
        }
    }

    fun updateBatteryWidget(tile: TileEntity, view: FrameLayout) {
        BatteryWidgetRenderer.render(tile, view, singleStates::get)
    }

    fun updateNormalSensorWidget(tile: TileEntity, child: FrameLayout) {
        SensorBackgroundRenderer.render(tile, child, singleStates::get)
    }

    fun getCollapsedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getCollapsedSensorIds(tile)
    }

    fun getExpandedSensorIds(tile: TileEntity): List<String> {
        return SensorConfigResolver.getExpandedSensorIds(tile)
    }

    fun buildSensorContent(tile: TileEntity, entityIds: List<String>, isExpanded: Boolean, container: LinearLayout) {
        SensorContentRenderer.render(
            tile,
            entityIds,
            isExpanded,
            container,
            ::loadSensorConfigs,
            singleStates::get,
            ::formatSensorValue
        )
    }

    fun loadSensorConfigs(tile: TileEntity, isExpanded: Boolean): List<SensorConfig> {
        return SensorConfigResolver.loadSensorConfigs(tile, isExpanded)
    }

    fun formatSensorValue(value: String, decimals: Int, entityId: String? = null): String {
        val unit = if (entityId != null) {
            entityAttributes[entityId]?.optString("unit_of_measurement") ?: ""
        } else {
            ""
        }
        return SensorValueFormatter.formatSensorValue(value, decimals, unit, tempUnit)
    }

    fun formatFloat(v: String, d: Int): String {
        return SensorValueFormatter.formatFloat(v, d)
    }
}
