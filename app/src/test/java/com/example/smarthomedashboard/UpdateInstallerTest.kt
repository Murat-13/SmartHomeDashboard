package com.example.smarthomedashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Установка готового обновления: разрешение Android, копия перед установкой (и продолжение без неё по
 * подтверждению), ночной режим, статусы PackageInstaller и действия после замены приложения.
 * Настоящая установка не выполняется: сессия подменена, кроме отдельной проверки параметров сессии.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class UpdateInstallerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val dir = File(context.cacheDir, "updates-install-test")
    private val backups = File(context.filesDir, "backups")
    private val commits = mutableListOf<Pair<File, Boolean>>()
    private var granted = true
    private var commitOk = true

    private val apk = object : ApkCheck {
        override fun installed() = ApkFacts(context.packageName, 900, setOf("cert"))
        override fun archive(file: File): ApkFacts? = null
    }
    // Адрес не HTTPS GitHub: проверка отказывает до сети, готовое обновление остаётся готовым.
    private val deps = AppUpdater.Deps(UpdateHttp(OkHttpClient(), "http://127.0.0.1:9/releases"), apk, dir)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        dir.deleteRecursively()
        backups.deleteRecursively()
        File(context.filesDir, "update_log.txt").delete()
        UpdateInstaller.committer = object : UpdateInstaller.Committer {
            override fun commit(context: Context, file: File, versionName: String, night: Boolean) =
                commitOk.also { commits += file to night }
        }
        UpdateInstaller.permission = object : UpdateInstaller.Permission {
            override fun granted(context: Context) = granted
        }
    }

    @After fun tearDown() {
        UpdateInstaller.committer = defaultCommitter
        UpdateInstaller.permission = defaultPermission
        dir.deleteRecursively()
    }

    private fun ready(): File {
        dir.mkdirs()
        val file = File(dir, "update-901.apk").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        prefs.edit().putString(AppUpdater.PREF_STATE, "READY").putString(AppUpdater.PREF_VERSION_NAME, "0.9.1")
            .putLong(AppUpdater.PREF_VERSION_CODE, 901).putString(AppUpdater.PREF_SHA, AppUpdater.sha256(file)).commit()
        return file
    }

    private fun preUpdateCopies() = backups.list()?.filter { it.startsWith(AutoBackup.PRE_UPDATE_PREFIX) }.orEmpty()
    private fun log() = UpdateLog.lines(context).joinToString("\n")
    private fun breakBackups() = prefs.edit().putString(AutoBackup.PREF_TREE, "content://invalid.example/tree/x").commit()

    @Test fun nothingToInstallWithoutReadyUpdate() {
        assertEquals(UpdateInstaller.Outcome.NO_UPDATE, UpdateInstaller.install(context, updaterDeps = deps))
        assertTrue(commits.isEmpty())
    }

    @Test fun backupThenSessionInOrder() {
        val file = ready()
        assertEquals(UpdateInstaller.Outcome.STARTED, UpdateInstaller.install(context, updaterDeps = deps))
        assertEquals(listOf(file to false), commits)
        assertEquals(1, preUpdateCopies().size)
        assertTrue(preUpdateCopies().single().endsWith("_to_0.9.1.json"))
        assertEquals(UpdateInstaller.InstallState.INSTALLING, UpdateInstaller.state(context).first)
        assertTrue(log().contains("Копия перед обновлением до 0.9.1 создана"))
        assertTrue(log().contains("Установка 0.9.1 запущена"))
        assertEquals("Установка: выполняется…", UpdateInstaller.statusLine(context))
    }

    @Test fun missingAndroidPermissionStopsBeforeBackup() {
        ready()
        granted = false
        assertEquals(UpdateInstaller.Outcome.NEEDS_PERMISSION, UpdateInstaller.install(context, updaterDeps = deps))
        assertTrue(commits.isEmpty())
        assertTrue(preUpdateCopies().isEmpty())
    }

    @Test fun failedBackupPausesUntilAdminConfirmsAndIsLogged() {
        ready()
        breakBackups()
        assertEquals(UpdateInstaller.Outcome.BACKUP_FAILED, UpdateInstaller.install(context, updaterDeps = deps))
        assertTrue("без копии установка не начинается", commits.isEmpty())
        assertEquals("Установка: копия перед обновлением не создана", UpdateInstaller.statusLine(context))
        assertEquals(UpdateInstaller.Outcome.STARTED,
            UpdateInstaller.install(context, allowWithoutBackup = true, updaterDeps = deps))
        assertEquals(1, commits.size)
        assertTrue(log().contains("Админ подтвердил PIN и продолжил установку 0.9.1 без копии"))
    }

    @Test fun sessionErrorIsStatusNotSuccess() {
        ready()
        commitOk = false
        assertEquals(UpdateInstaller.Outcome.FAILED, UpdateInstaller.install(context, updaterDeps = deps))
        assertEquals("Установка: не удалось начать установку", UpdateInstaller.statusLine(context))
        assertEquals("готовое обновление остаётся", AppUpdater.State.READY, AppUpdater.status(context).state)
    }

    @Test fun nightInstallOnlyWhenEnabledSilentAndBackedUp() {
        ready()
        assertFalse(UpdateInstaller.nightEnabled(context))
        assertNull("флажок по умолчанию выключен", UpdateInstaller.nightRun(context, deps))
        assertTrue(commits.isEmpty())

        UpdateInstaller.setNightEnabled(context, true)
        breakBackups()
        assertEquals(UpdateInstaller.Outcome.BACKUP_FAILED, UpdateInstaller.nightRun(context, deps))
        assertTrue("ночью без копии не ставится", commits.isEmpty())

        prefs.edit().remove(AutoBackup.PREF_TREE).commit()
        assertEquals(UpdateInstaller.Outcome.STARTED, UpdateInstaller.nightRun(context, deps))
        assertTrue(commits.single().second)
    }

    @Config(sdk = [30])
    @Test fun nightInstallNeedsAndroid12() {
        ready()
        UpdateInstaller.setNightEnabled(context, true)
        assertFalse(UpdateInstaller.silentSupported())
        assertNull(UpdateInstaller.nightRun(context, deps))
        assertEquals(UpdateInstaller.Outcome.NOT_SILENT, UpdateInstaller.install(context, night = true, updaterDeps = deps))
        assertTrue(commits.isEmpty())
    }

    private fun status(status: Int, night: Boolean = false, message: String? = null, confirm: Intent? = null) =
        UpdateInstaller.StatusReceiver().onReceive(context, Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(UpdateInstaller.EXTRA_NIGHT, night)
            .putExtra(UpdateInstaller.EXTRA_VERSION, "0.9.1")
            .apply { message?.let { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, it) } }
            .apply { confirm?.let { putExtra(Intent.EXTRA_INTENT, it) } })

    @Test fun pendingUserActionShowsSystemConfirmationButIsNotSuccess() {
        ready()
        status(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm = Intent("android.content.pm.action.CONFIRM_INSTALL"))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(context).nextStartedActivity?.action)
        assertEquals(UpdateInstaller.InstallState.NEEDS_CONFIRMATION, UpdateInstaller.state(context).first)
        assertEquals("Установка: Android запросил подтверждение", UpdateInstaller.statusLine(context))
        assertEquals(AppUpdater.State.READY, AppUpdater.status(context).state)
    }

    @Test fun pendingUserActionAtNightShowsNothing() {
        ready()
        status(PackageInstaller.STATUS_PENDING_USER_ACTION, night = true, confirm = Intent("android.content.pm.action.CONFIRM_INSTALL"))
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals("Установка: Android требует подтверждения — установите по кнопке", UpdateInstaller.statusLine(context))
        assertTrue(log().contains("тихо установить нельзя"))
    }

    @Test fun failuresAreReportedAndUpdateStaysReady() {
        ready()
        status(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals("Установка: установка отменена", UpdateInstaller.statusLine(context))
        status(PackageInstaller.STATUS_FAILURE_STORAGE, message = "Недостаточно места")
        assertEquals("Установка: установка не удалась (код ${PackageInstaller.STATUS_FAILURE_STORAGE}: Недостаточно места)",
            UpdateInstaller.statusLine(context))
        assertEquals(AppUpdater.State.READY, AppUpdater.status(context).state)
    }

    @Test fun replacedAfterOurInstallLogsCleansAndReopensPanel() {
        ready()
        UpdateInstaller.install(context, updaterDeps = deps)
        val original = AppUpdater.depsFactory
        AppUpdater.depsFactory = { deps }
        try {
            UpdateInstaller.ReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        } finally {
            AppUpdater.depsFactory = original
        }
        assertEquals(SplashActivity::class.java.name, shadowOf(context).nextStartedActivity?.component?.className)
        assertEquals(AppUpdater.State.NONE, AppUpdater.status(context).state)
        assertTrue(dir.list().isNullOrEmpty())
        assertEquals(UpdateInstaller.InstallState.NONE, UpdateInstaller.state(context).first)
        assertTrue(log().contains("Приложение обновлено до"))
    }

    @Test fun replacedByStudioOrAdbDoesNotReopenPanel() {
        UpdateInstaller.ReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun logKeepsLastEntriesNewestFirst() {
        repeat(UpdateLog.KEEP + 5) { UpdateLog.add(context, "запись $it", now = 1_800_000_000_000L + it * 1000L) }
        val lines = UpdateLog.lines(context)
        assertEquals(UpdateLog.KEEP, lines.size)
        assertTrue(lines.first().endsWith("запись ${UpdateLog.KEEP + 4}"))
    }

    @Test fun realSessionAsksForUpdateWithoutUserAction() {
        UpdateInstaller.committer = defaultCommitter
        val file = ready()
        assertTrue(defaultCommitter.commit(context, file, "0.9.1", night = false))
        val session = context.packageManager.packageInstaller.allSessions.single()
        assertEquals(context.packageName, session.appPackageName)
        // Поле скрыто в SDK, а Robolectric не переносит его в SessionInfo: читаем из самих параметров.
        val params = UpdateInstaller.sessionParams(context, file.length())
        val field = PackageInstaller.SessionParams::class.java.getDeclaredField("requireUserAction").apply { isAccessible = true }
        assertEquals(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED, field.getInt(params))
        val mode = PackageInstaller.SessionParams::class.java.getDeclaredField("mode").apply { isAccessible = true }
        assertEquals(PackageInstaller.SessionParams.MODE_FULL_INSTALL, mode.getInt(params))
    }

    private companion object {
        val defaultCommitter = UpdateInstaller.committer
        val defaultPermission = UpdateInstaller.permission
    }
}
