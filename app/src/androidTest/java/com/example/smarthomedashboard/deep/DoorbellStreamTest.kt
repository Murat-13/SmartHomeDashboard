package com.example.smarthomedashboard.deep

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.smarthomedashboard.DoorbellCard
import com.example.smarthomedashboard.DoorbellConfig
import com.example.smarthomedashboard.DoorbellVideoView
import com.example.smarthomedashboard.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Видео домофона на эмуляторе с настоящим RTSP-сервером (docs/deep-testing.md). Сервер mediamtx поднимает CI на
 * машине с эмулятором (адрес из аргумента rtspHost, обычно 10.0.2.2): тестовые ролики, без камер владельца.
 * Проверяется то, что ломалось на стене: картинка идёт, HD после раскрытия, а пропавшая, молчащая или
 * отказывающая камера не подвешивает панель (0.9.12). Без аргумента rtspHost тесты пропускаются.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DoorbellStreamTest {
    private lateinit var ha: FakeHomeAssistant
    private var scenario: ActivityScenario<MainActivity>? = null
    private val host: String? get() = argument("rtspHost")

    @After fun stop() {
        scenario?.close()
        if (::ha.isInitialized) ha.close()
    }

    private fun open(main: String, sub: String, autoCollapse: Boolean = true) {
        assumeTrue("Нужен аргумент rtspHost (RTSP-стенд CI)", host != null)
        assumeTrue("Только эмулятор: тест заменяет данные приложения", isEmulator())
        ha = FakeHomeAssistant()
        // Без камеры HA: только прямой RTSP, как у владельца при первом тесте Hikvision.
        val tiles = allTiles(DoorbellConfig(rtspMain = main, rtspSub = sub, visitor = "binary_sensor.deep_visitor",
            autoCollapse = autoCollapse))
        seedStates(ha, tiles)
        prepareApp(ha, tiles)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(30_000, "подключение к фальшивому HA") { ha.connected() >= 1 }
    }

    /** Экран панели — без ожидания свободного главного потока ([resumedActivity]): при живом видео его можно не дождаться. */
    private fun panel(): MainActivity? = resumedActivity() as? MainActivity

    private fun card(): DoorbellCard? = panel()?.let { a ->
        onMain { a.window.decorView.findViewWithTag<View>("deep_door")?.findViewWithTag<DoorbellCard>("door_compact") }
    }

    private fun status(): DoorbellVideoView.Status? = card()?.let { c -> onMain { c.video.status } }

    @Test fun subStreamPlaysOnTile() {
        open("rtsp://$host:8554/main1080", "rtsp://$host:8554/sub")
        waitFor(40_000, "картинка субпотока на плитке (статус ${status()})") { status() == DoorbellVideoView.Status.PLAYING }
        log("Субпоток на плитке: PLAYING")
    }

    @Test fun mainStreamAfterExpandIsHd() {
        open("rtsp://$host:8554/main1080", "rtsp://$host:8554/sub")
        waitFor(40_000, "субпоток") { status() == DoorbellVideoView.Status.PLAYING }
        val activity = panel()
        val tile = onMain { activity!!.window.decorView.findViewWithTag<View>("deep_door") }
        tap(activity!!, tile)
        val hd = runCatching {
            waitFor(40_000, "HD после раскрытия") {
                onMain {
                    activity!!.window.decorView.findViewWithTag<DoorbellCard>("door_expanded")
                        ?.findViewWithTag<View>("door_hd")?.let { it.alpha > 0.1f && it.isShown } == true
                }
            }
        }.isSuccess
        log("Основной поток 1080p после раскрытия: ${if (hd) "HD" else "нет HD"}")
        assertTrue("После раскрытия основной поток 1080p даёт «HD»", hd)
    }

    /**
     * Поворот экрана (решение владельца 23.11.2026): плитка и раскрытая карточка — те же объекты на новом экране, их
     * плееры не создаются заново и продолжают показывать кадры. Раньше оба плеера закрывались со старым экраном, новые
     * подключались заново («Подключение…» на секунду). Адреса потоков можно задать аргументами rtspPort, rtspMain,
     * rtspSub (стенд камер на Mac); без них — потоки стенда CI.
     */
    @Test fun rotationKeepsVideoWithoutReconnect() {
        val port = argument("rtspPort") ?: "8554"
        // Без автосворачивания: на медленном эмуляторе CI своё HD шло дольше 30 с, и карточка сворачивалась сама.
        open("rtsp://$host:$port/${argument("rtspMain") ?: "main1080"}", "rtsp://$host:$port/${argument("rtspSub") ?: "sub"}",
            autoCollapse = false)
        waitFor(40_000, "субпоток") { status() == DoorbellVideoView.Status.PLAYING }
        val activity = panel()
        tap(activity!!, onMain { activity.window.decorView.findViewWithTag<View>("deep_door") })
        fun expanded(of: MainActivity) = of.window.decorView.findViewWithTag<DoorbellCard>("door_expanded")
        waitFor(40_000, "свой поток раскрытой карточки") {
            onMain { expanded(activity!!)?.video?.let { it.status == DoorbellVideoView.Status.PLAYING && !it.hasGuest && it.framesShown > 5 } == true }
        }
        val compactBefore = card()!!
        val expandedBefore = onMain { expanded(activity!!) } ?: error("раскрытая карточка закрылась до поворота")
        val starts = onMain { compactBefore.video.playerStarts to expandedBefore.video.playerStarts }
        val framesBefore = onMain { expandedBefore.video.framesShown }

        scenario!!.recreate() // экран пересоздаётся «ради смены конфигурации» — как при повороте
        var next: MainActivity? = null
        waitFor(10_000, "раскрытая карточка на новом экране") {
            next = panel()
            next != null && next !== activity && onMain { expanded(next!!)?.isAttachedToWindow == true }
        }
        val expandedAfter = onMain { expanded(next!!) }
        assertTrue("раскрытая карточка — та же", expandedAfter === expandedBefore)
        assertTrue("плитка — та же", card() === compactBefore)
        assertTrue("карточка живёт на новом экране", onMain { com.example.smarthomedashboard.Movable.activityOf(expandedAfter.context) } === next)
        // Кадры идут дальше на новом экране тем же плеером.
        waitFor(10_000, "кадры после поворота") { onMain { expandedAfter.video.framesShown } > framesBefore + 10 }
        val after = onMain { compactBefore.video.playerStarts to expandedAfter.video.playerStarts }
        assertTrue("плееры не создавались заново: было $starts, стало $after", after == starts)
        assertTrue("видео идёт", onMain { expandedAfter.video.status } == DoorbellVideoView.Status.PLAYING)
        assertTrue("плитка идёт", status() == DoorbellVideoView.Status.PLAYING)
        log("Поворот: плееры те же ($after), кадров показано ${onMain { expandedAfter.video.framesShown }} (до поворота $framesBefore)")
    }

    /** Разрешение выше 1080p (как 2560×1920 у Reolink): на эмуляторе — программный декодер, результат — сведения, не приговор. */
    @Test fun aboveFullHdIsReported() {
        open("rtsp://$host:8554/main2560", "rtsp://$host:8554/main2560")
        val playing = runCatching { waitFor(40_000, "2560×1920") { status() == DoorbellVideoView.Status.PLAYING } }.isSuccess
        log("Поток 2560×1920 H.264 на эмуляторе: ${if (playing) "идёт" else "не идёт, статус ${status()}"}")
    }

    /** Камеры нет совсем (порт закрыт), адрес «в никуда» и камера, которая принимает соединение и молчит. */
    @Test fun missingSilentAndRefusingCamerasDoNotFreezePanel() {
        for ((name, url) in listOf(
            "порт закрыт" to "rtsp://$host:8555/none",
            "адрес не отвечает" to "rtsp://10.255.255.1:554/none",
            "молчащая камера" to "rtsp://$host:8556/silent"
        )) {
            open(url, url)
            val monitor = StallMonitor().apply { start() }
            val seconds = argument("stallSeconds")?.toIntOrNull() ?: 45
            val until = System.currentTimeMillis() + seconds * 1000L
            val seen = linkedSetOf<DoorbellVideoView.Status>()
            while (System.currentTimeMillis() < until) {
                assertTrue("$name: главный поток отвечает", mainThreadResponds(2_000))
                status()?.let { seen += it }
                Thread.sleep(250)
            }
            val summary = monitor.stop()
            val finalStatus = status()
            log("Домофон, $name: $summary; статус $finalStatus, были: $seen")
            assertTrue("$name: нет подвисаний больше 700 мс ($summary)", summary.max < 700)
            assertTrue("$name: плитка не показывает живое видео ($finalStatus)", finalStatus != DoorbellVideoView.Status.PLAYING)
            // Каждый вид пропавшей камеры обязан закончиться ошибкой и повтором, а не вечным «Подключение…»
            // (молчащая камера — через проверку первого кадра за 20 с, 0.9.13).
            // Ошибка между попытками держится от 0,5 с: опрос раз в 250 мс на медленном эмуляторе CI её пропускал
            // (20.11 и 22.11: «были: [CONNECTING]»). Неудачные попытки считает сам вид.
            val failed = card()?.let { c -> onMain { c.video.failedAttempts } } ?: 0
            assertTrue("$name: попытка заканчивается ошибкой (были: $seen, неудачных попыток $failed)",
                DoorbellVideoView.Status.ERROR in seen || failed > 0)
            scenario?.close()
            scenario = null
            ha.close()
        }
    }
}
