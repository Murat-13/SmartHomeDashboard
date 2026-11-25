package com.example.smarthomedashboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Сбои на планшете, безопасный запуск и отчёт (docs/reliability.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() { CrashLog.clear(context); SafeStart.resetForTests(context); CrashLog.now = System::currentTimeMillis }
    @After fun tearDown() { CrashLog.clear(context); SafeStart.resetForTests(context); CrashLog.now = System::currentTimeMillis }

    // ---- Запись ----

    @Test fun crashIsWrittenAndHandedToAndroid() {
        var passed: Throwable? = null
        val error = IllegalStateException("плитка", NullPointerException("x"))
        CrashLog.handle(context, Thread.currentThread(), error) { _, e -> passed = e }
        assertTrue("Android получает сбой как раньше", passed === error)
        val entry = CrashLog.entries(context).single()
        assertEquals(CrashLog.Kind.JAVA, entry.kind)
        assertEquals("IllegalStateException", entry.summary)
        assertTrue(entry.text, entry.text.contains("at com.example.smarthomedashboard.CrashLogTest") && entry.text.contains("Caused by: java.lang.NullPointerException"))
        assertTrue(entry.text, entry.text.contains("Версия ") && entry.text.contains("Android 14"))
    }

    @Test fun handlerNeverThrowsItself() {
        // Записать некуда: папка сбоев — файл.
        java.io.File(context.filesDir, "crashes").apply { deleteRecursively(); writeText("не папка") }
        var passed = false
        CrashLog.handle(context, Thread.currentThread(), RuntimeException("x")) { _, _ -> passed = true }
        assertTrue(passed)
        java.io.File(context.filesDir, "crashes").delete()
    }

    @Test fun onlyLastTenAreKept() {
        for (i in 1..14) CrashLog.write(context, CrashLog.Kind.JAVA, 1_700_000_000_000L + i, "сбой $i")
        val all = CrashLog.entries(context)
        assertEquals(CrashLog.KEEP, all.size)
        assertTrue(all.first().text.contains("сбой 14") && all.last().text.contains("сбой 5"))
    }

    @Test fun nothingAboutTheHouseIsWritten() {
        val secret = listOf("rtsp://admin:secret@192.168.1.50:554/live", "http://home.example.com:8123/api?token=abc", "192.168.1.50", "10.0.0.7:8123",
            "fe80::1ff:fe23:4567:890a", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", "owner@example.com", "light.kitchen_ceiling", "camera.front_gate", "my-house.duckdns.org", "\"Кухня Мурата\"", "{\"password\":\"qwerty\"}")
        for (s in secret) {
            val clean = CrashLog.sanitize("java.io.IOException: failed for $s at once")
            for (part in listOf("secret", "192.168", "10.0.0", "example.com", "fe80", "eyJ", "owner@", "kitchen", "front_gate", "duckdns", "Мурат", "qwerty")) if (part in s) assertFalse("$s → $clean", part in clean)
        }
        // Полезное остаётся.
        val keep = "java.lang.NullPointerException: Attempt to invoke virtual method void com.example.smarthomedashboard.TileManager.save() on a null object reference"
        assertEquals(keep, CrashLog.sanitize(keep))
        assertEquals("java.net.UnknownHostException: Unable to resolve host \"…\"", CrashLog.sanitize("java.net.UnknownHostException: Unable to resolve host \"ha.local\""))
        assertEquals("версия 0.9.66 в 12:34:56", CrashLog.sanitize("версия 0.9.66 в 12:34:56"))
        val trace = CrashLog.trace(java.io.IOException("нет связи с 192.168.1.50", IllegalStateException("http://ha.local:8123")))
        assertFalse(trace, "192.168" in trace || "ha.local" in trace)
        assertTrue("строки стека — целиком", trace.lines().any { it.trim().startsWith("at com.example.smarthomedashboard.CrashLogTest.nothingAboutTheHouseIsWritten") })
    }

    @Test fun onlyMainThreadOfAnrIsKept() {
        val lines = listOf("----- pid 1 -----", "\"Signal Catcher\" daemon", "  at x", "", "\"main\" prio=5 tid=1 Blocked", "  at com.example.smarthomedashboard.MainActivity.onCreate(MainActivity.kt:1)",
            "  - waiting to lock <0x0> held by thread 12", "", "\"other\"", "  at y")
        val text = CrashLog.mainThread(lines)
        assertTrue(text, text.contains("MainActivity.onCreate") && !text.contains("at y") && !text.contains("at x"))
        assertEquals("", CrashLog.mainThread(listOf("ничего")))
        assertEquals(CrashLog.Kind.ANR, CrashLog.kindOf(android.app.ApplicationExitInfo.REASON_ANR, 0, 100))
        assertEquals(CrashLog.Kind.NATIVE, CrashLog.kindOf(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 0, 100))
        assertEquals("память — только у работающего", CrashLog.Kind.MEMORY, CrashLog.kindOf(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 0, 100))
        assertNull("фоновое закрытие — обычное дело", CrashLog.kindOf(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 0, 400))
        assertNull(CrashLog.kindOf(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, 0, 100))
    }

    // ---- Безопасный запуск ----

    private var t = 10_000_000L
    /** Запуск процесса и сбой через [afterMs]. */
    private fun crashAfter(afterMs: Long): SafeStart.Mode {
        SafeStart.now = { t }
        SafeStart.processStarted(context)
        val mode = SafeStart.mode
        t += afterMs
        SafeStart.crashed(context)
        t += 5_000
        return mode
    }
    private fun start(): SafeStart.Mode { SafeStart.now = { t }; SafeStart.processStarted(context); return SafeStart.mode }

    @Test fun threeQuickCrashesOpenPanelSafely() {
        assertEquals(listOf(SafeStart.Mode.NORMAL, SafeStart.Mode.NORMAL, SafeStart.Mode.NORMAL), List(3) { crashAfter(4_000) })
        assertEquals(SafeStart.Mode.SAFE, start())
        assertTrue(SafeStart.active)
        // И в безопасном падает — экран с выходом.
        t += 3_000; SafeStart.crashed(context); t += 5_000
        assertEquals(SafeStart.Mode.SAFE, crashAfter(3_000))
        assertEquals(SafeStart.Mode.RESCUE, start())
        // Панель пробует открыться с него безопасно, человек — обычно.
        SafeStart.enterSafe(); assertEquals(SafeStart.Mode.SAFE, SafeStart.mode)
        SafeStart.leave(context); assertFalse(SafeStart.active)
        assertEquals(SafeStart.Mode.NORMAL, start())
    }

    /**
     * Второй аудит 25.11.2026, AUD-17 (решение владельца): о безопасном запуске — одно уведомление на выбранный телефон; после
     * «Обычный запуск» и нового входа — снова одно. Телефон не выбран — ничего не отправляется.
     */
    @Test fun safeStartTellsChosenPhoneOnce() {
        val sent = mutableListOf<Pair<String, Map<String, Any>>>()
        var accept = true
        DoorbellAway.reset()
        DoorbellAway.sender = { d, s, data, done -> if (accept) sent += "$d.$s" to data; done(accept) }
        val tile = com.example.smarthomedashboard.data.TileEntity("door_safe", DoorbellConfig.TYPE, "grid", "Домофон", 0, 0, 1, 1, config = DoorbellConfig.newTileConfig())
        try {
            SafeStart.notifyPhone(context, listOf(tile))
            assertTrue("обычный запуск — молчит", sent.isEmpty())
            List(3) { crashAfter(4_000) }
            assertEquals(SafeStart.Mode.SAFE, start())
            SafeStart.notifyPhone(context, listOf(tile))
            assertTrue("телефон не выбран", sent.isEmpty())
            DoorbellAway.save(context, tile.id, DoorbellAway.Settings(notifyService = "notify.mobile_app_phone"))
            accept = false
            SafeStart.notifyPhone(context, listOf(tile))
            accept = true
            SafeStart.notifyPhone(context, listOf(tile))
            SafeStart.notifyPhone(context, listOf(tile))
            assertEquals("не принятое Home Assistant повторяется, принятое — одно", 1, sent.size)
            assertEquals("notify.mobile_app_phone", sent.single().first)
            assertEquals(SafeStart.PHONE_TEXT, sent.single().second["message"])
            // Перезапуск в том же безопасном режиме — не повторяется.
            t += 600_000; assertEquals(SafeStart.Mode.SAFE, start())
            SafeStart.notifyPhone(context, listOf(tile))
            assertEquals(1, sent.size)
            SafeStart.leave(context)
            List(3) { crashAfter(4_000) }
            assertEquals(SafeStart.Mode.SAFE, start())
            SafeStart.notifyPhone(context, listOf(tile))
            assertEquals("новый вход в безопасный запуск — новое уведомление", 2, sent.size)
        } finally {
            DoorbellAway.save(context, tile.id, DoorbellAway.Settings()); DoorbellAway.reset()
        }
    }

    @Test fun rareOrLateCrashesAreNotALoop() {
        // Сбой после двух минут работы — не «сразу после запуска».
        repeat(5) { crashAfter(SafeStart.SURVIVE_MS + 1_000) }
        assertEquals(SafeStart.Mode.NORMAL, start())
        // Три быстрых, но в разное время дня.
        repeat(3) { crashAfter(4_000); t += SafeStart.WINDOW_MS }
        assertEquals(SafeStart.Mode.NORMAL, start())
        // Прожила две минуты — прежние сбои забыты.
        crashAfter(4_000); crashAfter(4_000)
        start(); SafeStart.survived(context); t += 60_000
        crashAfter(4_000)
        assertEquals(SafeStart.Mode.NORMAL, start())
    }

    @Test fun nativeCrashesAndAnrCountThroughAndroid() {
        val exits = mutableListOf<Pair<Long, TabletCheck.Exit>>()
        SafeStart.exits = { exits.reversed() }
        repeat(3) { start(); t += 10_000; exits += t to TabletCheck.Exit.CRASH; t += 5_000 }
        assertEquals(SafeStart.Mode.SAFE, start())
        SafeStart.resetForTests(context)
        // Закрыли руками или обновили — не сбой.
        SafeStart.exits = { emptyList() }
        repeat(5) { start(); t += 10_000 }
        assertEquals(SafeStart.Mode.NORMAL, start())
    }

    @Test fun safeModeEndsByItselfAfterQuietHour() {
        repeat(3) { crashAfter(4_000) }
        assertEquals(SafeStart.Mode.SAFE, start())
        // Две минуты прожила, час без сбоев — следующий запуск обычный.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(SafeStart.SURVIVE_MS + 1_000))
        t += SafeStart.SURVIVE_MS
        assertEquals("пока не прошёл час", SafeStart.Mode.SAFE, SafeStart.mode)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(SafeStart.RECOVER_MS))
        t += SafeStart.RECOVER_MS
        assertEquals(SafeStart.Mode.NORMAL, start())
    }

    /** Аудит 25.11.2026, STAB-04: исправная панель сама не перезапускается — час без сбоев, и обычный режим можно включить сразу. */
    @Test fun quietHourAllowsNormalModeWithoutRestart() {
        repeat(3) { crashAfter(4_000) }
        assertEquals(SafeStart.Mode.SAFE, start())
        t += SafeStart.RECOVER_MS - 1_000
        assertFalse("часа ещё нет", SafeStart.recovered(context))
        t += 1_000
        assertTrue(SafeStart.recovered(context))
        SafeStart.leave(context, auto = true)
        assertEquals(SafeStart.Mode.NORMAL, SafeStart.mode)
        assertFalse(SafeStart.recovered(context))
    }

    /** Обычный режим опять привёл к сбоям — следующая попытка вдвое позже, а не каждый час без конца. */
    @Test fun repeatedCrashesAfterReturnMakeNextReturnLater() {
        repeat(3) { crashAfter(4_000) }
        start()
        for (hours in listOf(1, 2, 4, 8, 16, 16)) {
            t += hours * SafeStart.RECOVER_MS - 1_000
            assertFalse("раньше $hours ч", SafeStart.recovered(context))
            t += 1_000
            assertTrue("через $hours ч", SafeStart.recovered(context))
            SafeStart.leave(context, auto = true)
            t += 60_000
            repeat(3) { crashAfter(4_000) } // видео снова роняет панель
            assertEquals(SafeStart.Mode.SAFE, start())
        }
        // Человек включил сам — счёт заново; сбой через день после возврата — тоже не «опять».
        SafeStart.leave(context)
        repeat(3) { crashAfter(4_000) }; start()
        t += SafeStart.RECOVER_MS
        assertTrue(SafeStart.recovered(context))
        SafeStart.leave(context, auto = true)
        t += 24 * 3_600_000L
        repeat(3) { crashAfter(4_000) }; start()
        t += SafeStart.RECOVER_MS
        assertTrue(SafeStart.recovered(context))
    }

    @Test fun safeModeTurnsOffHeavyThings() {
        repeat(3) { crashAfter(4_000) }
        start()
        LightMode.refresh(context)
        assertEquals("безопасный запуск", LightMode.reason)
        assertFalse(DoorbellVideoView.ENABLED)
        SafeStart.leave(context)
        assertNull(LightMode.reason)
        LightMode.resetForTests()
    }

    @Test fun withoutOverlayPermissionNothingIsScheduledAfterCrash() {
        SafeStart.restartLater(context)
        assertNull(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).nextScheduledAlarm)
    }

    // ---- Экраны ----

    private fun texts(v: View): String = when (v) { is TextView -> v.text.toString() + "\n"; is ViewGroup -> (0 until v.childCount).joinToString("") { texts(v.getChildAt(it)) }; else -> "" }

    @Test fun rescueScreenOffersWaysOut() {
        CrashLog.write(context, CrashLog.Kind.JAVA, 1_700_000_000_000L, "Поток: main\n\njava.lang.IllegalStateException: x")
        val activity = Robolectric.buildActivity(SafeStartActivity::class.java).setup().get()
        val all = texts(activity.window.decorView)
        assertTrue(all, all.contains("Панель не запускается") && all.contains("IllegalStateException") && all.contains("Настройки и плитки на месте"))
        activity.window.decorView.findViewWithTag<View>("safe_settings").performClick()
        assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        activity.window.decorView.findViewWithTag<View>("safe_panel").performClick()
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
    }

    @Test fun reportHasNoNamesAndAddresses() {
        val tiles = com.example.smarthomedashboard.data.TileManager(context)
        tiles.saveTiles(listOf(com.example.smarthomedashboard.data.TileEntity("cam", DoorbellConfig.CAMERA_TYPE, "grid", "Двор Мурата", 0, 0, 1, 1,
            config = DoorbellConfig(rtspMain = "rtsp://192.168.1.50/h264Preview_01_main", rtspUser = "admin", camera = "camera.front_gate").writeTo("{}"))))
        val text = SupportReport.build(context)
        assertTrue(text, text.contains("камера, основной Reolink") && text.contains("Home Assistant да") && text.contains("camera_modern 1"))
        for (bad in listOf("192.168", "Мурат", "front_gate", "admin")) assertFalse("$bad в отчёте", bad in text)
        assertNotNull(text)
    }

    @Test fun supportScreenListsCrashes() {
        val empty = Robolectric.buildActivity(SupportActivity::class.java).setup().get()
        assertTrue(texts(empty.window.decorView).contains("Сбоев не было"))
        CrashLog.write(context, CrashLog.Kind.ANR, 1_700_000_000_000L, "main")
        val one = Robolectric.buildActivity(SupportActivity::class.java).setup().get()
        assertTrue(texts(one.window.decorView), texts(one.window.decorView).contains("приложение перестало отвечать"))
    }
}
