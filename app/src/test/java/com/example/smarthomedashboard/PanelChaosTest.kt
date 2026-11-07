package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.example.smarthomedashboard.data.TileEntity
import com.example.smarthomedashboard.data.TileManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Хаос-прогон панели (0.9.13, docs/deep-testing.md): настоящие MainActivity, контроллер панели, карточки и отрисовка
 * (native-графика) на плитках всех типов со случайно испорченными настройками и случайными, в т. ч. мусорными,
 * состояниями HA. Ни одно сочетание не должно ронять панель — ни при построении, ни при событиях, отрисовке,
 * раскрытии и сворачивании. Прогон детерминирован: сид каждой итерации печатается при сбое и воспроизводится
 * через -Dchaos.seed=N. Число итераций — -Dchaos.iterations (в CI по умолчанию, ночью больше).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [PanelSessionResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelChaosTest(private val shard: Int) {
    companion object {
        /** Не больше [PER_SHARD] итераций на тест: Robolectric освобождает экраны только между тестами. */
        private const val PER_SHARD = 150
        /** Итерация (≈10 с имитируемого времени, сотни событий) дольше этого — подозрение на зависание. */
        private const val SLOW_MS = 20_000L
        private fun iterations() = System.getProperty("chaos.iterations")?.toIntOrNull() ?: 60

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "shard {0}")
        fun shards(): List<Array<Any>> = (0 until (iterations() + PER_SHARD - 1) / PER_SHARD).map { arrayOf<Any>(it) }

        val ENTITY = Regex("""\b(?:sensor|binary_sensor|light|switch|weather|camera|lock|input_button|button|select|sun|fan|climate|cover)\.[a-z0-9_]+""")
        val bitmap: Bitmap by lazy { Bitmap.createBitmap(1920, 1200, Bitmap.Config.ARGB_8888) }
        val canvas: Canvas by lazy { Canvas(bitmap) }
    }

    @Before fun noSession() = PinSecurityUi.session.revoke()
    @After fun cleanup() {
        PinSecurityUi.session.revoke()
        DoorbellHub.actions = null
        DoorbellHub.connection = null
        DoorbellHub.detach()
    }

    private class Failure(val seed: Long, val step: String, val error: Throwable)

    @Test fun randomConfigsAndStatesNeverBreakThePanel() {
        val single = System.getProperty("chaos.seed")?.toLongOrNull()
        val total = iterations()
        val seeds = single?.let { if (shard == 0) listOf(it) else emptyList() }
            ?: (shard * PER_SHARD + 1..minOf(total, (shard + 1) * PER_SHARD)).map { it * 7919L }
        if (seeds.isEmpty()) return
        val failures = mutableListOf<Failure>()
        stats.clear()
        val started = System.nanoTime()
        val progress = File("build/reports/chaos").apply { mkdirs() }.resolve("progress-$shard.txt")
        progress.writeText("")
        for (seed in seeds) {
            val began = System.nanoTime()
            progress.appendText("$seed start\n")
            val run = Run(seed)
            try {
                run.execute()
            } catch (e: Throwable) {
                failures += Failure(seed, run.step, e)
            } finally {
                run.close()
            }
            val ms = (System.nanoTime() - began) / 1_000_000
            progress.appendText("$seed ${ms} мс\n")
            // Сочетание, которое обрабатывается секунды, на планшете — подвисание: тоже дефект.
            if (ms > SLOW_MS) failures += Failure(seed, "медленно: $ms мс", IllegalStateException("Итерация $seed заняла $ms мс"))
        }
        System.getProperty("chaos.heapdump")?.let { path ->
            repeat(3) { System.gc() }
            java.lang.management.ManagementFactory.newPlatformMXBeanProxy(java.lang.management.ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic", com.sun.management.HotSpotDiagnosticMXBean::class.java)
                .dumpHeap("$path-$shard.hprof", true)
        }
        val report = failures.groupBy { it.error.javaClass.name + " @ " + origin(it.error) }.entries.joinToString("\n\n") { (key, list) ->
            "$key — ${list.size} раз, сиды ${list.take(5).map { it.seed }}, шаг «${list.first().step}»\n" +
                list.first().error.stackTraceToString().lines().take(18).joinToString("\n")
        }
        File("build/reports/chaos").apply { mkdirs() }.resolve("panel-$shard.txt").writeText(
            "Итераций: ${seeds.size}, сбоев: ${failures.size}, ${(System.nanoTime() - started) / 1_000_000} мс\n" +
                "Охват: ${stats.toSortedMap()}\n\n$report")
        // Прогон, который ничего не построил и не раскрыл, ничего и не проверил.
        if (single == null && seeds.size >= 20) {
            assertTrue("Плитки не строились: $stats", (stats["плиток на экране"] ?: 0) > seeds.size)
            assertTrue("Ничего не раскрылось: $stats", (stats["раскрытий"] ?: 0) > 0)
        }
        assertTrue("Панель упала на ${failures.size} из ${seeds.size} сочетаний:\n$report", failures.isEmpty())
    }

    private val stats = mutableMapOf<String, Int>()
    private fun count(key: String, by: Int = 1) { stats[key] = (stats[key] ?: 0) + by }

    private fun origin(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root.stackTrace.firstOrNull { it.className.startsWith("com.example") }?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
    }

    private inner class Run(seed: Long) {
        val rnd = Random(seed)
        var step = "создание экрана"
        val activity: MainActivity = Robolectric.buildActivity(MainActivity::class.java).get().apply { pinGestureFixture(this) }
        val manager = TileManager(activity)
        val panel = FrameLayout(activity)
        val viewModel get() = MainActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }.invoke(activity) as MainViewModel
        val controller get() = MainActivity::class.java.getDeclaredMethod("getPanelController").apply { isAccessible = true }.invoke(activity) as DashboardPanelController
        val state get() = ReflectionHelpers.getField<MutableStateFlow<MainUiState>>(viewModel, "_uiState")
        val entities = linkedSetOf<String>()

        fun execute() {
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity.resources.displayMetrics.widthPixels = 1920
            activity.resources.displayMetrics.heightPixels = 1200
            step = "сохранение плиток"
            val tiles = ChaosTiles.generate(rnd)
            manager.saveTiles(tiles)
            tiles.forEach { tile -> ENTITY.findAll(tile.config).forEach { entities += it.value } }
            ReflectionHelpers.setField(activity, "tileManager", manager)
            ReflectionHelpers.setField(activity, "bottomPanel", panel)
            ReflectionHelpers.setField(activity, "dimOverlay", View(activity).apply { visibility = View.GONE })
            ReflectionHelpers.setField(activity, "yandexWeatherManager", YandexWeatherManager(activity))
            val root = FrameLayout(activity)
            root.addView(panel, FrameLayout.LayoutParams(1920, 1200))
            activity.window.setContentView(root)
            ReflectionHelpers.setField(activity.delegate, "mSubDecorInstalled", true)
            ReflectionHelpers.setField(activity.delegate, "mWindow", activity.window)
            ArchitectureTestAccess.methods(activity).single { it.name == "connectDoorbells" }.apply { isAccessible = true }.invoke(activity)
            DoorbellHub.attach(activity.application)

            step = "построение панели"
            state.value = state.value.copy(haSession = HaSession(connected = true))
            refresh()
            layoutAndDraw()
            count("плиток на экране", panel.childCount)
            tiles.forEach { count("тип ${it.type}") }

            step = "первый снимок состояний"
            val snapshot = entities.map { HaEntityState(it, ChaosStates.state(rnd), ChaosStates.attributes(rnd)) }
            viewModel.onStatesSnapshot(snapshot)
            state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
            controller.renderInitialStates(snapshot.map { it.entityId })
            DoorbellHub.onState(manager.getAllTiles(), BatteryReadings.from(state.value))
            advance(100)
            layoutAndDraw()

            repeat(4) { round ->
                step = "поток событий, круг $round"
                events(40)
                advance(rnd.nextLong(0, 400))
                layoutAndDraw()
                step = "раскрытие, круг $round"
                val views = (0 until panel.childCount).map { panel.getChildAt(it) }
                if (views.isNotEmpty()) tap(views[rnd.nextInt(views.size)])
                advance(rnd.nextLong(50, 700))
                if (controller.hasExpansion()) count("раскрытий")
                events(20)
                layoutAndDraw()
                step = "сворачивание, круг $round"
                if (rnd.nextBoolean()) controller.collapseAll() else controller.collapseOnBack()
                advance(rnd.nextLong(0, 700))
                layoutAndDraw()
                if (rnd.nextInt(4) == 0) {
                    step = "обрыв связи, круг $round"
                    state.value = state.value.copy(haSession = HaSession(connected = false))
                    controller.renderSessionState()
                    advance(200)
                    state.value = state.value.copy(haSession = HaSession(connected = true, synced = true))
                    controller.renderSessionState()
                }
                if (rnd.nextInt(5) == 0) {
                    step = "перестроение панели, круг $round"
                    refresh()
                }
            }
            step = "долгое ожидание (таймеры, автосворачивание)"
            advance(5_000)
            layoutAndDraw()
        }

        fun events(count: Int) {
            if (entities.isEmpty()) return
            val list = entities.toList()
            repeat(count) {
                val id = if (rnd.nextInt(10) == 0) "sensor.unrelated_${rnd.nextInt(1000)}" else list[rnd.nextInt(list.size)]
                val value = ChaosStates.state(rnd)
                viewModel.onEntityStateChanged(id, value, ChaosStates.attributes(rnd))
                controller.renderEntityState(id, value)
                if (id.startsWith("weather.") && rnd.nextBoolean()) {
                    viewModel.onForecastChanged(id, ChaosStates.forecast(rnd))
                    controller.renderForecastState(id)
                }
            }
            DoorbellHub.onState(manager.getAllTiles(), BatteryReadings.from(state.value))
        }

        fun refresh() = ArchitectureTestAccess.methods(activity).single { it.name == "refreshBottomPanel" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }.invoke(activity)

        fun layoutAndDraw() {
            for (i in 0 until panel.childCount) {
                val v = panel.getChildAt(i)
                val w = v.layoutParams.width.coerceIn(1, 4000)
                val h = v.layoutParams.height.coerceIn(1, 4000)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
            }
            panel.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 1920, 1200)
            canvas.drawColor(0xFF000000.toInt())
            panel.draw(canvas)
        }

        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                val stepMs = minOf(16L, left)
                shadowOf(Looper.getMainLooper()).idleFor(stepMs, TimeUnit.MILLISECONDS)
                left -= stepMs
            }
        }

        fun tap(view: View) {
            val time = SystemClock.uptimeMillis()
            val x = (view.width / 2f).coerceAtLeast(1f)
            val y = (view.height / 2f).coerceAtLeast(1f)
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                val e = MotionEvent.obtain(time, time + i, action, x, y, 0)
                try { view.dispatchTouchEvent(e) } finally { e.recycle() }
            }
        }

        fun close() {
            runCatching { controller.collapseAll() }
            runCatching { DoorbellHub.RingUi.current?.closeNow() }
            runCatching { shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS) }
            DoorbellHub.actions = null
            DoorbellHub.connection = null
            DoorbellHub.detach()
            activity.getSharedPreferences("tiles_prefs", 0).edit().clear().commit()
            // Robolectric хранит все показанные Toast (с контекстом экрана) — без сброса экраны копились бы в памяти теста.
            org.robolectric.shadows.ShadowToast.reset()
            // Отложенные задачи этого экрана (автовозврат, таймеры) в тесте уже не наступят, но держат экран в памяти:
            // без очистки очереди каждая итерация оставляла бы свой экран, и длинный прогон упирался в нехватку памяти.
            val queue = Looper.getMainLooper().queue
            synchronized(queue) { ReflectionHelpers.setField(queue, "mMessages", null) }
            // Экран не проходит onDestroy (тестовая сборка без onCreate) — отписываем его от домофона, как сделал бы onDestroy.
            DoorbellHub.removeListener(ReflectionHelpers.getField(activity, "doorbellListener"))
            // Robolectric регистрирует каждый созданный экран в ActivityThread и держит до конца теста.
            ReflectionHelpers.getField<android.util.ArrayMap<*, *>>(org.robolectric.RuntimeEnvironment.getActivityThread(), "mActivities").clear()
        }
    }

}

