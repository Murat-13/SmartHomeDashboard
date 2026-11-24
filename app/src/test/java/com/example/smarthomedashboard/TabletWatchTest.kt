package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.example.smarthomedashboard.TabletCheck.Item
import com.example.smarthomedashboard.TabletCheck.Level
import com.example.smarthomedashboard.TabletWatch.Battery
import com.example.smarthomedashboard.TabletWatch.Charge
import com.example.smarthomedashboard.TabletWatch.Heat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Заряд, нагрев, остывание, возврат после включения (docs/setup-tz.md, часть 4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TabletWatchTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var clock = 1_800_000_000_000L / 3_600_000 * 3_600_000
    private val minute = 60_000L

    @Before fun setUp() {
        TabletWatch.resetForTests()
        TabletWatch.prefs(context).edit().clear().commit()
        context.getSharedPreferences("tablet_settings", Context.MODE_PRIVATE).edit().clear().commit()
        TabletWatch.now = { clock }
        LightMode.resetForTests()
        LightMode.memory = { 4_000_000_000L to false }
        LightMode.load(context)
        BootReturn.resetForTests()
    }

    @After fun tearDown() { TabletWatch.resetForTests(); LightMode.resetForTests(); BootReturn.resetForTests() }

    /** [minutes] показаний раз в минуту. */
    private fun run(minutes: Int, b: (Int) -> Battery) = repeat(minutes) { TabletWatch.sample(b(it)); clock += minute }

    // ---- Заряд ----

    @Test fun limitIsSeenWhenChargeHoldsWithoutCharging() {
        run(90) { Battery(true, false, 79, 250) }
        assertEquals("часа с половиной мало", Charge.WATCHING, TabletWatch.charge().state)
        run(120) { Battery(true, false, if (it % 60 < 30) 79 else 80, 250) }
        val facts = TabletWatch.charge()
        assertEquals(Charge.LIMIT, facts.state)
        assertEquals(80, facts.held)
    }

    @Test fun chargingTabletIsNotMistakenForLimit() {
        // Заряжается с 40 до 100 %: ни часа покоя — не лимит.
        run(5 * 60) { Battery(true, true, (40 + it / 5).coerceAtMost(100), 300) }
        assertEquals(Charge.WATCHING, TabletWatch.charge().state)
        // «Заряжается», но шесть часов стоит на 80 % — так тоже выглядит лимит.
        TabletWatch.resetForTests(); TabletWatch.now = { clock }
        run(4 * 60) { Battery(true, true, 80, 300) }
        assertEquals(Charge.WATCHING, TabletWatch.charge().state)
        run(3 * 60) { Battery(true, true, 80, 300) }
        assertEquals(Charge.LIMIT, TabletWatch.charge().state)
    }

    @Test fun fullChargeForDayIsBadAndUnpluggedIsNot() {
        run(20 * 60) { Battery(true, false, 98, 300) }
        assertEquals("ещё не сутки", Charge.WATCHING, TabletWatch.charge().state)
        run(5 * 60) { Battery(true, false, if (it == 0) 97 else 100, 300) }
        assertEquals(Charge.HIGH, TabletWatch.charge().state)
        assertEquals(97, TabletWatch.charge().held)
        run(1) { Battery(false, false, 99, 300) }
        assertEquals(Charge.UNPLUGGED, TabletWatch.charge().state)
    }

    @Test fun unpluggedHoursAreNotLimit() {
        run(5 * 60) { Battery(it % 2 == 0, false, 60, 250) }
        TabletWatch.sample(Battery(true, false, 60, 250))
        assertEquals(Charge.WATCHING, TabletWatch.charge().state)
    }

    // ---- Нагрев ----

    @Test fun heatNeedsTimeAndCoolsWithMargin() {
        run(10) { Battery(true, false, 80, 410) }
        assertEquals("десять минут ещё не прошло", Heat.OK, TabletWatch.heatFacts().level)
        run(2) { Battery(true, false, 80, 410) }
        assertEquals(Heat.WARM, TabletWatch.heatFacts().level)
        assertTrue(LightMode.active)
        assertEquals("аккумулятор тёплый", LightMode.reason)
        assertFalse("HD не трогается", LightMode.hot)
        // 38 °C — ещё тепло: нужно на 3 °C ниже.
        run(30) { Battery(true, false, 80, 380) }
        assertEquals(Heat.WARM, TabletWatch.heatFacts().level)
        run(12) { Battery(true, false, 80, 370) }
        assertEquals(Heat.OK, TabletWatch.heatFacts().level)
        assertFalse(LightMode.active)
        assertEquals(410, TabletWatch.heatFacts().day)
    }

    @Test fun hotCoolsPanelEvenIfLightModeIsOffButWarmRespectsChoice() {
        LightMode.set(context, LightMode.Choice.OFF)
        run(12) { Battery(true, false, 80, 420) }
        assertEquals(Heat.WARM, TabletWatch.heatFacts().level)
        assertFalse("выключено руками", LightMode.active)
        run(4) { Battery(true, false, 80, 455) }
        assertEquals(Heat.HOT, TabletWatch.heatFacts().level)
        assertTrue(TabletWatch.hot && LightMode.active)
        run(8) { Battery(true, false, 80, 415) }
        assertTrue("десять минут прохлады", TabletWatch.hot)
        run(4) { Battery(true, false, 80, 415) }
        assertFalse(TabletWatch.hot)
        assertEquals(Heat.WARM, TabletWatch.heatFacts().level)
    }

    @Test fun shortSpikeAndGapDoNotHeat() {
        run(1) { Battery(true, false, 80, 460) }
        run(5) { Battery(true, false, 80, 300) }
        assertEquals(Heat.OK, TabletWatch.heatFacts().level)
        // Панели не было на экране час: прежние минуты не в счёт.
        run(8) { Battery(true, false, 80, 410) }
        clock += 60 * minute
        run(5) { Battery(true, false, 80, 410) }
        assertEquals(Heat.OK, TabletWatch.heatFacts().level)
        run(20) { Battery(true, false, 80, null) }
        assertEquals("нет показаний — уровень прежний", Heat.OK, TabletWatch.heatFacts().level)
    }

    @Test fun weekIsKeptAcrossRestartWithoutAddresses() {
        run(60) { Battery(true, false, 80, 333) }
        clock += 2 * 24 * 60 * minute
        TabletWatch.battery = { Battery(true, false, 79, 301) }
        TabletWatch.minute(context)
        val stored = TabletWatch.prefs(context).getString("hours", "")!!
        assertTrue(stored, Regex("[0-9,;-]+").matches(stored))
        TabletWatch.resetForTests(); TabletWatch.now = { clock }
        TabletWatch.load(context)
        TabletWatch.sample(Battery(true, false, 79, 302))
        assertEquals(302, TabletWatch.heatFacts().day)
        assertEquals("за неделю", 333, TabletWatch.heatFacts().week)
        clock += 8 * 24 * 60 * minute
        TabletWatch.sample(Battery(true, false, 79, 290))
        assertEquals("старше недели забыто", 290, TabletWatch.heatFacts().week)
    }

    // ---- Строка ----

    @Test fun drawerRowOnlyForWhatBlocksAndHolds() {
        fun items(vararg levels: Pair<String, Level>) = levels.map { (id, level) -> Item(id, id, level, "") }
        TabletWatch.hold(items("exits" to Level.BAD, "home" to Level.TODO))
        assertNull(TabletWatch.text())
        clock += 10 * minute
        TabletWatch.hold(items("exits" to Level.BAD, "home" to Level.TODO))
        assertEquals("Планшет: есть советы (1)", TabletWatch.text())
        TabletWatch.hold(items("home" to Level.TODO))
        assertNull("исправлено — строка гаснет", TabletWatch.text())
    }

    // ---- Остывание и OLED ----

    @Test fun coolingIsQuarterLowerAndShiftStaysNear() {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 200)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, PanelCooling.brightness(context, false))
        assertEquals(200 / 255f * 0.75f, PanelCooling.brightness(context, true), 0.001f)
        val seen = (0 until 40).map { PanelCooling.shift(it) }
        assertTrue(seen.all { (x, y) -> kotlin.math.abs(x) <= PanelCooling.RADIUS && kotlin.math.abs(y) <= PanelCooling.RADIUS })
        assertTrue("картинка ходит", seen.toSet().size >= 12)
    }

    // ---- Возврат после включения ----

    @Test fun firstPanelAfterPowerOnTellsWhoOpenedIt() {
        var boot = 16
        BootReturn.bootCount = { boot }
        BootReturn.sinceBootMs = { 47_300 }
        assertNull("первый запуск этой версии — сравнивать не с чем", BootReturn.panelShown(context))
        assertNull("то же включение", BootReturn.panelShown(context))
        boot++
        BootReturn.launched(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        BootReturn.launched(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)) // потом открыли ещё раз
        val result = BootReturn.panelShown(context)!!
        assertEquals(BootReturn.How.HOME, result.how)
        assertEquals(47, result.seconds)
        assertFalse("никто не проверял", result.tested)
        assertEquals(result, BootReturn.last(context))
        assertNull(BootReturn.panelShown(context))
    }

    @Test fun rebootTestReportsOnceAndForgetsAfterDay() {
        var boot = 5
        BootReturn.bootCount = { boot }
        BootReturn.startTest(context)
        assertTrue(BootReturn.pending(context))
        boot++
        BootReturn.sinceBootMs = { 400_000 }
        BootReturn.launched(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        val failed = BootReturn.panelShown(context)!!
        assertTrue(failed.tested)
        assertEquals("Проверка перезагрузкой: панель сама не открылась", BootReturn.text(failed))
        assertFalse(BootReturn.pending(context))
        BootReturn.startTest(context)
        clock += 25 * 60 * minute
        assertFalse("через сутки уже не ждём", BootReturn.pending(context))
        assertEquals("Проверка перезагрузкой: панель открылась сама за 47 с", BootReturn.text(BootReturn.Result(BootReturn.How.OVERLAY, 47, clock, true)))
    }

    @Test fun fallbackIsOffUntilAskedAndNeedsPermission() {
        assertFalse(BootReturn.overlayEnabled(context))
        val receiver = BootReturn.BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull("без разрешения ничего не открывается", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        try {
            receiver.onReceive(context, Intent("something.else"))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertNotNull(started)
            assertEquals(SplashActivity::class.java.name, started.component!!.className)
            BootReturn.launched(started)
            var boot = 1
            BootReturn.bootCount = { boot }
            BootReturn.panelShown(context)
            boot = 2
            assertEquals(BootReturn.How.OVERLAY, BootReturn.panelShown(context)!!.how)
        } finally { org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false) }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
