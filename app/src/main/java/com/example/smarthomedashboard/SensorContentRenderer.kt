package com.example.smarthomedashboard

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.data.TileEntity

internal object SensorContentRenderer {
    // Строит строки обычных датчиков и батареи, сохраняя первый View как заголовок.
    // Для погодного дерева с иконкой, температурой и описанием этот контракт не подходит.
    fun render(
        tile: TileEntity,
        entityIds: List<String>,
        isExpanded: Boolean,
        container: LinearLayout,
        loadSensorConfigs: (TileEntity, Boolean) -> List<SensorConfig>,
        stateForEntity: (String) -> String?,
        formatSensorValue: (String, Int, String) -> String
    ) {
        val sensorConfigs = loadSensorConfigs(tile, isExpanded)
        val socEntityId = if (tile.type == "battery") BatteryWidgetRenderer.socEntityId(tile) else null

        val rows = mutableListOf<Pair<String, String>>()
        var maxNameLen = 0

        for (eid in entityIds) {
            val rawValue = stateForEntity(eid) ?: "—"
            val config = sensorConfigs.find { it.entityId == eid }
            val displayName = config?.displayName ?: eid.substringAfterLast("_").replace("_", " ")

            // Определяем точность по умолчанию
            val defaultDecimals = if (eid.contains("temp") || eid.contains("freq")) 1 else if (eid.contains("current") || eid.contains("energy")) 2 else 0
            val decimals = config?.decimals ?: defaultDecimals

            val formattedValue = if (eid == socEntityId && BatteryWidgetRenderer.socValue(rawValue) == null) {
                "—"
            } else {
                formatSensorValue(rawValue, decimals, eid)
            }
            rows.add(displayName to formattedValue)
            if (displayName.length > maxNameLen) maxNameLen = displayName.length
        }

        // Форматирование завершено до изменения Views: ошибка не оставит частично обновлённую карточку.
        val start = if (container.childCount == 0) 0 else 1
        val fontSize = tile.fontSize.toFloat()
        val fontPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, container.resources.displayMetrics)
        rows.forEachIndexed { index, (name, value) ->
            val position = start + index
            val existing = container.getChildAt(position)
            val rowView = if (existing is TextView && existing.getTag(R.id.sensor_content_renderer_row) == true) {
                existing
            } else {
                if (existing != null) container.removeViewAt(position)
                TextView(container.context).apply {
                    setTag(R.id.sensor_content_renderer_row, true)
                    textSize = fontSize
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setShadowLayer(4f, 2f, 2f, Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                }.also { container.addView(it, position) }
            }
            val text = "${name.padEnd(maxNameLen, ' ')}  $value"
            if (rowView.text.toString() != text) rowView.text = text
            if (rowView.textSize != fontPixels) rowView.textSize = fontSize
        }
        while (container.childCount > start + rows.size) container.removeViewAt(container.childCount - 1)
    }
}
