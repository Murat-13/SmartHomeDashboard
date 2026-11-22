package com.example.smarthomedashboard

import android.content.Context
import androidx.core.content.edit
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Калитки и ворота (docs/gate-tz.md): общий список проходов панели. Здесь — чистая модель (что за проход, какие у него
 * кнопки, какой командой Home Assistant их исполнить, где он сейчас) и хранение. Исполнение — [GateHub].
 *
 * Главное правило (решение владельца 22.11.2026, по образцу профессиональных домофонов): логика железа — в железе или в
 * Home Assistant. Импульс, автовыключение реле, порядок «отпустить магнит → открыть → закрыть → включить магнит» и
 * фотоэлементы держит реле или контроллер привода; приложение шлёт одну команду на действие и никогда не держит замок
 * открытым своим таймером.
 */
internal data class Gate(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Чем открывать: реле, кнопка или сценарий ([Kind.RELAY], [Kind.STEP], [Kind.SCRIPT]), замок ([Kind.LOCK]), привод ([Kind.COVER]). */
    val open: String = "",
    /** Чем закрывать — только у своего сценария ([Kind.SCRIPT]); у замка и привода — та же сущность [open]. */
    val close: String = "",
    /** Пешеходный проход у привода — отдельная сущность (реле, кнопка или сценарий). */
    val pedestrian: String = "",
    /** Датчик положения: `binary_sensor` двери или калитки, «вкл» — открыто. Лучший источник подтверждения. */
    val sensor: String = "",
    /** Умный замок: показывать «Запереть». */
    val lockButton: Boolean = false,
    /** Сколько ждать подтверждения после команды, с. У привода — с запасом на ход ворот. */
    val confirmSeconds: Int = DEFAULT_CONFIRM,
    val icon: Icon = Icon.WICKET
) {
    enum class Kind(val key: String, val title: String, val hint: String) {
        RELAY("relay", "Замок или защёлка через реле",
            "Электрозамок, защёлка или магнит через реле (например, Shelly). Одна кнопка «Открыть». Выключить реле " +
                "через 2–5 с должно само реле — настройте автовыключение в нём."),
        LOCK("lock", "Умный замок",
            "Замок, который виден в Home Assistant как замок. «Открыть» отщёлкивает дверь, если замок умеет, иначе " +
                "снимает с запирания; по желанию — «Запереть»."),
        COVER("cover", "Калитка или ворота с приводом",
            "Привод, который виден в Home Assistant как ворота (cover): «Открыть», «Закрыть», «Стоп», положение. " +
                "Магнит, фотоэлементы и автозакрытие — на контроллере привода."),
        STEP("step", "Привод с одной кнопкой «шаг»",
            "Контроллер ворот с одним входом «открыть / стоп / закрыть» через реле. Подпись кнопки — по датчику " +
                "положения; без датчика положение неизвестно."),
        SCRIPT("script", "Свой сценарий Home Assistant",
            "Любое железо: вы пишете в Home Assistant сценарий «открыть» (и, если нужно, «закрыть»), приложение " +
                "его запускает и ждёт датчик.");

        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
    }

    enum class Icon(val key: String, val title: String, val drawable: Int) {
        WICKET("wicket", "Калитка", R.drawable.ic_door_unlock),
        GATE("gate", "Ворота", R.drawable.ic_door_unlock),
        DOOR("door", "Дверь", R.drawable.ic_door_unlock);

        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: WICKET }
    }

    /** Действие кнопки. [STEP] — единственная кнопка привода «шаг». */
    enum class Action { OPEN, CLOSE, STOP, PEDESTRIAN, STEP }

    /** Все сущности прохода — для выбора в редакторе и проверки «настроен ли». */
    val entities: List<String> get() = listOf(open, close, pedestrian, sensor).filter { it.isNotBlank() }

    /** Настроен настолько, что кнопкой можно пользоваться. */
    val ready: Boolean get() = name.isNotBlank() && GateLogic.fits(kind, ROLE_OPEN, open)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind.key)
        put("open", open); put("close", close); put("pedestrian", pedestrian); put("sensor", sensor)
        put("lock_button", lockButton); put("confirm_seconds", confirmSeconds); put("icon", icon.key)
    }

    companion object {
        const val DEFAULT_CONFIRM = 15
        val CONFIRM_RANGE = 5..300
        const val ROLE_OPEN = "open"
        const val ROLE_CLOSE = "close"
        const val ROLE_PEDESTRIAN = "pedestrian"
        const val ROLE_SENSOR = "sensor"
        const val MAX = 8

        /** null — запись повреждена (нет id, имени или известного типа): пропускается, остальные проходы целы. */
        fun fromJson(j: JSONObject): Gate? {
            fun str(key: String) = j.optString(key).trim()
            val id = str("id").ifBlank { return null }
            val kind = Kind.of(j.optString("kind")) ?: return null
            return Gate(
                id = id, name = str("name").ifBlank { return null }, kind = kind,
                open = str("open"), close = str("close"), pedestrian = str("pedestrian"), sensor = str("sensor"),
                lockButton = j.optBoolean("lock_button", false),
                confirmSeconds = j.optInt("confirm_seconds", DEFAULT_CONFIRM).takeIf { it in CONFIRM_RANGE } ?: DEFAULT_CONFIRM,
                icon = Icon.of(j.optString("icon"))
            )
        }
    }
}

