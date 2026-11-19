package com.example.smarthomedashboard

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

/** Охрана (docs/guard-tz.md): человек стоит у входа дольше порога — уведомление и ★; прохожий — нет. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoorbellGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private val tile = "door_guard_test"
    private val sent = mutableListOf<Map<String, Any>>()
    private var person = false
    private var mono = 0L
    private var wallNow = at(1, 0)

    private fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(2026, 10, 20, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Before fun setUp() {
        DoorbellGuard.detach()
        DoorbellGuard.seen = { person }
        DoorbellGuard.tiles = { listOf(tile to "Калитка") }
        DoorbellGuard.clock = { mono }
        DoorbellGuard.wall = { wallNow }
        DoorbellAway.sender = { _, _, data, done -> sent += data; done(true) }
        DoorbellAway.save(context, tile, DoorbellAway.Settings(notifyService = "notify.mobile_app_iphone"))
        DoorbellGuard.save(context, tile, DoorbellGuard.Settings(enabled = true, star = false))
        DoorbellGuard.attach(context)
    }

    @After fun tearDown() {
        DoorbellGuard.detach()
        DoorbellGuard.save(context, tile, DoorbellGuard.Settings())
        DoorbellAway.save(context, tile, DoorbellAway.Settings())
    }

    private fun run(seconds: Int, seen: Boolean) {
        person = seen
        repeat(seconds) { mono += 1_000; wallNow += 1_000; DoorbellGuard.tick() }
    }

    @Test fun passerByIsIgnoredStandingPersonAlertsOnce() {
        // Ночь (01:00, расписание 00–06): прошёл мимо за 20 с — тишина.
        run(20, true); run(5, false)
        assertTrue(sent.isEmpty())
        // Стоит дольше минуты — одна тревога, и только одна, пока стоит.
        run(200, false)
        run(61, true)
        assertEquals(1, sent.size)
        assertTrue((sent[0]["message"] as String).startsWith("У калитки человек больше минуты · "))
        assertEquals("doorbell_guard_$tile", (sent[0]["data"] as JSONObject).getString("tag"))
        run(120, true)
        assertEquals(1, sent.size)
        // Ушёл ненадолго (≤ 2 мин) и вернулся — не вторая тревога.
        run(60, false); run(70, true)
        assertEquals(1, sent.size)
        // Никого 2 минуты, снова стоит — вторая.
        run(130, false); run(61, true)
        assertEquals(2, sent.size)
    }

    @Test fun shortRecognitionGapDoesNotResetPresence() {
        run(40, true); run(10, false); run(25, true)
        assertEquals("перерыв 10 с не сбрасывает: 40 + 10 + 25 > 60", 1, sent.size)
        val p = DoorbellGuard.Presence()
        assertFalse(p.update(0, true, 60_000))
        assertFalse(p.update(30_000, true, 60_000))
        assertFalse("перерыв 21 с — отсчёт заново", p.update(51_000, true, 60_000))
        for (t in listOf(60_000L, 75_000L, 90_000L, 105_000L)) assertFalse(p.update(t, true, 60_000))
        assertTrue("60 с от 51-й секунды", p.update(111_000, true, 60_000))
    }

    @Test fun scheduleAcrossMidnightManualOnAndNightOff() {
        assertTrue(DoorbellGuard.inWindow(30, 23 * 60, 7 * 60))
        assertTrue(DoorbellGuard.inWindow(23 * 60 + 5, 23 * 60, 7 * 60))
        assertFalse(DoorbellGuard.inWindow(12 * 60, 23 * 60, 7 * 60))
        assertFalse(DoorbellGuard.inWindow(6 * 60, 0, 6 * 60))
        assertFalse("равные начало и конец — окна нет", DoorbellGuard.inWindow(0, 60, 60))
        val s = DoorbellGuard.Settings(enabled = true)
        assertTrue(DoorbellGuard.armed(s, at(1, 0)))
        assertFalse("днём выключена", DoorbellGuard.armed(s, at(12, 0)))
        assertFalse("выключена в редакторе", DoorbellGuard.armed(s.copy(enabled = false), at(1, 0)))
        assertTrue("кнопкой днём", DoorbellGuard.armed(s.copy(manualOn = true), at(12, 0)))
        // Кнопка ночью — выключить до конца ночи; следующей ночью снова по расписанию.
        DoorbellGuard.toggle(context, tile, at(1, 0))
        val off = DoorbellGuard.settings(context, tile)
        assertFalse(DoorbellGuard.armed(off, at(2, 0)))
        assertEquals(at(6, 0), off.offUntil)
        assertTrue(DoorbellGuard.armed(off, at(1, 0) + 86_400_000L))
        // Днём кнопкой — включить.
        DoorbellGuard.toggle(context, tile, at(12, 0))
        assertTrue(DoorbellGuard.armed(DoorbellGuard.settings(context, tile), at(12, 0)))
        assertEquals(30, DoorbellGuard.parseTime("0:30"))
        assertEquals(23 * 60 + 59, DoorbellGuard.parseTime("23.59"))
        assertNull(DoorbellGuard.parseTime("24:00"))
        assertEquals("05:07", DoorbellGuard.time(307))
        assertEquals("90 с", DoorbellGuard.duration(90))
        assertEquals("2 мин", DoorbellGuard.duration(120))
    }

    @Test fun disarmedDaytimeDoesNothing() {
        wallNow = at(12, 0)
        run(200, true)
        assertTrue(sent.isEmpty())
    }

    /** ★ — у закрытого ролика события, где отметка; ролик ещё не закрыт — отметка ждёт. */
    @Test fun starsClosedEventClip() {
        val root = tmp.newFolder("root")
        val camera = RecLayout.cameraKey(tile)
        val start = at(1, 0)
        val clip = File(RecLayout.dayDir(root, camera, start).apply { mkdirs() }, RecLayout.closedName(RecFile.Kind.EVENT, start, 90_000))
            .apply { writeBytes(ByteArray(10)) }
        val archive = RecArchive(context, roots = { listOf(root) })
        val left = DoorbellGuard.starFor(context, tile, listOf(start + 30_000, start + 600_000), start + 700_000, archive)
        assertEquals("отметка без ролика ждёт", listOf(start + 600_000), left)
        assertFalse(clip.exists())
        assertTrue(archive.load(camera).events.single().starred)
        assertTrue("старая отметка без ролика отбрасывается",
            DoorbellGuard.starFor(context, tile, listOf(start + 600_000), start + 3 * 3_600_000L, archive).isEmpty())
    }
}
