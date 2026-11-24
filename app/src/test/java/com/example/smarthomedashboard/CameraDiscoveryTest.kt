package com.example.smarthomedashboard

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Поиск камер в домашней сети (docs/setup-tz.md, часть 3): только чтение, без паролей. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraDiscoveryTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun match(xaddrs: String, scopes: String, types: String = "tds:Device tdn:NetworkVideoTransmitter") =
        """<?xml version="1.0"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
        <SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch><d:Types>$types</d:Types><d:Scopes>$scopes</d:Scopes><d:XAddrs>$xaddrs</d:XAddrs></d:ProbeMatch></d:ProbeMatches>
        </SOAP-ENV:Body></SOAP-ENV:Envelope>"""

    // ---- ONVIF ----

    @Test fun answerOfCameraIsUnderstood() {
        val scopes = "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/hardware/Reolink%20Video%20Doorbell%20PoE " +
            "onvif://www.onvif.org/name/IPC onvif://www.onvif.org/Profile/Streaming"
        val one = WsDiscovery.parse(match("http://192.0.2.60:8000/onvif/device_service", scopes), "192.0.2.60").single()
        assertEquals(CameraFound("192.0.2.60", "", "Reolink Video Doorbell PoE", CameraBrand.REOLINK, 8000, setOf(CameraFound.Source.ONVIF), doorbell = true), one)
        assertEquals("марка в модели не повторяется", "Reolink Video Doorbell PoE", one.title)
        // Две сети у камеры: XAddrs — сначала чужой; адрес — откуда пришёл ответ, порт — его же.
        val two = WsDiscovery.parse(match("http://169.254.1.2:80/onvif/device_service http://192.0.2.61:8899/onvif/device_service",
            "onvif://www.onvif.org/hardware/DS-2CD2042 onvif://www.onvif.org/name/HIKVISION%20DS-2CD2042"), "192.0.2.61").single()
        assertEquals("192.0.2.61", two.host)
        assertEquals(8899, two.onvifPort)
        assertEquals(CameraBrand.HIKVISION, two.brand)
        assertEquals("Hikvision DS-2CD2042", two.title)
        assertFalse(two.doorbell)
    }

    @Test fun foreignAndBrokenAnswersAreIgnored() {
        assertTrue("принтер", WsDiscovery.parse(match("http://192.0.2.9:80/", "http://schemas.example/printer", "wsdp:Device print:PrintDeviceType"), "192.0.2.9").isEmpty())
        for (bad in listOf("", "не xml", "<a>", "<a/>", match("", "").replace("ProbeMatch>", "Other>"), "<x>".repeat(20_000)))
            assertTrue(WsDiscovery.parse(bad, "192.0.2.9").isEmpty())
        // Без адресов — обычный порт; слишком длинная модель обрезается.
        val bare = WsDiscovery.parse(match("", "onvif://www.onvif.org/hardware/" + "x".repeat(500)), "192.0.2.9").single()
        assertEquals(80, bare.onvifPort)
        assertEquals(60, bare.model.length)
    }

    @Test fun probesAreSentThreeTimesBothWaysAndAnswersCollected() {
        val camera = DatagramSocket(InetSocketAddress(loopback, 0))
        val asked = CopyOnWriteArrayList<String>()
        val thread = Thread {
            val buffer = ByteArray(8192)
            try { while (true) {
                val p = DatagramPacket(buffer, buffer.size); camera.receive(p)
                asked += String(p.data, 0, p.length, Charsets.UTF_8)
                val answer = match("http://127.0.0.1:8000/onvif/device_service", "onvif://www.onvif.org/hardware/Cam").toByteArray()
                camera.send(DatagramPacket(answer, answer.size, p.socketAddress))
            } } catch (_: java.io.IOException) {}
        }.apply { isDaemon = true; start() }
        val found = CopyOnWriteArrayList<CameraFound>()
        WsDiscovery.search(loopback, 2_200, target = InetSocketAddress(loopback, camera.localPort)) { found += it }
        camera.close(); thread.join(2_000)
        assertEquals(6, asked.size)
        assertEquals(3, asked.count { "NetworkVideoTransmitter" in it })
        assertTrue(asked.none { "Security" in it || "Password" in it })
        assertEquals(6, asked.map { it.substringAfter("uuid:").substringBefore("<") }.distinct().size)
        assertEquals("127.0.0.1", found.map { it.host }.distinct().single())
    }

    // ---- Reolink ----

    private fun reolink(ip: String, name: String, size: Int = 388) = ByteArray(size).also { data ->
        ip.toByteArray().copyInto(data, 108)
        name.toByteArray().copyInto(data, 132)
    }

    @Test fun reolinkAnswerIsCheckedAndParsed() {
        val found = ReolinkSearch.parse(reolink("192.0.2.60", "Калитка Doorbell"), "192.0.2.60")!!
        assertEquals(CameraBrand.REOLINK, found.brand)
        assertEquals(0, found.onvifPort)
        assertTrue(found.doorbell)
        assertNull("адрес внутри не совпал", ReolinkSearch.parse(reolink("192.0.2.61", "x"), "192.0.2.60"))
        assertNull("короткий ответ", ReolinkSearch.parse(ByteArray(40), "192.0.2.60"))
        assertNull(ReolinkSearch.parse(ByteArray(388) { 0xff.toByte() }, "192.0.2.60"))
        assertEquals("Reolink", ReolinkSearch.parse(reolink("192.0.2.60", ""), "192.0.2.60")!!.model)
        assertEquals("обрезанный ответ не роняет", "Reolink", ReolinkSearch.parse(reolink("192.0.2.60", "", 210), "192.0.2.60")!!.model)
    }

    @Test fun reolinkIsAskedByBroadcastAndHeardOnItsPort() {
        val answerPort = DatagramSocket(0).use { it.localPort }
        val camera = DatagramSocket(InetSocketAddress(loopback, 0))
        val asked = CopyOnWriteArrayList<ByteArray>()
        Thread {
            val buffer = ByteArray(64)
            try { while (true) {
                val p = DatagramPacket(buffer, buffer.size); camera.receive(p)
                asked += p.data.copyOf(p.length)
                val answer = reolink("127.0.0.1", "Двор")
                camera.send(DatagramPacket(answer, answer.size, loopback, answerPort))
            } } catch (_: java.io.IOException) {}
        }.apply { isDaemon = true; start() }
        val found = CopyOnWriteArrayList<CameraFound>()
        ReolinkSearch.search(listOf(loopback), 1_300, askPort = camera.localPort, answerPort = answerPort) { found += it }
        camera.close()
        assertTrue(asked.isNotEmpty() && asked.all { it.contentEquals(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0, 0)) })
        assertEquals("Двор", found.map { it.model }.distinct().single())
    }

    // ---- Home Assistant ----

    private fun home(vararg devices: Triple<String, String, String>, cameras: List<String> = devices.map { it.first }): HaHome = HaHome.parse(
        JSONArray(), JSONArray(), JSONArray(devices.map { (id, name, url) -> JSONObject().put("id", id).put("name", name).put("manufacturer", "Reolink")
            .put("model", "RLC-810A").put("configuration_url", url) }),
        JSONArray(cameras.map { JSONObject().put("entity_id", "camera.$it").put("device_id", it) }),
        JSONArray(cameras.map { JSONObject().put("entity_id", "camera.$it").put("state", "idle").put("attributes", JSONObject()) }))

    @Test fun camerasOfHomeAssistantGiveTheirAddresses() {
        val found = CameraDiscovery.fromHome(home(Triple("a", "Калитка", "http://192.0.2.60:80"), Triple("b", "Двор", "https://192.0.2.61/?ch=1"),
            Triple("c", "Без адреса", "homeassistant://config"), Triple("d", "Мусор", "http://"), Triple("e", "Лампа", "http://192.0.2.70")
            , cameras = listOf("a", "b", "c", "d"))) { it }
        assertEquals(listOf("192.0.2.60", "192.0.2.61"), found.map { it.host })
        assertEquals("Калитка", found[0].name)
        assertEquals(CameraBrand.REOLINK, found[0].brand)
    }

    // ---- Вместе ----

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(what, condition())
    }

    @Test fun sameCameraFromSeveralSourcesIsOneRow() {
        var rows = emptyList<CameraFound>()
        var finished = false
        val search = CameraDiscovery(RuntimeEnvironment.getApplication(), { rows = it }, { finished = true },
            home = { home(Triple("a", "Калитка", "http://192.168.77.60:80")) },
            onvif = { found, _ -> found(CameraFound("192.168.77.60", "", "Video Doorbell", CameraBrand.OTHER, 8000, setOf(CameraFound.Source.ONVIF), true))
                found(CameraFound("192.168.77.70", "", "DS-2CD", CameraBrand.HIKVISION, 80, setOf(CameraFound.Source.ONVIF))) },
            reolink = { found, _ -> Thread.sleep(50); found(CameraFound("192.168.77.60", "", "Reolink", CameraBrand.REOLINK, 0, setOf(CameraFound.Source.REOLINK))) })
        search.start()
        await("поиск закончен") { finished }
        assertEquals("домофон — первым", listOf("192.168.77.60", "192.168.77.70"), rows.map { it.host })
        val door = rows.first()
        assertEquals("имя — из Home Assistant", "Калитка", door.title)
        assertEquals(setOf(CameraFound.Source.ONVIF, CameraFound.Source.REOLINK, CameraFound.Source.HOME_ASSISTANT), door.sources)
        assertEquals(CameraBrand.REOLINK, door.brand)
        assertEquals(8000, door.onvifPort)
        search.stop()
    }

    @Test fun failedSourceDoesNotStopOthersAndStopSilencesSearch() {
        var rows = emptyList<CameraFound>()
        var finished = false
        val search = CameraDiscovery(RuntimeEnvironment.getApplication(), { rows = it }, { finished = true },
            home = { throw HaLogin.LoginException("нет связи") }, onvif = { _, _ -> throw java.net.SocketException("нет сети") },
            reolink = { found, _ -> found(CameraFound("192.168.77.60", model = "x")) })
        search.start()
        await("закончен") { finished }
        assertEquals(1, rows.size)
        finished = false
        val stopped = CameraDiscovery(RuntimeEnvironment.getApplication(), { error("после остановки") }, { finished = true },
            onvif = { found, cancelled -> while (!cancelled()) Thread.sleep(5); found(CameraFound("192.168.77.61")) }, reolink = { _, _ -> })
        stopped.start(); stopped.stop()
        Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(finished)
    }

    @Test fun brandsAndBroadcastAddress() {
        assertEquals(CameraBrand.TAPO, CameraBrand.of("TP-Link", "C200"))
        assertEquals(CameraBrand.UBIQUITI, CameraBrand.of("Ubiquiti", "G4 Doorbell"))
        assertEquals(CameraBrand.OTHER, CameraBrand.of("IPC", ""))
        assertEquals("192.0.2.255", CameraDiscovery.broadcast(InetAddress.getByName("192.0.2.40"), 24).hostAddress)
        assertEquals("10.20.31.255", CameraDiscovery.broadcast(InetAddress.getByName("10.20.30.40"), 23).hostAddress)
    }

    /**
     * Настоящая домашняя сеть: CAM_DISCOVERY=1. Только чтение, без паролей; адреса и имена не печатаются.
     */
    @Test fun realHomeNetwork() {
        assumeTrue(System.getenv("CAM_DISCOVERY") == "1")
        val onvif = CopyOnWriteArrayList<CameraFound>()
        val reolink = CopyOnWriteArrayList<CameraFound>()
        val start = System.nanoTime()
        val first = java.util.concurrent.atomic.AtomicLong(-1)
        val local = java.net.NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { it.inetAddresses.asSequence() }.first { it is java.net.Inet4Address && it.isSiteLocalAddress }
        val a = Thread { WsDiscovery.search(local, 4_000) { first.compareAndSet(-1, (System.nanoTime() - start) / 1_000_000); onvif += it } }.apply { start() }
        val b = Thread { ReolinkSearch.search(listOf(InetAddress.getByName("255.255.255.255"), CameraDiscovery.broadcast(local, 24)), 4_000) { reolink += it } }.apply { start() }
        a.join(); b.join()
        println("CAMERAS ONVIF: ${onvif.map { it.host }.distinct().size} (первый ответ ${first.get()} мс, модели ${onvif.map { it.model }.distinct()}), " +
            "Reolink: ${reolink.map { it.host }.distinct().size}, те же адреса: ${onvif.map { it.host }.toSet().containsAll(reolink.map { it.host })}")
        assertTrue(onvif.isNotEmpty() && reolink.isNotEmpty())
    }
}