/** Где сейчас проход и откуда это известно. */
internal data class GateStatus(val position: Position, val source: Source, val percent: Int? = null) {
    enum class Position { UNKNOWN, CLOSED, OPEN, OPENING, CLOSING, JAMMED, OFFLINE }
    /** Датчик положения — надёжнее всего; состояние замка или привода; без источника — только «команда принята». */
    enum class Source { SENSOR, DEVICE, NONE }

    val moving get() = position == Position.OPENING || position == Position.CLOSING
    val open get() = position == Position.OPEN || position == Position.OPENING

    companion object {
        val UNKNOWN = GateStatus(Position.UNKNOWN, Source.NONE)
        val OFFLINE = GateStatus(Position.OFFLINE, Source.NONE)
    }
}

/** Чистая логика проходов: команды, кнопки, положение и подтверждение. */
internal object GateLogic {
    data class Command(val domain: String, val service: String, val entity: String)

    /** Подходит ли сущность для роли у этого типа прохода (фильтры выбора в редакторе). */
    fun fits(kind: Gate.Kind, role: String, entity: String): Boolean {
        if (entity.isBlank()) return false
        val domain = entity.substringBefore('.')
        return when (role) {
            Gate.ROLE_OPEN -> when (kind) {
                Gate.Kind.RELAY, Gate.Kind.STEP -> domain in PULSE
                Gate.Kind.LOCK -> domain == "lock"
                Gate.Kind.COVER -> domain == "cover"
                Gate.Kind.SCRIPT -> domain in SCRIPTS
            }
            Gate.ROLE_CLOSE -> kind == Gate.Kind.SCRIPT && domain in SCRIPTS
            Gate.ROLE_PEDESTRIAN -> kind == Gate.Kind.COVER && (domain in PULSE || domain == "cover")
            Gate.ROLE_SENSOR -> domain == "binary_sensor"
            else -> false
        }
    }

    /** Реле, кнопки и сценарии: одно нажатие — одна команда. */
    private val PULSE = setOf("switch", "input_boolean", "button", "input_button", "script")
    private val SCRIPTS = setOf("script", "automation", "button", "input_button")

    private fun pulse(entity: String): Command? = when (entity.substringBefore('.')) {
        "switch", "input_boolean" -> Command(entity.substringBefore('.'), "turn_on", entity)
        "button", "input_button" -> Command(entity.substringBefore('.'), "press", entity)
        "script" -> Command("script", "turn_on", entity)
        "automation" -> Command("automation", "trigger", entity)
        "cover" -> Command("cover", "open_cover", entity)
        else -> null
    }

    private fun features(readings: BatteryReadings?, entity: String): Int? =
        ((readings?.of(entity) as? BatteryReading.Value)?.attributes)?.takeIf { it.has("supported_features") }?.optInt("supported_features")

    /** Замок умеет отщёлкивать (`lock.open`, LockEntityFeature.OPEN = 1). Не знаем — снимаем с запирания. */
    fun lockOpens(readings: BatteryReadings?, entity: String) = (features(readings, entity) ?: 0) and 1 != 0

