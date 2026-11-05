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
}
