package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * «Проверка планшета» (docs/setup-tz.md, часть 4): что мешает панели работать на стене круглосуточно и что с этим сделать.
 * Факты читаются с планшета ([read]), правила — отдельно ([evaluate]), без Android. Здесь ничего не меняется: кнопки только
 * открывают нужные экраны Android ([TabletActions]).
 */
internal object TabletCheck {
    /** [OK] — в порядке, [TODO] — сделайте, [BAD] — мешает уже сейчас, [UNKNOWN] — нельзя проверить или ещё рано. */
    enum class Level { BAD, TODO, UNKNOWN, OK }

    enum class Action(val label: String) {
        BATTERY_LIST("Открыть список исключений"),
        APP("Открыть настройки приложения"),
        BRAND("Открыть"),
        BRAND_DONE("Сделал"),
        BRAND_UNDO("Снять отметку"),
        HOME("Сделать главным экраном"),
        REBOOT_TEST("Проверить перезагрузкой"),
        REBOOT_CANCEL("Отменить проверку"),
        OVERLAY("Запасной способ: поверх других окон"),
        OVERLAY_OFF("Выключить запасной способ"),
        LOCK("Открыть настройки блокировки"),
        DREAM("Открыть настройки заставки"),
        CHARGE("Открыть настройки батареи"),
    }

    /** [state] — коротко, рядом с названием; [advice] — что сделать. */
    class Item(val id: String, val title: String, val level: Level, val state: String, val advice: String = "",
               val actions: List<Action> = emptyList())

    /** Почему процесс закончился раньше времени: Android закрыл работающее приложение или сбой. */
    enum class Exit { KILLED, CRASH }

    enum class Brand { SAMSUNG, HUAWEI, XIAOMI, LENOVO, GOOGLE, OPPO, OTHER }

    data class Facts(
        val now: Long = 0,
        val sdk: Int = 34,
        val brand: Brand = Brand.OTHER,
        val exempt: Boolean? = null,
        val restricted: Boolean? = null,
        val bucket: Int? = null,
        val brandDone: Boolean = false,
        /** Закрытия за неделю: момент и причина; null — Android до 11 не сообщает. */
        val exits: List<Pair<Long, Exit>>? = null,
        val nightAt: Long = 0,
        val homeEnabled: Boolean = false,
        val homeDefault: Boolean = false,
        val boot: BootReturn.Result? = null,
        val bootPending: Boolean = false,
        val overlay: Boolean = false,
        val bootOpen: Boolean = false,
        val secure: Boolean? = null,
        val dream: Boolean? = null,
        val dreamAt: Long = 0,
        val charge: TabletWatch.ChargeFacts = TabletWatch.ChargeFacts(TabletWatch.Charge.UNKNOWN),
        val heat: TabletWatch.HeatFacts = TabletWatch.HeatFacts(),
        val thermal: Int? = null,
    )

    const val WEEK_MS = 7 * 24 * 3_600_000L
    /** Столько закрытий за неделю — уже мешает. */
    private const val KILLS_BAD = 2

    // ---- Правила ----

    fun evaluate(f: Facts): List<Item> = listOfNotNull(battery(f), background(f), brand(f), exits(f), night(f), home(f), reboot(f), lock(f),
        dream(f), charge(f), heat(f)).sortedBy { it.level.ordinal }

    private fun battery(f: Facts) = when (f.exempt) {
        true -> Item("battery", "Экономия батареи", Level.OK, "приложение в исключениях")
        false -> Item("battery", "Экономия батареи", Level.TODO, "приложение не в исключениях",
            "Android может откладывать ночные копии и мешать записи. Найдите в списке SmartHomeDashboard и выберите «Без ограничений» " +
                "(до Android 12 — «Не экономить»).", listOf(Action.BATTERY_LIST))
        null -> Item("battery", "Экономия батареи", Level.UNKNOWN, "Android не сообщает")
    }

