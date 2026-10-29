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
    private val touch: () -> Unit,
    private val renderModernBattery: (TileEntity, FrameLayout) -> Unit = { _, _ -> }
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
            val view = bottomPanel.getChildAt(i)
            val tile = (view.tag as? String)?.let { id -> tileManager.getAllTiles().find { it.id == id } } ?: continue
            if (tile.type == "weather") updateWeatherCard(tile, view, refreshExpanded, useYandex)
        }
    }

    private fun updateWeatherCard(tile: TileEntity, view: View, refreshExpanded: Boolean, useYandex: Boolean) {
        val container = view.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null && expandedSensorView === view) {
            if (refreshExpanded) buildWeatherDetailedContent(tile, container)
            return
        }
        if (useYandex) {
            CompactWeatherRenderer.renderYandex(view, yandexWeatherManager.currentTemp,
                yandexWeatherManager.getWeatherIcon(yandexWeatherManager.currentState),
                yandexWeatherManager.translateCondition(yandexWeatherManager.currentState))
        } else {
            CompactWeatherRenderer.renderHomeAssistant(view, tile, singleStates::get, entityAttributes::get)
        }
    }

    fun updateTiles(tiles: Collection<TileEntity>, viewForTile: (String) -> View?) {
        val useYandex = if (tiles.any { it.type == "weather" }) {
            getSharedPreferences("dashboard_prefs", MODE_PRIVATE).getBoolean("use_yandex_weather", false)
        } else false
        tiles.forEach { tile ->
            if (tile.container != "grid") return@forEach
            val view = viewForTile(tile.id) as? FrameLayout ?: return@forEach
            if (tile.type == "weather") {
                updateWeatherCard(tile, view, true, useYandex)
                updateNormalSensorWidget(tile, view)
            } else {
                updateSensorCard(tile, view)
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
        val tiles = tileManager.getTilesByContainer("grid").associateBy { it.id }
        for (i in 0 until bottomPanel.childCount) {
            val child = bottomPanel.getChildAt(i) as? FrameLayout ?: continue
            val tile = tiles[child.tag as? String] ?: continue
            updateSensorCard(tile, child)
        }
    }

    private fun updateSensorCard(tile: TileEntity, child: FrameLayout) {
        // У Modern своё дерево и оценка достоверности; цветовые правила фона ей не применяются.
        if (tile.type == BatteryModernConfig.TYPE) {
            renderModernBattery(tile, child)
            return
        }
        val isExpanded = expandedSensorView === child
        val ids = if (isExpanded) getExpandedSensorIds(tile) else getCollapsedSensorIds(tile)
        val container = child.findViewWithTag<LinearLayout>("sensor_text_container")
        if (container != null && tile.type != "clock" && tile.type != "weather") {
            buildSensorContent(tile, ids, isExpanded, container)
        }
        when (tile.type) {
            "clock" -> {
                // Адресное событие не должно обновлять посторонние погодные деревья.
                val now = Date()
                child.findViewWithTag<TextView>("clock_text")?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                child.findViewWithTag<TextView>("clock_date")?.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            }
            "battery" -> updateBatteryWidget(tile, child)
            else -> updateNormalSensorWidget(tile, child)
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
