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

    private var archiveDebuggable = false

    private val apk = object : ApkCheck {
        override fun installed() = ApkFacts(context.packageName, 900, setOf("cert"))
        // Готовый файл перед установкой перепроверяется (0.9.2): здесь это пригодная release-сборка 0.9.1.
        override fun archive(file: File): ApkFacts? =
            ApkFacts(context.packageName, 901, setOf("cert"), debuggable = archiveDebuggable).takeIf { file.isFile }
    }
    // Адрес не HTTPS GitHub: проверка отказывает до сети, готовое обновление остаётся готовым.
    private val deps = AppUpdater.Deps(UpdateHttp(OkHttpClient(), "http://127.0.0.1:9/releases"), apk, dir)

    @Before fun setUp() {
        prefs.edit().clear().putInt("update_bucket", 0).commit()
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
        UpdateInstaller.returns = { returns }
    }
    /** Панель после замены откроется сама. */
    private var returns = true

    @After fun tearDown() {
        UpdateInstaller.committer = defaultCommitter
        UpdateInstaller.permission = defaultPermission
        UpdateInstaller.returns = defaultReturns
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

    @Test fun debugFileMarkedReadyIsNeverCommitted() {
        val file = ready()
        archiveDebuggable = true
        assertEquals(UpdateInstaller.Outcome.NO_UPDATE, UpdateInstaller.install(context, updaterDeps = deps))
        assertTrue(commits.isEmpty())
        assertTrue(preUpdateCopies().isEmpty())
        assertFalse(file.exists())
        assertEquals("Отладочная сборка не устанавливается", AppUpdater.status(context).message)
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

    /**
     * Аудит 25.11.2026, STAB-05: панель не главный экран и без «поверх других окон» после замены не откроется — ночью не ставится;
     * по кнопке — как раньше.
     */
    @Test fun nightInstallWaitsWhenPanelCannotReturn() {
        ready()
        UpdateInstaller.setNightEnabled(context, true)
        returns = false
        assertEquals(UpdateInstaller.Outcome.CANNOT_RETURN, UpdateInstaller.nightRun(context, deps))
        assertEquals("и на следующую ночь", UpdateInstaller.Outcome.CANNOT_RETURN, UpdateInstaller.nightRun(context, deps))
        assertTrue(commits.isEmpty())
        assertTrue("копия зря не делается", preUpdateCopies().isEmpty())
        assertEquals("Установка: ${UpdateInstaller.CANNOT_RETURN}", UpdateInstaller.statusLine(context))
        assertEquals("в журнале — один раз", 1, UpdateLog.lines(context).count { "не откроется сама" in it })
        assertEquals(AppUpdater.State.READY, AppUpdater.status(context).state)
        assertEquals("по кнопке — можно", UpdateInstaller.Outcome.STARTED, UpdateInstaller.install(context, updaterDeps = deps))
    }

    /**
     * Второй аудит 25.11.2026, AUD-24 (решение владельца: «только ночью и когда свободно»): панель занята — ночная установка
     * откладывается до следующей ночи; копия зря не делается.
     */
    @Test fun nightInstallWaitsWhilePanelIsBusy() {
        ready()
        UpdateInstaller.setNightEnabled(context, true)
        assertEquals(UpdateInstaller.Outcome.BUSY, UpdateInstaller.nightRun(context, deps) { "идёт разговор" })
        assertTrue(commits.isEmpty())
        assertTrue(preUpdateCopies().isEmpty())
        assertTrue(log(), log().contains("Ночная установка отложена до следующей ночи: идёт разговор"))
        assertEquals(AppUpdater.State.READY, AppUpdater.status(context).state)
        assertEquals("освободилась — ставится", UpdateInstaller.Outcome.STARTED, UpdateInstaller.nightRun(context, deps) { null })
    }

    /** Чем занята панель: разговор, команда проходу, недавние касания — и ничем через 10 минут покоя. */
    @Test fun panelBusyReasons() {
        var now = 1_000_000L
        PanelBusy.resetForTests(); PanelBusy.clock = { now }
        try {
            assertNull(PanelBusy.reason())
            val talk = Any()
            PanelBusy.talk(talk, true)
            assertEquals("идёт разговор", PanelBusy.reason())
            PanelBusy.talk(talk, false)
            assertNull(PanelBusy.reason())
            PanelBusy.touched()
            now += PanelBusy.QUIET_MS - 1
            assertEquals("панели недавно касались", PanelBusy.reason())
            now += 1
            assertNull("10 минут без касаний — свободна", PanelBusy.reason())
            PanelBusy.talk(talk, true)
            now += 16 * 60_000L
            assertNull("забытый разговор не держит установку вечно", PanelBusy.reason())
        } finally { PanelBusy.resetForTests() }
    }

    /** AUD-22: вторая установка, пока идёт первая, не начинается — ни одновременно, ни пока Android не ответил на первую. */
    @Test fun secondInstallDoesNotStartWhileFirstIsRunning() {
        ready()
        val inside = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        UpdateInstaller.committer = object : UpdateInstaller.Committer {
            override fun commit(context: Context, file: File, versionName: String, night: Boolean): Boolean {
                synchronized(commits) { commits += file to night }
                inside.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return true
            }
        }
        var first: UpdateInstaller.Outcome? = null
        val thread = Thread { first = UpdateInstaller.install(context, updaterDeps = deps) }.apply { start() }
        assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("одновременно", UpdateInstaller.Outcome.BUSY, UpdateInstaller.install(context, updaterDeps = deps))
        release.countDown(); thread.join(10_000)
        assertEquals(UpdateInstaller.Outcome.STARTED, first)
        assertEquals("сессия ещё не ответила", UpdateInstaller.Outcome.BUSY, UpdateInstaller.install(context, updaterDeps = deps))
        assertEquals(1, commits.size)
        // Android ответил отказом — можно снова.
        status(PackageInstaller.STATUS_FAILURE)
        assertEquals(UpdateInstaller.Outcome.STARTED, UpdateInstaller.install(context, updaterDeps = deps))
        // И пока первая ещё готовится (разрешение, копия настроек) — до того, как сессия отдана Android.
        status(PackageInstaller.STATUS_FAILURE)
        val preparing = java.util.concurrent.CountDownLatch(1); val go = java.util.concurrent.CountDownLatch(1)
        UpdateInstaller.permission = object : UpdateInstaller.Permission {
            override fun granted(context: Context): Boolean { preparing.countDown(); go.await(10, java.util.concurrent.TimeUnit.SECONDS); return true }
        }
        val third = Thread { UpdateInstaller.install(context, updaterDeps = deps) }.apply { start() }
        assertTrue(preparing.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("пока первая готовится", UpdateInstaller.Outcome.BUSY, UpdateInstaller.install(context, updaterDeps = deps))
        go.countDown(); third.join(10_000)
    }

    @Test fun panelReturnsOnlyAsHomeScreenOrOverOtherWindows() {
        assertFalse(defaultReturns(context))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        try { assertTrue(defaultReturns(context)) } finally { org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false) }
    }

    /** Запись с камер панели не ждёт: служба запускается сразу после замены, и после установки через adb тоже. */
    @Test fun recordingStartsRightAfterReplacement() {
        val always = RecordingConfig.Mode.CONTINUOUS_EVENTS
        com.example.smarthomedashboard.data.TileManager(context).saveTiles(listOf(com.example.smarthomedashboard.data.TileEntity("cam_upd", DoorbellConfig.CAMERA_TYPE,
            "grid", "Камера", 0, 0, 1, 1, config = RecordingConfig(mode = always, remoteMode = always).writeTo(
                DoorbellConfig(rtspMain = "rtsp://127.0.0.1:9/main", rtspSub = "rtsp://127.0.0.1:9/sub").writeTo(DoorbellConfig.newTileConfig())))))
        RecordingDevice(context).setEnabled("cam_upd", true)
        try {
            UpdateInstaller.ReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertEquals(RecorderService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
        } finally { RecordingDevice(context).setEnabled("cam_upd", false) }
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
        val defaultReturns = UpdateInstaller.returns
    }
}