    private fun background(f: Facts): Item? {
        if (f.sdk < Build.VERSION_CODES.P) return null
        val restricted = f.restricted == true || f.bucket == BUCKET_RESTRICTED
        return when {
            restricted -> Item("background", "Работа в фоне", Level.BAD, "Android ограничил приложение",
                "Запись с камер и ночные копии могут останавливаться. В настройках приложения откройте «Батарея» и разрешите работу без ограничений.",
                listOf(Action.APP))
            f.restricted == null && f.bucket == null -> Item("background", "Работа в фоне", Level.UNKNOWN, "Android не сообщает")
            else -> Item("background", "Работа в фоне", Level.OK, "без ограничений")
        }
    }

    private fun brand(f: Facts): Item? {
        val text = BRAND_TEXTS[f.brand] ?: return null
        return if (f.brandDone) Item("brand", "Автозапуск", Level.OK, "отмечено как сделанное", "", listOf(Action.BRAND_UNDO))
        else Item("brand", "Автозапуск", Level.UNKNOWN, "проверить нельзя — сделайте сами", text, listOf(Action.BRAND, Action.BRAND_DONE))
    }

    private fun exits(f: Facts): Item {
        val all = f.exits ?: return Item("exits", "Закрытия приложения", Level.UNKNOWN, "Android до 11 не сообщает")
        val week = all.filter { f.now - it.first in 0..WEEK_MS }
        val killed = week.filter { it.second == Exit.KILLED }
        val crashed = week.count { it.second == Exit.CRASH }
        val crashes = if (crashed > 0) ", сбоев: $crashed" else ""
        if (killed.isEmpty()) return Item("exits", "Закрытия приложения", Level.OK, "за 7 дней Android не закрывал$crashes")
        val lastText = AutoBackup.whenText(killed.maxOf { it.first }, f.now)
        return Item("exits", "Закрытия приложения", if (killed.size >= KILLS_BAD) Level.BAD else Level.TODO,
            "за 7 дней Android закрыл приложение ${killed.size} раз${timesEnd(killed.size)}, последний — $lastText$crashes",
            "Сделайте пункты «Экономия батареи» и «Автозапуск». Если планшету не хватает памяти — закройте лишние приложения.",
            listOf(Action.APP))
    }

    private fun night(f: Facts) = when {
        f.nightAt <= 0 -> Item("night", "Ночные задачи", Level.UNKNOWN, "ещё не было: первая — этой ночью")
        f.now - f.nightAt <= AutoBackup.STALE_MS -> Item("night", "Ночные задачи", Level.OK, "последняя — ${AutoBackup.whenText(f.nightAt, f.now)}")
        else -> Item("night", "Ночные задачи", Level.TODO, "не было с ${AutoBackup.whenText(f.nightAt, f.now)}",
            "Android откладывает копии настроек. Сделайте пункты «Экономия батареи» и «Автозапуск».", listOf(Action.BATTERY_LIST))
    }

    private fun home(f: Facts) = when {
        f.homeEnabled && f.homeDefault -> Item("home", "Главный экран", Level.OK, "панель")
        else -> Item("home", "Главный экран", Level.TODO, if (f.homeEnabled) "осталось выбрать SmartHomeDashboard" else "обычный рабочий стол",
            "Если панель — главный экран, Android сам открывает её после включения планшета, по кнопке «Домой» и после сбоя. Вернуть " +
                "рабочий стол можно в Настройках → «Устройство».", listOf(Action.HOME))
    }

