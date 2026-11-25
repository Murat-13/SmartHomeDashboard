package com.example.smarthomedashboard

import com.example.smarthomedashboard.RecordingTestSupport.rtp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Особенности камер разных марок (docs/cameras.md): каждая — поддельная камера со своей странностью, клиент обязан получить
 * видео. Списки странностей — из открытых клиентов (go2rtc, ffmpeg) и стандартов; на GitHub — при каждом изменении.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraQuirksTest {
    private val sessions = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<RtspSession>())
    private val servers = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<ServerSocket>())
    @After fun tearDown() { sessions.forEach { runCatching { it.abort() } }; servers.forEach { runCatching { it.close() } }; CameraTls.clearForTests() }

    /** Только петля: на всех адресах порт может совпасть с чужим сервером на 127.0.0.1, и подключение ушло бы туда. */
    private fun loopback() = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))

    private fun hash(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Что поддельная камера делает не так, как все. */
    private data class Quirk(
        /** Варианты авторизации (строки `WWW-Authenticate`); пусто — без пароля. */
        val challenges: List<String> = emptyList(),
        val options: String = "200 OK",
        /** Content-Base: {url} — наш адрес; null — заголовка нет. */
        val base: String? = "{url}/",
        val control: String = "trackID=1",
        val audioSetup: String = "200 OK",
        /** PLAY принимается только по этому адресу ({url}); null — по любому. */
        val playOnly: String? = null,
        val redirectTo: String? = null,
        val eol: String = "\r\n",
        /** Перед каждым пятым кадром — столько байтов мусора. */
        val garbage: Int = 0,
        val garbageByte: Char? = null,
        val blankLines: Boolean = false,
        /** Камера сама спрашивает внутри потока и рвёт сессию, если ответа нет. */
        val asks: Boolean = false,
        /** Каналы, которые назначила камера. */
        val channels: Pair<Int, Int> = 0 to 2,
        /** После PLAY запросы принимаются только с новым nonce. */
        val newNonceAfterPlay: Boolean = false,
        val sdpExtra: String = "",
        val timeout: Int = 60,
        /** SETUP видео принимается только по этому адресу ({url}); null — по любому. */
        val setupOnly: String? = null,
        /** Nonce одноразовый: второй запрос с тем же — отказ «устарел». */
        val stale: Boolean = false,
        /** RTCP камера шлёт в канал RTP. */
        val rtcpInside: Boolean = false,
        /** После PLAY камера молчит (сессия только с обратным каналом). */
        val silent: Boolean = false,
    )

    private val realm = "IP Camera (A1)"
    private val requests = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())
    /** Что случилось у самой поддельной камеры — в сообщение о неудаче. */
    private val problems = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())
    private val accepted = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())
    private val answers = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<String>())

    private fun sdp(control: String, eol: String, extra: String) = listOf("v=0", "o=- 1 1 IN IP4 0.0.0.0", "s=cam", "t=0 0", "m=video 0 RTP/AVP 96",
        "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==", "a=control:$control",
        "m=audio 0 RTP/AVP 8", "a=rtpmap:8 PCMA/8000", "a=control:trackID=2").joinToString(eol) + eol + extra

    private class Reader(val input: InputStream) {
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return out.toString().trimEnd('\r')
                if (b == '$'.code && out.isEmpty()) { input.read(); val n = (input.read() shl 8) or input.read(); repeat(n) { input.read() }; continue }
                out.append(b.toChar())
            }
        }
    }

    private fun authorized(q: Quirk, header: String?, method: String, uri: String, nonce: String): Boolean {
        if (q.challenges.isEmpty()) return true
        val h = header ?: return false
        if (h.startsWith("Basic ")) return q.challenges.any { it.startsWith("Basic") } && h == "Basic " + RecBase64.encode("admin:secret".toByteArray())
        val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(h).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
        val alg = if (p["algorithm"]?.startsWith("SHA-256") == true) "SHA-256" else "MD5"
        if (q.challenges.none { "Digest" in it && (if (alg == "SHA-256") "SHA-256" in it else "SHA-256" !in it) }) return false
        if (p["nonce"] != nonce || p["uri"] != uri) return false
        val ha1 = hash(alg, "admin:$realm:secret"); val ha2 = hash(alg, "$method:$uri")
        val expected = if (p["qop"] != null) hash(alg, "$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2") else hash(alg, "$ha1:$nonce:$ha2")
        return p["response"] == expected
    }

    private fun camera(q: Quirk, server: ServerSocket = loopback(), scheme: String = "rtsp"): String {
        servers += server
        val url = "$scheme://127.0.0.1:${server.localPort}/live/main?profile=1"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { serve(q, s, url) } catch (e: Exception) { problems += e.toString() } finally { runCatching { s.close() } } }
            }
        }
        return url
    }

    private fun serve(q: Quirk, s: Socket, url: String) {
        val reader = Reader(s.getInputStream())
        val out = s.getOutputStream()
        var nonce = "n1"
        var playing = false
        fun write(b: ByteArray) = synchronized(out) { out.write(b); out.flush() }
        fun reply(cseq: String, status: String, headers: List<String> = emptyList(), body: String = "") {
            val b = body.toByteArray()
            write((listOf("RTSP/1.0 $status", "CSeq: $cseq") + headers + (if (b.isNotEmpty()) listOf("Content-Length: ${b.size}") else emptyList())).joinToString("\r\n").toByteArray() +
                "\r\n\r\n".toByteArray() + b)
        }
        while (true) {
            val first = reader.line() ?: return
            if (first.isEmpty()) continue
            val headers = mutableMapOf<String, String>()
            while (true) { val l = reader.line() ?: return; if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            if (first.startsWith("RTSP/")) { answers += first + " " + headers["cseq"]; continue }
            val (method, uri) = first.split(' ')
            val cseq = headers["cseq"].orEmpty()
            requests += "$method $uri"
            if (method == "TEARDOWN") { reply(cseq, "200 OK"); return }
            if (!authorized(q, headers["authorization"], method, uri, nonce)) {
                if (!playing || q.newNonceAfterPlay) reply(cseq, "401 Unauthorized", q.challenges.map { "WWW-Authenticate: " + it.replace("{nonce}", nonce) +
                    (if (q.stale && headers["authorization"] != null) ", stale=TRUE" else "") })
                continue
            }
            if (q.stale) nonce = "n" + cseq
            accepted += method
            when (method) {
                "OPTIONS" -> reply(cseq, q.options, if (q.options.startsWith("200")) listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER") else emptyList())
                "DESCRIBE" -> if (q.redirectTo != null) reply(cseq, "302 Moved", listOf("Location: ${q.redirectTo}"))
                    else reply(cseq, "200 OK", listOf("Content-Type: application/sdp") + listOfNotNull(q.base?.let { "Content-Base: " + it.replace("{url}", url) }),
                        sdp(q.control.replace("{url}", url), q.eol, q.sdpExtra))
                "SETUP" -> {
                    val audio = uri.endsWith("trackID=2")
                    if (!audio && q.setupOnly != null && uri != q.setupOnly.replace("{dir}", url.substringBefore('?').substringBeforeLast('/') + "/")) reply(cseq, "404 Not Found")
                    else if (audio && !q.audioSetup.startsWith("200")) reply(cseq, q.audioSetup)
                    else { val ch = if (audio) q.channels.second else q.channels.first
                        reply(cseq, "200 OK", listOf("Session: 7788;timeout=${q.timeout}", "Transport: RTP/AVP/TCP;unicast;interleaved=$ch-${ch + 1}")) }
                }
                "PLAY" -> {
                    if (q.playOnly != null && uri != q.playOnly.replace("{url}", url)) { reply(cseq, "404 Not Found"); continue }
                    reply(cseq, "200 OK", listOf("Session: 7788"))
                    if (q.newNonceAfterPlay) nonce = "n2"
                    playing = true
                    thread(isDaemon = true) {
                        try {
                            for (i in 0 until if (q.silent) 0 else 400) {
                                if (q.rtcpInside && i % 10 == 5) write(byteArrayOf('$'.code.toByte(), q.channels.first.toByte(), 0, 28, 0x80.toByte(), 200.toByte(), 0, 6) + ByteArray(24))
                                val p = rtp(96, true, i, i * 3000L, byteArrayOf(0x65, 1, 2, 3))
                                if (q.blankLines) write("\r\n".toByteArray())
                                if (q.garbage > 0 && i % 5 == 4) write(ByteArray(q.garbage) { q.garbageByte?.code?.toByte() ?: (it * 7 % 31 + 1).toByte() })
                                write(byteArrayOf('$'.code.toByte(), q.channels.first.toByte(), (p.size shr 8).toByte(), p.size.toByte()) + p)
                                if (q.asks && i % 20 == 10) {
                                    val before = answers.size
                                    write("OPTIONS $url RTSP/1.0\r\nCSeq: ${900 + i}\r\n\r\n".toByteArray())
                                    val end = System.currentTimeMillis() + 1_500
                                    while (answers.size == before) { if (System.currentTimeMillis() > end) { s.close(); return@thread }; Thread.sleep(5) }
                                }
                                Thread.sleep(5)
                            }
                        } catch (_: Exception) {}
                    }
                }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(cseq, "200 OK")
            }
        }
    }

    /** Подключиться и получить [packets] пакетов видео. */
    private fun watch(url: String, packets: Int = 60, user: String = "admin", clock: () -> Long = { System.nanoTime() / 1_000_000 }): RtspSession {
        val session = RtspSession(url, user, "secret", readTimeoutMs = 4_000, clock = clock)
        sessions += session
        val setups = try { session.open { it.isVideo || it.isAudio } }
            catch (e: java.io.IOException) { throw AssertionError("не подключились: $e; у камеры: $problems; запросы: $requests", e) }
        val video = setups.first { it.track.isVideo }
        var got = 0
        val done = java.util.concurrent.CountDownLatch(1)
        val t = thread(isDaemon = true) {
            try {
                session.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { if (setup == video && ++got >= packets) done.countDown() }
                })
            } catch (_: Exception) {}
        }
        if (!done.await(8, TimeUnit.SECONDS)) fail("видео не пришло: $got из $packets; запросы: $requests")
        session.close(); t.join(2_000)
        return session
    }

    private fun digest(extra: String = "") = "Digest realm=\"$realm\", nonce=\"{nonce}\"$extra"

    // ---- Авторизация ----

    /** Hikvision (новые прошивки): MD5 и SHA-256 двумя строками — годится любая. */
    @Test fun hikvisionOffersTwoDigests() {
        watch(camera(Quirk(challenges = listOf(digest(", algorithm=\"MD5\""), digest(", algorithm=\"SHA-256\"")))))
        watch(camera(Quirk(challenges = listOf(digest(", algorithm=SHA-256, qop=\"auth\""), digest(", stale=\"FALSE\"")))))
    }

    /** Hanwha и Axis в строгом режиме: только SHA-256. */
    @Test fun onlySha256() { watch(camera(Quirk(challenges = listOf(digest(", algorithm=SHA-256, qop=\"auth,auth-int\", opaque=\"xyz\"")))))
        watch(camera(Quirk(challenges = listOf(digest(", algorithm=SHA-256"))))) }

    @Test fun basicOnlyOrBothInOneLine() {
        watch(camera(Quirk(challenges = listOf("Basic realm=\"$realm\""))))
        watch(camera(Quirk(challenges = listOf("Basic realm=\"$realm\", ${digest()}"))))
        // Digest есть — пароль открытым текстом не уходит.
        assertNull(DigestAuth.choose(listOf("Basic realm=\"x\"", "Digest realm=\"x\", nonce=\"1\""))!!.header("u", "p", "GET", "/").takeIf { it.startsWith("Basic") })
        assertNull("незнакомое не берём", DigestAuth.choose(listOf("Negotiate", "Digest realm=\"x\", nonce=\"1\", algorithm=SHA-512-256")))
    }

    /** Ответ по эталону RFC 7616, §3.9.1 (SHA-256) — с нашим cnonce считаем тем же способом. */
    @Test fun digestFollowsStandard() {
        val c = DigestAuth.choose(listOf("Digest realm=\"http-auth@example.org\", qop=\"auth, auth-int\", algorithm=SHA-256, " +
            "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\""))!!
        val h = c.header("Mufasa", "Circle of Life", "GET", "/dir/index.html")
        val p = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]+)").findAll(h).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }
        val ha1 = hash("SHA-256", "Mufasa:http-auth@example.org:Circle of Life")
        assertEquals("7987c64c30e25f1b74be53f966b49b90f2808aa92faf9a00262392d7b4794232", ha1)
        val ha2 = hash("SHA-256", "GET:/dir/index.html")
        assertEquals("9a3fdae9a622fe8de177c24fa9c070f2b181ec85e15dcbdc32e10c82ad450b04", ha2)
        assertEquals(hash("SHA-256", "$ha1:${p["nonce"]}:${p["nc"]}:${p["cnonce"]}:auth:$ha2"), p["response"])
        assertEquals("00000001", p["nc"])
    }

    /** Tapo и часть Hikvision: после PLAY старый nonce не годится — сессия держится новым. */
    @Test fun keepAliveSurvivesNewNonce() {
        var now = 0L
        val url = camera(Quirk(challenges = listOf(digest()), newNonceAfterPlay = true, timeout = 10))
        watch(url, packets = 200, clock = { now += 40; now })
        val keep = requests.count { it.startsWith("GET_PARAMETER") }
        assertEquals("отказ со старым nonce и сразу повтор с новым: $requests", 2, keep)
        assertEquals("новый принят", 1, accepted.count { it == "GET_PARAMETER" })
    }

    /** Nonce живёт один запрос (Hikvision KV): отказ «устарел» — ещё раз с новым, это не неверный пароль. */
    @Test fun oneTimeNonce() { watch(camera(Quirk(challenges = listOf(digest()), stale = true)), packets = 10) }

    @Test fun wrongPasswordIsStillWrong() {
        val url = camera(Quirk(challenges = listOf(digest())))
        val e = runCatching { RtspSession(url, "admin", "нет").open { true } }.exceptionOrNull() as RtspException
        assertEquals(401, e.status)
        val n = requests.count { it.startsWith("OPTIONS") }
        assertTrue("без перебора: $n", n <= 2)
    }

    // ---- Адреса ----

    /** Дешёвые камеры и камеры за роутером: в Content-Base и control — 0.0.0.0 или чужой адрес. */
    @Test fun wrongAddressesInDescription() {
        watch(camera(Quirk(base = "rtsp://0.0.0.0:554/live/main/", control = "trackID=1")))
        watch(camera(Quirk(base = "rtsp://192.168.1.108/live/main?profile=1/", control = "rtsp://192.168.1.108:554/live/main/trackID=1")))
        watch(camera(Quirk(base = null, control = "rtsp://10.0.0.1/track/trackID=1")))
        assertTrue("за пределы узла не ходим", requests.none { it.contains("0.0.0.0") || it.contains("192.168.1.108") || it.contains("10.0.0.1") })
        assertEquals("rtsp://cam:554/a?x=1", SdpParser.sameHost("rtsp://cam:554/a?x=1", "rtsp://cam:554/b"))
        assertEquals("rtsp://cam:8554/a?x=1", SdpParser.sameHost("rtsp://user:p@10.0.0.1:554/a?x=1", "rtsp://cam:8554/b"))
        assertEquals("trackID=1", SdpParser.sameHost("trackID=1", "rtsp://cam/b"))
    }

    @Test fun missingBaseAndOddControls() {
        watch(camera(Quirk(base = null)))
        watch(camera(Quirk(base = "/live/")))
        watch(camera(Quirk(control = "{url}/trackID=1")))
    }

    /** Регистраторы, которым адрес дорожки нужен по правилам RFC, а не приписанным в конец. */
    @Test fun trackAddressByRfc() {
        watch(camera(Quirk(setupOnly = "{dir}trackID=1", base = "{url}")), packets = 5)
        assertEquals("rtsp://cam/live/trackID=1", SdpParser.resolveRfc("rtsp://cam/live/main?profile=1", "trackID=1"))
        assertEquals("rtsp://cam/trackID=1", SdpParser.resolveRfc("rtsp://cam/live/main", "/trackID=1"))
        assertNull(SdpParser.resolveRfc("rtsp://cam/live/", "trackID=1"))
        assertNull(SdpParser.resolveRfc("rtsp://cam/live", "rtsp://cam/live/trackID=1"))
        assertEquals("rtsp://cam/live?x=1", SdpParser.resolve("rtsp://cam/live", "?x=1"))
    }

    @Test fun playOnlyByOriginalAddress() { watch(camera(Quirk(base = "{url}/stream/", playOnly = "{url}"))) }

    @Test fun recorderRedirectsInsideHomeOnly() {
        val real = camera(Quirk())
        watch(camera(Quirk(redirectTo = real)))
        val away = runCatching { RtspSession(camera(Quirk(redirectTo = "rtsp://8.8.8.8/live")), "admin", "secret").open { true } }.exceptionOrNull()
        assertEquals(302, (away as RtspException).status)
        // По кругу — не бесконечно.
        val server = loopback()
        val loop = "rtsp://127.0.0.1:${server.localPort}/live/main?profile=1"
        camera(Quirk(redirectTo = loop), server)
        assertTrue(runCatching { RtspSession(loop, "admin", "secret").open { true } }.exceptionOrNull() is RtspException)
    }

    // ---- Поток ----

    /** EZVIZ, Wansview: OPTIONS не понимают или отвечают ошибкой. */
    @Test fun optionsRefused() { for (status in listOf("404 Not Found", "405 Method Not Allowed", "501 Not Implemented", "400 Bad Request")) watch(camera(Quirk(options = status)), packets = 10) }

    /** Звук объявлен, но не отдаётся: видео важнее. */
    @Test fun audioRefused() { for (status in listOf("461 Unsupported Transport", "404 Not Found", "500 Internal Server Error")) watch(camera(Quirk(audioSetup = status)), packets = 10) }

    /** Каналы назначает камера (XMEye и регистраторы). */
    @Test fun cameraAssignsChannels() { watch(camera(Quirk(channels = 4 to 2))) }

    @Test fun lfOnlyDescriptionAndUnknownLines() { watch(camera(Quirk(eol = "\n", sdpExtra = "a=x-vendor:1\nm=application 0 RTP/AVP 107\na=rtpmap:107 vnd.onvif.metadata/90000\n"))) }

    /** Мусор и лишние переводы строк между кадрами. */
    @Test fun garbageBetweenFrames() {
        watch(camera(Quirk(blankLines = true)))
        watch(camera(Quirk(garbage = 37)), packets = 100)
    }

    /** Мусор с большими буквами не принимается за сообщение камеры; RTCP в канале RTP — не кадр. */
    @Test fun lettersInGarbageAndRtcpInsideRtpChannel() {
        watch(camera(Quirk(garbage = 41, garbageByte = 'R')), packets = 60)
        val url = camera(Quirk(rtcpInside = true))
        val session = RtspSession(url, "admin", "secret", readTimeoutMs = 4_000).also { sessions += it }
        session.open { it.isVideo }
        val types = java.util.concurrent.CopyOnWriteArrayList(mutableListOf<Int>())
        var rtcp = 0
        val done = java.util.concurrent.CountDownLatch(1)
        thread(isDaemon = true) {
            runCatching {
                session.read(object : RtspSession.Sink {
                    override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { types += packet[1].toInt() and 0x7F; if (types.size >= 50) done.countDown() }
                    override fun onRtcp(setup: RtspSession.Setup, packet: ByteArray, length: Int) { rtcp++ }
                })
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("RTCP отделён: $rtcp", rtcp >= 4 && types.all { it == 96 })
    }

    /** Сессия разговора: камера молчит, сессия держится запросами, а не рвётся по тишине. */
    @Test fun silentTalkSessionIsKept() {
        val url = camera(Quirk(silent = true))
        val session = RtspSession(url, "admin", "secret", readTimeoutMs = 300, require = TalkSession.BACKCHANNEL).also { sessions += it }
        session.open { it.isAudio }
        val t = thread(isDaemon = true) { runCatching { session.read(object : RtspSession.Sink { override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {} }) } }
        Thread.sleep(1_500)
        assertTrue("сессия жива", t.isAlive)
        assertTrue("держали запросами", requests.count { it.startsWith("GET_PARAMETER") } >= 3)
        session.close(); t.join(2_000)
    }

    /** Uniview, Hikvision NVR: камера сама спрашивает внутри потока и без ответа рвёт сессию. */
    @Test fun cameraAsksInsideStream() {
        watch(camera(Quirk(asks = true)), packets = 100)
        assertTrue("ответили: $answers", answers.size >= 4 && answers.all { it.startsWith("RTSP/1.0 200 OK 9") })
    }

    // ---- Кадры ----

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 1, 2, 3)
    private val pps = byteArrayOf(0x68, 0x11, 0x22)
    private val idr = byteArrayOf(0x65, 0x77, 0x55)
    private val slice = byteArrayOf(0x41, 0x66)

    private fun frames(vararg packets: ByteArray): List<VideoFrame> {
        val d = H264Depacketizer()
        return packets.flatMap { d.push(RtpPacket.parse(it, it.size)!!) }
    }

    /** Tapo, Reolink Duo: маркер конца кадра и на SPS/PPS — это не отдельный кадр, а начало следующего. */
    @Test fun markerOnParameterSets() {
        val out = frames(rtp(96, true, 1, 100, sps), rtp(96, true, 2, 100, pps), rtp(96, true, 3, 100, idr), rtp(96, true, 4, 3100, slice),
            rtp(96, true, 5, 6100, sps), rtp(96, true, 6, 6100, pps), rtp(96, true, 7, 6100, idr))
        assertEquals(listOf(true, false, true), out.map { it.key })
        assertEquals(listOf(listOf(0x67, 0x68, 0x65), listOf(0x41), listOf(0x67, 0x68, 0x65)), out.map { f -> f.nals.map { it[0].toInt() } })
    }

    /** Параметры и кадр одним пакетом через стартовые коды. */
    @Test fun severalNalsInOnePacket() {
        val code = byteArrayOf(0, 0, 0, 1)
        val out = frames(rtp(96, true, 1, 100, sps + code + pps + code + idr), rtp(96, true, 2, 3100, slice))
        assertEquals(listOf(listOf(0x67, 0x68, 0x65), listOf(0x41)), out.map { f -> f.nals.map { it[0].toInt() } })
        assertTrue(out[0].nals[0].contentEquals(sps) && out[0].nals[2].contentEquals(idr))
        assertTrue("обычный кадр не трогаем", AnnexB.split(slice).single() === slice && AnnexB.strip(slice) === slice)
        assertTrue(AnnexB.strip(code + sps + byteArrayOf(0)).contentEquals(sps))
    }

    /** Стартовые коды и лишние наборы в sprop: плеер получает ровно SPS и PPS, запись — чистые параметры. */
    @Test fun dirtyParametersInDescription() {
        val code = byteArrayOf(0, 0, 0, 1)
        fun sdp(sprop: String) = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=$sprop\r\na=control:t1\r\n"
        val clean = RecBase64.encode(sps) + "," + RecBase64.encode(pps)
        assertTrue(Media3Sdp.compatible(sdp(RecBase64.encode(code + sps) + "," + RecBase64.encode(code + pps))).contains("sprop-parameter-sets=$clean"))
        assertTrue(Media3Sdp.compatible(sdp("$clean," + RecBase64.encode(byteArrayOf(0x6D, 1)))).contains("sprop-parameter-sets=$clean\r\n"))
        assertEquals("чистое — как есть", sdp(clean), Media3Sdp.compatible(sdp(clean)))
        assertTrue("без PPS — взять из потока", Media3Sdp.lacksH264Params(SdpParser.parse(sdp(RecBase64.encode(sps))).single()))
        val d = VideoDepacketizer.create(SdpParser.parse(sdp(RecBase64.encode(code + sps) + "," + RecBase64.encode(code + pps))).single())!!
        assertTrue(d.sps!!.contentEquals(sps) && d.pps!!.contentEquals(pps))
    }

    /** Пробелы, порядок строк, незнакомые дорожки. */
    @Test fun oddDescriptions() {
        val tracks = SdpParser.parse("v=0\no=- broken\ns=\ns=two\nm=video 0 RTP/AVP 96\na=fmtp: 96 profile-level-id=640033; sprop-parameter-sets=Z2QAMw==,aO4=\n" +
            "a=rtpmap: 96  H264/90000 \na=control:track1\nm=vana 0 RTP/AVP 97\na=rtpmap:97 x/90000\nm=application/MERCURY 0 RTP/AVP smart/1/90000\nm=audio 0 RTP/AVP 0")
        assertEquals(listOf("video", "vana", "audio"), tracks.map { it.media })
        assertEquals("H264", tracks[0].encoding)
        assertEquals("Z2QAMw==,aO4=", tracks[0].fmtp["sprop-parameter-sets"])
        assertEquals("PCMU", tracks[2].encoding)
    }

    // ---- TLS ----

    private fun tlsServer(port: Int = 0): ServerSocket {
        val file = File.createTempFile("cam", ".p12").apply { delete() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "cam", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-storetype", "PKCS12",
            "-keystore", file.path, "-storepass", "camera", "-dname", "CN=UniFi Protect").redirectErrorStream(true).start()
        p.waitFor(30, TimeUnit.SECONDS)
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "camera".toCharArray()) } }
        file.delete()
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "camera".toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory.createServerSocket()
            .apply { reuseAddress = true; bind(java.net.InetSocketAddress("127.0.0.1", port)) }
    }

    /** UniFi Protect: rtsps, свой сертификат, `?enableSrtp` в ссылке. Подменённый сертификат — отказ. */
    @Test fun unifiOverTlsWithRememberedCertificate() {
        val server = tlsServer()
        val url = camera(Quirk(), server, "rtsps").substringBefore('?')
        watch("$url?enableSrtp")
        watch(url, packets = 10)
        assertTrue("параметр SRTP не отправлен", requests.none { it.contains("enableSrtp") })
        // Тот же адрес, другой сертификат (камеру подменили): подключение рвётся, отпечаток прежний.
        val port = server.localPort
        server.close()
        val swapped = tlsServer(port)
        camera(Quirk(), swapped, "rtsps")
        val e = runCatching { RtspSession(url, "admin", "secret").also { sessions += it }.open { true } }.exceptionOrNull()
        assertTrue("$e", e is javax.net.ssl.SSLException)
        // Человек сохранил камеру заново — новый сертификат принимается.
        CameraTls.forget(url)
        watch(url, packets = 10)
    }
}
