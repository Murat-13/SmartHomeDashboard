package com.example.smarthomedashboard

import android.os.Looper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections

/**
 * Калитки и ворота, этап 4 (docs/gate-tz.md): связь напрямую — Shelly Gen2 по HTTP RPC с паролем (Digest SHA-256),
 * свой HTTP-запрос, опрос положения, запасной путь при недоступном Home Assistant. Устройство — поддельное, в тесте.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class GateDirectTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var password: String? = null
    @Volatile private var output = false
    @Volatile private var door = false
    @Volatile private var coverState = "closed"
    private val states = HashMap<String, String>()
    private var connected = true
    private val readings get() = BatteryReadings(HaSession(connected, connected), states::get, { false }, { null })

    /** Поддельный Shelly: пароль как у настоящего (admin, Digest SHA-256, realm = id устройства). */
    private val shelly = object : Dispatcher() {
        private val nonces = Collections.synchronizedSet(HashSet<String>())
        private fun h(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path == "/shelly") return json(JSONObject().put("id", "shellyplus1-test").put("model", "SNSW-001X16EU").put("gen", 2)
                .put("auth_en", password != null))
            password?.let { pass ->
                val f = GateDirect.Digest.fields(request.getHeader("Authorization").orEmpty())
                val ha1 = h("admin:shellyplus1-test:$pass")
                val ha2 = h("${request.method}:${f["uri"]}")
                val good = h("$ha1:${f["nonce"]}:${f["nc"]}:${f["cnonce"]}:${f["qop"]}:$ha2")
                if (f["nonce"] !in nonces || f["response"] != good) {
                    val nonce = "n" + System.nanoTime()
                    nonces += nonce
                    return MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Digest qop=\"auth\", realm=\"shellyplus1-test\", nonce=\"$nonce\", algorithm=SHA-256")
                }
            }
            calls += path
            return when {
                path.startsWith("/rpc/Switch.Set") -> { output = true; json(JSONObject().put("was_on", false)) }
                path.startsWith("/rpc/Switch.GetStatus") -> json(JSONObject().put("id", 0).put("output", output))
                path.startsWith("/rpc/Input.GetStatus") -> json(JSONObject().put("id", 0).put("state", door))
                path.startsWith("/rpc/Cover.Open") -> { coverState = "opening"; MockResponse().setBody("null") }
                path.startsWith("/rpc/Cover.Stop") -> { coverState = "stopped"; MockResponse().setBody("null") }
                path.startsWith("/rpc/Cover.GetStatus") -> json(JSONObject().put("state", coverState).put("current_pos", 40))
                path.startsWith("/open") -> MockResponse().setBody("ok")
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())
    }

    @Before fun setUp() {
        GateHub.reset()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().commit()
        server.dispatcher = shelly
        server.start()
        GateHub.attach(context)
    }

    @After fun tearDown() {
        GateHub.reset()
        server.shutdown()
    }

    private val host get() = "${server.hostName}:${server.port}"

    private fun relay(input: Int = -1, link: Gate.Link = Gate.Link.DIRECT) = Gate("gd", "Калитка", Gate.Kind.RELAY, link = link,
        direct = Gate.Direct(host = host, input = input, pulseSeconds = 4))

    /** Ждём фоновую сеть и главный поток, пока не сбудется [condition]. */
    private fun await(what: String, stepMs: Long = 100, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("не дождались: $what")
    }

    // ---- Модель ----

    @Test fun directSurvivesJsonAndChoosesWhatCanBeSent() {
        val gate = relay(input = 0)
        assertEquals(gate, Gate.fromJson(gate.toJson()))
        assertEquals("Старый проход — через Home Assistant", Gate.Link.HA, Gate.fromJson(Gate("x", "К", Gate.Kind.RELAY, open = "switch.x").toJson().apply { remove("link"); remove("direct") })!!.link)
        assertTrue("Напрямую без сущностей HA — готов", gate.ready)
        assertFalse("Без адреса — не готов", gate.copy(direct = Gate.Direct(host = "")).ready)
        assertFalse("Замок — только через HA", Gate("l", "Замок", Gate.Kind.LOCK, link = Gate.Link.DIRECT, direct = Gate.Direct(host = "h")).hasDirect)
        assertEquals(listOf(Gate.Action.OPEN), GateLogic.actions(gate, readings))
        val drive = Gate("gc", "Ворота", Gate.Kind.COVER, link = Gate.Link.DIRECT, direct = Gate.Direct(host = "h"))
        assertEquals("Shelly-привод: «Стоп» во время хода", listOf(Gate.Action.OPEN, Gate.Action.CLOSE, Gate.Action.STOP),
            GateLogic.actions(drive, readings, moving = true))
        val http = Gate("gh", "Ворота", Gate.Kind.SCRIPT, link = Gate.Link.DIRECT,
            direct = Gate.Direct(protocol = Gate.Direct.Protocol.HTTP, openUrl = "http://h/open"))
        assertEquals("Без адреса «Закрыть» — только «Открыть»", listOf(Gate.Action.OPEN), GateLogic.actions(http, readings))
        assertEquals("Shelly: импульс выключается сам", "Switch.Set" to mapOf("id" to "0", "on" to "true", "toggle_after" to "4"),
            GateDirect.shellyCall(gate, Gate.Action.OPEN))
    }

    @Test fun fallbackOnlyWhenHomeAssistantIsDown() {
        val gate = relay(link = Gate.Link.HA).copy(open = "switch.wicket")
        assertFalse(GateLogic.useDirect(gate, readings))
        connected = false
        assertTrue("HA недоступен — напрямую", GateLogic.useDirect(gate, readings))
        assertFalse("Без связи напрямую запасного пути нет", GateLogic.useDirect(gate.copy(direct = null), readings))
    }

    // ---- Сеть: Shelly с паролем ----

    @Test fun shellyPulseWithDigestPassword() {
        password = "секрет-1"
        val gate = relay()
        assertEquals("Неверный пароль — понятная причина", GateDirect.Result(false, "неверный пароль устройства"),
            GateDirect.send(gate, Gate.Action.OPEN, "не тот"))
        assertTrue(calls.isEmpty())
        assertTrue(GateDirect.send(gate, Gate.Action.OPEN, "секрет-1").ok)
        assertEquals(listOf("/rpc/Switch.Set?id=0&on=true&toggle_after=4"), calls.toList())
        val state = GateDirect.poll(relay(input = 0), "секрет-1")
        assertEquals(DirectState(true, switchOn = true, input = false), state)
        assertNull("Не тот пароль — ответа нет", GateDirect.poll(gate, "не тот"))
        val info = GateDirect.probe(host, "секрет-1")
        assertNotNull(info)
        assertEquals("SNSW-001X16EU", info!!.model)
        assertTrue(info.authNeeded && info.authOk)
        assertFalse(GateDirect.probe(host, "не тот")!!.authOk)
    }

    /** Устройство закрывает соединение после каждого ответа (HTTP/1.0, простые контроллеры): опрос подряд не падает. */
    @Test fun deviceClosingEveryConnectionStillAnswersPolls() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request.path.orEmpty()
                return shelly.dispatch(request).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
            }
        }
        repeat(5) { assertNotNull("опрос ${it + 1}", GateDirect.poll(relay(input = 0), null)) }
    }

    /** GATE-01 (аудит 25.11.2026): проверка связи у своего HTTP не обращается к адресу команды — ни GET, ни HEAD. */
    @Test fun httpGateIsPolledWithoutTouchingCommandAddress() {
        val gate = Gate("gh", "Ворота", Gate.Kind.SCRIPT, link = Gate.Link.DIRECT, direct = Gate.Direct(protocol = Gate.Direct.Protocol.HTTP,
            openUrl = "http://$host/open?pulse=1", closeUrl = "http://$host/close"))
        assertEquals("устройство на связи", DirectState(true), GateDirect.poll(gate, null))
        Thread.sleep(200)
        assertEquals("ни одного запроса", 0, server.requestCount)
        assertNull("устройства нет", GateDirect.poll(gate.copy(direct = gate.direct!!.copy(openUrl = "http://127.0.0.1:1/open")), null))
        assertNull("адрес испорчен", GateDirect.poll(gate.copy(direct = gate.direct!!.copy(openUrl = "не адрес")), null))
        // Сама команда по-прежнему уходит на адрес — один раз.
        assertTrue(GateDirect.send(gate.copy(direct = gate.direct!!.copy(openUrl = "http://$host/open")), Gate.Action.OPEN, null).ok)
        assertEquals(1, server.requestCount)
    }

    // ---- SEC-02 (аудит 25.11.2026): пароль, вписанный в адрес своего запроса ----

    private fun httpGate(open: String, close: String = "", id: String = "gp") = Gate(id, "Ворота", Gate.Kind.SCRIPT, link = Gate.Link.DIRECT,
        direct = Gate.Direct(protocol = Gate.Direct.Protocol.HTTP, openUrl = open, closeUrl = close))

    @Test fun passwordIsSplitFromAddressOnlyWhenNothingElseChanges() {
        val (gate, secret) = GateDirect.splitPassword(httpGate("http://vasya:p%40ss@192.168.1.9:8080/o?pulse=1#x"))!!
        assertEquals("http://vasya@192.168.1.9:8080/o?pulse=1#x", gate.direct!!.openUrl)
        assertEquals("p@ss", secret)
        val both = GateDirect.splitPassword(httpGate("http://a:p@192.168.1.9/open", "http://b:p@192.168.1.10/close"))!!
        assertEquals("http://a@192.168.1.9/open" to "http://b@192.168.1.10/close", both.first.direct!!.let { it.openUrl to it.closeUrl })
        assertNull("пароля нет", GateDirect.splitPassword(httpGate("http://vasya@192.168.1.9/o")))
        assertNull("без логина и пароля", GateDirect.splitPassword(httpGate("http://192.168.1.9/o?a=b@c")))
        assertNull("пароли разные — сохранённый один на проход", GateDirect.splitPassword(httpGate("http://a:p@h/o", "http://a:q@h/c")))
        assertNull("у второго адреса пароля нет — ему ушёл бы чужой", GateDirect.splitPassword(httpGate("http://a:p@h/o", "http://other/c")))
        assertNull("адрес испорчен", GateDirect.splitPassword(httpGate("не адрес a:p@h")))
        val shelly = httpGate("http://a:p@h/o").let { it.copy(direct = it.direct!!.copy(protocol = Gate.Direct.Protocol.SHELLY, host = "192.168.1.9")) }
        assertNull("Shelly — свой пароль", GateDirect.splitPassword(shelly))
    }

    private class SoftKeys : SecretKeyProvider {
        var broken = false
        /** Ключа «нет», но новый создаётся. */
        var hidden = false
        private var key: javax.crypto.SecretKey? = null
        override fun existing() = key.takeUnless { broken || hidden }
        override fun create(): javax.crypto.SecretKey = if (broken) throw java.security.KeyStoreException("нет") else
            javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private fun prefs() = context.getSharedPreferences("dashboard_prefs", 0)
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    @Test fun addressPasswordMovesToEncryptedStorageAndCommandStillWorks() {
        val keys = SoftKeys()
        val previous = CameraPasswords.secrets
        CameraPasswords.secrets = { CameraSecrets(it, keys) }
        try {
            val address = "http://vasya:s%C3%A9cret@$host/open"
            // Повреждённая запись и незнакомое поле рядом остаются как были.
            val damaged = JSONObject().put("id", "x").put("kind", "неизвестный")
            val stored = org.json.JSONArray().put(damaged).put(httpGate(address).toJson().put("future", 7))
            prefs().edit().putString(GateStore.KEY, stored.toString()).commit()
            GateHub.invalidate()
            // Хранилище не пишет — адрес не трогаем: пароль не должен пропасть.
            keys.broken = true
            GateStore.hidePasswords(context)
            Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(stored.toString(), prefs().getString(GateStore.KEY, null))
            keys.broken = false
            GateStore.hidePasswords(context)
            awaitTrue("пароль убран из настроек") { "cret" !in prefs().getString(GateStore.KEY, "")!! }
            val now = org.json.JSONArray(prefs().getString(GateStore.KEY, null))
            assertEquals(damaged.toString(), now.getJSONObject(0).toString())
            assertEquals(7, now.getJSONObject(1).getInt("future"))
            val gate = GateStore.load(context).single()
            assertEquals("http://vasya@$host/open", gate.direct!!.openUrl)
            assertEquals("sécret", CameraPasswords.load(context, GateDirect.secretKey("gp")).value)
            assertFalse("в копию настроек пароль не попадает", ConfigBackup.hasAddressSecrets(ConfigBackup.build(context)))
            // Команда — с тем же логином и паролем.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == okhttp3.Credentials.basic("vasya", "sécret")) { calls += request.path.orEmpty(); MockResponse().setBody("ok") }
                    else MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"gate\"")
            }
            assertTrue(GateDirect.send(gate, Gate.Action.OPEN, CameraPasswords.load(context, GateDirect.secretKey("gp")).value).ok)
            assertEquals(listOf("/open"), calls.toList())
        } finally {
            CameraPasswords.secrets = previous
            CameraPasswords.clearCache()
            prefs().edit().remove(GateStore.KEY).commit()
            context.getSharedPreferences(CameraSecrets.PREFS, 0).edit().clear().commit()
            GateHub.invalidate()
        }
    }

    /** Калитка открывается и при сбое хранилища ключей: пароль, прочитанный раньше, остаётся в памяти. */
    @Test fun storageFailureDoesNotKeepGateClosed() {
        val keys = SoftKeys()
        val previous = CameraPasswords.secrets
        CameraPasswords.secrets = { CameraSecrets(it, keys) }
        try {
            GateHub.reset()
            GateHub.attach(context)
            val gate = httpGate("http://vasya@$host/open")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == okhttp3.Credentials.basic("vasya", "parol")) { calls += request.path.orEmpty(); MockResponse().setBody("ok") }
                    else MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"gate\"")
            }
            assertTrue(CameraSecrets(context, keys).write(GateDirect.secretKey("gp"), "parol"))
            keys.broken = true
            assertEquals("пароль ещё не читали — честная причина", "пароль не прочитан, повторите", GateHub.direct.send(gate, Gate.Action.OPEN).detail)
            keys.broken = false
            assertTrue(GateHub.direct.send(gate, Gate.Action.OPEN).ok)
            keys.broken = true
            assertTrue("сбой хранилища — пароль, прочитанный раньше", GateHub.direct.send(gate, Gate.Action.OPEN).ok)
            assertEquals(listOf("/open", "/open"), calls.toList())
            keys.broken = false
            assertTrue(CameraSecrets(context, keys).write(GateDirect.secretKey("gp"), ""))
            assertEquals("пароль удалили — это не сбой", GateDirect.WRONG_PASSWORD, GateHub.direct.send(gate, Gate.Action.OPEN).detail)
            assertEquals("связь проверяется без пароля и без запроса", DirectState(true), GateHub.direct.poll(gate))
        } finally {
            CameraPasswords.secrets = previous
            CameraPasswords.clearCache()
            context.getSharedPreferences(CameraSecrets.PREFS, 0).edit().clear().commit()
            GateHub.reset()
        }
    }

    /** Ключ временно недоступен: перенос не создаёт новый — иначе пароли камер стали бы нечитаемыми. */
    @Test fun moveNeverReplacesMissingKey() {
        val keys = SoftKeys()
        val previous = CameraPasswords.secrets
        CameraPasswords.secrets = { CameraSecrets(it, keys) }
        try {
            assertTrue(CameraSecrets(context, keys).write("cam", "camera-pass"))
            keys.hidden = true
            var done: Boolean? = null
            CameraPasswords.move(context, GateDirect.secretKey("gp"), "p") { done = it }
            awaitTrue("ответ") { done != null }
            assertEquals(false, done)
            keys.hidden = false
            assertEquals("camera-pass", CameraSecrets(context, keys).read("cam"))
        } finally {
            CameraPasswords.secrets = previous
            CameraPasswords.clearCache()
            context.getSharedPreferences(CameraSecrets.PREFS, 0).edit().clear().commit()
        }
    }

    @Test fun gateChangedMeanwhileIsNotOverwritten() {
        val keys = SoftKeys()
        val previous = CameraPasswords.secrets
        CameraPasswords.secrets = { CameraSecrets(it, keys) }
        try {
            GateStore.save(context, listOf(httpGate("http://a:p@192.168.1.9/open")))
            GateStore.hidePasswords(context)
            // Пока пароль записывался, адрес поменяли в редакторе.
            GateStore.save(context, listOf(httpGate("http://192.168.1.77/open")))
            awaitTrue("пароль записан") { CameraPasswords.load(context, GateDirect.secretKey("gp")).value == "p" }
            Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("http://192.168.1.77/open", GateStore.load(context).single().direct!!.openUrl)
        } finally {
            CameraPasswords.secrets = previous
            CameraPasswords.clearCache()
            prefs().edit().remove(GateStore.KEY).commit()
            context.getSharedPreferences(CameraSecrets.PREFS, 0).edit().clear().commit()
            GateHub.invalidate()
        }
    }

    @Test fun deviceGoneIsUnreachableNotCrash() {
        val gate = relay().copy(direct = Gate.Direct(host = "127.0.0.1:1"))
        assertEquals(GateDirect.Result(false, "устройство не отвечает"), GateDirect.send(gate, Gate.Action.OPEN, null))
        assertNull(GateDirect.poll(gate, null))
        assertNull(GateDirect.probe("127.0.0.1:1", null))
    }

    // ---- GATE-02 (аудит 25.11.2026): команда ушла устройству, а ответа нет ----

    /** Адрес — цифрами: у имени localhost два адреса, и после сбоя OkHttp начинает с другого. */
    private fun device(input: Int = -1) = relay(input).let { it.copy(direct = it.direct!!.copy(host = "127.0.0.1:${server.port}")) }

    /** Устройство приняло соединение и запрос, но не ответило: могло и выполнить. Не открылось соединение — точно нет. */
    @Test fun requestThatLeftButGotNoAnswerIsUnclear() {
        fun answerWith(policy: okhttp3.mockwebserver.SocketPolicy) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { calls += request.path.orEmpty(); return MockResponse().setSocketPolicy(policy) }
            }
        }
        answerWith(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        assertEquals(GateDirect.Result(false, "устройство не отвечает", unclear = true), GateDirect.send(device(), Gate.Action.OPEN, null))
        assertEquals("один импульс, без повтора", 1, calls.size)
        answerWith(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        assertEquals(GateDirect.Result(false, "устройство не отвечает", unclear = true),
            GateDirect.send(httpGate("http://127.0.0.1:${server.port}/open"), Gate.Action.OPEN, null))
        // Ответ есть — исход ясен, каким бы он ни был.
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        assertEquals(GateDirect.Result(false, "устройство отказало (500)"), GateDirect.send(device(), Gate.Action.OPEN, null))
    }

    /** Устройство ответило «нужен пароль» (команда не выполнена) и пропало до повтора с паролем — исход ясен. */
    @Test fun refusedWithoutPasswordThenGoneIsFailed() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Digest qop=\"auth\", realm=\"r\", nonce=\"n\", algorithm=SHA-256")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.SHUTDOWN_SERVER_AFTER_RESPONSE)
        }
        assertEquals(GateDirect.Result(false, "устройство не отвечает"), GateDirect.send(device(), Gate.Action.OPEN, "пароль"))
    }

    @Test fun unclearDirectCommandIsUnconfirmedAndNotRepeated() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/rpc/Switch.Set")) { calls += request.path.orEmpty(); output = true
                    return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return shelly.dispatch(request)
            }
        }
        // Положение узнать неоткуда — сразу «подтверждения нет», а не «не удалось, удерживайте, чтобы повторить».
        GateStore.save(context, listOf(device()))
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        await("итог") { GateHub.op("gd")?.busy == false }
        assertEquals(GateHub.Phase.UNCONFIRMED, GateHub.op("gd")!!.phase)
        assertEquals("устройство не отвечает", GateHub.op("gd")!!.detail)
        assertEquals(1, calls.count { it.startsWith("/rpc/Switch.Set") })
        GateHub.dismiss("gd")
        // Есть датчик — дождались факта.
        GateStore.save(context, listOf(device(input = 0)))
        GateHub.onState(context, readings)
        await("первый опрос") { calls.any { it.startsWith("/rpc/Input.GetStatus") } }
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        await("ждём факта") { GateHub.op("gd")?.phase == GateHub.Phase.WAITING }
        door = true
        await("подтверждение по входу") { GateHub.op("gd")?.phase == GateHub.Phase.DONE }
    }

    /** Устройство пропало после команды: «не отвечает» — не «новое положение» калитки. */
    @Test fun deviceGoneAfterCommandIsNotConfirmed() {
        val step = Gate("gs", "Ворота", Gate.Kind.STEP, link = Gate.Link.DIRECT, confirmSeconds = 10,
            direct = Gate.Direct(host = "127.0.0.1:${server.port}", input = 0, pulseSeconds = 1))
        GateStore.save(context, listOf(step))
        GateHub.onState(context, readings)
        await("положение известно") { GateHub.status(step).position == GateStatus.Position.CLOSED }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        assertTrue(GateHub.run("gs", Gate.Action.STEP))
        await("устройство не отвечает", stepMs = 500) { GateHub.status(step).position == GateStatus.Position.UNREACHABLE }
        assertEquals(GateHub.Phase.WAITING, GateHub.op("gs")?.phase)
        await("срок вышел", stepMs = 1_000) { GateHub.op("gs")?.busy == false }
        assertEquals(GateHub.Phase.UNCONFIRMED, GateHub.op("gs")?.phase)
    }

    @Test fun silentDeviceAtDeadlineIsUnconfirmed() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        GateStore.save(context, listOf(device().copy(confirmSeconds = 5)))
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        Thread.sleep(300) // запрос ушёл, ответа нет
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(GateHub.Phase.UNCONFIRMED, GateHub.op("gd")!!.phase)
        assertEquals("устройство не ответило", GateHub.op("gd")!!.detail)
    }

    // ---- Исполнение через GateHub ----

    @Test fun relayWithoutSensorSaysReactedNotOpened() {
        GateStore.save(context, listOf(relay()))
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        await("итог") { GateHub.op("gd")?.busy == false }
        assertEquals("Без датчика — честно «сработало»", GateHub.Phase.REACTED, GateHub.op("gd")!!.phase)
        assertEquals(1, calls.count { it.startsWith("/rpc/Switch.Set") })
    }

    @Test fun relayWithDoorInputConfirmsByInputAndReportsOpened() {
        GateStore.save(context, listOf(relay(input = 0)))
        var opened = 0
        GateHub.onOpened = { opened++ }
        GateHub.onState(context, readings)
        await("первый опрос") { calls.any { it.startsWith("/rpc/Input.GetStatus") } }
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        await("ждём датчик") { GateHub.op("gd")?.phase == GateHub.Phase.WAITING }
        assertFalse("Повтор, пока ждём, не уходит", GateHub.run("gd", Gate.Action.OPEN))
        door = true
        await("подтверждение по входу Shelly") { GateHub.op("gd")?.phase == GateHub.Phase.DONE }
        assertEquals(GateStatus.Position.OPEN, GateHub.status(GateHub.gate("gd")!!).position)
        assertEquals("Окно звонка узнаёт, что открыли", 1, opened)
        assertEquals("Одно удержание — одна команда", 1, calls.count { it.startsWith("/rpc/Switch.Set") })
    }

    @Test fun shellyDriveStopsAndShowsPercent() {
        val drive = Gate("gc", "Ворота", Gate.Kind.COVER, link = Gate.Link.DIRECT, direct = Gate.Direct(host = host))
        GateStore.save(context, listOf(drive))
        assertTrue(GateHub.run("gc", Gate.Action.OPEN))
        await("едут") { GateHub.status(drive).position == GateStatus.Position.OPENING }
        assertEquals(40, GateHub.status(drive).percent)
        assertTrue("«Стоп» видно во время хода", Gate.Action.STOP in GateHub.actions(drive))
        assertTrue(GateHub.run("gc", Gate.Action.STOP))
        await("остановились") { GateHub.op("gc")?.action == Gate.Action.STOP && GateHub.op("gc")?.phase == GateHub.Phase.DONE }
        assertTrue(calls.any { it.startsWith("/rpc/Cover.Stop?id=0") })
    }

    @Test fun silentDeviceBecomesUnreachableAfterTwoMisses() {
        GateStore.save(context, listOf(relay(input = 0).copy(direct = Gate.Direct(host = "127.0.0.1:1", input = 0))))
        val gate = GateHub.gate("gd")!!
        GateHub.onState(context, readings)
        // Никто не смотрит — опрос раз в 15 с: часы теста идут быстрее.
        await("два промаха", stepMs = 2_000) { GateHub.status(gate).position == GateStatus.Position.UNREACHABLE }
        assertTrue(GateHub.status(gate).noLink)
        assertEquals("устройство не отвечает", GateLogic.statusText(GateHub.status(gate)))
    }

    @Test fun homeAssistantDownFallsBackToShelly() {
        val gate = relay(link = Gate.Link.HA).copy(open = "switch.wicket")
        GateStore.save(context, listOf(gate))
        val sentToHa = mutableListOf<String>()
        GateHub.driver = GateHub.Driver { c, _ -> sentToHa += c.entity; true }
        connected = false
        GateHub.onState(context, readings)
        assertTrue(GateHub.run("gd", Gate.Action.OPEN))
        await("ушло на Shelly") { calls.any { it.startsWith("/rpc/Switch.Set") } }
        assertTrue("В Home Assistant ничего не ушло", sentToHa.isEmpty())
    }

    /** Черновик из редактора («Проверить») опрашивается, только пока его действие идёт, — потом нет (Deep tests 22.11). */
    @Test fun draftGateIsNotPolledAfterItsAction() {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        GateHub.direct = object : GateHub.DirectLink {
            override fun send(gate: Gate, action: Gate.Action) = GateDirect.Result(true)
            override fun poll(gate: Gate): DirectState? { polls.incrementAndGet(); return DirectState(true, switchOn = false, input = false) }
        }
        val draft = relay(input = 0)
        GateHub.listen {}
        assertTrue("Проверка черновика", GateHub.run(draft, Gate.Action.OPEN))
        await("опрос во время действия") { polls.get() > 0 }
        // Подтверждения нет (дверь «не открылась») — действие заканчивается; черновик больше не опрашивается.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(draft.confirmSeconds + 10L))
        GateHub.dismiss(draft.id)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val after = polls.get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Брошенный черновик не опрашивается", after, polls.get())
    }

    @Test fun ownHttpRequestWithBasicPassword() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != okhttp3.Credentials.basic("user", "pw")) {
                    return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"gate\"")
                }
                calls += "${request.method} ${request.path}"
                return MockResponse().setBody("ok")
            }
        }
        val gate = Gate("gh", "Ворота", Gate.Kind.SCRIPT, link = Gate.Link.DIRECT, direct = Gate.Direct(protocol = Gate.Direct.Protocol.HTTP,
            openUrl = "http://user:pw@$host/open?x=1", post = true))
        assertTrue(GateDirect.send(gate, Gate.Action.OPEN, null).ok)
        assertEquals(listOf("POST /open?x=1"), calls.toList())
    }
}