    private fun reboot(f: Facts): Item {
        val fallback = if (f.bootOpen && f.overlay) listOf(Action.OVERLAY_OFF) else listOf(Action.OVERLAY)
        val spare = if (f.bootOpen && f.overlay) " Запасной способ включён." else ""
        if (f.bootPending) return Item("reboot", "Возврат после перезагрузки", Level.UNKNOWN, "жду перезагрузку",
            "Перезагрузите планшет: удерживайте кнопку питания → «Перезагрузка». Потом ничего не касайтесь — панель сама скажет, за сколько открылась.",
            listOf(Action.REBOOT_CANCEL))
        val r = f.boot ?: return Item("reboot", "Возврат после перезагрузки", Level.UNKNOWN, "ещё не проверялся",
            "Так видно, вернётся ли панель сама после отключения света.$spare", listOf(Action.REBOOT_TEST) + (if (spare.isEmpty()) emptyList() else fallback))
        val date = AutoBackup.whenText(r.at, f.now)
        return when (r.how) {
            BootReturn.How.HOME, BootReturn.How.OVERLAY -> Item("reboot", "Возврат после перезагрузки", Level.OK,
                "открылась сама за ${duration(r.seconds)} ($date)", spare.trim(),
                listOf(Action.REBOOT_TEST) + (if (spare.isEmpty()) emptyList() else fallback))
            BootReturn.How.HAND -> Item("reboot", "Возврат после перезагрузки", if (r.tested) Level.BAD else Level.TODO,
                if (r.tested) "сама не открылась ($date)" else "в прошлый раз панель открыли руками ($date)",
                (if (f.homeDefault) "Панель — главный экран, но этого не хватило. Включите запасной способ и проверьте ещё раз."
                else "Сначала сделайте панель главным экраном (пункт «Главный экран») и проверьте ещё раз. Не поможет — запасной способ.") +
                    (if (f.secure == true) " Мешает и PIN-код Android: см. «Блокировка экрана»." else "") + spare,
                listOf(Action.REBOOT_TEST) + fallback)
        }
    }

    private fun lock(f: Facts) = when (f.secure) {
        true -> Item("lock", "Блокировка экрана", Level.TODO, "стоит PIN-код или пароль Android",
            "После включения планшет остановится на вводе кода — панель не откроется сама. Если планшет висит дома, выберите блокировку «Нет» или " +
                "«Провести по экрану»; настройки панели защищает её собственный PIN.", listOf(Action.LOCK))
        false -> Item("lock", "Блокировка экрана", Level.OK, "без кода")
        null -> Item("lock", "Блокировка экрана", Level.UNKNOWN, "Android не сообщает")
    }

    private fun dream(f: Facts): Item {
        val daily = if (f.brand == Brand.SAMSUNG) " У Samsung ещё есть Daily Board: поиск в настройках «Daily Board» → выключите автозапуск." else ""
        val seen = f.dreamAt > 0 && f.now - f.dreamAt in 0..WEEK_MS
        return when {
            seen -> Item("dream", "Заставка", Level.BAD, "закрыла панель ${AutoBackup.whenText(f.dreamAt, f.now)}",
                "Выключите заставку Android, чтобы она не закрывала панель.$daily", listOf(Action.DREAM))
            f.dream == true -> Item("dream", "Заставка", Level.TODO, "включена в Android",
                "Пока панель на экране, заставка обычно не появляется, но на док-станции может закрыть её. Лучше выключить.$daily", listOf(Action.DREAM))
            f.dream == false -> Item("dream", "Заставка", Level.OK, "выключена")
            else -> Item("dream", "Заставка", Level.UNKNOWN, "Android не сообщает")
        }
    }

    private fun charge(f: Facts): Item {
        val path = CHARGE_TEXTS[f.brand] ?: CHARGE_COMMON
        return when (f.charge.state) {
            TabletWatch.Charge.LIMIT -> Item("charge", "Лимит заряда", Level.OK, "работает: держится ${f.charge.held} %")
            TabletWatch.Charge.HIGH -> Item("charge", "Лимит заряда", Level.BAD, "больше суток не ниже ${f.charge.held} %",
                "Постоянно полный заряд быстрее старит аккумулятор, он может вздуться. $path", listOf(Action.CHARGE))
            TabletWatch.Charge.UNPLUGGED -> Item("charge", "Лимит заряда", Level.UNKNOWN, "планшет не на зарядке (${f.charge.level} %)")
            TabletWatch.Charge.WATCHING -> Item("charge", "Лимит заряда", Level.UNKNOWN, "наблюдаю, сейчас ${f.charge.level} %",
                "Узнать, включён ли лимит, можно только наблюдением — несколько часов на зарядке. $path", listOf(Action.CHARGE))
            TabletWatch.Charge.UNKNOWN -> Item("charge", "Лимит заряда", Level.UNKNOWN, "Android не сообщает")
        }
    }

