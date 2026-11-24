package com.example.smarthomedashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri

/** Кнопки «Проверки планшета»: открыть нужный экран Android или запомнить ответ человека. Сами настройки Android не меняются. */
internal object TabletActions {
    /** [changed] — перерисовать список. */
    fun run(activity: Activity, action: TabletCheck.Action, changed: () -> Unit) {
        when (action) {
            TabletCheck.Action.BATTERY_LIST -> open(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            TabletCheck.Action.APP -> open(activity, appDetails(activity))
            TabletCheck.Action.BRAND -> open(activity, brandIntents(TabletCheck.brandOf(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty())) +
                appDetails(activity))
            TabletCheck.Action.BRAND_DONE, TabletCheck.Action.BRAND_UNDO -> {
                TabletWatch.prefs(activity).edit { putBoolean(TabletCheck.KEY_BRAND_DONE, action == TabletCheck.Action.BRAND_DONE) }
                changed()
            }
            TabletCheck.Action.HOME -> {
                HomeScreenMode.setEnabled(activity, true)
                Toast.makeText(activity, "Выберите SmartHomeDashboard главным экраном", Toast.LENGTH_LONG).show()
                HomeScreenMode.openHomeSettings(activity)
            }
            TabletCheck.Action.REBOOT_TEST -> { BootReturn.startTest(activity); changed() }
            TabletCheck.Action.REBOOT_CANCEL -> { BootReturn.cancelTest(activity); changed() }
            TabletCheck.Action.OVERLAY -> overlay(activity, changed)
            TabletCheck.Action.OVERLAY_OFF -> { BootReturn.setOverlay(activity, false); changed() }
            TabletCheck.Action.LOCK -> open(activity, Intent(Settings.ACTION_SECURITY_SETTINGS))
            TabletCheck.Action.DREAM -> open(activity, Intent(Settings.ACTION_DREAM_SETTINGS))
            TabletCheck.Action.CHARGE -> open(activity, chargeIntents(TabletCheck.brandOf(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty())) +
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        }
    }

    private fun appDetails(activity: Activity) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())

    /** Запасной способ: разрешение «поверх других окон» даёт человек; панель открывается сама только после его «да». */
    private fun overlay(activity: Activity, changed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Запасной способ")
            .setMessage("Android откроет панель после включения планшета, если дать приложению разрешение «поверх других окон». Само приложение " +
                "поверх других окон ничего не показывает.\n\nЕсли переключатель серый (Android 15 и новее): Настройки → Приложения → " +
                "SmartHomeDashboard → ⋮ → «Разрешить ограниченные настройки», затем вернитесь сюда.")
            .setPositiveButton(if (Settings.canDrawOverlays(activity)) "Включить" else "Открыть разрешение") { _, _ ->
                BootReturn.setOverlay(activity, true)
                if (Settings.canDrawOverlays(activity)) changed()
                else open(activity, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri()))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Экраны автозапуска марок: у Samsung — официальный, у остальных — если откроется. */
    internal fun brandIntents(brand: TabletCheck.Brand): List<Intent> = when (brand) {
        TabletCheck.Brand.SAMSUNG -> listOf(Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
            .setPackage("com.samsung.android.lool").putExtra("activity_type", 2))
        TabletCheck.Brand.XIAOMI -> listOf(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        TabletCheck.Brand.HUAWEI -> listOf(Intent().setClassName("com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        else -> emptyList()
    }

    private fun open(activity: Activity, intent: Intent) = open(activity, listOf(intent))

    /** Экран лимита заряда: у Samsung — «Защита аккумулятора» (проверено на SM-X216B, One UI 8), иначе — экран батареи. */
    internal fun chargeIntents(brand: TabletCheck.Brand): List<Intent> = if (brand != TabletCheck.Brand.SAMSUNG) emptyList() else
        listOf("com.samsung.android.sm.ACTION_BATTERY_PROTECTION", "com.samsung.android.sm.ACTION_BATTERY").map { Intent(it).setPackage("com.samsung.android.lool") }

    /** Первый открывшийся экран; ни один — настройки Android. */
    private fun open(activity: Activity, intents: List<Intent>) {
        for (intent in intents) {
            try { activity.startActivity(intent); return }
            catch (_: ActivityNotFoundException) { Log.i(TAG, "Нет такого экрана Android") }
            catch (_: SecurityException) { Log.i(TAG, "Экран Android закрыт для приложений") }
        }
        try { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(activity, "Настройки Android не открылись", Toast.LENGTH_LONG).show() }
    }

    private const val TAG = "TabletActions"
}

/** Список «Проверки планшета»: сначала то, что мешает, в конце — то, что в порядке. Один и тот же — на экране «Планшет» и в мастере. */
internal object TabletCheckView {
    fun summary(items: List<TabletCheck.Item>): Pair<ModernUi.Status, String> {
        val todo = items.count { it.level == TabletCheck.Level.BAD || it.level == TabletCheck.Level.TODO }
        val bad = items.any { it.level == TabletCheck.Level.BAD }
        return when {
            bad -> ModernUi.Status.ERROR to "Сделайте: $todo — что-то мешает уже сейчас"
            todo > 0 -> ModernUi.Status.WARNING to "Сделайте: $todo"
            else -> ModernUi.Status.OK to "Всё, что можно проверить, в порядке"
        }
    }

    @SuppressLint("SetTextI18n")
    fun render(list: LinearLayout, items: List<TabletCheck.Item>, onAction: (TabletCheck.Action) -> Unit) {
        val context = list.context
        list.removeAllViews()
        val gap = context.resources.getDimensionPixelSize(R.dimen.modern_space_s)
        items.forEach { item ->
            val status = when (item.level) {
                TabletCheck.Level.OK -> ModernUi.Status.OK
                TabletCheck.Level.TODO -> ModernUi.Status.WARNING
                TabletCheck.Level.BAD -> ModernUi.Status.ERROR
                TabletCheck.Level.UNKNOWN -> ModernUi.Status.UNKNOWN
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "tablet_check_${item.id}" }
            box.addView(ModernUi.statusRow(context, status, "${item.title}: ${item.state}"))
            if (item.advice.isNotEmpty()) box.addView(ModernUi.secondary(context, item.advice).apply { tag = "tablet_advice_${item.id}" })
            item.actions.forEach { action ->
                box.addView(ModernUi.secondaryButton(context, action.label) { onAction(action) }.apply { tag = "tablet_action_${action.name}" },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            }
            list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (item.advice.isNotEmpty() || item.actions.isNotEmpty()) bottomMargin = gap })
        }
        if (items.isEmpty()) list.addView(ModernUi.statusRow(context, ModernUi.Status.PROGRESS, "Проверяю…"))
    }
}
