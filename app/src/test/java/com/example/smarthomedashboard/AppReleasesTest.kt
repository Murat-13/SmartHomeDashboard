package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test

/** Разбор GitHub Releases и выбор выпуска по каналу (этап 09б). */
class AppReleasesTest {
    private val sha = "a".repeat(64)

    private fun asset(name: String, digest: String? = null, state: String = "uploaded") =
        """{"name":"$name","size":6000000,"state":"$state","browser_download_url":"https://github.com/x/$name"""" +
            (digest?.let { ""","digest":"$it"""" } ?: "") + "}"

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, assets: List<String>) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,"body":"Что нового","assets":[${assets.joinToString(",")}]}"""

    @Test fun versionCodeMatchesGradleFormula() {
        assertEquals(900L, AppReleases.versionCode("0.9.0"))
        assertEquals(901L, AppReleases.versionCode("v0.9.1"))
        assertEquals(10000L, AppReleases.versionCode("v1.0.0"))
        assertEquals(10203L, AppReleases.versionCode("1.2.3"))
        assertNull(AppReleases.versionCode("v1.0"))
        assertNull(AppReleases.versionCode("v1.0.0-beta"))
        assertNull(AppReleases.versionCode("v1.100.0"))
    }

    @Test fun parsesUsableReleasesAndSkipsTheRest() {
        val json = "[" + listOf(
            release("v0.9.2", pre = true, assets = listOf(asset("SmartHomeDashboard-0.9.2.apk", "sha256:${sha.uppercase()}"))),
            release("v0.9.1", assets = listOf(asset("SmartHomeDashboard-0.9.1.apk"), asset("SmartHomeDashboard-0.9.1.apk.sha256"))),
            release("v0.9.3", draft = true, assets = listOf(asset("a.apk", "sha256:$sha"))),
            release("nightly", assets = listOf(asset("a.apk", "sha256:$sha"))),
            release("v0.9.4", assets = listOf(asset("a.apk"), asset("b.apk"))),
            release("v0.9.5", assets = listOf(asset("a.apk", "sha256:$sha", state = "new"))),
        ).joinToString(",") + "]"
        val parsed = AppReleases.parse(json)
        assertEquals(listOf("v0.9.2", "v0.9.1"), parsed.map { it.tag })
        val pre = parsed[0]
        assertTrue(pre.prerelease)
        assertEquals("0.9.2", pre.versionName)
        assertEquals(902L, pre.versionCode)
        assertEquals(sha, pre.sha256)
        assertNull(pre.sha256Url)
        val stable = parsed[1]
        assertNull(stable.sha256)
        assertEquals("https://github.com/x/SmartHomeDashboard-0.9.1.apk.sha256", stable.sha256Url)
        assertEquals("Что нового", stable.notes)
    }

    @Test fun garbageIsEmptyNotCrash() {
        assertTrue(AppReleases.parse("").isEmpty())
        assertTrue(AppReleases.parse("{\"message\":\"Not Found\"}").isEmpty())
        assertTrue(AppReleases.parse("[1, \"x\", {}]").isEmpty())
    }

    @Test fun channelPicksNewestAllowedAboveInstalled() {
        fun r(code: Long, pre: Boolean) = AppRelease("t$code", "n$code", code, pre, "a.apk", "https://github.com/a", 1, sha, null, "")
        val list = listOf(r(901, false), r(902, true), r(899, false))
        assertEquals(901L, AppReleases.pick(list, UpdateChannel.STABLE, 900)?.versionCode)
        assertEquals(902L, AppReleases.pick(list, UpdateChannel.TEST, 900)?.versionCode)
        assertNull(AppReleases.pick(list, UpdateChannel.STABLE, 901))
        assertNull(AppReleases.pick(list, UpdateChannel.TEST, 902))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.from(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.from("garbage"))
        assertEquals(UpdateChannel.TEST, UpdateChannel.from("test"))
    }

    @Test fun sha256FileFormats() {
        assertEquals(sha, AppReleases.parseSha256File("$sha  SmartHomeDashboard-0.9.1.apk\n"))
        assertEquals(sha, AppReleases.parseSha256File(sha.uppercase()))
        assertNull(AppReleases.parseSha256File("not a hash"))
    }

    @Test fun onlyGithubHostsAreTrusted() {
        assertTrue(UpdateHttp.trusted("api.github.com"))
        assertTrue(UpdateHttp.trusted("github.com"))
        assertTrue(UpdateHttp.trusted("release-assets.githubusercontent.com"))
        assertFalse(UpdateHttp.trusted("github.com.evil.example"))
        assertFalse(UpdateHttp.trusted("evilgithub.com"))
    }
}
