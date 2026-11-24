package com.example.smarthomedashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Вход на настоящем Home Assistant стенда (tools/ha-stand): HA_STAND=http://127.0.0.1:8124,
 * HA_STAND_SECRETS=<secrets.json стенда>. Без них пропускается. Страница входа заменена её же REST-вызовами.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaLoginStandTest {
    private val stand = System.getenv("HA_STAND").orEmpty()
    private val secrets by lazy { JSONObject(File(System.getenv("HA_STAND_SECRETS")!!).readText()) }
    private val http = OkHttpClient()

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> = http.newCall(Request.Builder().url(stand + path)
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
        r.code to (runCatching { JSONObject(r.body!!.string()) }.getOrNull() ?: JSONObject())
    }

    /** То, что делает страница входа: способ, логин и пароль → код. */
    private fun code(attempt: HaLogin.Attempt, user: String): String {
        val (status, flow) = post("/auth/login_flow", JSONObject().put("client_id", attempt.clientId).put("redirect_uri", attempt.redirect)
            .put("handler", JSONArray(listOf("homeassistant", JSONObject.NULL)))
            .put("code_challenge", HaLogin.challenge(attempt.verifier)).put("code_challenge_method", "S256"))
        assertEquals(200, status)
        val (done, result) = post("/auth/login_flow/" + flow.getString("flow_id"), JSONObject().put("client_id", attempt.clientId)
            .put("username", user).put("password", secrets.getString(user)))
        assertEquals(200, done)
        return result.getString("result")
    }

    @Test fun realHomeAssistantIsRecognised() {
        assumeTrue(stand.isNotEmpty())
        val check = HaServer.check(stand)
        assertEquals(HaServer.State.READY, check.state)
        assertEquals(stand, check.origin)
    }

    @Test fun administratorAndUserGetLongLivedAccessFromTabletAndPhone() {
        assumeTrue(stand.isNotEmpty())
        val names = mutableSetOf<String>()
        for (user in listOf("owner", "guest")) for (attempt in listOf(HaLogin.onTablet(stand),
            HaLogin.onPhone(stand, "http://192.168.77.50:41234", "path"))) {
            val granted = HaLogin.grant(attempt, code(attempt, user), "Проверка входа ${HaLogin.secret(4)}")
            assertTrue("длинный токен", granted.token.length > 100)
            assertEquals(user == "owner", granted.admin)
            assertTrue(names.add(granted.token))
            // Постоянный доступ работает как токен: REST отвечает.
            http.newCall(Request.Builder().url("$stand/api/").header("Authorization", "Bearer ${granted.token}").build()).execute()
                .use { assertEquals(200, it.code) }
        }
    }

    @Test fun foreignVerifierUsedCodeAndTakenNameAreRefusedOrRetried() {
        assumeTrue(stand.isNotEmpty())
        val attempt = HaLogin.onTablet(stand)
        val intercepted = code(attempt, "guest")
        val foreign = HaLogin.Attempt(attempt.origin, attempt.clientId, attempt.redirect, attempt.state, "x".repeat(64))
        assertThrows(HaLogin.LoginException::class.java) { HaLogin.grant(foreign, intercepted, "Чужой") }
        val name = "Панель ${HaLogin.secret(4)}"
        val first = HaLogin.onTablet(stand).let { HaLogin.grant(it, code(it, "guest"), name) }
        val second = HaLogin.onTablet(stand).let { HaLogin.grant(it, code(it, "guest"), name) }
        assertNotEquals(first.token, second.token)
        val again = HaLogin.onTablet(stand)
        val once = code(again, "guest")
        HaLogin.grant(again, once, "Панель ${HaLogin.secret(4)}")
        assertThrows("код одноразовый", HaLogin.LoginException::class.java) { HaLogin.grant(again, once, "Панель") }
        assertFalse(first.toString().contains(first.token))
    }
}