    /** Привод умеет «Стоп» (CoverEntityFeature.STOP = 8). Не знаем — кнопки нет: несработавший «Стоп» хуже отсутствующего. */
    fun coverStops(readings: BatteryReadings?, entity: String) = (features(readings, entity) ?: 0) and 8 != 0

    fun command(gate: Gate, action: Gate.Action, readings: BatteryReadings?): Command? = when (gate.kind) {
        Gate.Kind.RELAY -> if (action == Gate.Action.OPEN) pulse(gate.open)?.takeIf { gate.open.substringBefore('.') in PULSE } else null
        Gate.Kind.STEP -> if (action == Gate.Action.STEP) pulse(gate.open)?.takeIf { gate.open.substringBefore('.') in PULSE } else null
        Gate.Kind.LOCK -> when (action) {
            Gate.Action.OPEN -> Command("lock", if (lockOpens(readings, gate.open)) "open" else "unlock", gate.open)
            Gate.Action.CLOSE -> if (gate.lockButton) Command("lock", "lock", gate.open) else null
            else -> null
        }.takeIf { gate.open.startsWith("lock.") }
        Gate.Kind.COVER -> when (action) {
            Gate.Action.OPEN -> Command("cover", "open_cover", gate.open)
            Gate.Action.CLOSE -> Command("cover", "close_cover", gate.open)
            Gate.Action.STOP -> Command("cover", "stop_cover", gate.open)
            Gate.Action.PEDESTRIAN -> gate.pedestrian.takeIf { it.isNotBlank() }?.let(::pulse)
            Gate.Action.STEP -> null
        }?.takeIf { gate.open.startsWith("cover.") }
        Gate.Kind.SCRIPT -> when (action) {
            Gate.Action.OPEN -> pulse(gate.open)?.takeIf { gate.open.substringBefore('.') in SCRIPTS }
            Gate.Action.CLOSE -> gate.close.takeIf { it.isNotBlank() }?.let(::pulse)?.takeIf { gate.close.substringBefore('.') in SCRIPTS }
            else -> null
        }
    }

    /**
     * Кнопки прохода по порядку: главная — первой. «Стоп» — только пока привод движется ([moving]: по приводу или пока
     * ждём подтверждения своей команды): тогда он и нужен, а в покое кнопок меньше.
     */
    fun actions(gate: Gate, readings: BatteryReadings?, moving: Boolean = status(gate, readings).moving): List<Gate.Action> = when (gate.kind) {
        Gate.Kind.RELAY -> listOf(Gate.Action.OPEN)
        Gate.Kind.STEP -> listOf(Gate.Action.STEP)
        Gate.Kind.LOCK -> listOfNotNull(Gate.Action.OPEN, Gate.Action.CLOSE.takeIf { gate.lockButton })
        Gate.Kind.COVER -> listOfNotNull(Gate.Action.OPEN, Gate.Action.CLOSE,
            Gate.Action.STOP.takeIf { moving && coverStops(readings, gate.open) },
            Gate.Action.PEDESTRIAN.takeIf { gate.pedestrian.isNotBlank() })
        Gate.Kind.SCRIPT -> listOfNotNull(Gate.Action.OPEN, Gate.Action.CLOSE.takeIf { gate.close.isNotBlank() })
    }.filter { command(gate, it, readings) != null }

    /** «Стоп» — сразу по касанию; остальное — удержанием 1 с (случайное касание калитку не откроет). */
    fun instant(action: Gate.Action) = action == Gate.Action.STOP

