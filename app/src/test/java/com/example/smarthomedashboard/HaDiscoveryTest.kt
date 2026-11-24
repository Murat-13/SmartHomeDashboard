package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Поиск Home Assistant в сети и проверка адреса (docs/setup-tz.md, часть 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaDiscoveryTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    // ---- Проверка адреса ----

    private fun check(response: MockResponse): HaServer.Check = MockWebServer().use { server ->
        server.enqueue(response)
        server.start()
        HaServer.check(server.url("/").toString())
    }

    @Test fun onlyHomeAssistantIsRecognised() {
        val ready = check(MockResponse().setBody("""{"providers":[{"name":"Home Assistant Local","id":null,"type":"homeassistant"}],"preselect_remember_me":true}"""))
        assertEquals(HaServer.State.READY, ready.state)
        assertTrue(ready.origin, ready.origin.startsWith("http://"))
        // Прежние версии Home Assistant отвечали списком.
        assertEquals(HaServer.State.READY, check(MockResponse().setBody("""[{"name":"x","id":null,"type":"homeassistant"}]""")).state)
        val onboarding = check(MockResponse().setResponseCode(400).setBody("""{"message":"Onboarding not finished","code":"onboarding_required"}"""))
        assertEquals(HaServer.State.ONBOARDING, onboarding.state)
        assertTrue(onboarding.message.contains("ещё не настроен"))
        for (other in listOf(MockResponse().setBody("<html>Роутер</html>"), MockResponse().setResponseCode(404), MockResponse().setBody("{}"),
            MockResponse().setBody("x".repeat(300_000)))) assertEquals(HaServer.State.NOT_HA, check(other).state)
        assertEquals(HaServer.State.UNREACHABLE, HaServer.check("http://127.0.0.1:1").state)
        assertEquals(HaServer.State.UNREACHABLE, HaServer.check("   ").state)
    }

    @Test fun originKeepsPortAndDropsPath() {
        assertEquals("http://192.168.1.10:8123", HaServer.origin("192.168.1.10:8123"))
        assertEquals("https://ha.example.com", HaServer.origin("https://ha.example.com/"))
        assertEquals("https://ha.example.com:8443", HaServer.origin("wss://ha.example.com:8443/api/websocket"))
        assertEquals("http://homeassistant.local:8123", HaServer.origin("http://homeassistant.local:8123/lovelace"))
    }

    @Test fun redirectToHttpsProxyIsFollowed() {
        MockWebServer().use { target ->
            target.enqueue(MockResponse().setBody("""{"providers":[{"type":"homeassistant"}]}"""))
            target.start()
            MockWebServer().use { proxy ->
                proxy.enqueue(MockResponse().setResponseCode(301).setHeader("Location", target.url("/auth/providers").toString()))
                proxy.start()
                val result = HaServer.check(proxy.url("/").toString())
                assertEquals(HaServer.State.READY, result.state)
                assertEquals(HaServer.origin(target.url("/").toString()), result.origin)
            }
        }
    }

    // ---- Поиск ----

    private class FakeBrowser : HaBrowser {
        var onService: ((HaService) -> Unit)? = null
        var stopped = false
        override fun start(onService: (HaService) -> Unit) { this.onService = onService }
        override fun stop() { stopped = true }
    }

    private fun service(host: String, vararg txt: Pair<String, String>) = HaService("Дом", host, 8123, mapOf(*txt))

    private fun discovery(browser: FakeBrowser, found: MutableList<HaFound>, names: List<String> = emptyList(),
                          alive: (String) -> HaServer.State) = HaDiscovery(context, { found.clear(); found.addAll(it) }, browser,
        { address -> HaServer.Check(alive(address), HaServer.origin(address), "") }, names, { InetAddress.getByName("192.0.2.40") to 24 })

    @Test fun mdnsServiceUsesInternalUrlWhenItAnswersAndFallsBackToAddress() {
        val found = CopyOnWriteArrayList<HaFound>()
        val browser = FakeBrowser()
        val search = discovery(browser, found) { if (it.contains("dead")) HaServer.State.UNREACHABLE else HaServer.State.READY }
        search.start()
        browser.onService!!(service("192.0.2.10", "location_name" to "Мой дом", "version" to "2026.11.3", "uuid" to "u1",
            "internal_url" to "http://192.0.2.10:8123", "external_url" to "https://home.example.com"))
        await("первый найден") { found.size == 1 }
        assertEquals(HaFound("http://192.0.2.10:8123", "Мой дом", "2026.11.3", "u1", "https://home.example.com"), found.single())
        // internal_url не отвечает — берётся адрес из mDNS; внешний http не предлагается; имя «….local» не используется.
        browser.onService!!(service("192.0.2.11", "uuid" to "u2", "internal_url" to "http://dead.example:8123", "external_url" to "http://open.example.com"))
        browser.onService!!(service("192.0.2.12", "uuid" to "u3", "internal_url" to "http://homeassistant.local:8123"))
        await("все найдены") { found.size == 3 }
        assertEquals(setOf("http://192.0.2.10:8123", "http://192.0.2.11:8123", "http://192.0.2.12:8123"), found.map { it.address }.toSet())
        assertEquals("", found.single { it.uuid == "u2" }.external)
        assertEquals("без названия — имя службы", "Дом", found.single { it.uuid == "u3" }.name)
        // Тот же сервер ещё раз (два интерфейса) — одна строка.
        browser.onService!!(service("192.0.2.10", "location_name" to "Мой дом", "version" to "2026.11.3", "uuid" to "u1",
            "internal_url" to "http://192.0.2.10:8123", "external_url" to "https://home.example.com"))
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, found.size)
        search.stop()
        assertTrue(browser.stopped)
    }

    @Test fun silentAndForeignServicesAreNotShownAndUnfinishedIsMarked() {
        val found = CopyOnWriteArrayList<HaFound>()
        val browser = FakeBrowser()
        val search = discovery(browser, found) {
            when { it.contains(".10:") -> HaServer.State.UNREACHABLE; it.contains(".11:") -> HaServer.State.NOT_HA; else -> HaServer.State.ONBOARDING }
        }
        search.start()
        browser.onService!!(service("192.0.2.10")); browser.onService!!(service("192.0.2.11"))
        browser.onService!!(service("192.0.2.12", "version" to "0000.0.0"))
        await("только ненастроенный") { found.size == 1 }
        assertTrue(found.single().onboarding)
        assertEquals("версия заглушки не показывается", "", found.single().version)
        search.stop()
    }

    @Test fun usualNamesAreTriedOnlyAfterPause() {
        val found = CopyOnWriteArrayList<HaFound>()
        val asked = CopyOnWriteArrayList<String>()
        val search = discovery(FakeBrowser(), found, listOf("http://127.0.0.1:8123")) { asked += it; HaServer.State.READY }
        search.start()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(asked.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        await("найден по имени") { found.size == 1 }
        search.stop()
        // После остановки поздний ответ ничего не меняет.
        val after = found.toList()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(after, found.toList())
    }

    @Test fun scanCoversOnlyOwnSubnet() {
        val hosts = HaScan.hosts(InetAddress.getByName("192.0.2.40"), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.0.2.1", hosts.first())
        assertEquals("192.0.2.254", hosts.last())
        assertFalse("себя не проверяем", "192.0.2.40" in hosts)
        assertEquals("большая сеть — только своя /24", 253, HaScan.hosts(InetAddress.getByName("10.20.30.40"), 16).size)
        assertTrue(HaScan.hosts(InetAddress.getByName("10.20.30.40"), 16).all { it.startsWith("10.20.30.") })
        assertEquals(listOf("192.0.2.41", "192.0.2.42"), HaScan.hosts(InetAddress.getByName("192.0.2.43"), 30).filter { it != "192.0.2.43" }.take(2))
        assertTrue(HaScan.hosts(InetAddress.getByName("2001:db8::1"), 64).isEmpty())
    }
}
