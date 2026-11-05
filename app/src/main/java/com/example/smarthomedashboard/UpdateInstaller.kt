package com.example.smarthomedashboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

/**
 * Установка скачанного и проверенного обновления ([AppUpdater.readyFile]) через PackageInstaller.
 * Порядок: разрешение Android на установку → копия «перед обновлением» → сессия с
 * USER_ACTION_NOT_REQUIRED (Android 12+: самообновление без окна, если система разрешит).
 * Не получилась копия — пауза; админ может продолжить по PIN после предупреждения (пишется в [UpdateLog]).
 * STATUS_PENDING_USER_ACTION успехом не считается. Ночью — только тихо и только с копией.
 */
internal object UpdateInstaller {
    /** Ночная автоустановка; по умолчанию выключена (решение владельца 02.11.2026). */
    const val PREF_NIGHT = "update_night_install"
    const val PREF_INSTALL_STATE = "update_install_state"
    const val PREF_INSTALL_MESSAGE = "update_install_message"
    const val EXTRA_NIGHT = "night"
    const val EXTRA_VERSION = "version"
    private const val WORK = "update_night_install"

    enum class InstallState { NONE, INSTALLING, NEEDS_CONFIRMATION, FAILED }

    enum class Outcome { STARTED, NO_UPDATE, NEEDS_PERMISSION, BACKUP_FAILED, NOT_SILENT, FAILED }

    /** Запуск сессии установки; отделён для тестов. */
    internal interface Committer {
        /** false — сессию создать или записать не удалось. */
        fun commit(context: Context, file: File, versionName: String, night: Boolean): Boolean
    }

    /** Разрешение Android «Установка неизвестных приложений» для самой панели. */
    internal interface Permission {
        fun granted(context: Context): Boolean
    }

    private object AndroidPermission : Permission {
        override fun granted(context: Context) =
            Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }

