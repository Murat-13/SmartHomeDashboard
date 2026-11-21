package com.example.smarthomedashboard

import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// «Домофон»: настройки, состояние плитки и события звонка/связи/калитки. Чистая логика, время задаёт тест.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoorbellLogicTest {
    private val full = DoorbellConfig(camera = "camera.door", rtspMain = "rtsp://10.0.0.5:554/h264Preview_01_main",
        rtspSub = "rtsp://10.0.0.5:554/h264Preview_01_sub", rtspUser = "admin", visitor = "binary_sensor.door_visitor",
        person = "binary_sensor.door_person", animal = "binary_sensor.door_animal", lock = "lock.gate",
        light = "light.entrance", quickReply = "select.door_reply")

    private fun tile(config: DoorbellConfig = full, id: String = "door") =
        TileEntity(id, DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1, config = config.writeTo(DoorbellConfig.newTileConfig()))

    private fun readings(values: Map<String, String>, attrs: Map<String, JSONObject> = emptyMap(), connected: Boolean = true) =
        BatteryReadings(HaSession(connected, connected), values::get, { false }, { attrs[it] })

    private val normal = mapOf("camera.door" to "idle", "binary_sensor.door_visitor" to "off", "binary_sensor.door_person" to "off",
        "lock.gate" to "locked", "light.entrance" to "off", "sun.sun" to "above_horizon")

    @Test fun configRoundTripDefaultsAndUnknownKeysKept() {
        val custom = full.copy(mode = DoorbellConfig.VideoMode.SNAPSHOT, collapseSeconds = 45, autoCollapse = false, ringVolume = 33,
            ringVoice = false, quietStartMin = 22 * 60, quietEndMin = 6 * 60, quietPercent = 10, nightBrightness = 30,
            lightOffMinutes = 5, offlineSound = false, aspect = 16f / 9f, ringTone = "content://x/1")
        val text = custom.writeTo("""{"button_size":640,"widget_height":480,"foreign":"keep"}""")
        assertEquals(custom, DoorbellConfig.from(text))
        assertEquals("Чужие ключи config не теряются", "keep", JSONObject(text).getString("foreign"))
        val defaults = DoorbellConfig.from("{}")
        assertEquals(DoorbellConfig.VideoMode.LIVE, defaults.mode)
        assertTrue(defaults.autoCollapse); assertEquals(30, defaults.collapseSeconds)
        assertEquals(23 * 60, defaults.quietStartMin); assertEquals(7 * 60, defaults.quietEndMin)
        assertEquals(20, defaults.quietPercent); assertEquals(40, defaults.nightBrightness); assertEquals(3, defaults.lightOffMinutes)
        assertEquals(4f / 3f, defaults.aspect, 0.001f)
        // Повреждённые значения — значения по умолчанию, а не падение.
        val broken = DoorbellConfig.fromJson(JSONObject().put("collapse_seconds", -4).put("aspect", 99.0).put("mode", "x"))
        assertEquals(30, broken.collapseSeconds); assertEquals(4f / 3f, broken.aspect, 0.001f)
        assertEquals(DoorbellConfig.VideoMode.LIVE, broken.mode)
        assertFalse("Пароль в config не попадает", text.contains("password"))
    }

    @Test fun newTileIsFourByThreeAndKeepsExistingSize() {
        val fresh = JSONObject(DoorbellConfig.newTileConfig())
        assertEquals(480, fresh.getInt("button_size")); assertEquals(360, fresh.getInt("widget_height"))
        val kept = JSONObject(DoorbellConfig.newTileConfig("""{"button_size":800,"widget_height":600}"""))
        assertEquals(800, kept.getInt("button_size"))
        assertEquals(800 to 600, DoorbellConfig.fit(1000, 600, 4f / 3f))
        assertEquals(600 to 450, DoorbellConfig.fit(600, 900, 4f / 3f))
        assertEquals("Минимум по высоте", 120, DoorbellConfig.fit(10, 10, 4f / 3f).second)
    }

    @Test fun credentialsAreEncodedIntoRtspUrlOnly() {
        assertEquals("rtsp://admin:p%40ss%20w@10.0.0.5:554/main",
            DoorbellConfig.withCredentials("rtsp://10.0.0.5:554/main", "admin", "p@ss w"))
        assertEquals("Логин в адресе уже есть — не трогаем", "rtsp://u:p@h/s", DoorbellConfig.withCredentials("rtsp://u:p@h/s", "admin", "x"))
        assertEquals("rtsp://admin@h/s", DoorbellConfig.withCredentials("rtsp://h/s", "admin", null))
        assertEquals("rtsp://h/s", DoorbellConfig.withCredentials("rtsp://h/s", "", "x"))
        assertEquals(Triple("rtsp://10.0.0.5:554/main", "admin", "p@ss w"),
            DoorbellConfig.splitCredentials("rtsp://admin:p%40ss%20w@10.0.0.5:554/main"))
        assertEquals(Triple("rtsp://h/s", null, null), DoorbellConfig.splitCredentials("rtsp://h/s"))
    }

    @Test fun dependenciesIncludeSunOnlyForAutoLight() {
        assertTrue(DoorbellConfig.SUN in full.entityIds)
        assertFalse(DoorbellConfig.SUN in full.copy(autoLight = false).entityIds)
        assertFalse("Адрес RTSP — не сущность", full.entityIds.any { it.startsWith("rtsp") })
        val plan = PanelSubscriptionPlan.create(listOf(tile()), null, false)
        assertTrue(plan.entityIds.containsAll(listOf("camera.door", "binary_sensor.door_visitor", "lock.gate", "sun.sun")))
        assertFalse(plan.entityIds.any { it.startsWith("rtsp") })
        assertTrue(DoorbellConfig.SUN in PanelEntityIds.describe(tile()).ids)
    }

    @Test fun snapshotReadsDetectionsRepliesPictureAndSun() {
        val attrs = mapOf(
            "camera.door" to JSONObject().put("entity_picture", "/api/camera_proxy/camera.door?token=abc"),
            "select.door_reply" to JSONObject().put("options", JSONArray(listOf("off", "Сейчас подойду", "Оставьте у калитки"))))
        val s = DoorbellModel.snapshot(full, readings(normal + mapOf("binary_sensor.door_person" to "on", "sun.sun" to "below_horizon",
            "select.door_reply" to "off"), attrs))
        assertEquals(true, s.online)
        assertTrue(s.person); assertFalse(s.ringing)
        assertEquals(DoorbellModel.Lock.CLOSED, s.lock)
        assertEquals(false, s.lightOn)
        assertTrue(s.sunDown)
        assertEquals("/api/camera_proxy/camera.door?token=abc", s.picture)
        assertEquals(listOf("off", "Сейчас подойду", "Оставьте у калитки"), s.quickReplies)
        val offline = DoorbellModel.snapshot(full, readings(normal + ("camera.door" to "unavailable")))
        assertEquals(false, offline.online)
        val noHa = DoorbellModel.snapshot(full, readings(normal, connected = false))
        assertNull("Без связи с HA о домофоне неизвестно", noHa.online)
        assertEquals("Нет связи с Home Assistant", noHa.haStatus)
    }

    /** Смена только атрибутов камеры/фраз домофона доходит до проверок; атрибуты прочих сущностей — нет. */
    @Test fun attributeOnlyChangesOfDoorbellEntitiesWakeChecks() {
        DoorbellHub.onState(listOf(tile()), readings(normal))
        try {
            val vm = MainViewModel()
            vm.onStatesSnapshot(listOf(HaEntityState("camera.door", "idle", JSONObject().put("entity_picture", "/a?token=1")),
                HaEntityState("select.door_reply", "off", JSONObject().put("options", JSONArray(listOf("off")))),
                HaEntityState("sensor.t", "20", JSONObject().put("x", 1))))
            val before = PanelAlerts.stateKey(vm.uiState.value)
            vm.onEntityStateChanged("sensor.t", "20", JSONObject().put("x", 2))
            assertEquals("Чужие атрибуты не будят проверку", before, PanelAlerts.stateKey(vm.uiState.value))
            vm.onEntityStateChanged("camera.door", "idle", JSONObject().put("entity_picture", "/a?token=2"))
            assertNotEquals("Новый токен снимка доходит", before, PanelAlerts.stateKey(vm.uiState.value))
            val mid = PanelAlerts.stateKey(vm.uiState.value)
            vm.onEntityStateChanged("select.door_reply", "off", JSONObject().put("options", JSONArray(listOf("off", "Иду"))))
            assertNotEquals("Новые фразы доходят", mid, PanelAlerts.stateKey(vm.uiState.value))
        } finally { DoorbellHub.detach() }
    }

    @Test fun openCommandPerDomain() {
        assertEquals(DoorbellModel.OpenCommand("lock", "unlock", "unlocked"), DoorbellModel.openCommand("lock.gate"))
        assertEquals("press", DoorbellModel.openCommand("button.gate")!!.service)
        assertEquals("turn_on", DoorbellModel.openCommand("switch.gate_relay")!!.service)
        assertEquals("open_cover", DoorbellModel.openCommand("cover.gate")!!.service)
        assertNull(DoorbellModel.openCommand("sensor.x"))
        assertTrue(DoorbellModel.fits("lock", "button.gate")); assertFalse(DoorbellModel.fits("camera", "sensor.x"))
    }

    @Test fun ringOnlyOnOffToOnWithDebounceAndNotOnStartup() {
        val m = DoorbellMonitor()
        val tiles = listOf(tile())
        assertTrue("Запуск во время звонка — не звоним", m.evaluate(tiles, readings(normal + ("binary_sensor.door_visitor" to "on")), 0).isEmpty())
        m.evaluate(tiles, readings(normal), 1_000)
        assertEquals(listOf(DoorbellMonitor.Event.Ring("door")), m.evaluate(tiles, readings(normal + ("binary_sensor.door_visitor" to "on")), 5_000))
        m.evaluate(tiles, readings(normal), 5_500)
        assertTrue("Дребезг кнопки", m.evaluate(tiles, readings(normal + ("binary_sensor.door_visitor" to "on")), 6_000).isEmpty())
        m.evaluate(tiles, readings(normal), 7_000)
        assertEquals(1, m.evaluate(tiles, readings(normal + ("binary_sensor.door_visitor" to "on")), 9_000).size)
        // Связь с HA пропала и вернулась при нажатой кнопке — это не новый звонок.
        assertTrue(m.evaluate(tiles, readings(normal, connected = false), 20_000).isEmpty())
        assertTrue(m.evaluate(tiles, readings(normal + ("binary_sensor.door_visitor" to "on")), 30_000).isEmpty())
    }

    /** Прямые события (docs/direct-events-tz.md): одно нажатие из вебхука и из HA — один звонок, первый источник. */
    @Test fun directAndHaRingsMergeFirstSourceWins() {
        val m = DoorbellMonitor()
        val tiles = listOf(tile())
        val on = readings(normal + ("binary_sensor.door_visitor" to "on"))
        m.evaluate(tiles, readings(normal), 0)
        assertEquals(DoorbellMonitor.Event.Ring("door", "вебхук"), m.direct("door", "вебхук", 10_000))
        assertTrue("HA через 0,8 с — то же нажатие", m.evaluate(tiles, on, 10_800).isEmpty())
        assertNull("Опрос того же нажатия", m.direct("door", "опрос", 11_000))
        m.evaluate(tiles, readings(normal), 25_000)
        assertEquals("Новое нажатие вебхуком при всё ещё «вкл» в HA", DoorbellMonitor.Event.Ring("door", "вебхук"),
            m.direct("door", "вебхук", 26_000))
        assertNull("Дребезг вебхука", m.direct("door", "вебхук", 27_000))
        assertNotNull("Повтор вебхуком через 3 с", m.direct("door", "вебхук", 29_500))
        // HA первым: вебхук того же нажатия молчит, через 5 с — новый звонок.
        m.evaluate(tiles, readings(normal), 40_000)
        assertEquals(listOf(DoorbellMonitor.Event.Ring("door")), m.evaluate(tiles, on, 50_000))
        assertNull(m.direct("door", "вебхук", 50_400))
        assertNotNull(m.direct("door", "вебхук", 55_100))
        // Без вебхука HA звонит как раньше.
        m.evaluate(tiles, readings(normal), 70_000)
        assertEquals(1, m.evaluate(tiles, on, 80_000).size)
    }

    @Test fun offlineAfterConfirmationOnceAndOnlineAgain() {
        val m = DoorbellMonitor()
        val tiles = listOf(tile())
        m.evaluate(tiles, readings(normal), 0)
        assertTrue(m.evaluate(tiles, readings(normal + ("camera.door" to "unavailable")), 1_000).isEmpty())
        assertEquals(16_000L, m.nextCheckAt)
        assertTrue("Короткий провал молчит", m.evaluate(tiles, readings(normal), 5_000).isEmpty())
        m.evaluate(tiles, readings(normal + ("camera.door" to "unavailable")), 6_000)
        assertEquals(listOf(DoorbellMonitor.Event.Offline("door")), m.evaluate(tiles, readings(normal + ("camera.door" to "unavailable")), 21_000))
        assertTrue(m.offline("door"))
        assertTrue("Один раз", m.evaluate(tiles, readings(normal + ("camera.door" to "unavailable")), 60_000).isEmpty())
        assertEquals(listOf(DoorbellMonitor.Event.Online("door")), m.evaluate(tiles, readings(normal), 61_000))
        assertFalse(m.offline("door"))
        val startup = DoorbellMonitor()
        assertTrue("Запуск при недоступном — без звука, но «нет сигнала»",
            startup.evaluate(tiles, readings(normal + ("camera.door" to "unavailable")), 0).isEmpty())
        assertTrue(startup.offline("door"))
        assertTrue("Без связи с HA — не «домофон пропал»", DoorbellMonitor().apply { evaluate(tiles, readings(normal), 0) }
            .evaluate(tiles, readings(normal, connected = false), 30_000).isEmpty())
    }

    @Test fun gateOpenedFromLockAndButtonFacts() {
        val m = DoorbellMonitor()
        m.evaluate(listOf(tile()), readings(normal), 0)
        assertEquals(listOf(DoorbellMonitor.Event.GateOpened("door")),
            m.evaluate(listOf(tile()), readings(normal + ("lock.gate" to "unlocked")), 1_000))
        assertTrue("Закрылась — не событие", m.evaluate(listOf(tile()), readings(normal), 2_000).isEmpty())
        m.evaluate(listOf(tile()), readings(normal + ("lock.gate" to "unavailable")), 3_000)
        assertTrue("Вернулся из «недоступен» сразу открытым — не событие",
            m.evaluate(listOf(tile()), readings(normal + ("lock.gate" to "unlocked")), 4_000).isEmpty())
        val button = tile(full.copy(lock = "button.gate"))
        val b = DoorbellMonitor()
        b.evaluate(listOf(button), readings(normal + ("button.gate" to "2026-11-06T01:00:00+00:00")), 0)
        assertEquals(1, b.evaluate(listOf(button), readings(normal + ("button.gate" to "2026-11-06T02:00:00+00:00")), 1_000).size)
    }

    @Test fun ringVolumeAndQuietHours() {
        val c = DoorbellConfig(ringVolume = 80, quietPercent = 25)
        assertEquals(0.8f, c.ringVolumeAt(12 * 60), 0.001f)
        assertEquals(0.2f, c.ringVolumeAt(23 * 60 + 10), 0.001f)
        assertTrue(c.isQuiet(3 * 60)); assertFalse(c.isQuiet(7 * 60))
    }

    @Test fun agoTexts() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 6, 14, 30, 0) }.timeInMillis
        assertEquals("только что", DoorbellModel.ago(now - 20_000, now))
        assertEquals("5 мин назад", DoorbellModel.ago(now - 5 * 60_000, now))
        assertEquals("в 12:30", DoorbellModel.ago(now - 2 * 3_600_000, now))
        assertEquals("вчера в 21:30", DoorbellModel.ago(now - 17 * 3_600_000, now))
    }

    /** Живое видео догоняет накопленное отставание и не дёргает скорость на границе (гистерезис). */
    @Test fun liveVideoCatchesUpWithHysteresis() {
        assertEquals("малый буфер — обычная скорость", 1f, LiveCatchUp.speedFor(200, 1f))
        assertEquals("на границе без ускорения — не ускоряемся", 1f, LiveCatchUp.speedFor(400, 1f))
        assertEquals("полоса над целью — 0,1 с", LiveCatchUp.SPEED, LiveCatchUp.speedFor(450, 1f))
        assertEquals("прежний порог (проверочный выключатель)", 1f, LiveCatchUp.speedFor(450, 1f, LiveCatchUp.WIDE_START_MS))
        assertEquals(LiveCatchUp.SPEED, LiveCatchUp.speedFor(800, 1f))
        assertEquals("большое отставание — быстрее", LiveCatchUp.FAR_SPEED, LiveCatchUp.speedFor(2_000, 1f))
        assertEquals("догоняем, пока не опустимся ниже цели", LiveCatchUp.SPEED, LiveCatchUp.speedFor(500, LiveCatchUp.FAR_SPEED))
        assertEquals("догнали — обычная скорость", 1f, LiveCatchUp.speedFor(250, LiveCatchUp.SPEED))
        // Плеер, который не опускается ниже 450 мс (ждёт кадры от декодера): пороги сдвигаются, цель — пол + 0,1 с.
        assertEquals(0L, LiveCatchUp.shiftFor(0)); assertEquals(0L, LiveCatchUp.shiftFor(150)); assertEquals(250L, LiveCatchUp.shiftFor(450))
        assertEquals("у пола не ускоряемся", 1f, LiveCatchUp.speedFor(640 - LiveCatchUp.shiftFor(450), 1f))
        assertEquals(LiveCatchUp.SPEED, LiveCatchUp.speedFor(800 - LiveCatchUp.shiftFor(450), 1f))
        assertEquals("остановились выше пола", 1f, LiveCatchUp.speedFor(540 - LiveCatchUp.shiftFor(450), LiveCatchUp.SPEED))
    }

    /** Замер «звук против картинки»: картинка старше на 700 мс, звук — на 300, звук приходит раньше видео на 70 мс. */
    @Test fun soundLeadIsVideoLagMinusSoundLagPlusEarlierArrival() {
        val lead = SoundLead()
        assertEquals("замеров мало", null, lead.summary())
        lead.add(700, 300, 70); lead.add(720, 300, 70); lead.add(650, 320, 70)
        assertEquals("раньше картинки на 470 мс (от 400 до 490; плюс — звук раньше)", lead.summary())
        lead.reset()
        lead.add(300, 500, null); lead.add(300, 520, null); lead.add(310, 500, null)
        assertEquals("позже картинки на 200 мс (от -220 до -190; плюс — звук раньше; без поправки на приход)", lead.summary())
    }

    /** Задержка камеры по RTCP: отчёт отправителя связывает метку RTP с часами камеры. */
    @Test fun arrivalDelayFromSenderReport() {
        var now = 1_795_000_000_000L
        val probe = ArrivalDelay(90_000) { now }
        // Отчёт: метка RTP 4 294 000 000 (у края 2^32) = момент now − 1000 мс по часам камеры.
        val wall = now - 1_000
        val sec = wall / 1000 + 2_208_988_800L
        val frac = (wall % 1000 shl 32) / 1000
        val sr = ByteArray(28)
        sr[0] = 0x80.toByte(); sr[1] = 200.toByte(); sr[3] = 6
        fun put(o: Int, v: Long) { for (i in 0..3) sr[o + i] = (v shr (24 - 8 * i)).toByte() }
        put(8, sec); put(12, frac); put(16, 4_294_000_000L)
        probe.senderReport(sr, sr.size)
        // Кадр снят через 500 мс после отчёта (метка перешла через 2^32), пришёл «сейчас» → задержка 500 мс.
        assertNull(probe.frame((4_294_000_000L + 45_000) and 0xFFFFFFFFL))
        now += ArrivalDelay.REPORT_MS
        val delay = probe.frame((4_294_000_000L + 45_000 + 90_000L * 60) and 0xFFFFFFFFL)!!
        assertTrue("задержка $delay", delay in 495..505)
    }

    @Test fun screenDelayIsHowMuchOlderTheShownFrameIsThanTheFreshestArrived() {
        // Край буфера 10,000 с; кадр 9,300 с показан 100 мс назад: в момент показа край был 9,900 → задержка 600 мс.
        assertEquals(600L, ScreenDelay.of(edgeMs = 10_000, ptsUs = 9_300_000, shownAtNs = 5_000_000_000L, nowNs = 5_100_000_000L))
        // Показ назначен на 20 мс вперёд — край к тому моменту уйдёт дальше.
        assertEquals(720L, ScreenDelay.of(edgeMs = 10_000, ptsUs = 9_300_000, shownAtNs = 5_020_000_000L, nowNs = 5_000_000_000L))
    }

    @Test fun screenDelaySummaryGivesMedianAndNinetiethPercentileAndSkipsNonsense() {
        val delay = ScreenDelay()
        org.junit.Assert.assertNull("мало замеров — нет итога", delay.also { it.add(300) }.summary())
        delay.reset()
        (1..20).forEach { delay.add(it * 100L) }
        delay.add(-2_000)   // смена потока
        delay.add(60_000)   // источник стоял
        assertEquals("медиана 1100 мс, 90 % — до 1900 мс, к концу 1700 мс", delay.summary())
        delay.reset()
        repeat(6) { delay.add(-100) }
        assertEquals("кадр чуть свежее края буфера — ноль", "медиана 0 мс, 90 % — до 0 мс", delay.summary())
    }

    @Test fun screenDelayKeepsOnlyRecentSamplesOfLongPlayback() {
        // Плитка показывает видео часами: итог — по последним замерам, старые вытесняются.
        val delay = ScreenDelay()
        repeat(ScreenDelay.LIMIT) { delay.add(900) }
        repeat(ScreenDelay.LIMIT) { delay.add(300) }
        assertEquals("медиана 300 мс, 90 % — до 300 мс, к концу 300 мс", delay.summary())
        repeat(8) { delay.add(500) }
        assertEquals("медиана 300 мс, 90 % — до 300 мс, к концу 500 мс", delay.summary())
    }

    @Test fun burstToLiveLastsExactlyAsLongAsBacklogNeeds() {
        // Запас убывает со скоростью «скорость рывка − 1»: с 2,4 с до 0,6 с при 4× — за 0,6 с.
        assertEquals(600L, LiveCatchUp.burstMs(bufferedMs = 2_400, toMs = 600))
        assertEquals("небольшое отставание снимает обычное ускорение", 0L, LiveCatchUp.burstMs(bufferedMs = 1_000, toMs = 600))
        assertEquals(0L, LiveCatchUp.burstMs(bufferedMs = 300, toMs = 600))
        assertEquals("рывок не бывает долгим", 1_500L, LiveCatchUp.burstMs(bufferedMs = 30_000, toMs = 600))
    }

    @Test fun streamWithLateKeyframesGetsMoreReserveThanEvenStream() {
        // Цель догоняющего режима — 300 мс плюс сдвиг. Субпоток: самый поздний кадр на 0,17 с позже обычного;
        // основной: на 0,37 с — ему нужен запас больше паузы на ключевом кадре.
        assertEquals(70L, LiveCatchUp.shiftFor(LiveCatchUp.floorFor(170)))
        assertEquals(270L, LiveCatchUp.shiftFor(LiveCatchUp.floorFor(370)))
        assertEquals("не измерено — как раньше", 0L, LiveCatchUp.shiftFor(LiveCatchUp.floorFor(0)))
        assertEquals("сбой связи — не неровность", 800L, LiveCatchUp.shiftFor(LiveCatchUp.floorFor(9_000)))
    }

    @Test fun onlyCompleteRtspAddressGoesToPlayer() {
        // Адрес вводят по букве: до узла плееру отдавать нечего (Media3 на таком адресе падает с ошибкой).
        listOf("", "r", "rtsp", "rtsp:", "rtsp:/", "rtsp://", "rtsp:///stream", "rtsp://user:pass@", "rtsp://:554/x",
            "http://10.0.0.5/x", "rtsp://bad host/x").forEach { assertEquals(it, "", DoorbellConfig.playableRtsp(it)) }
        listOf("rtsp://10.0.0.5", "rtsp://10.0.0.5:554/h264Preview_01_sub", "RTSP://cam.local/stream1",
            "rtsps://cam.local:322/s", "rtsp://user:p@ss@10.0.0.5:554/Streaming/Channels/101",
            "rtsp://10.0.0.5:554/cam/realmonitor?channel=1&subtype=0", "rtsp://[fe80::1]:554/x")
            .forEach { assertEquals(it, DoorbellConfig.playableRtsp(it)) }
    }
}
