package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity

/**
 * Состояние плитки «Домофон» из уже полученных показаний HA (без запросов и без View).
 * Звонок, потеря связи и открытие калитки как события — в [DoorbellMonitor].
 */
internal object DoorbellModel {
    /** Что показать на плитке сейчас. [online] = null — связи с HA нет или идёт синхронизация (о домофоне неизвестно). */
    data class Snapshot(
        val online: Boolean?,
        val ringing: Boolean,
        val detections: Set<DoorbellConfig.Detection>,
        val lock: Lock,
        val lightOn: Boolean?,
        val quickReplies: List<String>,
        val sunDown: Boolean,
        /** Путь снимка HA (`/api/camera_proxy/...?token=...`) — токен доступа обновляет сам HA. */
        val picture: String?,
        val haStatus: String?
    ) {
        val person get() = DoorbellConfig.Detection.PERSON in detections
    }

    enum class Lock { NONE, CLOSED, OPEN, UNKNOWN }

    fun snapshot(config: DoorbellConfig, readings: BatteryReadings): Snapshot {
        fun on(id: String) = (readings.of(id) as? BatteryReading.Value)?.raw == "on"
        val camera = readings.of(config.camera)
        val probe = if (config.camera.isNotBlank()) camera else readings.of(config.visitor)
        val online = when (probe) {
            is BatteryReading.Value -> true
            is BatteryReading.Unavailable, BatteryReading.Missing -> false
            BatteryReading.NotConfigured -> if (config.rtspMain.isNotBlank() || config.rtspSub.isNotBlank()) true else null
            else -> null
        }
        val picture = (camera as? BatteryReading.Value)?.attributes?.optString("entity_picture")?.takeIf { it.startsWith("/") }
        val light = readings.of(config.light)
        val replies = (readings.of(config.quickReply) as? BatteryReading.Value)?.attributes?.optJSONArray("options")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        return Snapshot(
            online = online,
            ringing = on(config.visitor),
            detections = config.detections.filter { on(it.first) }.mapTo(LinkedHashSet()) { it.second },
            lock = lockState(config.lock, readings),
            lightOn = (light as? BatteryReading.Value)?.raw?.let { it == "on" },
            quickReplies = replies,
            sunDown = (readings.of(DoorbellConfig.SUN) as? BatteryReading.Value)?.raw == "below_horizon",
            picture = picture,
            haStatus = readings.status
        )
    }

    fun lockState(entityId: String, readings: BatteryReadings): Lock {
        if (entityId.isBlank()) return Lock.NONE
        val raw = (readings.of(entityId) as? BatteryReading.Value)?.raw ?: return Lock.UNKNOWN
        return when (entityId.substringBefore('.')) {
            "lock" -> if (raw == "unlocked" || raw == "open" || raw == "opening" || raw == "unlocking") Lock.OPEN else Lock.CLOSED
            "switch", "input_boolean" -> if (raw == "on") Lock.OPEN else Lock.CLOSED
            "cover" -> if (raw == "open" || raw == "opening") Lock.OPEN else Lock.CLOSED
            // Кнопка не имеет состояния «открыто»: факт нажатия — смена отметки времени (см. DoorbellMonitor).
            else -> Lock.CLOSED
        }
    }

    /** Команда «Открыть калитку» для домена сущности: сервис и ожидаемый факт (null — смена отметки кнопки). */
    data class OpenCommand(val domain: String, val service: String, val expected: String?)

    fun openCommand(entityId: String): OpenCommand? = when (entityId.substringBefore('.')) {
        "lock" -> OpenCommand("lock", "unlock", "unlocked")
        "switch" -> OpenCommand("switch", "turn_on", "on")
        "input_boolean" -> OpenCommand("input_boolean", "turn_on", "on")
        "cover" -> OpenCommand("cover", "open_cover", "open")
        "button" -> OpenCommand("button", "press", null)
        "input_button" -> OpenCommand("input_button", "press", null)
        else -> null
    }

    /** Подходит ли сущность для роли (фильтры выбора в редакторе). */
    fun fits(role: String, entityId: String): Boolean {
        val domain = entityId.substringBefore('.')
        return when (role) {
            "camera" -> domain == "camera"
            "visitor", "person", "animal", "vehicle", "motion" -> domain == "binary_sensor"
            "lock" -> domain in setOf("lock", "switch", "button", "input_button", "cover", "input_boolean")
            "light" -> domain == "light" || domain == "switch"
            "quick_reply" -> domain == "select"
            else -> true
        }
    }

    /** «2 мин назад», «в 14:05», «вчера в 21:40» — для подписи последнего звонка. */
    fun ago(at: Long, now: Long): String {
        val diff = (now - at).coerceAtLeast(0)
        val min = diff / 60_000
        return when {
            min < 1 -> "только что"
            min < 60 -> "$min мин назад"
            else -> {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
                val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val time = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                val days = (startOfDay(today) - startOfDay(cal)) / 86_400_000L
                when (days) {
                    0L -> "в $time"
                    1L -> "вчера в $time"
                    else -> "%02d.%02d в %s".format(cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1, time)
                }
            }
        }
    }

