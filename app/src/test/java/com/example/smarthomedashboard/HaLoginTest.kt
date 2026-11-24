package com.example.smarthomedashboard

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Вход в Home Assistant: код → постоянный доступ (docs/setup-tz.md, часть 1) и вход с телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaLoginTest {
    /** Home Assistant на MockWebServer: выдаёт доступ по коду с PKCE, как настоящий (проверено на стенде). */
    private class FakeHa : AutoCloseable {
        val server = MockWebServer()
        var challenge = ""
        val codes = mutableMapOf("CODE" to "")
        var taken = setOf<String>()
        var tokenStatus = 200
        val revoked = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<JSONObject>()
        var admin = true
        var external = "https://home.example.com"
        val origin get() = HaServer.origin(server.url("/").toString())

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val form = if (request.method == "POST") request.body.readUtf8().split('&').associate {
                        it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") } else emptyMap()
                    return when (request.requestUrl!!.encodedPath) {
                        "/auth/token" -> when {
                            tokenStatus != 200 -> MockResponse().setResponseCode(tokenStatus).setBody("""{"error":"access_denied"}""")
                            form["code"] != "CODE" || HaLogin.challenge(form["code_verifier"].orEmpty()) != challenge ->
                                MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid code verifier"}""")
                            else -> MockResponse().setBody("""{"access_token":"TEMP","expires_in":1800,"refresh_token":"REFRESH","token_type":"Bearer"}""")
                        }
                        "/auth/revoke" -> { revoked += form["token"].orEmpty(); MockResponse() }
                        "/api/websocket" -> MockResponse().withWebSocketUpgrade(Session())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        private inner class Session : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required","ha_version":"2026.11.3"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                fun answer(result: Any, ok: Boolean = true) = webSocket.send(JSONObject().put("id", json.getInt("id")).put("type", "result")
                    .put("success", ok).put(if (ok) "result" else "error", result).toString())
                when (json.getString("type")) {
                    "auth" -> webSocket.send(if (json.getString("access_token") == "TEMP") """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                    "get_config" -> answer(JSONObject().put("location_name", "Мой дом").put("external_url", external))
                    "auth/current_user" -> answer(JSONObject().put("is_admin", admin))
                    "auth/long_lived_access_token" -> {
                        commands += json
                        if (json.getString("client_name") in taken) answer(JSONObject().put("code", "unknown_error"), ok = false)
                        else answer("LONG-LIVED")
                    }
                    else -> answer(JSONArray())
                }
            }
        }

        override fun close() { server.shutdown() }
    }

    @Test fun authorizeAddressCarriesPkceAndState() {
        val attempt = HaLogin.onTablet("http://192.0.2.10:8123")
        val url = attempt.url.toHttpUrl()
        assertEquals("/auth/authorize", url.encodedPath)
        assertEquals("http://192.0.2.10:8123/", url.queryParameter("client_id"))
        assertTrue(url.queryParameter("redirect_uri")!!.startsWith("http://192.0.2.10:8123/?panel_login="))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(HaLogin.challenge(attempt.verifier), url.queryParameter("code_challenge"))
        assertEquals(43, url.queryParameter("code_challenge")!!.length)
        assertTrue("проверочное слово не уходит", !attempt.url.contains(attempt.verifier))
        assertNotEquals(HaLogin.onTablet("http://192.0.2.10:8123").state, attempt.state)
        // Пример из RFC 7636, приложение B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", HaLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun onlyOwnReturnWithOwnStateGivesCode() {
        val attempt = HaLogin.onTablet("http://192.0.2.10:8123")
        val back = "${attempt.redirect}&code=CODE&state=${attempt.state}"
        assertTrue(HaLogin.isReturn(attempt, back))
        assertEquals("CODE", HaLogin.code(attempt, back))
        assertFalse(HaLogin.isReturn(attempt, "http://192.0.2.10:8123/"))
        assertFalse(HaLogin.isReturn(attempt, attempt.redirect + "x&code=1"))
        assertFalse(HaLogin.isReturn(attempt, "http://192.0.2.11:8123" + back.removePrefix("http://192.0.2.10:8123")))
        for (bad in listOf("${attempt.redirect}&code=CODE&state=чужое", "${attempt.redirect}&code=CODE", "${attempt.redirect}&state=${attempt.state}",
            "${attempt.redirect}&state=${attempt.state}&code=" + "a".repeat(600)))
            assertThrows(bad, HaLogin.LoginException::class.java) { HaLogin.code(attempt, bad) }
    }

    @Test fun codeBecomesLongLivedAccessAndTemporaryIsRevoked() = FakeHa().use { ha ->
        val attempt = HaLogin.onTablet(ha.origin)
        ha.challenge = HaLogin.challenge(attempt.verifier)
        val granted = HaLogin.grant(attempt, "CODE", "Панель — тест")
        assertEquals("LONG-LIVED", granted.token)
        assertEquals("Мой дом", granted.name)
        assertEquals("https://home.example.com", granted.external)
        assertTrue(granted.admin)
        assertEquals("Панель — тест", ha.commands.single().getString("client_name"))
        assertEquals(3650, ha.commands.single().getInt("lifespan"))
        assertEquals(listOf("REFRESH"), ha.revoked)
        assertFalse("токен не печатается", granted.toString().contains("LONG-LIVED"))
    }

    @Test fun takenNameIsRetriedOnceAndUnencryptedExternalAddressIsIgnored() = FakeHa().use { ha ->
        val attempt = HaLogin.onTablet(ha.origin)
        ha.challenge = HaLogin.challenge(attempt.verifier)
        ha.taken = setOf("Панель")
        ha.admin = false
        ha.external = "http://home.example.com"
        val granted = HaLogin.grant(attempt, "CODE", "Панель")
        assertEquals("LONG-LIVED", granted.token)
        assertEquals("", granted.external)
        assertFalse(granted.admin)
        assertEquals(2, ha.commands.size)
        assertTrue(ha.commands[1].getString("client_name").startsWith("Панель "))
    }

    @Test fun refusalsHaveClearTextAndNothingIsIssued() = FakeHa().use { ha ->
        val attempt = HaLogin.onTablet(ha.origin)
        // Чужое проверочное слово (код перехвачен) — отказ.
        ha.challenge = HaLogin.challenge("x".repeat(64))
        assertTrue(assertThrows(HaLogin.LoginException::class.java) { HaLogin.grant(attempt, "CODE", "Панель") }.message!!.contains("не принял вход"))
        ha.tokenStatus = 403
        assertTrue(assertThrows(HaLogin.LoginException::class.java) { HaLogin.grant(attempt, "CODE", "Панель") }.message!!.contains("не разрешён"))
        assertTrue(ha.commands.isEmpty())
        ha.server.shutdown()
        assertThrows(HaLogin.LoginException::class.java) { HaLogin.grant(attempt, "CODE", "Панель") }
        Unit
    }

    // ---- С телефона ----

    private fun get(url: String): Pair<Int, Map<String, List<String>>> {
        val c = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 2_000; c.readTimeout = 5_000
        return try { c.responseCode to c.headerFields.filterKeys { it != null } } finally { c.disconnect() }
    }

    @Test fun phonePageSendsToHomeAssistantAndAcceptsOnlyOwnReturnOnce() {
        val codes = CopyOnWriteArrayList<String>()
        PhoneLogin("http://192.0.2.10:8123", InetAddress.getByName("127.0.0.1"), { _, code -> codes += code }).use { page ->
            page.start()
            val base = page.link.substringBeforeLast('/')
            val (status, headers) = get(page.link)
            assertEquals(302, status)
            val to = headers["Location"]!!.single().toHttpUrl()
            assertEquals("192.0.2.10", to.host)
            assertEquals("$base/", to.queryParameter("client_id"))
            assertEquals("${page.link}/done", to.queryParameter("redirect_uri"))
            assertTrue(headers.entries.any { it.key.equals("Cache-Control", true) && it.value == listOf("no-store") })
            // Без секретного пути — ничего; чужое состояние — отказ.
            assertEquals(404, get("$base/").first)
            assertEquals(404, get("$base/done?code=A&state=${page.attempt.state}").first)
            assertEquals(400, get("${page.link}/done?code=A&state=x").first)
            assertTrue(codes.isEmpty())
            assertEquals(200, get("${page.link}/done?code=A&state=${page.attempt.state}").first)
            // Обновление страницы на телефоне второй раз вход не запускает.
            assertEquals(200, get("${page.link}/done?code=B&state=${page.attempt.state}").first)
            assertEquals(200, get(page.link).first)
            assertEquals(listOf("A"), codes.toList())
        }
    }

    @Test fun phonePageClosesByItselfAndSecretsDiffer() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val page = PhoneLogin("http://192.0.2.10:8123", InetAddress.getByName("127.0.0.1"), { _, _ -> }, { closed.countDown() }, lifetimeMs = 50)
        page.start()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertThrows(java.io.IOException::class.java) { get(page.link) }
        PhoneLogin("http://192.0.2.10:8123", InetAddress.getByName("127.0.0.1"), { _, _ -> }).use { other ->
            assertNotEquals(page.link.substringAfterLast('/'), other.link.substringAfterLast('/'))
            assertTrue("128 бит", other.link.substringAfterLast('/').length >= 22)
        }
    }
}
