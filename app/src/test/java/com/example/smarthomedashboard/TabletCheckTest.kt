package com.example.smarthomedashboard

import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import com.example.smarthomedashboard.TabletCheck.Action
import com.example.smarthomedashboard.TabletCheck.Brand
import com.example.smarthomedashboard.TabletCheck.Exit
import com.example.smarthomedashboard.TabletCheck.Facts
import com.example.smarthomedashboard.TabletCheck.Level
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** «Проверка планшета» (docs/setup-tz.md, часть 4): правила на фактах и экран. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TabletCheckTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val good = Facts(now = now, sdk = 34, brand = Brand.SAMSUNG, exempt = true, restricted = false, bucket = 10, brandDone = true, exits = emptyList(),
        nightAt = now - 10 * hour, homeEnabled = true, homeDefault = true, boot = BootReturn.Result(BootReturn.How.HOME, 47, now - 5 * hour, true),
        secure = false, dream = false, charge = TabletWatch.ChargeFacts(TabletWatch.Charge.LIMIT, 79, 80), heat = TabletWatch.HeatFacts(TabletWatch.Heat.OK, 254, 284, 312),
        thermal = 0)

    @Before fun setUp() { TabletWatch.resetForTests(); TabletWatch.prefs(context).edit().clear().commit(); LightMode.resetForTests() }
    @After fun tearDown() { TabletWatch.resetForTests(); BootReturn.resetForTests(); LightMode.resetForTests() }

    private fun item(f: Facts, id: String) = TabletCheck.evaluate(f).single { it.id == id }

    @Test fun goodTabletHasNothingToDo() {
        val items = TabletCheck.evaluate(good)
        assertEquals(11, items.size)
        assertTrue(items.joinToString { "${it.id} ${it.level}" }, items.all { it.level == Level.OK })
        assertEquals(ModernUi.Status.OK to "Всё, что можно проверить, в порядке", TabletCheckView.summary(items))
        assertEquals("аккумулятор 25,4 °C, за сутки до 28,4 °C, за неделю до 31,2 °C", item(good, "heat").state)
        assertEquals("работает: держится 80 %", item(good, "charge").state)
        assertTrue(item(good, "reboot").state, item(good, "reboot").state.startsWith("открылась сама за 47 с"))
    }

    @Test fun whatBlocksComesFirstAndEveryAdviceHasSomethingToDo() {
        val bad = good.copy(exempt = false, restricted = true, exits = List(3) { now - it * hour to Exit.KILLED } + (now to Exit.CRASH),
            homeEnabled = false, homeDefault = false, secure = true, dream = true,
            boot = BootReturn.Result(BootReturn.How.HAND, 300, now - hour, true), charge = TabletWatch.ChargeFacts(TabletWatch.Charge.HIGH, 100, 97),
            heat = TabletWatch.HeatFacts(TabletWatch.Heat.HOT, 461, 470, 470))
        val items = TabletCheck.evaluate(bad)
        assertEquals(items.map { it.level.ordinal }, items.map { it.level.ordinal }.sorted())
        assertEquals(setOf("background", "exits", "reboot", "charge", "heat"), items.filter { it.level == Level.BAD }.map { it.id }.toSet())
        assertEquals(setOf("battery", "home", "lock", "dream"), items.filter { it.level == Level.TODO }.map { it.id }.toSet())
        items.filter { it.level == Level.BAD || it.level == Level.TODO }.forEach {
            assertTrue("${it.id}: совет есть", it.advice.isNotEmpty())
            assertTrue("${it.id}: кнопка есть", it.actions.isNotEmpty() || it.id == "heat")
        }
        assertEquals("Сделайте: 9 — что-то мешает уже сейчас", TabletCheckView.summary(items).second)
        assertTrue(item(bad, "exits").state, item(bad, "exits").state.startsWith("за 7 дней Android закрыл приложение 3 раза"))
        assertTrue(item(bad, "exits").state.endsWith("сбоев: 1"))
        assertTrue("мешает и код блокировки", item(bad, "reboot").advice.contains("PIN-код Android"))
        assertTrue(item(bad, "reboot").actions.contains(Action.OVERLAY))
    }

    @Test fun unknownIsNotPresentedAsProblem() {
        val items = TabletCheck.evaluate(Facts(now = now, sdk = 24))
        assertTrue(items.joinToString { "${it.id} ${it.level}" }, items.none { it.level == Level.BAD })
        assertNull("ограничения в фоне бывают с Android 9", items.firstOrNull { it.id == "background" })
        assertEquals(Level.UNKNOWN, items.single { it.id == "exits" }.level)
        assertEquals(Level.UNKNOWN, items.single { it.id == "charge" }.level)
        // Планшет без своих экранов автозапуска — строки нет.
        assertNull(TabletCheck.evaluate(good.copy(brand = Brand.GOOGLE)).firstOrNull { it.id == "brand" })
    }

    @Test fun onlyUnaskedClosingsCount() {
        val foreground = 100
        val cached = 400
        assertEquals(Exit.KILLED, TabletCheck.exitKind(ApplicationExitInfo.REASON_LOW_MEMORY, 0, foreground))
        assertNull("неиспользуемый фоновый процесс — обычное дело", TabletCheck.exitKind(ApplicationExitInfo.REASON_LOW_MEMORY, 0, cached))
        assertEquals(Exit.KILLED, TabletCheck.exitKind(ApplicationExitInfo.REASON_SIGNALED, 9, 125))
        assertNull(TabletCheck.exitKind(ApplicationExitInfo.REASON_SIGNALED, 11, 125))
        assertEquals(Exit.CRASH, TabletCheck.exitKind(ApplicationExitInfo.REASON_ANR, 0, foreground))
        // Настоящие записи московского планшета: обновление и остановка через adb.
        assertNull(TabletCheck.exitKind(16, 0, foreground))
        assertNull(TabletCheck.exitKind(ApplicationExitInfo.REASON_USER_REQUESTED, 0, foreground))
        // Старше недели — не в счёт; один раз — совет, но ещё не «мешает».
        assertEquals(Level.OK, item(good.copy(exits = listOf(now - 8 * 24 * hour to Exit.KILLED)), "exits").level)
        val once = item(good.copy(exits = listOf(now - hour to Exit.KILLED)), "exits")
        assertEquals(Level.TODO, once.level)
        assertTrue(once.state, once.state.contains("1 раз,"))
    }

    @Test fun rebootRowGuidesFromTestToFallback() {
        val never = item(good.copy(boot = null), "reboot")
        assertEquals(Level.UNKNOWN to listOf(Action.REBOOT_TEST), never.level to never.actions)
        val waiting = item(good.copy(boot = null, bootPending = true), "reboot")
        assertEquals(listOf(Action.REBOOT_CANCEL), waiting.actions)
        val natural = item(good.copy(boot = BootReturn.Result(BootReturn.How.HAND, 400, now - hour, false), homeDefault = false), "reboot")
        assertEquals("сама не открылась, но не проверяли", Level.TODO, natural.level)
        assertTrue(natural.advice.contains("главным экраном"))
        val failed = item(good.copy(boot = BootReturn.Result(BootReturn.How.HAND, 400, now - hour, true)), "reboot")
        assertEquals(Level.BAD, failed.level)
        assertTrue(failed.advice, failed.advice.contains("этого не хватило"))
        val spare = item(good.copy(boot = BootReturn.Result(BootReturn.How.OVERLAY, 130, now - hour, true), overlay = true, bootOpen = true), "reboot")
        assertEquals(Level.OK, spare.level)
        assertTrue(spare.state, spare.state.startsWith("открылась сама за 2 мин"))
        assertTrue(spare.actions.contains(Action.OVERLAY_OFF))
    }

    @Test fun brandsAndScreensOfAndroid() {
        assertEquals(Brand.SAMSUNG, TabletCheck.brandOf("samsung"))
        assertEquals(Brand.HUAWEI, TabletCheck.brandOf("HUAWEI"))
        assertEquals(Brand.HUAWEI, TabletCheck.brandOf("HONOR"))
        assertEquals(Brand.XIAOMI, TabletCheck.brandOf("Xiaomi", "Redmi"))
        assertEquals(Brand.LENOVO, TabletCheck.brandOf("LENOVO"))
        assertEquals(Brand.GOOGLE, TabletCheck.brandOf("Google"))
        assertEquals(Brand.OPPO, TabletCheck.brandOf("OnePlus"))
        assertEquals(Brand.OTHER, TabletCheck.brandOf("Teclast"))
        val samsung = TabletActions.brandIntents(Brand.SAMSUNG).single()
        assertEquals("com.samsung.android.lool", samsung.`package`)
        assertEquals("неспящие приложения", 2, samsung.getIntExtra("activity_type", -1))
        assertTrue(TabletActions.brandIntents(Brand.OTHER).isEmpty())
        assertEquals("com.samsung.android.sm.ACTION_BATTERY_PROTECTION", TabletActions.chargeIntents(Brand.SAMSUNG).first().action)
        assertTrue(TabletActions.chargeIntents(Brand.HUAWEI).isEmpty())
    }

    // ---- Экран «Планшет» ----

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(what: String, ready: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!ready()) { idle(); if (System.currentTimeMillis() > end) throw AssertionError("Не дождались: $what"); Thread.sleep(10) }
        idle()
    }
    private fun screen(): TabletActivity {
        val c = Robolectric.buildActivity(TabletActivity::class.java, Intent(context, TabletActivity::class.java))
        ReflectionHelpers.setField(c.get(), "pinGateAccess", PinAccess(object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        }, object : PinAccess.Legacy { override fun read(): String? = null; override fun remove() = true },
            clock = object : PinAccess.Clock { override fun elapsed() = 0L; override fun boot() = "test" }, worker = {}))
        c.setup()
        await("проверено") { c.get().view("tablet_check_home") != null }
        return c.get()
    }
    private fun TabletActivity.view(tag: String): View? = window.decorView.findViewWithTag(tag)
    private fun texts(v: View): String = when (v) {
        is TextView -> v.text.toString() + "\n"
        is ViewGroup -> (0 until v.childCount).joinToString("") { texts(v.getChildAt(it)) }
        else -> ""
    }

    @Test fun screenShowsChecksAndButtonsOnlyOpenAndroidOrRememberAnswer() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 3)
        val activity = screen()
        assertTrue(texts(activity.view("tablet_checks")!!).contains("Главный экран: обычный рабочий стол"))
        // «Сделал» запоминается, отметку можно снять.
        activity.view("tablet_action_BRAND_DONE")!!.performClick()
        await("отмечено") { texts(activity.view("tablet_checks")!!).contains("Автозапуск: отмечено как сделанное") }
        activity.view("tablet_action_BRAND_UNDO")!!.performClick()
        await("снято") { activity.view("tablet_action_BRAND_DONE") != null }
        // Экраны Android открываются, в настройках приложения ничего не меняется.
        activity.view("tablet_action_BATTERY_LIST")!!.performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertFalse(HomeScreenMode.isEnabled(context))
        activity.view("tablet_action_HOME")!!.performClick()
        assertEquals(Settings.ACTION_HOME_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertTrue("разрешена; главным экраном её выбирает человек в окне Android", HomeScreenMode.isEnabled(context))
        HomeScreenMode.setEnabled(context, false)
        // Проверка перезагрузкой: ждём, пока не отменят.
        activity.view("tablet_action_REBOOT_TEST")!!.performClick()
        await("ждёт") { activity.view("tablet_action_REBOOT_CANCEL") != null }
        assertTrue(BootReturn.pending(context))
        activity.view("tablet_action_REBOOT_CANCEL")!!.performClick()
        await("отменена") { activity.view("tablet_action_REBOOT_TEST") != null }
        assertFalse(BootReturn.pending(context))
        assertFalse("запасной способ сам не включается", BootReturn.overlayEnabled(context))
        // Экран OLED — по выбору.
        assertFalse(TabletWatch.oled(context))
        (activity.view("tablet_oled") as CompoundButton).isChecked = true
        assertTrue(TabletWatch.oled(context))
    }

    /** Найдено на эмуляторе: с Android 11 чужой рабочий стол приложению не виден — поиск главного экрана находил саму панель. */
    @Test fun homeScreenIsJudgedByRoleNotByVisibleApps() {
        HomeScreenMode.setEnabled(context, true)
        try {
            val roles = shadowOf(context.getSystemService(android.app.role.RoleManager::class.java))
            roles.addAvailableRole(android.app.role.RoleManager.ROLE_HOME)
            assertFalse("разрешена, но выбран другой рабочий стол", HomeScreenMode.isDefaultHome(context))
            assertEquals("осталось выбрать SmartHomeDashboard", TabletCheck.evaluate(TabletCheck.read(context)).single { it.id == "home" }.state)
            roles.addHeldRole(android.app.role.RoleManager.ROLE_HOME)
            assertTrue(HomeScreenMode.isDefaultHome(context))
        } finally { HomeScreenMode.setEnabled(context, false) }
    }

    @Test fun everyActionHasReadableButton() {
        val list = LinearLayout(Robolectric.buildActivity(android.app.Activity::class.java).also { it.get().setTheme(R.style.Theme_SmartHomeDashboard_Modern) }.setup().get())
        list.orientation = LinearLayout.VERTICAL
        val pressed = mutableListOf<Action>()
        TabletCheckView.render(list, TabletCheck.evaluate(good.copy(exempt = false, brandDone = false, homeDefault = false, secure = true, dream = true, boot = null))) { pressed += it }
        fun click(v: View) { if (v.tag is String && (v.tag as String).startsWith("tablet_action_")) v.performClick(); if (v is ViewGroup) (0 until v.childCount).forEach { click(v.getChildAt(it)) } }
        click(list)
        assertEquals(listOf(Action.BATTERY_LIST, Action.HOME, Action.LOCK, Action.DREAM, Action.BRAND, Action.BRAND_DONE, Action.REBOOT_TEST), pressed)
    }
}