/** Плитки всех типов: исходные настройки — как создают редакторы, затем случайная порча полей и значений. */
internal object ChaosTiles {
    private val types = listOf("button", "group", "sensor", "battery", "weather", "clock", BatteryModernConfig.TYPE,
        PowerModernConfig.TYPE, InverterModernConfig.TYPE, HomeClimateConfig.TYPE, DoorbellConfig.TYPE, "unknown_type")

    fun generate(rnd: Random): List<TileEntity> = (0 until rnd.nextInt(1, 9)).map { i ->
        val type = types[rnd.nextInt(types.size)]
        var config = JSONObject(seed(type, i, rnd))
        if (rnd.nextInt(3) > 0) config = ChaosJson.mutate(config, rnd, depth = 0) as? JSONObject ?: JSONObject()
        // Сетевые источники видео в JVM не открываются; адрес и логин камеры — только пустые.
        config.optJSONObject(DoorbellConfig.KEY)?.apply { put("rtsp_main", ""); put("rtsp_sub", ""); put("rtsp_user", "") }
        val text = when (rnd.nextInt(20)) {
            0 -> "not json"
            1 -> ""
            2 -> "[]"
            else -> config.toString()
        }
        val container = if (type == "button" || type == "group") (if (rnd.nextInt(8) == 0) "grid" else "bottom_panel")
            else if (rnd.nextInt(8) == 0) "bottom_panel" else "grid"
        TileEntity(
            id = if (rnd.nextInt(15) == 0) "dup" else "t$i",
            type = type,
            container = container,
            title = ChaosStates.text(rnd),
            x = weird(rnd, 1800), y = weird(rnd, 1100),
            width = weird(rnd, 4), height = weird(rnd, 4),
            appearance = if (rnd.nextInt(6) == 0) ChaosStates.text(rnd) else "{}",
            conditions = if (rnd.nextInt(8) == 0) "{]" else "{}",
            config = text,
            enabled = rnd.nextInt(10) != 0,
            collapsedSensorIds = if (rnd.nextInt(6) == 0) """["sensor.a", null, 5]""" else "[]",
            colorRules = if (rnd.nextInt(5) == 0) colorRules(rnd) else "[]",
            fontSize = weird(rnd, 40),
            icon = if (rnd.nextInt(4) == 0) ChaosStates.text(rnd) else "",
            sourceType = listOf("auto", "manual", "", "???")[rnd.nextInt(4)],
            page = 0
        )
    }

