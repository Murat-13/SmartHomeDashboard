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

    @Test fun deviceGoneIsUnreachableNotCrash() {
        val gate = relay().copy(direct = Gate.Direct(host = "127.0.0.1:1"))
        assertEquals(GateDirect.Result(false, "устройство не отвечает"), GateDirect.send(gate, Gate.Action.OPEN, null))
        assertNull(GateDirect.poll(gate, null))
        assertNull(GateDirect.probe("127.0.0.1:1", null))
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
