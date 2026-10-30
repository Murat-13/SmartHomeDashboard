package com.example.smarthomedashboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import org.json.JSONObject

/**
 * Контурные иконки Modern (сетка 24×24, линия 2) — рисуются прямо на Canvas, без набора ассетов.
 * Подбор: значок HA (`mdi:*`) → device_class → домен → общая иконка питания.
 */
internal enum class ModernIcon(private vararg val data: String) {
    BULB("M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5", "M9 18h6", "M10 22h4"),
    LAMP("M8 2h8l4 10H4L8 2Z", "M12 12v6", "M8 22v-2c0-1.1.9-2 2-2h4a2 2 0 0 1 2 2v2H8Z"),
    SUN("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2", "M12 20v2", "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
        "M2 12h2", "M20 12h2", "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41"),
    TREE("M17 14l3 3.3a1 1 0 0 1-.7 1.7H4.7a1 1 0 0 1-.7-1.7L7 14h-.3a1 1 0 0 1-.7-1.7L9 9h-.2A1 1 0 0 1 8 7.3L12 3l4 4.3a1 1 0 0 1-.8 1.7H15l3 3.3a1 1 0 0 1-.7 1.7H17Z", "M12 22v-3"),
    GARAGE("M22 8.35V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.35A2 2 0 0 1 3.26 6.5l8-3.2a2 2 0 0 1 1.48 0l8 3.2A2 2 0 0 1 22 8.35Z", "M6 22V11h12v11", "M6 15h12", "M6 18.5h12"),
    GATE("M3 21V5", "M21 21V5", "M3 8h18", "M3 18h18", "M7.5 8v10", "M12 8v10", "M16.5 8v10"),
    POWER("M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04"),
    SOCKET("M12 22v-5", "M9 8V2", "M15 8V2", "M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"),
    FAN("M10.83 16.38a6.08 6.08 0 0 1-8.62-7l5.41 1.45a6.08 6.08 0 0 1 7-8.62l-1.45 5.41a6.08 6.08 0 0 1 8.62 7l-5.41-1.45a6.08 6.08 0 0 1-7 8.62l1.45-5.41Z", "M12 12v.01"),
    LOCK("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4"),
    FLAME("M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z"),
    TV("M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M17 2l-5 5-5-5"),
    BLINDS("M3 3h18", "M20 7H8", "M20 11H8", "M10 19h10", "M8 15h12", "M4 3v14", "M4 17a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    PLAY("M6 3l14 9-14 9V3z"),
    TOGGLE("M8 6h8a6 6 0 0 1 0 12H8a6 6 0 0 1 0-12z", "M16 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    HELP("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"),
    WARNING("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 8v4", "M12 16h.01"),
    EMPTY_GRID("M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
        "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z");

    private val path: Path by lazy {
        Path().apply { data.forEach { addPath(PathParser.createPathFromPathData(it)) } }
    }

    /** Иконка в квадрате [size] с левым верхним углом (x, y); цвет и альфа — из [paint]. */
    fun draw(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        val scale = size / 24f
        val stroke = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        // Тонкие значки крошечных миниатюр остаются различимыми.
        paint.strokeWidth = (2f).coerceAtLeast(1.2f / scale)
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.strokeWidth = stroke
    }

    companion object {
        /** [hint] — пользовательская иконка плитки (`mdi:...`), если она задана. */
        fun forEntity(entityId: String, attributes: JSONObject?, hint: String? = null): ModernIcon {
            byName(hint)?.let { return it }
            byName(attributes?.optString("icon"))?.let { return it }
            val domain = entityId.substringBefore('.')
            val deviceClass = attributes?.optString("device_class").orEmpty()
            when (deviceClass) {
                "outlet" -> return SOCKET
                "garage", "garage_door" -> return GARAGE
                "gate" -> return GATE
                "blind", "curtain", "shade", "shutter", "awning", "window" -> return BLINDS
                "tv" -> return TV
            }
            return when (domain) {
                "light" -> BULB
                "switch" -> POWER
                "fan" -> FAN
                "cover" -> BLINDS
                "lock" -> LOCK
                "climate", "water_heater" -> FLAME
                "media_player" -> TV
                "script", "scene", "automation" -> PLAY
                "input_boolean" -> TOGGLE
                else -> POWER
            }
        }

        private fun byName(raw: String?): ModernIcon? {
            val name = raw?.trim()?.lowercase()?.removePrefix("mdi:")?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                "garage" in name -> GARAGE
                "gate" in name || "fence" in name -> GATE
                "lock" in name -> LOCK
                "tree" in name || "flower" in name || "sprinkler" in name || "grass" in name || "pine" in name -> TREE
                "sun" in name || "outdoor" in name || "spotlight" in name || "flood" in name -> SUN
                "lamp" in name || "ceiling" in name || "chandelier" in name || "sconce" in name -> LAMP
                "bulb" in name || "light" in name -> BULB
                "fan" in name -> FAN
                "socket" in name || "outlet" in name || "plug" in name -> SOCKET
                "fire" in name || "flame" in name || "boiler" in name || "radiator" in name ||
                    "thermostat" in name || "heat" in name -> FLAME
                "tv" in name || "television" in name -> TV
                "blind" in name || "curtain" in name || "shutter" in name || "window" in name -> BLINDS
                "toggle" in name -> TOGGLE
                "play" in name || "script" in name -> PLAY
                "power" in name -> POWER
                else -> null
            }
        }
    }
}