    private fun weird(rnd: Random, normal: Int): Int = when (rnd.nextInt(14)) {
        0 -> 0
        1 -> -rnd.nextInt(1, 5000)
        2 -> Int.MAX_VALUE
        3 -> Int.MIN_VALUE
        4 -> 100_000
        else -> rnd.nextInt(0, normal + 1)
    }

    private fun colorRules(rnd: Random) = JSONArray().apply {
        repeat(rnd.nextInt(0, 4)) {
            put(JSONObject().put("from", ChaosStates.number(rnd)).put("to", ChaosStates.number(rnd))
                .put("color", listOf("#FF0000", "red", "", "#GGGGGG", "#12")[rnd.nextInt(5)]))
        }
    }.toString()

    private fun id(prefix: String, i: Int, rnd: Random) = "$prefix.fz${i}_${rnd.nextInt(4)}"

    private fun seed(type: String, i: Int, rnd: Random): String {
        val size = JSONObject().put("button_size", rnd.nextInt(120, 700)).put("widget_height", rnd.nextInt(120, 700))
        return when (type) {
            "button" -> size.put("entity_id", id("light", i, rnd)).apply {
                if (rnd.nextBoolean()) put(TileButtonStyle.CONFIG_KEY, TileButtonStyle.MODERN.key)
                if (rnd.nextBoolean()) put("child_power", JSONObject().put("show_power", true).put("power_entity_id", id("sensor", i, rnd)))
            }.toString()
            "group" -> size.put("entity_ids", JSONArray(listOf(id("light", i, rnd), id("switch", i, rnd), id("light", i + 10, rnd))))
                .put("child_names", JSONArray(listOf("Один", "Два", "Три"))).apply {
                    if (rnd.nextBoolean()) put(TileButtonStyle.CONFIG_KEY, TileButtonStyle.MODERN.key)
                }.toString()
            "sensor" -> size.put("collapsed_sensor_configs", JSONArray().put(JSONObject().put("entity_id", id("sensor", i, rnd))
                .put("display_name", "Т").put("decimals", 1)))
                .put("expanded_sensor_configs", JSONArray().put(JSONObject().put("entity_id", id("sensor", i + 1, rnd)).put("display_name", "В")))
                .put("graph_entity_id", id("sensor", i, rnd)).toString()
            "battery" -> size.put("entity_id", id("sensor", i, rnd)).toString()
            "weather" -> size.put("entity_id", id("weather", i, rnd)).toString()
            "clock" -> size.toString()
            BatteryModernConfig.TYPE -> fill(BatteryModernConfig.newTileConfig(size.toString()), i, rnd)
            PowerModernConfig.TYPE -> fill(PowerModernConfig.newTileConfig(
                listOf(PowerModernConfig.PROFILE_GRID, PowerModernConfig.PROFILE_INVERTER, "solar", "")[rnd.nextInt(4)], size.toString()), i, rnd)
            InverterModernConfig.TYPE -> fill(InverterModernConfig.newTileConfig(size.toString()), i, rnd)
            HomeClimateConfig.TYPE -> HomeClimateConfig(
                weather = HomeClimateConfig.Weather(entityId = id("weather", i, rnd)),
                rooms = (0 until rnd.nextInt(0, 6)).map { HomeClimateConfig.Room("r$it", "Комната $it", null, id("sensor", i + it, rnd), id("sensor", i + it + 20, rnd)) },
                favorites = listOf("r0", "r1", "missing")
            ).writeTo(size.toString())
            DoorbellConfig.TYPE -> DoorbellConfig(camera = id("camera", i, rnd), visitor = id("binary_sensor", i, rnd),
                person = id("binary_sensor", i + 1, rnd), motion = id("binary_sensor", i + 2, rnd), lock = id("lock", i, rnd),
                light = id("light", i, rnd), quickReply = id("select", i, rnd), autoLight = rnd.nextBoolean())
                .writeTo(DoorbellConfig.newTileConfig(size.toString()))
            else -> size.put("entity_id", id("sensor", i, rnd)).toString()
        }
    }