    @SuppressLint("InlinedApi") // константы нагрева встраиваются при сборке
    private fun heat(f: Facts): Item {
        val h = f.heat
        val android = when {
            f.thermal == null -> ""
            f.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "; Android замедляет планшет"
            f.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "; Android: заметно нагрет"
            else -> ""
        }
        val value = h.now?.let { "аккумулятор ${degrees(it)}" + (h.day?.let { d -> ", за сутки до ${degrees(d)}" } ?: "") +
            (h.week?.let { w -> ", за неделю до ${degrees(w)}" } ?: "") } ?: "температуру аккумулятора Android не сообщает"
        val advice = "Уберите солнце и закрытую рамку, дайте воздух сзади; зарядка — обычная, не быстрая."
        return when {
            h.level == TabletWatch.Heat.HOT -> Item("heat", "Нагрев", Level.BAD, "$value$android — слишком горячо", "$advice Панель сама остывает.")
            h.level == TabletWatch.Heat.WARM -> Item("heat", "Нагрев", Level.TODO, "$value$android — тепло", "$advice Пока тепло — облегчённый режим.")
            f.thermal != null && f.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Item("heat", "Нагрев", Level.BAD, "$value$android", advice)
            f.thermal != null && f.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Item("heat", "Нагрев", Level.TODO, "$value$android", advice)
            h.now == null && f.thermal == null -> Item("heat", "Нагрев", Level.UNKNOWN, value)
            else -> Item("heat", "Нагрев", Level.OK, value)
        }
    }

    fun degrees(tenths: Int) = String.format(Locale.US, "%.1f", tenths / 10.0).replace('.', ',') + " °C"

    fun duration(seconds: Int) = if (seconds < 120) "$seconds с" else "${seconds / 60} мин"

    /** «2 раза», «5 раз», «22 раза». */
    private fun timesEnd(n: Int) = if (n % 10 in 2..4 && n % 100 !in 12..14) "а" else ""

    /** Автозапуск по маркам — то, что подтвердили страницы марок (docs/setup-tz.md, «Источники»). */
    private val BRAND_TEXTS = mapOf(
        Brand.SAMSUNG to "Samsung усыпляет приложения, которыми не пользуются. В списке «Не уходят в сон автоматически» нажмите «+» и добавьте " +
            "SmartHomeDashboard.",
        Brand.HUAWEI to "Huawei: в поиске настроек найдите «Запуск приложений», у SmartHomeDashboard выключите автоматическое управление и разрешите " +
            "автозапуск и работу в фоне. Ещё: Батарея → «Сохранять подключение во время сна».",
        Brand.XIAOMI to "Xiaomi: в настройках приложения включите «Автозапуск», в «Контроле активности» (экономия батареи) выберите «Нет ограничений».",
        Brand.LENOVO to "Lenovo: в настройках батареи найдите управление фоновыми приложениями и разрешите SmartHomeDashboard. Часть моделей после " +
            "перезагрузки сбрасывает выбор главного экрана — проверьте перезагрузкой.",
        Brand.OPPO to "В настройках приложения откройте «Батарея» и разрешите автозапуск и работу в фоне.",
        Brand.OTHER to "Если у марки есть свой список автозапуска или «спящих» приложений, разрешите в нём SmartHomeDashboard.",
    )

    private const val CHARGE_COMMON = "Поищите в настройках батареи «защиту» или «лимит заряда»; если её нет — не держите планшет в жаре и заряжайте " +
        "не быстрой зарядкой."
    private val CHARGE_TEXTS = mapOf(
        Brand.SAMSUNG to "Samsung: Настройки → Батарея → «Защита аккумулятора» → «Максимальная», 80 %.",
        Brand.HUAWEI to "Huawei: Настройки → Батарея → «Состояние батареи» → предел зарядки (HarmonyOS 4.3.1 и новее), 70–80 %.",
        Brand.XIAOMI to "Xiaomi: Настройки → Батарея → «Защита батареи», 80 %. На части моделей её нет.",
        Brand.LENOVO to "Lenovo: Настройки → Батарея → режим защиты батареи. На части моделей его нет.",
        Brand.GOOGLE to "Pixel Tablet: на док-станции планшет сам держит 90 %, после нескольких дней — 70–80 %.",
    )

