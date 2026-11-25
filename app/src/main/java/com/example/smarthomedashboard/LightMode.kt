package com.example.smarthomedashboard

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.core.content.edit
import com.example.smarthomedashboard.data.TileManager
import java.util.Locale

/**
 * Облегчённый режим для слабых планшетов (docs/diagnostics-tz.md, часть 2, решение владельца 4): раскрытие без размытия
 * фона, короче анимации, не больше [MAX_LIVE] живых видео на странице — остальные плитки камер показывают снимок Home
 * Assistant раз в [SNAPSHOT_MS]. Включается сам (мало памяти или раскрытие запинается), его можно включить или выключить
 * на экране «Планшет» — выбор пользователя сильнее автоматики. При сильном нагреве (П2) включается на время нагрева, и
 * раскрытая плитка показывает субпоток.
 */
internal object LightMode {
    enum class Choice(val key: String, val title: String) { AUTO("auto", "Сам"), ON("on", "Включён"), OFF("off", "Выключен") }

    private const val PREFS = "tablet_settings"
    private const val KEY = "light_mode"
    const val MAX_LIVE = 2
    const val SNAPSHOT_MS = 10_000L
    /** Памяти не больше — облегчённый режим сам. Порог ниже, чем в ТЗ (3 ГБ): см. docs/diagnostics-tz.md, «Этап 3». */
    private const val LOW_MEMORY_BYTES = 2_300_000_000L
    /** Кадр раскрытия дольше — «опоздал» (меньше 30 к/с); опоздавших от [LATE_SHARE] — раскрытие запинается. */
    private const val LATE_FRAME_NS = 33_000_000L
    private const val LATE_SHARE = 0.3
    private const val MIN_EXPANSIONS = 3
    private const val MIN_FRAMES = 30
    private const val SHORTER = 0.6

    @Volatile var choice = Choice.AUTO
        private set
    /** Включён ли режим сейчас (по выбору, автоматике и нагреву). */
    @Volatile var active = false
        private set
    /** Почему включён (для экрана «Планшет»); null — выключен. */
    @Volatile var reason: String? = null
        private set
    /** Сильный нагрев (П2): режим на время нагрева, раскрытая плитка — субпотоком. */
    @Volatile var hot = false
        private set

    /** Память планшета (в тестах — подмена). */
    internal var memory: (Context) -> Pair<Long, Boolean> = { context ->
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        info.totalMem to (manager?.isLowRamDevice == true)
    }

    // Плавность раскрытия: кадров всего и опоздавших, раскрытий измерено (с запуска приложения).
    private var frames = 0
    private var late = 0
    private var expansions = 0
    /** Почему включился бы сам по памяти (считается при загрузке); null — памяти хватает. */
    @Volatile private var memoryReason: String? = null
    /** Нагрев аккумулятора ([TabletWatch]): «тепло» — режим, если он не выключен руками; «слишком горячо» — в любом случае. */
    @Volatile private var batteryHeat = TabletWatch.Heat.OK

    fun load(context: Context) {
        choice = Choice.entries.firstOrNull { it.key == prefs(context).getString(KEY, null) } ?: Choice.AUTO
        refresh(context)
        watchHeat(context)
    }

    fun set(context: Context, value: Choice) {
        choice = value
        prefs(context).edit { putString(KEY, value.key) }
        refresh(context)
    }

    /** Пересчитать; [context] — заодно заново оценить память. */
    @Synchronized
    fun refresh(context: Context? = null) {
        if (context != null) memoryReason = memoryReason(context)
        val auto = autoReason()
        val (on, why) = when {
            SafeStart.active -> true to "безопасный запуск"
            hot -> true to "планшет перегрет"
            batteryHeat == TabletWatch.Heat.HOT -> true to "аккумулятор слишком горячий"
            choice == Choice.ON -> true to "по выбору"
            choice == Choice.OFF -> false to null
            batteryHeat == TabletWatch.Heat.WARM -> true to "аккумулятор тёплый"
            else -> (auto != null) to auto
        }
        if (on != active) Log.i(TAG, if (on) "Облегчённый режим включён: $why" else "Облегчённый режим выключен")
        active = on
        reason = why
    }

