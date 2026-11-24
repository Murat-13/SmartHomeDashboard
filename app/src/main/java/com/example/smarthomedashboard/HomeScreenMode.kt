package com.example.smarthomedashboard

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Панель как главный экран Android. На Android 10+ приложение не может само открыться после
 * перезагрузки, а главный экран система запускает всегда: после загрузки, по кнопке «Домой»
 * и после падения. Псевдоним HomeLauncher в манифесте выключен по умолчанию, чтобы установка
 * не подменяла рабочий стол без явного согласия.
 */
internal object HomeScreenMode {
    private fun alias(context: Context) = ComponentName(context, SplashActivity::class.java.`package`!!.name + ".HomeLauncher")

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * Выбрана ли панель главным экраном (а не только разрешена им быть). На Android 10+ — по роли: чужой рабочий стол с
     * Android 11 приложению не виден, и поиск главного экрана находил бы саму панель.
     */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true) return roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /** Системный экран выбора главного экрана; на прошивках без него — общие настройки. */
    fun openHomeSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun isHomeLaunch(intent: Intent?): Boolean = intent?.hasCategory(Intent.CATEGORY_HOME) == true
}
