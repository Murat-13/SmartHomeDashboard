package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.edit
import java.util.concurrent.Executors

/**
 * Что панель знает о планшете со временем (docs/setup-tz.md, часть 4): заряд и температура по часам за неделю, перегрев
 * аккумулятора и советы, которые держатся [HOLD_MS]. Раз в минуту, пока панель на экране, без сети и без пробуждений.
 * Числа лежат в своих настройках этого планшета — в копии и в облако не попадают.
 */
internal object TabletWatch {
    /** Показания Android сейчас; [tenths] — десятые доли °C, null — планшет не сообщает. */
    data class Battery(val plugged: Boolean, val charging: Boolean, val level: Int, val tenths: Int?)

    /** Час наблюдения: минут всего, на зарядке, «заряжается»; крайние заряды и наибольшая температура. */
    internal data class Hour(val hour: Long, var minutes: Int = 0, var plugged: Int = 0, var charging: Int = 0,
                             var minLevel: Int = 101, var maxLevel: Int = -1, var maxTenths: Int = NO_TEMP)

    enum class Charge { LIMIT, HIGH, WATCHING, UNPLUGGED, UNKNOWN }

    /** [level] — сейчас; [held] — у какого заряда держится лимит (или не опускается без него). */
    data class ChargeFacts(val state: Charge, val level: Int = -1, val held: Int = -1)

    enum class Heat { OK, WARM, HOT }

    /** Температура аккумулятора, десятые °C: сейчас, наибольшая за сутки и за неделю. */
    data class HeatFacts(val level: Heat = Heat.OK, val now: Int? = null, val day: Int? = null, val week: Int? = null)

    const val HOLD_MS = 10 * 60_000L
    private const val NO_TEMP = Int.MIN_VALUE
    private const val HOUR_MS = 3_600_000L
    private const val KEEP_HOURS = 7 * 24L
    private const val SAVE_MS = 15 * 60_000L
    /** Факты об Android (исключения, закрытия, заставка) перечитываются реже, чем заряд. */
    private const val FACTS_EVERY = 10

    internal const val PREFS = "tablet_watch"
    private const val KEY_HOURS = "hours"
    private const val KEY_OLED = "oled"
    private const val KEY_DREAM = "dream_at"

    private val io = Executors.newSingleThreadExecutor { Thread(it, "tablet-watch").apply { isDaemon = true } }
    internal var now: () -> Long = System::currentTimeMillis
    /** Показания Android (в тестах — подмена). */
    internal var battery: (Context) -> Battery? = ::readBattery

    private val hours = ArrayDeque<Hour>()
    private var loaded = false
    private var savedAt = 0L
    private var last: Battery? = null
    private var heat = HeatState()
    private var ticks = 0
    private var facts: TabletCheck.Facts? = null
    private val badSince = HashMap<String, Long>()

    /** Советы, из-за которых стоит строка в боковой панели: мешают и держатся. */
    @Volatile var advice: List<TabletCheck.Item> = emptyList()
        private set
    /** Аккумулятор слишком горячий — панель остывает ([PanelCooling]). */
    @Volatile var hot = false
        private set