    private fun memoryReason(context: Context): String? {
        val (total, lowRam) = memory(context)
        return if (lowRam || (total in 1..LOW_MEMORY_BYTES)) "мало памяти (${String.format(Locale.US, "%.1f", total / 1e9).replace('.', ',')} ГБ)"
            else null
    }

    /** Почему режим включился бы сам; null — планшет справляется. */
    internal fun autoReason(): String? {
        memoryReason?.let { return it }
        synchronized(this) {
            if (expansions >= MIN_EXPANSIONS && frames >= MIN_FRAMES && late.toDouble() / frames >= LATE_SHARE) {
                return "раскрытие запинается (${late * 100 / frames} % кадров дольше 33 мс)"
            }
        }
        return null
    }

    /** Длительность анимации раскрытия с учётом режима. */
    fun ms(base: Long): Long = if (active) (base * SHORTER).toLong() else base

    /** Итог одного раскрытия: кадров [total], из них опоздало [lateFrames]. */
    fun expansionMeasured(total: Int, lateFrames: Int) {
        if (total <= 0) return
        synchronized(this) { frames += total; late += lateFrames; expansions++ }
        refresh()
    }

    /**
     * Замер плавности раскрытия: кадры окна [window] до вызова возвращённой функции (true — раскрытие дошло до конца и идёт
     * в счёт, false — прервано: только снять слушатель). Вызвать обязательно: иначе слушатель остаётся у окна до его закрытия
     * (аудит 25.11.2026, STAB-02). Android 7+ (FrameMetrics); на экране без аппаратного ускорения ничего не меряется.
     */
    fun measure(window: Window?): (Boolean) -> Unit {
        if (window == null) return {}
        var total = 0
        var lateFrames = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            total++
            if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > LATE_FRAME_NS) lateFrames++
        }
        try {
            window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: RuntimeException) { return {} }
        return { finished ->
            try { window.removeOnFrameMetricsAvailableListener(listener) } catch (_: RuntimeException) {}
            if (finished) expansionMeasured(total, lateFrames)
        }
    }

    /** Аккумулятор нагрелся или остыл. HD при этом не меняется — только при сильном нагреве Android ([hot]). */
    fun battery(level: TabletWatch.Heat) {
        if (level == batteryHeat) return
        batteryHeat = level
        refresh()
    }

    /** Нагрев (Android 10+): «сильный» и выше — режим на время нагрева. */
    private fun watchHeat(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        try {
            heat(power.currentThermalStatus)
            power.addThermalStatusListener { heat(it) }
        } catch (_: RuntimeException) {}
    }

    @android.annotation.SuppressLint("InlinedApi") // константа нагрева встраивается при сборке; зовут только с Android 10
    internal fun heat(status: Int) {
        val now = status >= PowerManager.THERMAL_STATUS_SEVERE
        if (now == hot) return
        hot = now
        Log.i(TAG, if (now) "Планшет перегрет — облегчённый режим, HD субпотоком" else "Нагрев спал")
        refresh()
    }

    /**
     * Плитка [tileId] показывает живое видео? В облегчённом режиме — только первые [MAX_LIVE] видеоплитки страницы
     * (сверху вниз, слева направо); остальные — снимок, если у них есть камера Home Assistant.
     */
    fun liveAllowed(context: Context, tileId: String?): Boolean {
        if (!active || tileId == null) return true
        val tiles = try { TileManager(context).loadTiles() } catch (_: Exception) { return true }
        val me = tiles.firstOrNull { it.id == tileId } ?: return true
        val page = tiles.filter { it.page == me.page && DoorbellConfig.isVideo(it.type) }
            .filter { DoorbellConfig.from(it.config).mode != DoorbellConfig.VideoMode.SNAPSHOT }
            .sortedWith(compareBy({ it.y }, { it.x }))
        val index = page.indexOfFirst { it.id == tileId }
        return index < MAX_LIVE || DoorbellConfig.from(me.config).let { it.camera.isBlank() && it.snapshotUrl.isBlank() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun resetForTests() {
        choice = Choice.AUTO; active = false; reason = null; hot = false; memoryReason = null; batteryHeat = TabletWatch.Heat.OK
        synchronized(this) { frames = 0; late = 0; expansions = 0 }
    }

    private const val TAG = "LightMode"
}
