package com.example.smarthomedashboard.deep

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.smarthomedashboard.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Настоящее приложение на эмуляторе с фальшивым HA (docs/deep-testing.md): запуск, все типы плиток, поток событий,
 * раскрытия во время потока, обрыв и возврат связи, поворот экрана. Плитки и сущности искусственные.
 * Длительный прогон (soak) — только при аргументе soakMinutes (ночной CI).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PanelEndToEndTest {
    private lateinit var ha: FakeHomeAssistant
    private lateinit var ids: List<String>
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun start() {
        assumeTrue("Только эмулятор: тест заменяет данные приложения", isEmulator())
        ha = FakeHomeAssistant()
        val tiles = allTiles()
        ids = seedStates(ha, tiles)
        prepareApp(ha, tiles)
    }

    @After fun stop() {
        scenario?.close()
        if (::ha.isInitialized) ha.close()
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also {
        scenario = it
        waitFor(30_000, "подключение к фальшивому HA и снимок состояний") { ha.auths.get() >= 1 && ha.snapshots.get() >= 1 && ha.connected() >= 1 }
    }

    private fun activity(): MainActivity {
        var found: MainActivity? = null
        scenario!!.onActivity { found = it }
        return found!!
    }

    private fun tile(id: String): View? {
        val activity = activity()
        return onMain { activity.window.decorView.findViewWithTag<View>(id) }
    }

    @Test fun startsConnectsAndShowsEveryTileType() {
        launch()
        for (id in listOf("deep_sensor", "deep_battery", "deep_weather", "deep_bms", "deep_grid", "deep_inverter",
                "deep_climate", "deep_door", "deep_light", "deep_group")) {
            waitFor(10_000, "плитка $id на экране") { tile(id)?.isShown == true }
        }
        val commandsBefore = ha.commands.size
        // Сам запуск не шлёт команд устройствам.
        Thread.sleep(2_000)
        assertEquals("Запуск панели не отправляет команд устройствам", commandsBefore, ha.commands.size)
        assertEquals(0, commandsBefore)
    }

    @Test fun eventStormWithExpansionsKeepsPanelResponsive() {
        launch()
        waitFor(10_000, "плитки построены") { tile("deep_door") != null }
        val seconds = argument("stormSeconds")?.toIntOrNull() ?: 60
        val monitor = StallMonitor().apply { start() }
        val rnd = Random(2026)
        val until = System.currentTimeMillis() + seconds * 1000L
        val expandable = listOf("deep_sensor", "deep_bms", "deep_grid", "deep_inverter", "deep_climate", "deep_door")
        var events = 0
        var nextTap = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            // ~200 событий в секунду: больше, чем реальный дом (замер 03.11: десятки в секунду в пике).
            repeat(20) {
                val id = ids[rnd.nextInt(ids.size)]
                ha.push(id, value(id, rnd), JSONObject().put("unit_of_measurement", if (rnd.nextBoolean()) "W" else "kW"))
                events++
            }
            if (System.currentTimeMillis() > nextTap) {
                tile(expandable[rnd.nextInt(expandable.size)])?.let { tap(activity(), it) }
                Thread.sleep(900)
                val current = activity()
                onMain { current.onBackPressedDispatcher.onBackPressed() }
                nextTap = System.currentTimeMillis() + 2_500
            }
            assertTrue("Главный поток отвечает", mainThreadResponds(2_000))
            Thread.sleep(100)
        }
        val summary = monitor.stop()
        log("Шторм $seconds с, событий $events: $summary")
        assertTrue("Кадры шли: $summary", summary.frames > seconds * 10)
        assertTrue("Нет подвисаний больше 1 с: $summary", summary.max < 1_000)
        assertEquals("Шторм событий не шлёт команд устройствам", 0, ha.commands.size)
    }

    @Test fun connectionLossAndReturnRecoversWithoutRestart() {
        launch()
        repeat(3) { round ->
            val auths = ha.auths.get()
            ha.refuse = true
            ha.dropAll()
            Thread.sleep(4_000)
            assertTrue("Без связи главный поток отвечает", mainThreadResponds(2_000))
            ha.refuse = false
            waitFor(90_000, "переподключение, круг $round") { ha.auths.get() > auths && ha.connected() >= 1 }
            ha.push(ids.first(), "17")
        }
        assertNotNull("Панель жива после обрывов", tile("deep_sensor"))
    }

    @Test fun rotationAndRecreationKeepPanelAndConnection() {
        launch()
        repeat(3) {
            scenario!!.recreate()
            waitFor(10_000, "плитки после пересоздания") { tile("deep_door") != null }
        }
        waitFor(30_000, "связь после пересоздания") { ha.connected() >= 1 }
        // Пересоздания не плодят соединений: не больше одного живого.
        Thread.sleep(3_000)
        assertTrue("Соединений не больше одного: ${ha.connected()}", ha.connected() <= 1)
    }

    /**
     * Старые экраны после пересоздания (поворот, смена темы, восстановление после сбоя) освобождаются: ни
     * статические реестры (домофон, голос, PIN), ни отложенные задачи и анимации их не удерживают.
     */
    @Test fun recreatedScreensAreNotLeaked() {
        launch()
        val old = mutableListOf<java.lang.ref.WeakReference<MainActivity>>()
        repeat(6) {
            scenario!!.onActivity { old += java.lang.ref.WeakReference(it) }
            tile("deep_bms")?.let { v -> tap(activity(), v); Thread.sleep(700) }
            scenario!!.recreate()
            waitFor(10_000, "плитки после пересоздания") { tile("deep_door") != null }
        }
        var alive = old.size
        repeat(20) {
            if (alive == 0) return@repeat
            Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(250)
            alive = old.count { it.get() != null }
        }
        log("Старых экранов в памяти после 6 пересозданий: $alive")
        assertTrue("Старые экраны удерживаются в памяти: $alive из ${old.size}", alive <= 1)
    }

    /** Ночной прогон: часы работы под потоком событий, звонками и обрывами — ресурсы не растут без предела. */
    @Test fun soakKeepsResourcesBounded() {
        val minutes = argument("soakMinutes")?.toIntOrNull() ?: 0
        assumeTrue("Длительный прогон только по аргументу soakMinutes", minutes > 0)
        launch()
        waitFor(10_000, "плитки построены") { tile("deep_door") != null }
        Thread.sleep(20_000)
        val baseline = Resources.sample()
        log("Soak старт: $baseline")
        val samples = mutableListOf(baseline)
        val rnd = Random(7)
        val monitor = StallMonitor().apply { start() }
        val end = System.currentTimeMillis() + minutes * 60_000L
        var minute = 0
        while (System.currentTimeMillis() < end) {
            val minuteEnd = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < minuteEnd) {
                repeat(5) { val id = ids[rnd.nextInt(ids.size)]; ha.push(id, value(id, rnd)) }
                Thread.sleep(100)
            }
            minute++
            if (minute % 10 == 0) { ha.push("binary_sensor.deep_visitor", "on"); Thread.sleep(3_000); ha.push("binary_sensor.deep_visitor", "off") }
            if (minute % 15 == 0) { ha.dropAll(); waitFor(120_000, "переподключение в soak") { ha.connected() >= 1 } }
            if (minute % 5 == 0) {
                val now = Resources.sample()
                samples += now
                log("Soak $minute мин: $now; кадры: ${monitor.snapshot()}")
            }
            assertTrue("Главный поток отвечает (минута $minute)", mainThreadResponds(5_000))
        }
        val frames = monitor.stop()
        val last = Resources.sample()
        log("Soak конец $minutes мин: $last; кадры: $frames")
        // Рост после прогрева: куча Java — не более 24 МБ, потоки — не более 8, дескрипторы — не более 40.
        assertTrue("Куча Java растёт: $baseline → $last", last.javaKb - baseline.javaKb < 24 * 1024)
        assertTrue("Потоки копятся: $baseline → $last", last.threads - baseline.threads <= 8)
        assertTrue("Дескрипторы копятся: $baseline → $last", last.fds - baseline.fds <= 40)
        assertTrue("Нет подвисаний больше 2 с: $frames", frames.max < 2_000)
    }

    private fun value(id: String, rnd: Random): String = when (id.substringBefore('.')) {
        "light", "switch", "binary_sensor" -> if (rnd.nextBoolean()) "on" else "off"
        "lock" -> if (rnd.nextBoolean()) "locked" else "unlocked"
        "weather" -> listOf("sunny", "cloudy", "rainy")[rnd.nextInt(3)]
        "camera" -> "idle"
        else -> when (rnd.nextInt(30)) {
            0 -> "unavailable"
            1 -> "unknown"
            else -> "%.1f".format(java.util.Locale.ROOT, rnd.nextDouble(-500.0, 5000.0))
        }
    }
}