    /**
     * Положение прохода. Движение привода — по приводу (датчик «открыто» его не покажет); иначе датчик положения;
     * иначе состояние замка, привода или реле.
     */
    fun status(gate: Gate, readings: BatteryReadings?): GateStatus {
        readings ?: return GateStatus.UNKNOWN
        val device = readings.of(gate.open)
        if (device == BatteryReading.Disconnected) return GateStatus.OFFLINE
        val raw = (device as? BatteryReading.Value)?.raw
        val percent = if (gate.kind == Gate.Kind.COVER) (device as? BatteryReading.Value)?.attributes
            ?.takeIf { it.has("current_position") }?.optInt("current_position")?.coerceIn(0, 100) else null
        if (gate.kind == Gate.Kind.COVER && (raw == "opening" || raw == "closing")) {
            return GateStatus(if (raw == "opening") GateStatus.Position.OPENING else GateStatus.Position.CLOSING, GateStatus.Source.DEVICE, percent)
        }
        if (gate.sensor.isNotBlank()) {
            when ((readings.of(gate.sensor) as? BatteryReading.Value)?.raw) {
                "on" -> return GateStatus(GateStatus.Position.OPEN, GateStatus.Source.SENSOR, percent)
                "off" -> return GateStatus(GateStatus.Position.CLOSED, GateStatus.Source.SENSOR, percent)
            }
        }
        val position = when (gate.kind) {
            Gate.Kind.LOCK -> when (raw) {
                "locked" -> GateStatus.Position.CLOSED
                "unlocked", "open" -> GateStatus.Position.OPEN
                "opening", "unlocking" -> GateStatus.Position.OPENING
                "locking" -> GateStatus.Position.CLOSING
                "jammed" -> GateStatus.Position.JAMMED
                else -> null
            }
            Gate.Kind.COVER -> when (raw) {
                "open" -> GateStatus.Position.OPEN
                "closed" -> GateStatus.Position.CLOSED
                else -> null
            }
            // Реле: включено — замок под током (открыт), выключено — закрыт. У кнопок и сценариев положения нет.
            Gate.Kind.RELAY -> when {
                gate.open.substringBefore('.') !in setOf("switch", "input_boolean") -> null
                raw == "on" -> GateStatus.Position.OPEN
                raw == "off" -> GateStatus.Position.CLOSED
                else -> null
            }
            Gate.Kind.STEP, Gate.Kind.SCRIPT -> null
        } ?: return GateStatus(GateStatus.Position.UNKNOWN, GateStatus.Source.NONE, percent)
        return GateStatus(position, GateStatus.Source.DEVICE, percent)
    }

    /**
     * Действие подтвердилось новым положением. Без источника положения подтверждения нет — честно «команда принята»;
     * реле или кнопка, которые при этом отозвались (новое состояние сущности), — «сработало» ([reacted]).
     */
    fun confirmed(action: Gate.Action, before: GateStatus, now: GateStatus): Boolean = when (action) {
        Gate.Action.OPEN, Gate.Action.PEDESTRIAN -> now.open
        Gate.Action.CLOSE -> now.position == GateStatus.Position.CLOSED || now.position == GateStatus.Position.CLOSING
        Gate.Action.STOP -> !now.moving && now.position != GateStatus.Position.UNKNOWN
        Gate.Action.STEP -> now.position != GateStatus.Position.UNKNOWN && now.position != before.position
    }

    fun reacted(beforeRaw: String?, nowRaw: String?) = nowRaw != null && nowRaw != beforeRaw

    /** Подпись кнопки. Привод «шаг» — по положению. */
    fun label(gate: Gate, action: Gate.Action, status: GateStatus): String = when (action) {
        Gate.Action.OPEN -> verb("Открыть", gate.name)
        Gate.Action.CLOSE -> verb(if (gate.kind == Gate.Kind.LOCK) "Запереть" else "Закрыть", gate.name)
        Gate.Action.STOP -> "Стоп"
        Gate.Action.PEDESTRIAN -> "Пешеходный проход"
        Gate.Action.STEP -> when (status.position) {
            GateStatus.Position.OPEN -> verb("Закрыть", gate.name)
            GateStatus.Position.CLOSED -> verb("Открыть", gate.name)
            GateStatus.Position.OPENING, GateStatus.Position.CLOSING -> "Стоп: ${gate.name.trim()}"
            else -> "${gate.name.trim()}: открыть или закрыть"
        }
    }

    /**
     * «Открыть калитку», «Открыть ворота»; своё имя склонять не пытаемся — «Открыть: Задний двор». Склоняется только
     * первое слово из известных.
     */
    fun verb(verb: String, name: String): String {
        val words = name.trim().split(Regex("\\s+"), limit = 2)
        val first = words.first().lowercase()
        val known = ACCUSATIVE[first] ?: return "$verb: ${name.trim()}"
        return "$verb $known" + (words.getOrNull(1)?.let { " $it" } ?: "")
    }

