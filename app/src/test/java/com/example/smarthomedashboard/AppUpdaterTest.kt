package com.example.smarthomedashboard

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Проверка обновлений на синтетическом GitHub (MockWebServer): выбор по каналу, загрузка, SHA-256,
 * пакет/версия/подпись, отказы сети и места. Настоящий GitHub и планшет не участвуют.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AppUpdaterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("dashboard_prefs", 0)
    private val dir = File(context.cacheDir, "updates-test")
    private val server = MockWebServer()
    private val apkBytes = ByteArray(4096) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val paths = mutableListOf<String>()

    private var releasesJson = "[]"
    private var releasesCode = 200
    private var apkBody = apkBytes

    private val installed = ApkFacts(context.packageName, 900, setOf("cert-release"))
    private var archiveFacts: ApkFacts? = ApkFacts(context.packageName, 901, setOf("cert-release"))

    private val apkCheck = object : ApkCheck {
        override fun installed() = installed
        override fun archive(file: File) = archiveFacts?.takeIf { file.isFile }
    }

    private fun deps(free: Long = Long.MAX_VALUE) = AppUpdater.Deps(
        UpdateHttp(OkHttpClient(), server.url("/releases").toString(), requireHttps = false), apkCheck, dir, { free })

    private fun release(tag: String, pre: Boolean = false, digest: String? = apkSha, sidecar: Boolean = false): String {
        val apk = """{"name":"app.apk","size":${apkBytes.size},"state":"uploaded","browser_download_url":"${server.url("/apk/$tag")}"""" +
            (digest?.let { ""","digest":"sha256:$it"""" } ?: "") + "}"
        val side = if (sidecar) """,{"name":"app.apk.sha256","size":80,"browser_download_url":"${server.url("/sha/$tag")}"}""" else ""
        return """{"tag_name":"$tag","prerelease":$pre,"draft":false,"body":"Заметки $tag","assets":[$apk$side]}"""
    }

    @Before fun setUp() {
        // Постепенный выпуск — своими тестами ниже; здесь планшет из первых десяти процентов.
        prefs.edit().clear().putInt("update_bucket", 0).commit()
        dir.deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return when {
                    path.startsWith("/releases") -> MockResponse().setResponseCode(releasesCode).setBody(releasesJson)
                    path.startsWith("/apk/") -> MockResponse().setBody(Buffer().write(apkBody))
                    path.startsWith("/sha/") -> MockResponse().setBody("$apkSha  app.apk\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun downloads() = paths.count { it.startsWith("/apk/") }

    @Test fun noNewerReleaseIsUpToDate() {
        releasesJson = "[${release("v0.9.0")}]"
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.UP_TO_DATE, status.state)
        assertEquals(0, downloads())
        assertNull(AppUpdater.drawerText(context))
    }

    @Test fun downloadsVerifiesAndDoesNotDownloadAgain() {
        releasesJson = "[${release("v0.9.1")}]"
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.READY, status.state)
        assertEquals("0.9.1", status.versionName)
        assertEquals("Заметки v0.9.1", status.notes)
        val file = AppUpdater.readyFile(context, deps())
        assertNotNull(file)
        assertArrayEquals(apkBytes, file!!.readBytes())
        assertEquals(listOf(file.name), dir.list()!!.toList())
        assertEquals("Доступно обновление 0.9.1", AppUpdater.drawerText(context))
        assertTrue(AppUpdater.statusText(context).second.startsWith("Обновление 0.9.1 скачано и проверено"))

        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps()).state)
        assertEquals("готовый проверенный файл повторно не скачивается", 1, downloads())

        // Подменённый на диске файл больше не считается готовым.
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(AppUpdater.readyFile(context, deps()))
    }

    @Test fun stableIgnoresPrereleaseTestChannelTakesIt() {
        releasesJson = "[${release("v0.9.1", pre = true)}]"
        assertEquals(UpdateChannel.STABLE, AppUpdater.channel(context))
        assertEquals(AppUpdater.State.UP_TO_DATE, AppUpdater.check(context, deps()).state)
        AppUpdater.setChannel(context, UpdateChannel.TEST)
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps()).state)
        // Вернулись на стабильный — скачанный тестовый выпуск убирается.
        AppUpdater.setChannel(context, UpdateChannel.STABLE)
        assertEquals(AppUpdater.State.UP_TO_DATE, AppUpdater.check(context, deps()).state)
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun sidecarSha256IsUsedWhenGithubDigestMissing() {
        releasesJson = "[${release("v0.9.1", digest = null, sidecar = true)}]"
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps()).state)
        assertTrue(paths.any { it.startsWith("/sha/") })
    }

    @Test fun releaseWithoutHashIsRefusedWithoutDownload() {
        releasesJson = "[${release("v0.9.1", digest = null)}]"
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertTrue(status.message.contains("SHA-256"))
        assertEquals(0, downloads())
    }

    @Test fun hashMismatchLeavesNoFile() {
        releasesJson = "[${release("v0.9.1", digest = "b".repeat(64))}]"
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertTrue(status.message.contains("SHA-256 не совпал"))
        assertTrue(dir.list().isNullOrEmpty())
        assertNull(AppUpdater.readyFile(context, deps()))
    }

    @Test fun foreignSignatureIsRejectedAndNotDownloadedEveryCheck() {
        releasesJson = "[${release("v0.9.1")}]"
        archiveFacts = ApkFacts(context.packageName, 901, setOf("cert-debug"))
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertTrue(status.message.contains("подпись"))
        assertTrue(dir.list().isNullOrEmpty())
        AppUpdater.check(context, deps())
        assertEquals("тот же отвергнутый файл не качается каждые 6 часов", 1, downloads())
    }

    @Test fun debugApkWithCorrectSignatureIsRejectedDeletedLoggedAndNotRedownloaded() {
        File(context.filesDir, "update_log.txt").delete()
        releasesJson = "[${release("v0.9.1")}]"
        archiveFacts = ApkFacts(context.packageName, 901, setOf("cert-release"), debuggable = true)
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertEquals("Отладочная сборка не устанавливается", status.message)
        assertTrue("отвергнутый файл удалён", dir.list().isNullOrEmpty())
        assertNull(AppUpdater.readyFile(context, deps()))
        assertTrue(UpdateLog.lines(context).first().endsWith("Выпуск 0.9.1 отвергнут: Отладочная сборка не устанавливается"))
        AppUpdater.check(context, deps())
        assertEquals("тот же файл повторно не скачивается", 1, downloads())
    }

    @Test fun releaseApkWithSameSignatureIsAccepted() {
        releasesJson = "[${release("v0.9.1")}]"
        archiveFacts = ApkFacts(context.packageName, 901, setOf("cert-release"), debuggable = false)
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps()).state)
        assertNotNull(AppUpdater.readyFile(context, deps()))
    }

    @Test fun previouslyAcceptedFileCannotBypassNewCheck() {
        // Состояние, оставленное версией без проверки debuggable: файл скачан, хэш совпадает, статус «готово».
        dir.mkdirs()
        val file = File(dir, "update-901.apk").apply { writeBytes(apkBytes) }
        prefs.edit().putString(AppUpdater.PREF_STATE, "READY").putString(AppUpdater.PREF_VERSION_NAME, "0.9.1")
            .putLong(AppUpdater.PREF_VERSION_CODE, 901).putString(AppUpdater.PREF_SHA, apkSha).commit()
        archiveFacts = ApkFacts(context.packageName, 901, setOf("cert-release"), debuggable = true)

        assertNull("установка не получит файл", AppUpdater.readyFile(context, deps()))
        assertFalse(file.exists())
        assertEquals(AppUpdater.State.ERROR, AppUpdater.status(context).state)
        assertEquals("Отладочная сборка не устанавливается", AppUpdater.status(context).message)
        assertNull(AppUpdater.drawerText(context))

        releasesJson = "[${release("v0.9.1")}]"
        assertEquals(AppUpdater.State.ERROR, AppUpdater.check(context, deps()).state)
        assertEquals("отвергнутый «код:хэш» не перекачивается", 0, downloads())
    }

    @Test fun realPackageCheckTreatsNonApkAsUnusable() {
        dir.mkdirs()
        val junk = File(dir, "junk.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(PackageApkCheck(context).archive(junk))
        assertNull(PackageApkCheck(context).archive(File(dir, "missing.apk")))
    }

    @Test fun wrongPackageOrVersionInsideApkIsRejected() {
        releasesJson = "[${release("v0.9.1")}]"
        archiveFacts = ApkFacts("com.example.other", 901, setOf("cert-release"))
        assertTrue(AppUpdater.check(context, deps()).message.contains("другого приложения"))
        prefs.edit().clear().putInt("update_bucket", 0).commit()
        archiveFacts = ApkFacts(context.packageName, 950, setOf("cert-release"))
        assertTrue(AppUpdater.check(context, deps()).message.contains("версия в файле"))
        prefs.edit().clear().putInt("update_bucket", 0).commit()
        archiveFacts = null
        assertTrue(AppUpdater.check(context, deps()).message.contains("не является приложением"))
    }

    @Test fun lowDiskSpaceStopsBeforeDownload() {
        releasesJson = "[${release("v0.9.1")}]"
        val status = AppUpdater.check(context, deps(free = 1024))
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertTrue(status.message.contains("мало места"))
        assertEquals(0, downloads())
    }

    @Test fun githubFailuresBecomeStatusAndKeepReadyUpdate() {
        releasesCode = 500
        assertEquals("GitHub ответил ошибкой 500", AppUpdater.check(context, deps()).message)
        releasesCode = 403
        assertEquals("GitHub временно ограничил запросы", AppUpdater.check(context, deps()).message)

        releasesCode = 200
        releasesJson = "[${release("v0.9.1")}]"
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps()).state)
        server.shutdown()
        val offline = AppUpdater.check(context, deps())
        assertEquals("скачанное обновление сбой связи не отменяет", AppUpdater.State.READY, offline.state)
        assertNotNull(AppUpdater.readyFile(context, deps()))
    }

    @Test fun unreachableGithubIsStatusNotCrash() {
        server.shutdown()
        val status = AppUpdater.check(context, deps())
        assertEquals(AppUpdater.State.ERROR, status.state)
        assertEquals("нет связи с GitHub", status.message)
        assertTrue(AppUpdater.statusText(context).second.startsWith("Обновления: нет связи с GitHub"))
    }

    @Test fun httpsPolicyRejectsPlainHttpAndForeignHosts() {
        val strict = UpdateHttp(OkHttpClient(), server.url("/releases").toString())
        assertThrows(IOException::class.java) { strict.releases() }
        assertThrows(IOException::class.java) { strict.text("https://example.com/x.sha256") }
        assertThrows(IOException::class.java) { strict.download(server.url("/apk/x").toString(), File(dir, "x"), 1000) }
        assertEquals("до сервера не дошёл ни один запрос", 0, server.requestCount)
    }

    @Test fun defaultSourceIsHttpsGithubApi() {
        assertTrue(UpdateHttp.RELEASES_URL.startsWith("https://api.github.com/repos/Murat-13/SmartHomeDashboard/releases"))
    }

    @Test fun oversizedDownloadIsCut() {
        apkBody = ByteArray(5000)
        val http = UpdateHttp(OkHttpClient(), server.url("/releases").toString(), requireHttps = false)
        dir.mkdirs()
        assertThrows(IOException::class.java) { http.download(server.url("/apk/x").toString(), File(dir, "x"), 1000) }
    }

    // ---- Постепенный выпуск (docs/updates.md) ----

    private val day = 24 * 3_600_000L

    @Test fun stableReleaseReachesTabletsOverThreeDays() {
        assertEquals(listOf(10, 10, 50, 50, 100, 100), listOf(0L, day - 1, day, 2 * day - 1, 2 * day, 30 * day).map { AppUpdater.Rollout.share(it) })
        releasesJson = "[${release("v0.9.1")}]"
        // Планшет из последней половины: в первые сутки и во вторые — ждёт, на третьи — берёт.
        prefs.edit().putInt("update_bucket", 73).commit()
        val start = 1_000_000_000L
        val first = AppUpdater.check(context, deps(), start)
        assertEquals(AppUpdater.State.WAITING, first.state)
        assertEquals(0, downloads())
        assertTrue(first.message, "через 48 ч" in first.message)
        assertTrue(AppUpdater.statusText(context).second, AppUpdater.statusText(context).second.startsWith("Версия 0.9.1 дойдёт до этого планшета"))
        assertNull("в боковой панели не мигает", AppUpdater.drawerText(context))
        assertEquals(AppUpdater.State.WAITING, AppUpdater.check(context, deps(), start + day + 1).state)
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps(), start + 2 * day).state)
    }

    @Test fun bucketsTakeTheirTurn() {
        releasesJson = "[${release("v0.9.1")}]"
        val start = 1_000_000_000L
        fun state(bucket: Int, after: Long): AppUpdater.State {
            prefs.edit().clear().putInt("update_bucket", bucket).commit(); dir.deleteRecursively()
            AppUpdater.check(context, deps(), start)
            return AppUpdater.check(context, deps(), start + after).state
        }
        assertEquals(AppUpdater.State.READY, state(9, 0))
        assertEquals(AppUpdater.State.WAITING, state(10, day - 1))
        assertEquals(AppUpdater.State.READY, state(10, day))
        assertEquals(AppUpdater.State.READY, state(49, day))
        assertEquals(AppUpdater.State.WAITING, state(50, 2 * day - 1))
        assertEquals(AppUpdater.State.READY, state(99, 2 * day))
    }

    @Test fun buttonAndTestChannelDoNotWait() {
        prefs.edit().putInt("update_bucket", 99).commit()
        releasesJson = "[${release("v0.9.1")}]"
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps(), 1_000, manual = true).state)
        // Скачанное по кнопке следующая обычная проверка не отбирает.
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps(), 2_000).state)
        assertNotNull(AppUpdater.readyFile(context, deps()))
        prefs.edit().clear().putInt("update_bucket", 99).commit(); dir.deleteRecursively()
        AppUpdater.setChannel(context, UpdateChannel.TEST)
        releasesJson = "[${release("v0.9.1", pre = true)}]"
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps(), 1_000).state)
    }

    @Test fun recalledReleaseNeverReachesTheRest() {
        prefs.edit().putInt("update_bucket", 60).commit()
        releasesJson = "[${release("v0.9.1")}]"
        AppUpdater.check(context, deps(), 1_000)
        // Выпуск отозвали (снова pre-release): ждавшие его не получат; вышла следующая — отсчёт заново.
        releasesJson = "[${release("v0.9.1", pre = true)}]"
        assertEquals(AppUpdater.State.UP_TO_DATE, AppUpdater.check(context, deps(), 1_000 + 3 * day).state)
        releasesJson = "[${release("v0.9.2")}, ${release("v0.9.1", pre = true)}]"
        archiveFacts = ApkFacts(context.packageName, 902, setOf("cert-release"))
        assertEquals(AppUpdater.State.WAITING, AppUpdater.check(context, deps(), 1_000 + 3 * day).state)
        assertEquals(AppUpdater.State.READY, AppUpdater.check(context, deps(), 1_000 + 5 * day).state)
    }

    @Test fun bucketIsRandomOnceAndClockGoingBackIsNotForever() {
        prefs.edit().clear().commit()
        val previous = AppUpdater.Rollout.random
        try {
            AppUpdater.Rollout.random = { 42 }
            assertEquals(42, AppUpdater.Rollout.bucket(context))
            AppUpdater.Rollout.random = { 7 }
            assertEquals("запомнено", 42, AppUpdater.Rollout.bucket(context))
        } finally { AppUpdater.Rollout.random = previous }
        assertEquals(day, AppUpdater.Rollout.waitMs(context, 950, 10 * day))
        assertEquals("часы перевели назад — не дольше обычного", day, AppUpdater.Rollout.waitMs(context, 950, 5 * day))
    }
}