    fun text(): String? = advice.size.takeIf { it > 0 }?.let { "Планшет: есть советы ($it)" }

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Раз в минуту с панели; [done] — на главном потоке. */
    fun tick(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        io.execute {
            try { minute(app) } catch (e: Exception) { Log.w(TAG, "Слежение за планшетом: ${e.javaClass.simpleName}") }
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
    }

    internal fun minute(app: Context) {
        load(app)
        battery(app)?.let { sample(it) }
        val fresh = synchronized(this) {
            val old = facts
            if (old == null || ticks++ % FACTS_EVERY == 0) null else old.copy(charge = charge(), heat = heatFacts(), now = now())
        } ?: TabletCheck.read(app)
        synchronized(this) { facts = fresh }
        hold(TabletCheck.evaluate(fresh))
        save(app)
    }

    // ---- Показания ----

    private fun readBattery(context: Context): Battery? {
        val i = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: RuntimeException) { null } ?: return null
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 } ?: return null
        val temp = if (i.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) else 0
        return Battery(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING,
            level * 100 / scale, temp.takeIf { it in MIN_TENTHS..MAX_TENTHS && it != 0 })
    }

    /** Показание за минуту. */
    @Synchronized
    internal fun sample(b: Battery) {
        val t = now()
        last = b
        val hour = t / HOUR_MS
        val h = hours.lastOrNull()?.takeIf { it.hour == hour } ?: Hour(hour).also { hours.addLast(it) }
        h.minutes++
        if (b.plugged) h.plugged++
        if (b.charging) h.charging++
        h.minLevel = minOf(h.minLevel, b.level)
        h.maxLevel = maxOf(h.maxLevel, b.level)
        b.tenths?.let { h.maxTenths = maxOf(h.maxTenths, it) }
        while (hours.isNotEmpty() && (hours.first().hour <= hour - KEEP_HOURS || hours.first().hour > hour)) hours.removeFirst()
        val before = heat.level
        val level = heat.sample(b.tenths, t)
        if (level != before) {
            hot = level == Heat.HOT
            LightMode.battery(level)
            Log.i(TAG, "Аккумулятор: ${when (level) { Heat.OK -> "остыл"; Heat.WARM -> "тёплый"; Heat.HOT -> "слишком горячий" }}")
        }
    }

    /** Нагрев с задержкой: «тепло» — 10 минут от 40 °C, «слишком горячо» — 2 минуты от 45 °C; назад — на 3 °C ниже 10 минут. */
    internal class HeatState {
        var level = Heat.OK
            private set
        private var lastAt = 0L
        private val since = HashMap<Int, Long>()

        fun sample(tenths: Int?, t: Long): Heat {
            if (tenths == null) return level
            // Панель была не на экране: прежние минуты не в счёт, уровень остаётся.
            if (t - lastAt > GAP_MS || t < lastAt) since.clear()
            lastAt = t
            fun held(key: Int, on: Boolean, ms: Long): Boolean {
                if (!on) { since.remove(key); return false }
                return t - since.getOrPut(key) { t } >= ms
            }
            val hot = held(HOT, tenths >= HOT, HOT_HOLD_MS)
            val warm = held(WARM, tenths >= WARM, HOLD_MS)
            val belowHot = held(-HOT, tenths <= HOT - BACK, HOLD_MS)
            val belowWarm = held(-WARM, tenths <= WARM - BACK, HOLD_MS)
            level = when {
                hot -> Heat.HOT
                level == Heat.HOT -> if (!belowHot) Heat.HOT else if (belowWarm) Heat.OK else Heat.WARM
                level == Heat.WARM -> if (belowWarm) Heat.OK else Heat.WARM
                warm -> Heat.WARM
                else -> Heat.OK
            }
            return level
        }
    }

    /**
     * Лимит заряда официально не узнать — только понаблюдать: на зарядке, заряд держится (разброс до [SPREAD] %) не выше
     * 94 %. «Не заряжается» — достаточно трёх часов, «заряжается» — шести. 95 % и выше сутки подряд — лимита нет.
     */
    @Synchronized
    internal fun charge(): ChargeFacts {
        val b = last ?: return ChargeFacts(Charge.UNKNOWN)
        if (!b.plugged) return ChargeFacts(Charge.UNPLUGGED, b.level)
        val hour = now() / HOUR_MS
        // Текущий час ещё не полный — окно берётся с запасом в час.
        fun window(size: Int) = hours.filter { it.hour >= hour - size && it.minutes >= MIN_MINUTES }.takeLast(size)
        fun pluggedIn(list: List<Hour>) = list.all { it.plugged * 10 >= it.minutes * 9 }
        fun held(size: Int): List<Hour>? = window(size).takeIf { list ->
            list.size >= size && pluggedIn(list) && list.maxOf { it.maxLevel } in LIMIT_LEVELS &&
                list.maxOf { it.maxLevel } - list.minOf { it.minLevel } <= SPREAD
        }
        val calm = held(CALM_HOURS)?.takeIf { list -> list.sumOf { it.charging } * 2 <= list.sumOf { it.minutes } } ?: held(LONG_HOURS)
        if (calm != null) return ChargeFacts(Charge.LIMIT, b.level, calm.maxOf { it.maxLevel })
        val day = window(DAY_HOURS)
        val wholeDay = hours.any { it.hour <= hour - DAY_HOURS }
        if (wholeDay && day.size >= DAY_SEEN && pluggedIn(day) && day.all { it.minLevel >= HIGH_LEVEL }) {
            return ChargeFacts(Charge.HIGH, b.level, day.minOf { it.minLevel })
        }
        return ChargeFacts(Charge.WATCHING, b.level)
    }

    @Synchronized
    internal fun heatFacts(): HeatFacts {
        val hour = now() / HOUR_MS
        fun max(size: Long) = hours.filter { it.hour > hour - size }.maxOfOrNull { it.maxTenths }?.takeIf { it != NO_TEMP }
        return HeatFacts(heat.level, last?.tenths, max(DAY_HOURS.toLong()), max(KEEP_HOURS))
    }

    /** Показание сейчас, если панель ещё не спрашивала (экран «Планшет» до первой минуты). */
    fun refresh(context: Context) {
        load(context)
        if (last == null) battery(context)?.let { b -> synchronized(this) { if (last == null) last = b } }
    }

    // ---- Заставка ----

    /** Заставка Android появилась, пока на экране была панель. */
    fun dreamOverPanel(context: Context) = prefs(context).edit { putLong(KEY_DREAM, now()) }
    fun dreamAt(context: Context) = prefs(context).getLong(KEY_DREAM, 0)

    // ---- Экран OLED ----

    fun oled(context: Context) = prefs(context).getBoolean(KEY_OLED, false)
    fun setOled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_OLED, on) }

    // ---- Строка в боковой панели ----

    @Synchronized
    internal fun hold(items: List<TabletCheck.Item>) {
        val t = now()
        val bad = items.filter { it.level == TabletCheck.Level.BAD }
        badSince.keys.retainAll(bad.mapTo(HashSet()) { it.id })
        bad.forEach { badSince.getOrPut(it.id) { t } }
        advice = bad.filter { t - badSince.getValue(it.id) >= HOLD_MS }
    }

    // ---- Хранение ----

    @Synchronized
    internal fun load(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).getString(KEY_HOURS, null)?.split(';')?.forEach { line ->
            val v = line.split(',').map { it.toLongOrNull() ?: return@forEach }
            if (v.size == FIELDS) hours.addLast(Hour(v[0], v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt()))
        }
    }

    @Synchronized
    private fun save(context: Context) {
        val t = now()
        if (t - savedAt < SAVE_MS && t >= savedAt) return
        savedAt = t
        val text = hours.joinToString(";") { "${it.hour},${it.minutes},${it.plugged},${it.charging},${it.minLevel},${it.maxLevel},${it.maxTenths}" }
        prefs(context).edit { putString(KEY_HOURS, text) }
    }

    @Synchronized
    internal fun resetForTests() {
        hours.clear(); loaded = false; savedAt = 0; last = null; ticks = 0; facts = null; badSince.clear(); advice = emptyList(); hot = false
        heat = HeatState(); now = System::currentTimeMillis; battery = ::readBattery
        LightMode.battery(Heat.OK)
    }

    private const val TAG = "TabletWatch"
    private const val HOT = 450
    private const val WARM = 400
    private const val BACK = 30
    private const val HOT_HOLD_MS = 2 * 60_000L
    private const val GAP_MS = 5 * 60_000L
    private const val MIN_TENTHS = -300
    private const val MAX_TENTHS = 1000
    private const val MIN_MINUTES = 30
    private const val CALM_HOURS = 3
    private const val LONG_HOURS = 6
    private const val DAY_HOURS = 24
    private const val DAY_SEEN = 20
    private const val HIGH_LEVEL = 95
    private const val SPREAD = 6
    private val LIMIT_LEVELS = 40..94
    private const val FIELDS = 7
}