    /** Пустые поля датчиков шаблона получают сущности, иначе шаблон без источников показывает лишь заглушки. */
    private fun fill(config: String, i: Int, rnd: Random): String {
        fun walk(value: Any?): Any? = when (value) {
            is JSONObject -> value.apply {
                keys().asSequence().toList().forEach { key ->
                    val v = opt(key)
                    if (v is String && v.isEmpty() && (key.contains("entity") || key == "camera")) put(key, id("sensor", i, rnd))
                    else walk(v)
                }
            }
            is JSONArray -> value.apply { for (k in 0 until length()) walk(opt(k)) }
            else -> value
        }
        return walk(JSONObject(config)).toString()
    }
}

/** Случайная порча JSON: замена типов, крайние числа, удаление/добавление ключей, вложенность, длинные строки. */
internal object ChaosJson {
    fun mutate(value: Any?, rnd: Random, depth: Int): Any? {
        if (depth > 6) return value
        if (rnd.nextInt(12) == 0) return junk(rnd)
        return when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().asSequence().toList().forEach { key ->
                    when (rnd.nextInt(20)) {
                        0 -> Unit // ключ пропал
                        1 -> out.put(key, JSONObject.NULL)
                        else -> out.put(key, mutate(value.opt(key), rnd, depth + 1))
                    }
                }
                if (rnd.nextInt(10) == 0) out.put("x_${rnd.nextInt(100)}", junk(rnd))
            }
            is JSONArray -> JSONArray().also { out ->
                for (i in 0 until value.length()) if (rnd.nextInt(15) != 0) out.put(mutate(value.opt(i), rnd, depth + 1))
                if (rnd.nextInt(8) == 0) out.put(junk(rnd))
                if (rnd.nextInt(20) == 0) repeat(200) { out.put(value.opt(0) ?: 1) }
            }
            // Числа в настройках (точность, размеры, пороги) — крайние значения.
            is Number -> if (rnd.nextInt(4) == 0) listOf(-1, 0, Int.MAX_VALUE, Int.MIN_VALUE, 1_000_000_000, -0.5, 1e18)[rnd.nextInt(7)] else value
            else -> value
        }
    }

    fun junk(rnd: Random): Any = when (rnd.nextInt(14)) {
        0 -> JSONObject.NULL
        1 -> ""
        2 -> -1
        3 -> Int.MAX_VALUE
        4 -> Long.MIN_VALUE
        5 -> 1e300
        6 -> -0.0
        7 -> true
        8 -> JSONArray()
        9 -> JSONObject()
        10 -> "NaN"
        11 -> ChaosStates.text(rnd)
        12 -> JSONArray().put(JSONObject().put("a", JSONArray().put(JSONArray())))
        else -> rnd.nextInt(-100, 100_000)
    }
}

