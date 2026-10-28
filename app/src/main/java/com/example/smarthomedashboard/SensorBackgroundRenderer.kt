package com.example.smarthomedashboard

import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray

internal object SensorBackgroundRenderer {
    fun render(
        tile: TileEntity,
        child: FrameLayout,
        stateProvider: (String) -> String?
    ) {
        // Применяем правила цвета (колонки От и До)
        var finalColor: String? = null
        try {
            val rulesJson = tile.colorRules
            if (rulesJson.isNotEmpty() && rulesJson != "[]") {
                val rulesArr = JSONArray(rulesJson)
                // Проходим по всем правилам. Последнее сработавшее перекроет предыдущие.
                for (i in 0 until rulesArr.length()) {
                    val obj = rulesArr.getJSONObject(i)
                    val rEntityId = obj.optString("entity_id")
                    val rFromStr = obj.optString("from", "")
                    val rToStr = obj.optString("to", "")
                    val rColor = obj.optString("color_hex", "")

                    if (rEntityId.isNotEmpty() && rColor.isNotEmpty()) {
                        val currentVal = stateProvider(rEntityId) ?: ""
                        if (ColorRuleMatcher.matches(currentVal, rFromStr, rToStr)) {
                            finalColor = rColor
                        }
                    }
                }
            }

            // Без сработавшего правила фон одинаковый для всех плиток, независимо от названия.
            child.setBackgroundColor((finalColor ?: "#80333333").toColorInt())
        } catch (_: Exception) {
            child.setBackgroundColor("#80333333".toColorInt())
        }
    }
}