    private object SessionCommitter : Committer {
        override fun commit(context: Context, file: File, versionName: String, night: Boolean): Boolean {
            val installer = context.packageManager.packageInstaller
            val params = sessionParams(context, file.length())
            var id = -1
            return try {
                id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(context, StatusReceiver::class.java)
                        .putExtra(EXTRA_NIGHT, night).putExtra(EXTRA_VERSION, versionName)
                    // Изменяемый: PackageInstaller дописывает в него статус. Намерение явное — к нашему приёмнику.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
                }
                true
            } catch (_: Exception) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                false
            }
        }
    }

    /** Полная установка поверх себя; на Android 12+ — просьба обойтись без окна подтверждения. */
    internal fun sessionParams(context: Context, size: Long) =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

    @androidx.annotation.VisibleForTesting
    internal var committer: Committer = SessionCommitter

    @androidx.annotation.VisibleForTesting
    internal var permission: Permission = AndroidPermission

    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    fun nightEnabled(context: Context) = prefs(context).getBoolean(PREF_NIGHT, false)

    fun setNightEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_NIGHT, enabled) }
        UpdateLog.add(context, if (enabled) "Ночная автоустановка включена" else "Ночная автоустановка выключена")
    }

    /** Тихая установка вообще возможна только с Android 12. */
    fun silentSupported() = Build.VERSION.SDK_INT >= 31

    fun canInstall(context: Context) = permission.granted(context)

    /**
     * Запуск установки готового обновления. [allowWithoutBackup] — админ по PIN подтвердил продолжение без копии.
     * [night] — фоновая установка: только при поддержке тихой установки, без продолжения без копии.
     */
    fun install(context: Context, allowWithoutBackup: Boolean = false, night: Boolean = false,
                updaterDeps: AppUpdater.Deps = AppUpdater.deps(context)): Outcome {
        val file = AppUpdater.readyFile(context, updaterDeps) ?: return Outcome.NO_UPDATE
        val version = AppUpdater.status(context).versionName.orEmpty()
        if (night && !silentSupported()) return Outcome.NOT_SILENT
        if (!canInstall(context)) {
            UpdateLog.add(context, "Установка $version: нет разрешения Android на установку приложений")
            return Outcome.NEEDS_PERMISSION
        }
        if (!AutoBackup.beforeUpdate(context, version)) {
            UpdateLog.add(context, "Копия перед обновлением до $version не создана — установка на паузе")
            if (night || !allowWithoutBackup) {
                setState(context, InstallState.FAILED, "копия перед обновлением не создана")
                return Outcome.BACKUP_FAILED
            }
            UpdateLog.add(context, "Админ подтвердил PIN и продолжил установку $version без копии")
        } else {
            UpdateLog.add(context, "Копия перед обновлением до $version создана")
        }
        setState(context, InstallState.INSTALLING, "")
        if (!committer.commit(context, file, version, night)) {
            UpdateLog.add(context, "Установка $version не запущена: ошибка сессии установки")
            setState(context, InstallState.FAILED, "не удалось начать установку")
            return Outcome.FAILED
        }
        UpdateLog.add(context, "Установка $version запущена" + if (night) " (ночная)" else "")
        return Outcome.STARTED
    }

    fun state(context: Context): Pair<InstallState, String> {
        val prefs = prefs(context)
        val state = runCatching { InstallState.valueOf(prefs.getString(PREF_INSTALL_STATE, null) ?: "NONE") }
            .getOrDefault(InstallState.NONE)
        return state to prefs.getString(PREF_INSTALL_MESSAGE, "").orEmpty()
    }

    private fun setState(context: Context, state: InstallState, message: String) {
        prefs(context).edit { putString(PREF_INSTALL_STATE, state.name).putString(PREF_INSTALL_MESSAGE, message) }
    }

    /** Строка о последней попытке для «О приложении»; null — показывать нечего. */
    fun statusLine(context: Context): String? {
        if (AppUpdater.status(context).state != AppUpdater.State.READY) return null
        val (state, message) = state(context)
        return when (state) {
            InstallState.NONE -> null
            InstallState.INSTALLING -> "Установка: выполняется…"
            InstallState.NEEDS_CONFIRMATION -> "Установка: Android запросил подтверждение"
            InstallState.FAILED -> "Установка: $message"
        }
    }

    /** Результат сессии от PackageInstaller. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
            val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(200)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (night || confirm == null) {
                        // Ночью окно не показывается: сессия отменяется, обновление остаётся готовым к установке по кнопке.
                        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                        UpdateLog.add(context, "Установка $version: Android требует подтверждения — тихо установить нельзя")
                        setState(context, InstallState.FAILED, "Android требует подтверждения — установите по кнопке")
                    } else {
                        UpdateLog.add(context, "Установка $version: Android запросил подтверждение")
                        setState(context, InstallState.NEEDS_CONFIRMATION, "")
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> UpdateLog.add(context, "Установка $version: Android сообщил об успехе")
                else -> {
                    val reason = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "установка отменена"
                        else "установка не удалась (код $status${detail?.let { ": $it" } ?: ""})"
                    UpdateLog.add(context, "Установка $version: $reason")
                    setState(context, InstallState.FAILED, reason)
                }
            }
        }
    }

    /**
     * Приложение заменено новой версией (MY_PACKAGE_REPLACED). Если замену запустили мы — записать
     * результат, убрать скачанный файл и открыть панель снова (Android может запретить запуск из фона;
     * в режиме «главный экран» панель откроет сама система).
     */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val ours = state(context).first in setOf(InstallState.INSTALLING, InstallState.NEEDS_CONFIRMATION)
            AppUpdater.afterUpdate(context)
            setState(context, InstallState.NONE, "")
            if (!ours) return
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            UpdateLog.add(context, "Приложение обновлено до ${info?.versionName ?: "новой версии"}")
            runCatching {
                context.startActivity(Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Ежедневно около 3:30 (после автокопии в 3:00); сама задача ничего не делает, если флажок выключен. */
    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<NightWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (_: IllegalStateException) {
            // WorkManager не инициализирован (тестовое окружение без манифеста).
        }
    }

    /** Ночная попытка: свежая проверка, затем тихая установка готового обновления. */
    internal fun nightRun(context: Context, deps: AppUpdater.Deps = AppUpdater.deps(context)): Outcome? {
        if (!nightEnabled(context) || !silentSupported()) return null
        AppUpdater.check(context, deps)
        return install(context, night = true, updaterDeps = deps).takeIf { it != Outcome.NO_UPDATE }
    }

    class NightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            withContext(Dispatchers.IO) { runCatching { nightRun(applicationContext) } }
            return Result.success()
        }
    }
}