/** Состояния и атрибуты HA, включая мусор, который присылают сломанные интеграции. */
internal object ChaosStates {
    private val states = listOf("on", "off", "unavailable", "unknown", "", "0", "-0", "1", "23.5", "-12.75", "230", "1e309",
        "-1e309", "NaN", "Infinity", "-Infinity", "12,5", "１２", "0x1F", "  42  ", "9999999999999999999", "locked", "unlocked",
        "jammed", "open", "idle", "streaming", "recording", "playing", "2026-11-07T10:00:00+03:00", "sunny", "rainy", "home",
        "Голос", "true", "false", "None", "null", "🙂", "Load", "Battery", "Grid", "Solar", "standby")

    fun state(rnd: Random): String = if (rnd.nextInt(25) == 0) text(rnd) else states[rnd.nextInt(states.size)]

    fun number(rnd: Random): Any = when (rnd.nextInt(8)) {
        0 -> Double.MAX_VALUE
        1 -> -Double.MAX_VALUE
        2 -> 0
        3 -> "abc"
        4 -> JSONObject.NULL
        else -> rnd.nextDouble(-1000.0, 1000.0)
    }

    fun text(rnd: Random): String = when (rnd.nextInt(7)) {
        0 -> ""
        1 -> "x".repeat(rnd.nextInt(500, 5000))
        2 -> "\u0000‮￿"
        3 -> "Очень длинное название устройства в доме ".repeat(8)
        4 -> "%s %d %n"
        5 -> "😀".repeat(50)
        else -> "Текст ${rnd.nextInt(1000)}"
    }

