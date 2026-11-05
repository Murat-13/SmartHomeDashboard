package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import com.example.smarthomedashboard.data.TileEntity

/**
 * Голосовые уведомления панели (решение владельца 06.11.2026, docs/voice-alerts.md): планшет произносит
 * короткую фразу после мягкого сигнала. Здесь — события, настройки и монитор (чистая логика без Android
 * и времени); голос — [VoiceAnnouncer], связка с данными панели — [PanelAlerts].
 */
internal enum class AlertEvent(
    val key: String,
    val title: String,
    val defaultOn: Boolean,
    /** Одно и то же событие не повторяется чаще: защита от «болтовни». */
    val cooldownMs: Long
) {
    GRID_LOST("grid_lost", "Сеть 220 В пропала", true, 3 * 60_000L),
    GRID_RESTORED("grid_restored", "Сеть 220 В появилась", true, 3 * 60_000L),
    DOORBELL_RING("doorbell_ring", "Звонок в калитку", true, 10_000L),
    DOORBELL_OFFLINE("doorbell_offline", "Домофон не на связи", true, 10 * 60_000L),
    BATTERY_LOW("battery_low", "Низкий заряд АКБ", false, 30 * 60_000L),
    INVERTER_OVERLOAD("inverter_overload", "Перегрузка инвертора", false, 5 * 60_000L),
    INVERTER_FAULT("inverter_fault", "Авария инвертора", false, 5 * 60_000L);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Что произнести: событие и текст фразы. */
internal data class Alert(val event: AlertEvent, val phrase: String)

/**
 * Настройки голоса — отдельно на каждом планшете (`dashboard_prefs`, в экспорт не входят: у родителей и у владельца
 * они разные). Громкость — доля от максимума, независимо от системной громкости.
 */
internal data class VoiceAlertSettings(
    val enabled: Set<AlertEvent> = AlertEvent.entries.filter { it.defaultOn }.toSet(),
    val volumePercent: Int = 70,
    val quietStartMin: Int = 23 * 60,
    val quietEndMin: Int = 7 * 60,
    /** Громкость в тихие часы — доля от обычной. */
    val quietPercent: Int = 20,
    /** Порог «низкий заряд АКБ», %. */
    val batteryLowPercent: Int = 20
) {
    fun isQuiet(minuteOfDay: Int): Boolean = when {
        quietStartMin == quietEndMin -> false
        quietStartMin < quietEndMin -> minuteOfDay in quietStartMin until quietEndMin
        else -> minuteOfDay >= quietStartMin || minuteOfDay < quietEndMin
    }

    /** Итоговая громкость 0..1 с учётом тихих часов. */
    fun volumeAt(minuteOfDay: Int): Float {
        val base = volumePercent.coerceIn(0, 100) / 100f
        return if (isQuiet(minuteOfDay)) base * quietPercent.coerceIn(0, 100) / 100f else base
    }

    fun save(prefs: SharedPreferences) = prefs.edit().apply {
        AlertEvent.entries.forEach { putBoolean(KEY_EVENT + it.key, it in enabled) }
        putInt(KEY_VOLUME, volumePercent)
        putInt(KEY_QUIET_START, quietStartMin)
        putInt(KEY_QUIET_END, quietEndMin)
        putInt(KEY_QUIET_PERCENT, quietPercent)
        putInt(KEY_BATTERY_LOW, batteryLowPercent)
    }.apply()

    companion object {
        const val PREFS = "dashboard_prefs"
        private const val KEY_EVENT = "voice_alert_"
        private const val KEY_VOLUME = "voice_volume"
        private const val KEY_QUIET_START = "voice_quiet_start"
        private const val KEY_QUIET_END = "voice_quiet_end"
        private const val KEY_QUIET_PERCENT = "voice_quiet_percent"
        private const val KEY_BATTERY_LOW = "voice_battery_low"

        fun load(prefs: SharedPreferences): VoiceAlertSettings {
            val d = VoiceAlertSettings()
            fun int(key: String, default: Int, range: IntRange) = prefs.getInt(key, default).takeIf { it in range } ?: default
            return VoiceAlertSettings(
                enabled = AlertEvent.entries.filter { prefs.getBoolean(KEY_EVENT + it.key, it.defaultOn) }.toSet(),
                volumePercent = int(KEY_VOLUME, d.volumePercent, 0..100),
                quietStartMin = int(KEY_QUIET_START, d.quietStartMin, 0 until 24 * 60),
                quietEndMin = int(KEY_QUIET_END, d.quietEndMin, 0 until 24 * 60),
                quietPercent = int(KEY_QUIET_PERCENT, d.quietPercent, 0..100),
                batteryLowPercent = int(KEY_BATTERY_LOW, d.batteryLowPercent, 1..90)
            )
        }

        fun load(context: Context) = load(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        fun minuteText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

        /** «23:00» → минуты дня; неверный ввод — null. */
        fun parseMinute(text: String): Int? {
            val m = Regex("""^\s*(\d{1,2})[:.\s](\d{2})\s*$""").find(text) ?: return Regex("""^\s*(\d{1,2})\s*$""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 }?.let { it * 60 }
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) h * 60 + min else null
        }
    }
}

/**
 * Решает, что произнести, по данным панели. Без Android и часов: время передаёт вызывающий.
 *
 * - Сеть 220 В («Сеть · Вход»): состояние подтверждается тем же [PowerStateTracker], что и цвет плитки (1,8 с),
 *   поэтому короткие провалы молчат. Первое подтверждённое состояние после запуска — только запоминается.
 *   Потеря связи с HA не считается ни пропажей, ни появлением сети: решает следующее достоверное состояние.
 * - АКБ: ниже порога — один раз; снова — только после подъёма на 5% выше порога.
 * - Инвертор: подтверждённая авария / перегруз ([InverterLookTracker]); снова — после возврата в норму.
 * - Повтор одного события не чаще [AlertEvent.cooldownMs].
 */
internal class PanelAlertMonitor {
    private val gridTrackers = mutableMapOf<String, PowerStateTracker>()
    private val gridPresent = mutableMapOf<String, Boolean>()
    private val batteryArmed = mutableMapOf<String, Boolean>()
    private val inverterTrackers = mutableMapOf<String, InverterLookTracker>()
    private val inverterState = mutableMapOf<String, InverterModel.Look>()
    private val lastSpoken = mutableMapOf<AlertEvent, Long>()

    /** Время следующей проверки подтверждения (ожидается смена состояния); null — ждать нечего. */
    var nextCheckAt: Long? = null
        private set

    fun evaluate(tiles: List<TileEntity>, readings: BatteryReadings, settings: VoiceAlertSettings, now: Long): List<Alert> {
        val out = mutableListOf<Alert>()
        var next: Long? = null
        val ids = HashSet<String>()
        tiles.forEach { tile ->
            ids += tile.id
            when (tile.type) {
                PowerModernConfig.TYPE -> grid(tile, readings, now, out)?.let { next = minOf(next ?: it, it) }
                BatteryModernConfig.TYPE -> battery(tile, readings, settings, out)
                InverterModernConfig.TYPE -> inverter(tile, readings, now, out)?.let { next = minOf(next ?: it, it) }
            }
        }
        // Удалённые плитки не держат состояние.
        listOf(gridTrackers, gridPresent, batteryArmed, inverterTrackers, inverterState).forEach { it.keys.retainAll(ids) }
        nextCheckAt = next
        return out.filter { allow(it.event, settings, now) }
    }

    /** Событие от плитки домофона (звонок, потеря связи) проходит те же фильтры. */
    fun external(alert: Alert, settings: VoiceAlertSettings, now: Long): Alert? = alert.takeIf { allow(it.event, settings, now) }

    private fun allow(event: AlertEvent, settings: VoiceAlertSettings, now: Long): Boolean {
        if (event !in settings.enabled) return false
        val last = lastSpoken[event]
        if (last != null && now - last < event.cooldownMs) return false
        lastSpoken[event] = now
        return true
    }

    private fun grid(tile: TileEntity, readings: BatteryReadings, now: Long, out: MutableList<Alert>): Long? {
        val config = PowerModernConfig.from(tile.config)
        if (config.profile != PowerModernConfig.PROFILE_GRID) return null
        val reading = readings.of(config.row(PowerModernConfig.ROLE_VOLTAGE))
        val voltage = if (readings.status == null) (reading as? BatteryReading.Value)?.number else null
        // Нет достоверных данных (связь с HA, датчик): состояние сети неизвестно — молчим и не сбрасываем прежнее.
        if (voltage == null) return null
        val tracker = gridTrackers.getOrPut(tile.id) { PowerStateTracker() }
        val confirmed = tracker.update(voltage, config, now)
        val rule = confirmed?.let { config.rules.getOrNull(it) }
        val present = when {
            confirmed == null -> null
            rule == null -> voltage >= GRID_PRESENT_V
            (rule.to ?: Double.MAX_VALUE) < GRID_LOST_V -> false
            rule.from >= GRID_PRESENT_V -> true
            else -> null
        }
        if (present != null) {
            val before = gridPresent.put(tile.id, present)
            if (before != null && before != present) {
                out += if (present) Alert(AlertEvent.GRID_RESTORED, "Сеть появилась") else Alert(AlertEvent.GRID_LOST, "Сеть пропала")
            }
        }
        return tracker.nextCheckAt
    }

    private fun battery(tile: TileEntity, readings: BatteryReadings, settings: VoiceAlertSettings, out: MutableList<Alert>) {
        if (readings.status != null) return
        val config = BatteryModernConfig.from(tile.config)
        val soc = (readings.of(config.row(BatteryModernConfig.ROLE_SOC)) as? BatteryReading.Value)?.number ?: return
        val armed = batteryArmed[tile.id] ?: (soc >= settings.batteryLowPercent)
        when {
            armed && soc < settings.batteryLowPercent -> {
                batteryArmed[tile.id] = false
                out += Alert(AlertEvent.BATTERY_LOW, "Низкий заряд аккумулятора: ${soc.toInt()} процентов")
            }
            !armed && soc >= settings.batteryLowPercent + 5 -> batteryArmed[tile.id] = true
            else -> batteryArmed[tile.id] = armed
        }
    }

    private fun inverter(tile: TileEntity, readings: BatteryReadings, now: Long, out: MutableList<Alert>): Long? {
        val config = InverterModernConfig.from(tile.config)
        val snapshot = InverterModel.snapshot(config, readings)
        val tracker = inverterTrackers.getOrPut(tile.id) { InverterLookTracker() }
        val look = tracker.update(snapshot.look, now)
        if (look == InverterModel.Look.NO_DATA) return tracker.nextCheckAt
        val before = inverterState.put(tile.id, look)
        if (before != null && before != look) {
            val overloaded = snapshot.loadPercent?.let { it >= config.overloadPercent } == true
            when {
                look == InverterModel.Look.FAULT -> out += Alert(AlertEvent.INVERTER_FAULT, "Авария инвертора")
                look == InverterModel.Look.WARNING && overloaded && before != InverterModel.Look.FAULT ->
                    out += Alert(AlertEvent.INVERTER_OVERLOAD, "Перегрузка инвертора")
            }
        }
        return tracker.nextCheckAt
    }

    companion object {
        /** Подтверждённое правило целиком ниже — сети нет. */
        const val GRID_LOST_V = 100.0
        /** Подтверждённое правило целиком выше — сеть есть. Между ними — состояние не меняется. */
        const val GRID_PRESENT_V = 150.0
    }
}

/**
 * Связка: состояние HA панели → [PanelAlertMonitor] → [VoiceAnnouncer]. Один экземпляр на процесс; работает,
 * пока жив [MainViewModel] — в том числе когда открыты настройки или редактор (иначе тревога пропала бы).
 * Плитки читаются из хранилища (кэш по версии), поэтому событие с плитки на другой странице тоже звучит.
 */
internal object PanelAlerts {
    private var app: Context? = null
    private var announcer: VoiceAnnouncer? = null
    private val monitor = PanelAlertMonitor()
    private var tiles: com.example.smarthomedashboard.data.TileManager? = null
    private var settings: VoiceAlertSettings? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastReadings: BatteryReadings? = null
    private val recheck = Runnable { lastReadings?.let(::evaluate) }

    /** Отключение на время тестов и для экранов без панели. */
    @Volatile internal var enabled = true

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        tiles = com.example.smarthomedashboard.data.TileManager(context.applicationContext)
        DoorbellHub.attach(context)
    }

    fun settings(): VoiceAlertSettings = settings ?: app?.let { VoiceAlertSettings.load(it) }?.also { settings = it } ?: VoiceAlertSettings()

    fun updateSettings(value: VoiceAlertSettings) {
        val context = app ?: return
        value.save(context.getSharedPreferences(VoiceAlertSettings.PREFS, Context.MODE_PRIVATE))
        settings = value
    }

    /** Новое состояние HA. Главный поток. */
    fun onState(model: MainUiState) {
        if (app == null || !enabled) return
        evaluate(BatteryReadings.from(model))
    }

    private fun evaluate(readings: BatteryReadings) {
        lastReadings = readings
        val list = tiles?.getAllTiles() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        monitor.evaluate(list, readings, settings(), now).forEach(::say)
        main.removeCallbacks(recheck)
        monitor.nextCheckAt?.let { main.postDelayed(recheck, (it - now).coerceAtLeast(0) + 20) }
        // Домофон: звонок, связь, калитка — свои правила звука (мелодия плитки, без общего кулдауна звонка).
        DoorbellHub.onState(list, readings)
    }

    /** Событие плитки домофона (звонок, потеря связи) — через те же фильтры и очередь. */
    fun external(alert: Alert, tone: android.net.Uri? = null, volume: Float? = null, tag: String = "") {
        if (app == null || !enabled) return
        val s = settings()
        monitor.external(alert, s, android.os.SystemClock.uptimeMillis())?.let { say(it, tone, volume, tag) }
    }

    private fun say(alert: Alert, tone: android.net.Uri? = null, volume: Float? = null, tag: String = "") {
        val context = app ?: return
        val minute = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val level = volume ?: settings().volumeAt(minute)
        if (level <= 0f) return
        val voice = announcer ?: VoiceAnnouncer(context).also { announcer = it }
        voice.speak(VoiceAnnouncer.Sound(alert.phrase, tone ?: VoiceAnnouncer.resource(context, R.raw.alert_chime), level, tag))
    }

    /**
     * Звук плитки (звонок домофона): мелодия и необязательная фраза сразу, без общего кулдауна;
     * повтор с тем же [tag] начинает звучание заново. Тихие часы и громкость уже учтены в [volume].
     */
    fun sound(phrase: String?, tone: android.net.Uri, volume: Float, tag: String) {
        val context = app ?: return
        if (!enabled || volume <= 0f) return
        val voice = announcer ?: VoiceAnnouncer(context).also { announcer = it }
        voice.cancel(tag)
        voice.speak(VoiceAnnouncer.Sound(phrase, tone, volume, tag))
    }

    /** Проверка из настроек: сигнал и фраза на заданной громкости. */
    fun preview(phrase: String, volume: Float) {
        val context = app ?: return
        val voice = announcer ?: VoiceAnnouncer(context).also { announcer = it }
        voice.cancel("preview")
        voice.speak(VoiceAnnouncer.Sound(phrase, VoiceAnnouncer.resource(context, R.raw.alert_chime), volume, "preview"))
    }

    fun announcer(): VoiceAnnouncer? = app?.let { announcer ?: VoiceAnnouncer(it).also { v -> announcer = v } }

    fun cancel(tag: String) = announcer?.cancel(tag)

    /** Сейчас звучит сигнал или фраза (окно звонка включает звук видео после них). */
    val speaking get() = announcer?.busy == true

    /** ViewModel очищен (панель закрыта): голос освобождается. */
    fun detach() {
        main.removeCallbacks(recheck)
        DoorbellHub.detach()
        announcer?.release()
        announcer = null
        app = null
        tiles = null
        settings = null
        lastReadings = null
    }
}