    // ---- Факты ----

    fun brandOf(manufacturer: String, brand: String = ""): Brand {
        val text = "$manufacturer $brand".lowercase(Locale.ROOT)
        return when {
            "samsung" in text -> Brand.SAMSUNG
            "huawei" in text || "honor" in text -> Brand.HUAWEI
            "xiaomi" in text || "redmi" in text || "poco" in text -> Brand.XIAOMI
            "lenovo" in text -> Brand.LENOVO
            "google" in text -> Brand.GOOGLE
            "oppo" in text || "oneplus" in text || "realme" in text -> Brand.OPPO
            else -> Brand.OTHER
        }
    }

    /** Факты с планшета — рабочий поток. */
    fun read(context: Context): Facts {
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        val manager = app.getSystemService(ActivityManager::class.java)
        val prefs = TabletWatch.prefs(app)
        TabletWatch.refresh(app)
        return Facts(
            now = TabletWatch.now(),
            sdk = Build.VERSION.SDK_INT,
            brand = brandOf(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()),
            exempt = safe { power?.isIgnoringBatteryOptimizations(app.packageName) },
            restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) safe { manager?.isBackgroundRestricted } else null,
            bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) safe { app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket } else null,
            brandDone = prefs.getBoolean(KEY_BRAND_DONE, false),
            exits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) safe { readExits(app, manager) } else null,
            nightAt = app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE).getLong(AutoBackup.PREF_LAST_CHECK, 0),
            homeEnabled = safe { HomeScreenMode.isEnabled(app) } == true,
            homeDefault = safe { HomeScreenMode.isDefaultHome(app) } == true,
            boot = BootReturn.last(app),
            bootPending = BootReturn.pending(app),
            overlay = safe { Settings.canDrawOverlays(app) } == true,
            bootOpen = safe { BootReturn.overlayEnabled(app) } == true,
            secure = safe { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure },
            dream = safe { Settings.Secure.getInt(app.contentResolver, "screensaver_enabled", 0) == 1 },
            dreamAt = TabletWatch.dreamAt(app),
            charge = TabletWatch.charge(),
            heat = TabletWatch.heatFacts(),
            thermal = DeviceFacts.thermal(app),
        )
    }

    private fun <T> safe(block: () -> T?): T? = try { block() } catch (_: RuntimeException) { null }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExits(app: Context, manager: ActivityManager?): List<Pair<Long, Exit>>? =
        manager?.getHistoricalProcessExitReasons(app.packageName, 0, 0)?.filter { it.processName == app.packageName }
            ?.mapNotNull { info -> exitKind(info.reason, info.status, info.importance)?.let { info.timestamp to it } }

    /**
     * Считается только то, чего панель не просила: закрытие работающего приложения из-за памяти, нагрузки или «заморозки» и
     * сбои. Обновление, остановка руками и закрытие неиспользуемого фонового процесса — обычное дело.
     */
    @SuppressLint("InlinedApi") // константы встраиваются при сборке; зовут только с Android 11
    internal fun exitKind(reason: Int, status: Int, importance: Int): Exit? = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Exit.CRASH
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, REASON_FREEZER,
        ApplicationExitInfo.REASON_OTHER -> Exit.KILLED.takeIf { importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE }
        ApplicationExitInfo.REASON_SIGNALED -> Exit.KILLED.takeIf { status == SIGKILL && importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE }
        else -> null
    }

    internal const val KEY_BRAND_DONE = "brand_done"
    private const val BUCKET_RESTRICTED = 45
    private const val REASON_FREEZER = 14
    private const val SIGKILL = 9
}