    private val ACCUSATIVE = mapOf("калитка" to "калитку", "ворота" to "ворота", "дверь" to "дверь", "гараж" to "гараж",
        "шлагбаум" to "шлагбаум", "замок" to "замок", "калитку" to "калитку")

    /** Состояние словами: «Закрыта», «Открывается… 40 %», «Нет связи». [source] — откуда известно. */
    fun statusText(status: GateStatus): String = when (status.position) {
        GateStatus.Position.CLOSED -> "закрыто"
        GateStatus.Position.OPEN -> "открыто"
        GateStatus.Position.OPENING -> "открывается…" + (status.percent?.let { " $it %" } ?: "")
        GateStatus.Position.CLOSING -> "закрывается…" + (status.percent?.let { " $it %" } ?: "")
        GateStatus.Position.JAMMED -> "заклинило"
        GateStatus.Position.OFFLINE -> "нет связи с Home Assistant"
        GateStatus.Position.UNKNOWN -> ""
    } + if (status.source == GateStatus.Source.SENSOR && !status.moving) " (датчик)" else ""
}

/** PIN для открытия (решение владельца 4): тот же PIN, что у настроек и архива. */
internal enum class GatePin(val key: String, val title: String) {
    OFF("off", "Выключен"),
    OUTSIDE_RING("outside_ring", "Вне окна звонка"),
    ALWAYS("always", "Всегда");

    fun required(inRingWindow: Boolean) = this == ALWAYS || (this == OUTSIDE_RING && !inRingWindow)

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OFF }
}

/**
 * Хранение списка проходов — в настройках панели (`dashboard_prefs`, ключи [KEY] и [KEY_PIN]); входят в экспорт и
 * резервную копию вместе с остальными настройками ([ConfigBackup.STRING_DEFAULTS]).
 *
 * Переход с 0.9.48 и раньше: у домофона было одно поле «Замок калитки» (`DoorbellConfig.lock`). При первом чтении, пока
 * списка нет, из каждого такого поля создаётся проход «Калитка» с id [legacyId] — плитка без своего списка проходов
 * показывает его ([DoorbellConfig.gateIds]). Настройки плиток при этом не переписываются: прежняя версия, если на неё
 * вернуться, работает как раньше.
 */
internal object GateStore {
    const val KEY = "gates_json"
    const val KEY_PIN = "gate_pin"
    private const val PREFS = "dashboard_prefs"

    fun legacyId(tileId: String) = "legacy_$tileId"

    fun load(context: Context): List<Gate> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) {
            val migrated = migrate(TileManager(context).loadTiles())
            save(context, migrated)
            return migrated
        }
        return parse(raw)
    }

    fun parse(raw: String): List<Gate> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(Gate::fromJson) }.distinctBy { it.id }.take(Gate.MAX)
    } catch (_: Exception) { emptyList() }

    fun save(context: Context, gates: List<Gate>) {
        val json = JSONArray().apply { gates.take(Gate.MAX).forEach { put(it.toJson()) } }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json) }
        GateHub.invalidate()
    }

    fun pin(context: Context): GatePin = GatePin.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PIN, null))

    fun setPin(context: Context, pin: GatePin) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_PIN, pin.key) }

    /** Проходы из прежнего поля «Замок калитки» у плиток домофона. */
    fun migrate(tiles: List<TileEntity>): List<Gate> {
        val locks = tiles.filter { it.type == DoorbellConfig.TYPE }.mapNotNull { tile ->
            DoorbellConfig.from(tile.config).lock.takeIf { it.isNotBlank() }?.let { tile.id to it }
        }
        return locks.mapIndexedNotNull { i, (tileId, lock) ->
            val kind = when (lock.substringBefore('.')) {
                "lock" -> Gate.Kind.LOCK
                "cover" -> Gate.Kind.COVER
                "switch", "input_boolean", "button", "input_button" -> Gate.Kind.RELAY
                else -> return@mapIndexedNotNull null
            }
            Gate(legacyId(tileId), if (locks.size > 1) "Калитка ${i + 1}" else "Калитка", kind, open = lock)
        }
    }

    fun newId(existing: List<Gate>): String {
        var n = existing.size + 1
        while (existing.any { it.id == "gate_$n" }) n++
        return "gate_$n"
    }
}
