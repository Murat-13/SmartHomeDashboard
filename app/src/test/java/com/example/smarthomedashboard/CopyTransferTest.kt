package com.example.smarthomedashboard

import com.example.smarthomedashboard.TileScale.Screen
import com.example.smarthomedashboard.data.TileEntity
import com.google.gson.Gson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Копирование на второй планшет (docs/setup-tz.md, часть 5): код, шифрование, одноразовая страница, масштаб плиток. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyTransferTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val code = "K7H2MX9AB"
    private val secretText = "Плитка «Секретная кухня»"
    private val payload = CopyPayload(JSONObject().put("tiles_json", "[]").put("temp_unit", secretText).toString(), Screen(1920, 1200, 1.5f), "samsung X", "0.9.63").toJson()
    private val events = CopyOnWriteArrayList<Pair<CopyServer.Event, Int>>()

    private fun server(lifetime: Long = 60_000) = CopyServer(loopback, code, payload, "samsung X", lifetime) { e, n -> events += e to n }.also { it.start() }
    private fun failed(block: () -> Unit) = assertThrows(CopyClient.Failed::class.java) { block() }

    // ---- Код и ключи ----

    @Test fun codeCanBeTypedInAnyKeyboardLayout() {
        assertEquals(code, CopyCode.parse("K7H-2MX-9AB"))
        assertEquals("русские буквы, пробелы, строчные", code, CopyCode.parse(" к7н 2мх 9ав "))
        assertNull(CopyCode.parse("K7H-2MX-9A"))
        assertNull("лишний знак", CopyCode.parse("K7H-2MX-9ABC"))
        assertNull("буквы вне кода не отбрасываются молча", CopyCode.parse("K7H-2MX-9AБ"))
        val all = List(2000) { CopyCode.create() }
        assertTrue(all.all { it.length == CopyCode.LENGTH && CopyCode.parse(CopyCode.pretty(it)) == it })
        assertEquals("коды не повторяются", all.size, all.toSet().size)
        assertEquals("K7H-2MX-9AB", CopyCode.pretty(code))
    }

    /** Эталоны PBKDF2-HMAC-SHA256 из RFC 7914, §11. */
    @Test fun keyDerivationMatchesStandard() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(CopyCrypto.pbkdf2("passwd", "salt".toByteArray(), 1)))
        assertEquals("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56", hex(CopyCrypto.pbkdf2("Password", "NaCl".toByteArray(), 80_000)))
    }

    // ---- Передача ----

    @Test fun rightCodeGetsSettingsOnceAndPageCloses() {
        server().use { s ->
            val got = CopyClient(s.host).take(code)
            assertEquals("samsung X", got.name)
            assertEquals(Screen(1920, 1200, 1.5f), got.screen)
            assertTrue(got.config.contains(secretText))
            assertEquals(CopyServer.Event.SENT, events.first { it.first != CopyServer.Event.CLOSED }.first)
            failed { CopyClient(s.host).take(code) }
        }
        assertEquals("закрыта один раз", 1, events.count { it.first == CopyServer.Event.CLOSED })
    }

    @Test fun wrongCodesAreCountedAndLockPage() {
        server().use { s ->
            repeat(4) { i -> assertEquals(4 - i, failed { CopyClient(s.host).take("AAAAAAAA$i".replace('0', '7').replace('1', '8')) }.left) }
            assertTrue(failed { CopyClient(s.host).take("BBBBBBBBB") }.message!!.startsWith("Слишком много неверных кодов"))
            assertTrue("закрыта — верный код уже не помогает", failed { CopyClient(s.host).take(code) }.left == -1)
            assertEquals(listOf(4, 3, 2, 1), events.filter { it.first == CopyServer.Event.WRONG }.map { it.second })
            assertTrue(events.any { it.first == CopyServer.Event.LOCKED })
        }
    }

    /** Всё, что идёт по сети: ни кода, ни настроек открытым текстом; страница из QR кода не знает. */
    @Test fun nothingSecretTravelsInPlainText() {
        val seen = StringBuilder()
        server().use { s ->
            val proxy = MockWebServer()
            proxy.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readByteArray()
                    seen.append(request.path).append(String(body))
                    val c = URL("http://${s.host}${request.path}").openConnection() as HttpURLConnection
                    if (request.method == "POST") { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body) } }
                    val answer = (if (c.responseCode < 400) c.inputStream else c.errorStream).use { it.readBytes() }
                    seen.append(String(answer))
                    return MockResponse().setResponseCode(c.responseCode).setBody(okio.Buffer().write(answer))
                }
            }
            proxy.start()
            val page = URL("http://${s.host}/").readText()
            assertTrue(page.contains("intent://copy/${s.host}/'+c+'#Intent;scheme=smarthomedashboard;package=com.example.smarthomedashboard;end"))
            assertFalse("кода в странице нет", page.contains(code))
            assertEquals("http://${s.host}/#$code", s.link)
            assertTrue(CopyClient("127.0.0.1:${proxy.port}").take(code).config.contains(secretText))
            proxy.shutdown()
        }
        assertTrue(seen.length > 200)
        assertFalse(seen.contains(code))
        assertFalse(seen.contains("Секретная") || seen.contains("temp_unit") || seen.contains("1920"))
    }

    @Test fun strangerCannotPretendToBeOldTablet() {
        var rounds = CopyCrypto.ROUNDS
        var version = 1
        val fake = MockWebServer()
        fake.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                CopyServer.HELLO -> MockResponse().setBody(JSONObject().put("v", version).put("name", "x").put("salt", CopyCrypto.text(ByteArray(16)))
                    .put("nonce", CopyCrypto.text(ByteArray(16))).put("rounds", rounds).toString())
                else -> MockResponse().setBody(JSONObject().put("iv", CopyCrypto.text(ByteArray(12))).put("data", CopyCrypto.text(ByteArray(64))).toString())
            }
        }
        fake.start()
        val host = "127.0.0.1:${fake.port}"
        assertTrue("без кода подделать нельзя", failed { CopyClient(host).take(code) }.message!!.contains("повреждёнными"))
        rounds = 1
        assertTrue("дешёвый ключ не считаем", failed { CopyClient(host).take(code) }.message!!.contains("не панель"))
        rounds = CopyCrypto.ROUNDS; version = 2
        assertTrue(failed { CopyClient(host).take(code) }.message!!.contains("другая версия"))
        fake.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>роутер</html>") }
        assertTrue(failed { CopyClient(host).take(code) }.message!!.contains("не панель"))
        fake.shutdown()
        assertTrue(failed { CopyClient(host).take(code) }.message!!.contains("не отвечает"))
    }

    @Test fun pageClosesByItselfAndIgnoresGarbage() {
        server(lifetime = 100).use {
            val end = System.currentTimeMillis() + 5_000
            while (events.none { it.first == CopyServer.Event.CLOSED } && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue(events.any { it.first == CopyServer.Event.CLOSED })
        }
        server().use { s ->
            for (path in listOf("/x", "/copy/v1", "/../etc")) assertEquals(404, (URL("http://${s.host}$path").openConnection() as HttpURLConnection).responseCode)
            java.net.Socket("127.0.0.1", s.port).use { it.getOutputStream().write("POST ${CopyServer.TAKE} HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray()) }
            assertTrue("живёт дальше", CopyClient(s.host).take(code).config.isNotEmpty())
        }
    }

    @Test fun payloadIsSameAsBackupFileWithoutSecrets() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dashboard_prefs", 0).edit().clear().putString("ha_local_host", "http://192.168.77.10:8123").putString("ha_token", "ТОКЕН").commit()
        val p = CopyPayload.of(context, Screen(1280, 800, 1f))
        assertEquals(ConfigBackup.build(context).toString(), p.config)
        assertFalse(p.toJson().contains("ТОКЕН"))
        val back = CopyPayload.parse(p.toJson())
        assertEquals(p.config, back.config)
        assertNull("невозможный экран не принимается", CopyPayload.parse(JSONObject(p.toJson()).put("screen", JSONObject().put("w", -5).put("h", 9).put("density", 1)).toString()).screen)
    }

    // ---- Масштаб ----

    private fun tile(id: String, container: String, x: Int, y: Int, w: Int, h: Int, page: Int = 0, portrait: String = "") = TileEntity(id, "x", container, id, x, y, 1, 1,
        config = JSONObject().put("button_size", w).put("widget_height", h).put("mine", "keep").toString(), page = page, portrait = portrait)
    private fun scaled(tiles: List<TileEntity>, from: Screen, to: Screen) = JSONArray(TileScale.apply(Gson().toJson(tiles), from, to)).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun scaleKeepsLayoutWithoutOverlapsOnAnyScreen() {
        val random = Random(7)
        val screens = listOf(Screen(1920, 1200, 1.5f), Screen(2000, 1200, 2f), Screen(1280, 800, 1f), Screen(1024, 600, 1f), Screen(2560, 1600, 2f), Screen(800, 1280, 1.3f))
        repeat(200) {
            val from = screens.random(random); val to = screens.random(random)
            // Сетка без наложений внутри экрана «откуда».
            val cols = random.nextInt(2, 9); val rows = random.nextInt(2, 6)
            val cw = from.long / cols; val ch = (from.short - 60) / rows
            val tiles = (0 until cols * rows).map { i ->
                val w = random.nextInt(cw / 2, cw + 1); val h = random.nextInt(ch / 2, ch + 1)
                tile("t$i", "grid", (i % cols) * cw, (i / cols) * ch, w, h)
            }
            val r = scaled(tiles, from, to).map { j -> JSONObject(j.getString("config")).let { c -> intArrayOf(j.getInt("x"), j.getInt("y"), c.getInt("button_size"), c.getInt("widget_height")) } }
            r.forEach { (x, y, w, h) -> assertTrue("внутри $to: $x+$w, $y+$h", x >= 0 && y >= 0 && x + w <= to.long + 1 && y + h <= to.short - 60 + 1) }
            for (i in r.indices) for (j in i + 1 until r.size) {
                val (ax, ay, aw, ah) = r[i]; val (bx, by, bw, bh) = r[j]
                assertFalse("наложение $i/$j", ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah)
            }
        }
    }

    @Test fun scaleKeepsEverythingElse() {
        val from = Screen(1920, 1200, 1.5f); val to = Screen(1280, 800, 1f)
        val s = TileScale.landscape(from, to)
        val list = scaled(listOf(tile("climate", "grid", 100, 50, 600, 300, portrait = PortraitLayout.Place(40, 90, 300, 150).json()),
            TileEntity("button", "button", "bottom_panel", "Свет", 20, 1000, 1, 1, config = JSONObject().put("entity_id", "light.x").toString(), page = 2, fontSize = 20),
            TileEntity("broken", "x", "grid", "Битая", 9, 9, 1, 1, config = "не json")), from, to)
        val climate = list[0]; val config = JSONObject(climate.getString("config"))
        assertEquals("2:1 сохранено", config.getInt("button_size"), config.getInt("widget_height") * 2)
        assertEquals("keep", config.getString("mine"))
        assertEquals((100 * s).toInt(), climate.getInt("x"))
        val place = PortraitLayout.Place.parse(climate.getString("portrait"))!!
        assertNotEquals(40, place.x)
        val button = JSONObject(list[1].getString("config"))
        assertEquals("размер кнопки по умолчанию тоже стал меньше", (160 * s).toInt(), button.getInt("button_size"))
        assertFalse(button.has("widget_height"))
        assertEquals(2, list[1].getInt("page"))
        assertEquals("текст — с учётом плотности", (20 * s * 1.5f).toInt().toDouble(), list[1].getInt("fontSize").toDouble(), 1.0)
        assertEquals("не json", list[2].getString("config"))
        assertEquals("[1", TileScale.apply("[1", from, to))
        assertFalse(TileScale.needed(from, Screen(1200, 1920, 1.5f)))
        assertTrue(TileScale.needed(from, to))
    }
}