    fun attributes(rnd: Random): JSONObject = JSONObject().apply {
        if (rnd.nextInt(3) == 0) return@apply
        if (rnd.nextBoolean()) put("unit_of_measurement", listOf("W", "kW", "V", "A", "%", "°C", "°F", "Wh", "kWh", "", "MW", "mW", "??")[rnd.nextInt(13)])
        if (rnd.nextInt(3) == 0) put("device_class", listOf("power", "voltage", "battery", "temperature", "", "nonsense")[rnd.nextInt(6)])
        if (rnd.nextInt(3) == 0) put("friendly_name", text(rnd))
        if (rnd.nextInt(4) == 0) put("brightness", number(rnd))
        if (rnd.nextInt(4) == 0) put("temperature", number(rnd))
        if (rnd.nextInt(4) == 0) put("humidity", number(rnd))
        if (rnd.nextInt(5) == 0) put("forecast", forecast(rnd))
        if (rnd.nextInt(5) == 0) put("options", JSONArray(listOf("Сейчас выйду", "", JSONObject.NULL, 5)))
        if (rnd.nextInt(5) == 0) put("entity_picture", "/api/camera_proxy/camera.x?token=t")
        if (rnd.nextInt(6) == 0) put("access_token", "t")
        if (rnd.nextInt(8) == 0) put(text(rnd).take(40), ChaosJson.junk(rnd))
    }

    fun forecast(rnd: Random): JSONArray? = when (rnd.nextInt(6)) {
        0 -> null
        1 -> JSONArray()
        2 -> JSONArray().put(JSONObject.NULL).put(5).put("x")
        else -> JSONArray().apply {
            repeat(rnd.nextInt(1, 60)) { h ->
                put(JSONObject().put("datetime", listOf("2026-11-07T${h % 24}:00:00+03:00", "bad", "", "2026-13-45T99:99:99Z")[rnd.nextInt(4)])
                    .put("temperature", number(rnd)).put("templow", number(rnd)).put("condition", state(rnd))
                    .put("precipitation", number(rnd)).put("precipitation_probability", number(rnd)).put("wind_speed", number(rnd)))
            }
        }
    }
}
