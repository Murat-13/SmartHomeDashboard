package com.example.smarthomedashboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import org.json.JSONObject

/**
 * Контурные иконки Modern (сетка 24×24, линия 2, скруглённые концы) — рисуются прямо на Canvas,
 * без набора ассетов. Все контуры одного семейства (пропорции Lucide): одинаковые толщина и поля 2/24.
 *
 * [key] — устойчивый ключ для сохранения выбора пользователя (`modern_icon` в config плитки), [label] — подпись
 * в редакторе. Служебные значки (недоступно, ошибка, пустая группа) ключа не имеют и не выбираются.
 * Автоподбор без выбора: значок HA (`mdi:*`) → device_class → домен → общая иконка питания.
 */
internal enum class ModernIcon(val key: String?, val label: String?, private vararg val data: String) {
    // Порядок объявления — порядок в сетке выбора.
    POWER("power", "Питание", "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04"),
    BULB("lightbulb", "Лампа", "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5", "M9 18h6", "M10 22h4"),
    LAMP("lamp", "Светильник", "M8 2h8l4 10H4L8 2Z", "M12 12v6", "M8 22v-2c0-1.1.9-2 2-2h4a2 2 0 0 1 2 2v2H8Z"),
    CEILING_LIGHT("ceiling_light", "Люстра", "M12 2v5", "M6 7h12l4 9H2l4-9Z", "M9.17 16a3 3 0 1 0 5.66 0"),
    FLASH("flash", "Энергия", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"),
    BOILER("water_heater", "Бойлер", "M7 2h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M9 22v-2", "M15 22v-2",
        "M12 7c1.5 1.6 2.5 2.9 2.5 4.3a2.5 2.5 0 0 1-5 0C9.5 9.9 10.5 8.6 12 7z"),
    FAN("fan", "Вентилятор", "M10.83 16.38a6.08 6.08 0 0 1-8.62-7l5.41 1.45a6.08 6.08 0 0 1 7-8.62l-1.45 5.41a6.08 6.08 0 0 1 8.62 7l-5.41-1.45a6.08 6.08 0 0 1-7 8.62l1.45-5.41Z", "M12 12v.01"),
    SNOWFLAKE("snowflake", "Охлаждение", "M2 12h20", "M12 2v20", "M20 16l-4-4 4-4", "M4 8l4 4-4 4", "M16 4l-4 4-4-4", "M8 20l4-4 4 4"),
    HOME("home", "Дом", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .71-1.53l7-6a2 2 0 0 1 2.58 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
    GATE("gate", "Ворота", "M3 21V5", "M21 21V5", "M3 8h18", "M3 18h18", "M7.5 8v10", "M12 8v10", "M16.5 8v10"),
    GARAGE("garage", "Гараж", "M22 8.35V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.35A2 2 0 0 1 3.26 6.5l8-3.2a2 2 0 0 1 1.48 0l8 3.2A2 2 0 0 1 22 8.35Z", "M6 22V11h12v11", "M6 15h12", "M6 18.5h12"),
    THERMOMETER("thermometer", "Температура", "M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"),
    SOCKET("plug", "Розетка", "M12 22v-5", "M9 8V2", "M15 8V2", "M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"),
    WATER("water", "Вода", "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"),
    AIR_CONDITIONER("air_conditioner", "Кондиционер", "M6 12H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2", "M6 8h12",
        "M18.3 17.7a2.5 2.5 0 0 1-3.16 3.83 2.53 2.53 0 0 1-1.14-2V12", "M6.6 15.6A2 2 0 1 0 10 17v-5"),
    FLAME("flame", "Отопление", "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z"),
    SUN("sun", "Уличный свет", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2", "M12 20v2", "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
        "M2 12h2", "M20 12h2", "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41"),
    TREE("tree", "Сад", "M17 14l3 3.3a1 1 0 0 1-.7 1.7H4.7a1 1 0 0 1-.7-1.7L7 14h-.3a1 1 0 0 1-.7-1.7L9 9h-.2A1 1 0 0 1 8 7.3L12 3l4 4.3a1 1 0 0 1-.8 1.7H15l3 3.3a1 1 0 0 1-.7 1.7H17Z", "M12 22v-3"),
    LOCK("lock", "Замок", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4"),
    BLINDS("blinds", "Шторы", "M3 3h18", "M20 7H8", "M20 11H8", "M10 19h10", "M8 15h12", "M4 3v14", "M4 17a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    TV("tv", "Телевизор", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M17 2l-5 5-5-5"),
    PLAY("play", "Сценарий", "M6 3l14 9-14 9V3z"),
    TOGGLE("toggle", "Тумблер", "M8 6h8a6 6 0 0 1 0 12H8a6 6 0 0 1 0-12z", "M16 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    // Помещения (плитка «Часы · Погода · Климат» и кнопки). То же семейство контуров.
    BED("bed", "Спальня", "M2 4v16", "M2 8h18a2 2 0 0 1 2 2v10", "M2 17h20", "M6 8v9"),
    SOFA("sofa", "Гостиная", "M20 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v3",
        "M2 16a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5a2 2 0 0 0-4 0v1.5a.5.5 0 0 1-.5.5h-11a.5.5 0 0 1-.5-.5V11a2 2 0 0 0-4 0z",
        "M4 18v2", "M20 18v2", "M12 4v9"),
    KITCHEN("kitchen", "Кухня", "M2 12h20", "M20 12v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8", "M4 8l16-4",
        "M8.86 6.78l-.45-1.81a2 2 0 0 1 1.45-2.43l1.94-.48a2 2 0 0 1 2.43 1.46l.45 1.8"),
    BATH("bath", "Ванная", "M10 4L8 6", "M17 19v2", "M2 12h20", "M7 19v2",
        "M9 5L7.62 3.62A2.12 2.12 0 0 0 4 5v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"),
    BABY("baby", "Детская", "M9 12h.01", "M15 12h.01", "M10 16c.5.3 1.2.5 2 .5s1.5-.2 2-.5",
        "M19 6.3a9 9 0 0 1 1.8 3.9 2 2 0 0 1 0 3.6 9 9 0 0 1-17.6 0 2 2 0 0 1 0-3.6A9 9 0 0 1 12 3c2 0 3.5 1.1 3.5 2.5s-.9 2.5-2 2.5c-.8 0-1.5-.4-1.5-1"),
    OFFICE("office", "Кабинет", "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8", "M12 17v4"),
    BOX("box", "Кладовая", "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
        "M12 22V12", "M3.29 7L12 12l8.71-5", "M7.5 4.27l9 5.15"),
    SERVER("server", "Серверная", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M6 6h.01", "M6 18h.01"),
    HELP(null, null, "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"),
    WARNING(null, null, "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 8v4", "M12 16h.01"),
    EMPTY_GRID(null, null, "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
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
        /** Ключ выбранной иконки в config кнопки/группы. Нет ключа — прежнее поведение (автоподбор). */
        const val CONFIG_KEY = "modern_icon"

        /** Иконки, доступные для выбора, в порядке сетки редактора. */
        val choices: List<ModernIcon> = entries.filter { it.key != null }

        private val aliases = mapOf("boiler" to "water_heater", "ac_unit" to "air_conditioner")

        /** Иконка по сохранённому ключу; неизвестный ключ (например, из более новой версии) — null. */
        fun fromKey(raw: String?): ModernIcon? {
            val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val canonical = aliases[key] ?: key
            return choices.firstOrNull { it.key == canonical }
        }

        fun fromConfig(config: String): ModernIcon? = try {
            fromConfig(JSONObject(config))
        } catch (_: org.json.JSONException) {
            null
        }

        fun fromConfig(config: JSONObject): ModernIcon? = fromKey(config.optString(CONFIG_KEY))

        /**
         * Записывает выбор только при изменении. «Авто» (null) удаляет ключ; неизвестный сохранённый
         * ключ не трогаем, пока пользователь явно не выберет другую иконку.
         */
        fun writeIfChanged(config: JSONObject, icon: ModernIcon?, changed: Boolean) {
            if (!changed) return
            if (icon != null && fromConfig(config) == icon) return
            if (icon == null) config.remove(CONFIG_KEY) else config.put(CONFIG_KEY, icon.key)
        }

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