    private fun startOfDay(c: java.util.Calendar): Long = (c.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * События домофонов по всем плиткам: звонок (кнопка выкл → вкл), потеря связи (подтверждённая [OFFLINE_CONFIRM_MS]),
 * открытие калитки (факт HA). Первое увиденное состояние только запоминается: перезапуск панели во время звонка
 * или после него не звонит повторно. Без связи с HA событий нет. Время — монотонное, задаётся вызывающим.
 */
internal class DoorbellMonitor {
    sealed class Event(open val tileId: String) {
        data class Ring(override val tileId: String) : Event(tileId)
        data class Offline(override val tileId: String) : Event(tileId)
        data class Online(override val tileId: String) : Event(tileId)
        data class GateOpened(override val tileId: String) : Event(tileId)
    }

    private class State {
        var visitor: Boolean? = null
        var lastRingAt = Long.MIN_VALUE / 2
        var online: Boolean? = null
        var offlineSince: Long? = null
        var reportedOffline = false
        var lock: String? = null
    }

    private val states = mutableMapOf<String, State>()
    var nextCheckAt: Long? = null
        private set

    fun evaluate(tiles: List<TileEntity>, readings: BatteryReadings, now: Long): List<Event> {
        val out = mutableListOf<Event>()
        nextCheckAt = null
        val live = tiles.filter { it.type == DoorbellConfig.TYPE }
        states.keys.retainAll(live.mapTo(HashSet()) { it.id })
        for (tile in live) {
            val config = DoorbellConfig.from(tile.config)
            val s = states.getOrPut(tile.id) { State() }
            visitor(tile.id, config, readings, s, now, out)
            online(tile.id, config, readings, s, now, out)?.let { nextCheckAt = minOf(nextCheckAt ?: it, it) }
            lock(tile.id, config, readings, s, out)
        }
        return out
    }

    private fun visitor(id: String, config: DoorbellConfig, readings: BatteryReadings, s: State, now: Long, out: MutableList<Event>) {
        val reading = readings.of(config.visitor)
        if (reading !is BatteryReading.Value && reading !is BatteryReading.Unavailable) return
        val on = (reading as? BatteryReading.Value)?.raw == "on"
        val before = s.visitor
        s.visitor = on
        // Дребезг кнопки и повторная публикация состояния не дают второго звонка в течение [RING_DEBOUNCE_MS].
        if (before == false && on && now - s.lastRingAt >= RING_DEBOUNCE_MS) {
            s.lastRingAt = now
            out += Event.Ring(id)
        }
    }

    private fun online(id: String, config: DoorbellConfig, readings: BatteryReadings, s: State, now: Long, out: MutableList<Event>): Long? {
        val probe = config.camera.ifBlank { config.visitor }
        val reading = readings.of(probe)
        val online = when (reading) {
            is BatteryReading.Value -> true
            is BatteryReading.Unavailable, BatteryReading.Missing -> false
            else -> return null // нет связи с HA, синхронизация или не настроено: о домофоне ничего не известно
        }
        val before = s.online
        s.online = online
        if (online) {
            s.offlineSince = null
            if (s.reportedOffline) { s.reportedOffline = false; out += Event.Online(id) }
            return null
        }
        if (before == null && s.offlineSince == null) {
            // Запуск панели при уже недоступном домофоне: показать, но без звука потери связи.
            s.offlineSince = now - OFFLINE_CONFIRM_MS
            s.reportedOffline = true
            return null
        }
        val since = s.offlineSince ?: now.also { s.offlineSince = it }
        if (s.reportedOffline) return null
        if (now - since >= OFFLINE_CONFIRM_MS) {
            s.reportedOffline = true
            out += Event.Offline(id)
            return null
        }
        return since + OFFLINE_CONFIRM_MS
    }

    private fun lock(id: String, config: DoorbellConfig, readings: BatteryReadings, s: State, out: MutableList<Event>) {
        if (config.lock.isBlank()) return
        val reading = readings.of(config.lock)
        // Замок недоступен — прежнее состояние забываем: возврат сразу в «открыто» не значит, что калитку открыли.
        if (reading is BatteryReading.Unavailable || reading == BatteryReading.Missing) { s.lock = null; return }
        val raw = (reading as? BatteryReading.Value)?.raw ?: return
        val before = s.lock
        s.lock = raw
        if (before == null || before == raw) return
        val opened = when (config.lock.substringBefore('.')) {
            "button", "input_button" -> true // новая отметка времени нажатия
            else -> DoorbellModel.lockState(config.lock, readings) == DoorbellModel.Lock.OPEN
        }
        if (opened) out += Event.GateOpened(id)
    }

    /** Был ли домофон недоступен дольше порога (для рамки «Нет сигнала» без ожидания следующего события). */
    fun offline(tileId: String): Boolean = states[tileId]?.reportedOffline == true

    companion object {
        const val RING_DEBOUNCE_MS = 3_000L
        const val OFFLINE_CONFIRM_MS = 15_000L
    }
}
