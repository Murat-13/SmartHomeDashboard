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
        prefs.edit().clear().commit()
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
        prefs.edit().clear().commit()
        archiveFacts = ApkFacts(context.packageName, 950, setOf("cert-release"))
        assertTrue(AppUpdater.check(context, deps()).message.contains("версия в файле"))
        prefs.edit().clear().commit()
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
}
