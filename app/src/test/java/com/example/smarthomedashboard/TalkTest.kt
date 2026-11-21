package com.example.smarthomedashboard

import android.Manifest
import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** Разговор через домофон (docs/talk-tz.md): G.711, обратный канал ONVIF, кнопка. Камера — как Reolink владельца. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TalkTest {
    @Test fun g711MatchesReferenceAndRoundTrips() {
        assertEquals(0xFF.toByte(), G711.ulaw(0))
        assertEquals(0x80.toByte(), G711.ulaw(32767))
        assertEquals(0x00.toByte(), G711.ulaw(-32768))
        assertEquals(0xD5.toByte(), G711.alaw(0))
        assertEquals(0xAA.toByte(), G711.alaw(32767))
        assertEquals(0x2A.toByte(), G711.alaw(-32768))
        // Кодер согласован с декодером записи (G711Depacketizer): ошибка — в пределах шага квантования.
        var x = -32768
        while (x <= 32767) {
            val limit = maxOf(16, abs(x) / 14)
            val u = G711Depacketizer.decodeULaw(G711.ulaw(x).toInt() and 0xFF)
            val a = G711Depacketizer.decodeALaw(G711.alaw(x).toInt() and 0xFF)
            assertTrue("μ-law $x → $u", abs(u - x) <= limit)
            assertTrue("A-law $x → $a", abs(a - x) <= limit)
            x += 37
        }
    }

    /** Описание как у Reolink владельца с `Require: www.onvif.org/ver20/backchannel`. */
    private val reolinkBackchannel = "v=0\r\no=- 1 1 IN IP4 192.0.2.10\r\ns=Session streamed by \"preview\"\r\nt=0 0\r\n" +
        "a=control:*\r\na=range:npt=now-\r\n" +
        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=recvonly\r\na=control:track1\r\n" +
        "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n" +
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\n" +
        "a=recvonly\r\na=control:track2\r\n" +
        "m=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendonly\r\na=control:track3\r\n"

    @Test fun sdpDirectionMarksBackchannel() {
        val tracks = SdpParser.parse(reolinkBackchannel)
        assertEquals(listOf("recvonly", "recvonly", "sendonly"), tracks.map { it.direction })
        assertEquals("track3", tracks[2].control)
        assertEquals("PCMU", tracks[2].encoding)
    }

    /** Поддельная камера: требует `Require` в каждом запросе, отдаёт обратный канал и копит присланные RTP. */
    private class Camera(val sdp: String) {
        val server = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        val packets = CopyOnWriteArrayList<ByteArray>()
        val torn = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    fun reply(cseq: String, headers: String = "", body: String = "") {
                        val b = body.toByteArray()
                        out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" +
                            (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
                        out.flush()
                    }
                    try {
                        while (true) {
                            val first = input.read()
                            if (first < 0) break
                            if (first == '$'.code) {
                                input.readUnsignedByte()
                                val len = input.readUnsignedShort()
                                packets += ByteArray(len).also { input.readFully(it) }
                                continue
                            }
                            val head = StringBuilder().append(first.toChar())
                            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                            val text = head.toString()
                            requests += text
                            val cseq = Regex("CSeq: (\\d+)").find(text)!!.groupValues[1]
                            if (!text.contains("Require: www.onvif.org/ver20/backchannel")) {
                                out.write("RTSP/1.0 551 Option not supported\r\nCSeq: $cseq\r\n\r\n".toByteArray()); continue
                            }
                            when (text.substringBefore(' ')) {
                                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub/\r\n", sdp)
                                "SETUP" -> reply(cseq, "Session: 9;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
                                "TEARDOWN" -> { reply(cseq); torn.countDown(); break }
                                else -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val url get() = "rtsp://127.0.0.1:${server.localPort}/h264Preview_01_sub"
    }

    private fun session(camera: Camera) = TalkSession(RtspSession(camera.url, "", null, readTimeoutMs = 5_000, require = TalkSession.BACKCHANNEL))

    @Test fun talkSessionStreamsSilenceAndVoiceContinuously() {
        val camera = Camera(reolinkBackchannel)
        val talk = session(camera)
        assertEquals("PCMU", talk.open())
        assertTrue("SETUP только обратного канала", camera.requests.single { it.startsWith("SETUP") }.startsWith("SETUP ${camera.url}/track3 "))
        assertTrue(camera.requests.any { it.startsWith("PLAY") })
        Thread.sleep(150) // сразу после PLAY идёт тишина — камера не «засыпает»
        talk.beginTalkspurt()
        val pcm = ShortArray(400) { (it * 50).toShort() }
        talk.send(pcm, 400) // 2 полных кадра голоса, 80 отсчётов ждут следующего
        Thread.sleep(250)
        talk.close()
        assertTrue("TEARDOWN", camera.torn.await(3, TimeUnit.SECONDS))
        val packets = camera.packets.toList()
        assertTrue("пакетов ${packets.size}", packets.size >= 15)
        fun seq(p: ByteArray) = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        fun ts(p: ByteArray) = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
        packets.zipWithNext().forEach { (a, b) ->
            assertEquals(172, a.size)
            assertEquals(0x80, a[0].toInt() and 0xFF)
            assertEquals("номера подряд", (seq(a) + 1) and 0xFFFF, seq(b))
            assertEquals("метки +160", 160L, (ts(b) - ts(a)) and 0xFFFFFFFFL)
        }
        assertTrue("до голоса — тишина μ-law", packets.first().copyOfRange(12, 172).all { it == 0xFF.toByte() })
        val first = packets.indexOfFirst { it[1].toInt() and 0x80 != 0 }
        assertTrue("маркер у первого кадра голоса", first > 0)
        assertEquals("PT 0", 0, packets[first][1].toInt() and 0x7F)
        assertArrayEquals(ByteArray(160) { G711.ulaw(pcm[it].toInt()) }, packets[first].copyOfRange(12, 172))
        assertArrayEquals(ByteArray(160) { G711.ulaw(pcm[160 + it].toInt()) }, packets[first + 1].copyOfRange(12, 172))
        assertEquals("маркер один", 1, packets.count { it[1].toInt() and 0x80 != 0 })
        assertEquals(2, talk.sentVoice)
    }

    /** Регрессия 19.11.2026: карточка обновляется раз в секунду — разговор не должен переподключаться. */
    @Test fun repeatedPrepareKeepsOneSession() {
        val camera = Camera(reolinkBackchannel)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val talk = DoorbellTalk(activity) { _, result -> result(null) }
        talk.prepare(camera.url, "")
        val until = System.currentTimeMillis() + 5_000
        while (talk.state != DoorbellTalk.State.READY) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) fail("Не подключились: ${talk.state}")
            Thread.sleep(20)
        }
        repeat(10) { talk.prepare(camera.url, ""); shadowOf(Looper.getMainLooper()).idle() }
        // И во время разговора (именно тогда канал рвался каждую секунду).
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        talk.press()
        assertEquals(DoorbellTalk.State.TALKING, talk.state)
        repeat(10) { talk.prepare(camera.url, ""); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(DoorbellTalk.State.TALKING, talk.state)
        talk.release()
        Thread.sleep(200)
        assertEquals(DoorbellTalk.State.READY, talk.state)
        assertEquals("одна сессия", 1, camera.requests.count { it.startsWith("DESCRIBE") })
        assertTrue(camera.packets.isNotEmpty())
        talk.dispose()
    }

    /** Быстрая фраза своим голосом (19.11.2026): уходит голосом подряд, с одним маркером, канал открывается сам. */
    @Test fun phrasePlaysThroughBackchannel() {
        val camera = Camera(reolinkBackchannel)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val talk = DoorbellTalk(activity) { _, result -> result(null) }
        talk.prepare(camera.url, "")
        val pcm = ShortArray(1_600) { ((it * 37) % 8_000 - 4_000).toShort() } // 0,2 с = 10 кадров
        assertTrue(talk.play(pcm))
        fun voice() = camera.packets.count { p -> p.size == 172 && p.copyOfRange(12, 172).any { it != 0xFF.toByte() } }
        val until = System.currentTimeMillis() + 5_000
        while (voice() < 10 && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertEquals(10, voice())
        assertEquals("маркер один — у первого кадра фразы", 1, camera.packets.count { it[1].toInt() and 0x80 != 0 })
        assertFalse("пока звучит — вторая фраза не накладывается", talk.play(pcm) && talk.state != DoorbellTalk.State.TALKING)
        talk.dispose()
    }

    /** Разговор как по телефону (этап 2): «Ответить» — голос идёт, «Завершить» — конец; сам — через 2 мин тишины. */
    @Test fun callStartsEndsAndStopsAfterSilence() {
        val camera = Camera(reolinkBackchannel)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val talk = DoorbellTalk(activity) { _, result -> result(null) }
        talk.street = object : StreetSource { // звук с улицы проверяется отдельным тестом
            override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) {}
            override fun stop() {}
        }
        var now = 0L
        talk.clock = { now }
        val calls = mutableListOf<Boolean>()
        talk.onCall = { calls += it }
        val button = TalkButton(activity, talk)
        assertEquals("Ответить", button.text.toString())
        talk.prepare(camera.url, "")
        val until = System.currentTimeMillis() + 5_000
        while (talk.state != DoorbellTalk.State.READY) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) fail("Не подключились: ${talk.state}")
            Thread.sleep(20)
        }
        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        button.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 1f, 1f, 0))
        assertTrue(talk.inCall)
        assertEquals(listOf(true), calls)
        assertEquals("Завершить", button.text.toString())
        assertFalse("во время разговора фраза не накладывается", talk.play(ShortArray(1600)))
        // Тишина 2 минуты — разговор завершается сам.
        now += DoorbellTalk.CALL_SILENCE_MS + 1_000
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(talk.inCall)
        assertEquals(listOf(true, false), calls)
        assertEquals("Ответить", button.text.toString())
        // Снова — и «Завершить» кнопкой.
        talk.startCall()
        assertTrue(talk.inCall)
        talk.endCall()
        assertFalse(talk.inCall)
        assertEquals(listOf(true, false, true, false), calls)
        // Закрытие окна посреди разговора — тоже конец.
        talk.startCall()
        talk.dispose()
        assertEquals(false, calls.last())
    }

    @Test fun cameraWithoutBackchannelIsExplained() {
        val camera = Camera(reolinkBackchannel.substringBefore("m=audio 0 RTP/AVP 0"))
        val error = assertThrows(TalkException::class.java) { session(camera).open() }
        assertEquals(TalkSession.NO_CHANNEL, error.message)
    }

    @Test fun chimeToneAndConfig() {
        assertEquals(6_400, TalkCheck.tone().size)
        assertTrue(DoorbellConfig(rtspSub = "rtsp://192.0.2.10/sub").canTalk)
        assertFalse("без RTSP разговора нет", DoorbellConfig().canTalk)
        val off = DoorbellConfig(rtspSub = "rtsp://192.0.2.10/sub", talk = false)
        assertFalse(DoorbellConfig.fromJson(JSONObject(off.toJson().toString())).talk)
        assertTrue("старые плитки без ключа — разговор включён", DoorbellConfig.fromJson(JSONObject()).talk)
    }

    @Test fun holdWithoutMicPermissionAsksForIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val talk = DoorbellTalk(activity)
        val button = TalkButton(activity, talk).apply { callMode = false }
        var status = ""
        button.onStatus = { status = it }
        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        button.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 1f, 1f, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DoorbellTalk.State.ERROR, talk.state)
        assertTrue(status, status.contains("Разрешите микрофон"))
        val asked = shadowOf(activity).lastRequestedPermission
        assertNotNull(asked)
        assertTrue(asked!!.requestedPermissions.contains(Manifest.permission.RECORD_AUDIO))
        talk.dispose()
    }

    private fun tone(amplitude: Double, frame: Int) = ShortArray(160) { i ->
        (amplitude * kotlin.math.sin(2 * Math.PI * 400 * (frame * 160 + i) / 8_000.0)).toInt().toShort()
    }
    private fun rms(pcm: ShortArray) = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    /** Тихий голос (как у планшета в режиме разговора) усиливается до разборчивой громкости у домофона. */
    @Test fun quietVoiceIsAmplifiedTowardsTarget() {
        val gain = VoiceGain()
        var out = 0.0
        repeat(150) { f -> val pcm = tone(1_000.0, f); val before = rms(pcm); gain.apply(pcm, 160, before); out = rms(pcm) }
        assertTrue("громкость после усиления $out", out > VoiceGain.TARGET * 0.8 && out < VoiceGain.TARGET * 1.2)
        assertTrue(gain.gain <= VoiceGain.MAX_GAIN)
    }

    /** Громкий голос не усиливается и не хрипит: пики мягко сжимаются, обрезания нет. */
    @Test fun loudVoiceIsNotBoostedAndNotClipped() {
        val gain = VoiceGain()
        repeat(100) { f ->
            val pcm = tone(30_000.0, f)
            gain.apply(pcm, 160, rms(pcm))
            assertTrue(pcm.all { kotlin.math.abs(it.toInt()) <= VoiceGain.CEILING })
        }
        assertEquals(1.0, gain.gain, 0.05)
        assertTrue("потолок пиков — динамик домофона выше только искажает", VoiceGain.limit(100_000.0) <= VoiceGain.CEILING)
        assertEquals("ниже колена — без изменений", 8_000, VoiceGain.limit(8_000.0).toInt())
    }

    /** Тишина и фон между фразами не подтягивают усиление вверх (шум не нарастает). */
    @Test fun silenceDoesNotRaiseGain() {
        val gain = VoiceGain()
        repeat(50) { f -> val pcm = tone(3_000.0, f); gain.apply(pcm, 160, rms(pcm)) }
        val afterSpeech = gain.gain
        repeat(500) { f -> val pcm = tone(100.0, f); gain.apply(pcm, 160, rms(pcm)) }
        assertEquals(afterSpeech, gain.gain, 1e-9)
    }

    /** Звук с улицы в разговоре — отдельно от видео: пошёл — звук плеера выключен; конец разговора — обратно. */
    @Test fun callPlaysStreetAudioSeparatelyAndMutesPlayer() {
        val camera = Camera(reolinkBackchannel)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val talk = DoorbellTalk(activity) { _, result -> result(null) }
        val events = mutableListOf<String>()
        talk.street = object : StreetSource {
            override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) { events += "start"; onSound(true) }
            override fun stop() { events += "stop" }
        }
        val ducks = mutableListOf<Boolean>()
        talk.onDuck = { ducks += it }
        talk.prepare(camera.url, "")
        val until = System.currentTimeMillis() + 5_000
        while (talk.state != DoorbellTalk.State.READY) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) fail("Не подключились: ${talk.state}")
            Thread.sleep(20)
        }
        talk.startCall()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("start"), events)
        assertEquals("звук плеера выключен", listOf(true), ducks)
        talk.endCall()
        assertEquals(listOf("start", "stop"), events)
        assertEquals(listOf(true, false), ducks)
        // Отдельный звук не пошёл — остаётся звук плеера (не выключаем).
        talk.street = object : StreetSource {
            override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) { onSound(false) }
            override fun stop() {}
        }
        talk.startCall()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), ducks)
        talk.dispose()
    }

    /**
     * Одновременный разговор: у канала с 16 кГц (протокол Reolink) включается эхоподавление — канал отдаёт опору, звук
     * улицы и микрофон получают общий [DuplexEcho]; у ONVIF-канала всё остаётся как было (защита «говорит кто-то один»).
     */
    @Test fun widebandCallUsesEchoCancellerAndOnvifCallDoesNot() {
        val taps = mutableListOf<Boolean>()
        val wide = object : TalkChannel {
            override fun open() = "тест 16 кГц"
            override fun beginTalkspurt() {}
            override fun send(pcm: ShortArray, count: Int) {}
            override val wideband get() = true
            override fun tap(sink: ((ShortArray, Int) -> Unit)?) { taps += sink != null }
            override val queued = 0
            override val alive = true
            override val sentPackets = 0
            override val sentVoice = 0
            override fun close() {}
        }
        class Street : StreetSource {
            val echoes = mutableListOf<DuplexEcho?>()
            override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) = onSound(true)
            override fun stop() {}
            override val rate get() = DuplexEcho.RATE
            override fun duplex(echo: DuplexEcho?) { echoes += echo; echo?.active = true }
        }
        fun call(url: String, street: Street) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val talk = DoorbellTalk(activity) { _, result -> result(null) }
            talk.street = street
            talk.prepare(url, "")
            val until = System.currentTimeMillis() + 5_000
            while (talk.state != DoorbellTalk.State.READY) {
                shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > until) fail("Не подключились: ${talk.state}")
                Thread.sleep(20)
            }
            talk.startCall()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(talk.inCall)
            talk.endCall()
            talk.dispose()
        }
        TalkChannels.forget() // прежние тесты могли запомнить, что у 127.0.0.1 протокола Reolink нет
        TalkChannels.reolink = { _, _, _ -> wide }
        try {
            val street = Street()
            call("rtsp://127.0.0.1:1/h264Preview_01_sub", street)
            assertEquals("канал отдаёт опору на время разговора", listOf(true, false), taps)
            assertEquals(2, street.echoes.size)
            assertNotNull(street.echoes[0])
            assertNull("конец разговора — эхоподавление снято", street.echoes[1])
        } finally {
            TalkChannels.reolink = { h, u, p -> BaichuanTalkSession(h, u, p) }
            TalkChannels.forget()
        }
        // Канал ONVIF (G.711, 8 кГц): эхоподавления нет, прежняя защита.
        val camera = Camera(reolinkBackchannel)
        val plain = Street()
        call(camera.url, plain)
        assertTrue("у ONVIF эхоподавление не включается: ${plain.echoes}", plain.echoes.all { it == null })
        TalkChannels.forget()
    }

    /**
     * Темп микрофона: настоящий опережает время не больше чем на свой буфер; источник, отдающий звук без ожидания
     * (поддельный микрофон тестов), притормаживается — иначе поток микрофона заваливал главный поток сообщениями.
     */
    @Test fun microphonePaceCatchesSourceThatDoesNotWait() {
        val frame = 20_000_000L
        val real = MicPace(frame)
        var now = 1_000_000_000L
        // Сначала разом отдаётся накопленный буфер (0,4 с), дальше — по кадру каждые 20 мс.
        repeat(20) { assertFalse(real.ahead(now)) }
        repeat(500) { now += frame; assertFalse(real.ahead(now)) }
        val fake = MicPace(frame)
        var flagged = 0
        repeat(100) { now += 1_000; if (fake.ahead(now)) flagged++ }
        assertTrue("после 0,6 с «звука» за доли миллисекунды — тормозить: $flagged", flagged in 60..75)
    }

    /**
     * Потерянные по дороге пакеты звука улицы считаются по номерам RTP (на их место — тишина той же длины), а не по
     * меткам времени: у домофона Reolink метки убегают без всяких потерь (стенд 20.11.2026).
     */
    @Test fun lostStreetPacketsAreCountedBySequenceNumbers() {
        val gaps = StreetAudio.RtpGaps()
        assertEquals("первый пакет", 0, gaps.missing(65_534))
        assertEquals("подряд", 0, gaps.missing(65_535))
        assertEquals("подряд через ноль", 0, gaps.missing(0))
        assertEquals("пропал один", 1, gaps.missing(2))
        assertEquals("пропало три", 3, gaps.missing(6))
        assertEquals("разрыв — не восполняем", 0, gaps.missing(5_000))
        assertEquals("назад — не потеря", 0, gaps.missing(4_990))
    }

    /** Сигнал стенда для эхоподавления: речь три раза с паузами (первый проход — обучение). */
    @Test fun probeEchoSignalRepeatsSpeechThreeTimes() {
        val speech = ShortArray(16_000) { 1_000 }
        val signal = TalkProbe.echoSignal(16_000, speech)
        assertEquals(16_000 + 3 * (16_000 + 24_000), signal.size)
        assertEquals("перед речью секунда тишины", 0, signal[15_999].toInt())
        assertEquals(1_000, signal[16_000].toInt())
        assertEquals("между проходами — тишина", 0, signal[16_000 + 16_000 + 100].toInt())
        assertEquals(1_000, signal[16_000 + 40_000].toInt())
        assertEquals("для канала 8 кГц речь прорежена", 8_000 + 3 * (8_000 + 12_000), TalkProbe.echoSignal(8_000, speech).size)
    }

    /** Запас звука с улицы: копится до 150 мс, кончился — копится снова; больше 400 мс — сбрасывается до 150 мс. */
    @Test fun streetPlayoutBuffersJitterAndTrimsBacklog() {
        val r = 16_000
        assertEquals(0, StreetAudio.Playout.excess(r * 300 / 1000, r))
        assertEquals(r * 350 / 1000, StreetAudio.Playout.excess(r * 500 / 1000, r))
        val chunk = r / 50
        assertTrue("пока не набралось 150 мс — копим", StreetAudio.Playout.buffering(r * 100 / 1000, r, true, chunk))
        assertFalse("набралось — играем", StreetAudio.Playout.buffering(r * 150 / 1000, r, true, chunk))
        assertFalse("играем, пока есть хоть порция", StreetAudio.Playout.buffering(chunk, r, false, chunk))
        assertTrue("кончилось — снова копим", StreetAudio.Playout.buffering(chunk - 1, r, false, chunk))
    }

    /** Защита от эха: 350 мс после громкого звука с улицы микрофон молчит; улица не звучала — открыт. */
    @Test fun echoGateClosesShortlyAfterLoudStreet() {
        val loud = 1_000_000_000L
        assertFalse(EchoGate.closed(0L, loud))
        assertTrue(EchoGate.closed(loud, loud + 100_000_000L))
        assertTrue(EchoGate.closed(loud, loud + 340_000_000L))
        assertFalse(EchoGate.closed(loud, loud + 400_000_000L))
    }

    private fun sine(freq: Double, amp: Double, n: Int, rate: Int = 16_000) =
        ShortArray(n) { (amp * kotlin.math.sin(2 * Math.PI * freq * it / rate)).toInt().toShort() }

    /** Голос для динамика домофона: басы и гул срезаны (динамик их не отдаёт), остальное — без изменений. */
    @Test fun speechFilterCutsBassAndKeepsVoiceBand() {
        fun through(freq: Double): Double {
            val pcm = sine(freq, 2_000.0, 16_000)
            SpeechFilter(16_000).apply(pcm, pcm.size)
            return rms(pcm.copyOfRange(8_000, 16_000)) / (2_000.0 / kotlin.math.sqrt(2.0))
        }
        assertTrue("100 Гц: ${through(100.0)}", through(100.0) < 0.12)
        assertTrue("500 Гц: ${through(500.0)}", through(500.0) in 0.8..1.0)
        assertTrue("1 кГц: ${through(1_000.0)}", through(1_000.0) in 0.95..1.05)
        assertTrue("3 кГц: ${through(3_000.0)}", through(3_000.0) in 0.95..1.05)
    }

    /** Ровный шум комнаты — не речь (не подтягивает усиление и не глушит улицу); голос поверх шума — речь. */
    @Test fun noiseFloorTellsSpeechFromRoomNoise() {
        val floor = NoiseFloor()
        repeat(500) { assertFalse(floor.speech(50.0 + (it % 7))) }
        assertTrue(floor.speech(400.0))
        assertFalse("тише порога речи — не голос", NoiseFloor().also { f -> repeat(50) { f.speech(5.0) } }.speech(40.0))
    }

    /** Фон между словами приглушается плавно, речь — нет. */
    @Test fun backgroundBetweenWordsIsQuieter() {
        val gain = VoiceGain()
        repeat(20) { gain.apply(tone(2_000.0, it), 160, 1_414.0, speech = true) }
        var out = ShortArray(0)
        repeat(20) { f -> out = tone(200.0, f); gain.apply(out, 160, 141.0, speech = false) }
        val speechGainOnNoise = gain.gain * 141.0
        assertTrue("фон ${rms(out)} против ${speechGainOnNoise}", rms(out) < speechGainOnNoise * (VoiceGain.QUIET + 0.05))
    }

    /** Уличный фон из динамика рядом с микрофоном — не речь: иначе он уходит в домофон и снова глушит улицу. */
    @Test fun streetFromSpeakerIsNotTakenForOwnVoice() {
        val floor = NoiseFloor()
        repeat(200) { floor.speech(25.0) }
        val fromSpeaker = 90.0
        assertFalse(floor.speech(fromSpeaker) && fromSpeaker > EchoGate.OVER_SPEAKER * fromSpeaker)
        val voice = 400.0
        assertTrue(floor.speech(voice) && voice > EchoGate.OVER_SPEAKER * fromSpeaker)
    }

    /** Измерительный сигнал: тоны 1 кГц нужных уровней, без перегрузки; для канала 8 кГц — без частот выше полосы. */
    @Test fun probeSignalHasCalibratedTones() {
        val wide = TalkProbe.signal(16_000, null)
        val first = 16_000 + 16_000 * TalkProbe.TONE_MS / 2000 // середина первого тона
        val peak = (first - 400 until first + 400).maxOf { kotlin.math.abs(wide[it].toInt()) }
        assertEquals(32_767 * Math.pow(10.0, TalkProbe.LEVELS_DB.first() / 20.0), peak.toDouble(), 20.0)
        assertTrue(wide.maxOf { kotlin.math.abs(it.toInt()) } <= 32_767 * Math.pow(10.0, -3 / 20.0) + 2)
        val narrow = TalkProbe.signal(8_000, ShortArray(16_000) { 1_000 })
        assertTrue("у узкого канала меньше тонов и речь прорежена", narrow.size * 2 < wide.size + 16_000 + 16_000)
    }

    /** Симплекс: пауза после «Говорить» — 2,5 с, а с каналом Reolink и отдельным плеером звука — 1,5 с. */
    @Test fun simplexTailIsShorterOnlyForQuickEchoPath() {
        assertEquals(2_500L, EchoGate.tailMs(quick = false))
        assertEquals(1_500L, EchoGate.tailMs(quick = true))
    }

    private fun call(url: String = Camera(reolinkBackchannel).url, setup: (DoorbellTalk) -> Unit = {}): DoorbellTalk {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val talk = DoorbellTalk(activity) { _, result -> result(null) }
        setup(talk)
        talk.prepare(url, "")
        val until = System.currentTimeMillis() + 5_000
        while (talk.state != DoorbellTalk.State.READY) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) fail("Не подключились: ${talk.state}")
            Thread.sleep(20)
        }
        return talk
    }

    /** Источник звука улицы, который отвечает по команде теста: каждый запуск отдаёт свой ответ. */
    private class ScriptedStreet : StreetSource {
        val answers = CopyOnWriteArrayList<(Boolean) -> Unit>()
        @Volatile var stops = 0
        override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) { answers += onSound }
        override fun stop() { stops++ }
    }

    /**
     * Отдельный звук улицы оборвался посреди разговора (камера переподключилась — ретранслятор закрыл слушателей): гостя
     * снова играет плеер, отдельный звук запускается заново в том же разговоре, а после «Завершить» попыток больше нет.
     * Раньше об обрыве никто не узнавал: плееры оставались заглушёнными до конца разговора (аудит 21.11.2026).
     */
    @Test fun streetAudioLostMidCallGivesPlayerSoundBackAndRestarts() {
        val street = ScriptedStreet()
        val ducks = mutableListOf<Boolean>()
        val talk = call { it.street = street; it.onDuck = { on -> ducks += on } }
        val looper = shadowOf(Looper.getMainLooper())
        talk.startCall()
        looper.idle()
        assertEquals(1, street.answers.size)
        // Отдельный звук не пошёл с первого раза — остаётся звук плеера, попытка повторяется.
        street.answers[0](false)
        looper.idle()
        assertEquals(emptyList<Boolean>(), ducks)
        assertFalse(DoorbellVideoView.streetAudio)
        looper.idleFor(DoorbellTalk.STREET_RETRY_MS.first() + 100, TimeUnit.MILLISECONDS)
        assertEquals("вторая попытка", 2, street.answers.size)
        street.answers[1](true)
        looper.idle()
        assertEquals("звук плеера выключен: играет отдельный", listOf(true), ducks)
        assertTrue(DoorbellVideoView.streetAudio)
        // Обрыв после нескольких секунд разговора.
        street.answers[1](false)
        looper.idle()
        assertEquals("звук плеера возвращён", listOf(true, false), ducks)
        assertFalse(DoorbellVideoView.streetAudio)
        assertTrue("разговор продолжается", talk.inCall)
        looper.idleFor(DoorbellTalk.STREET_RETRY_MS.first() + 100, TimeUnit.MILLISECONDS)
        assertEquals("отдельный звук запускается заново", 3, street.answers.size)
        street.answers[2](true)
        looper.idle()
        assertEquals("снова один источник", listOf(true, false, true), ducks)
        assertTrue(DoorbellVideoView.streetAudio)
        // Обрыв и «Завершить», пока идёт восстановление: попыток больше нет, плееры не заглушены.
        street.answers[2](false)
        looper.idle()
        talk.endCall()
        assertEquals(listOf(true, false, true, false), ducks)
        looper.idleFor(30, TimeUnit.SECONDS)
        assertEquals("после конца разговора звук улицы не запускается", 3, street.answers.size)
        assertFalse(DoorbellVideoView.streetAudio)
        talk.dispose()
    }

    /** Запоздалый ответ звука улицы от прежнего разговора не глушит плееры следующего. */
    @Test fun lateAnswerOfPreviousCallDoesNotMuteNextCall() {
        val street = ScriptedStreet()
        val ducks = mutableListOf<Boolean>()
        val talk = call { it.street = street; it.onDuck = { on -> ducks += on } }
        val looper = shadowOf(Looper.getMainLooper())
        talk.startCall()
        looper.idle()
        talk.endCall()
        talk.startCall()
        looper.idle()
        assertEquals(2, street.answers.size)
        street.answers[0](true) // первый разговор уже закончен
        looper.idle()
        assertEquals("ответ прежнего разговора не в счёт", emptyList<Boolean>(), ducks)
        assertFalse(DoorbellVideoView.streetAudio)
        street.answers[0](false)
        looper.idle()
        looper.idleFor(30, TimeUnit.SECONDS)
        assertEquals("и новых попыток из-за него нет", 2, street.answers.size)
        street.answers[1](true)
        looper.idle()
        assertEquals(listOf(true), ducks)
        talk.endCall()
        talk.dispose()
        assertFalse(DoorbellVideoView.streetAudio)
    }

    /** Поддельная камера со звуком G.711: после PLAY шлёт пакеты по 20 мс; [packets] — сколько до обрыва (null — без конца). */
    private class AudioCamera(private val packets: Int?) {
        val server = ServerSocket(0)
        val url get() = "rtsp://127.0.0.1:${server.localPort}/audio"

        init {
            thread(isDaemon = true) {
                try { while (true) { val s = server.accept(); thread(isDaemon = true) { serve(s) } } } catch (_: Exception) {}
            }
        }

        private fun serve(s: Socket) = s.use {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            fun reply(cseq: String, headers: String = "", body: String = "") {
                val b = body.toByteArray()
                synchronized(out) {
                    out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" +
                        (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
                    out.flush()
                }
            }
            try {
                while (true) {
                    val first = input.read()
                    if (first < 0) break
                    if (first == '$'.code) { input.readUnsignedByte(); input.skipBytes(input.readUnsignedShort()); continue }
                    val head = StringBuilder().append(first.toChar())
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val text = head.toString()
                    val cseq = Regex("CSeq: (\\d+)").find(text)!!.groupValues[1]
                    when (text.substringBefore(' ')) {
                        "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $url/\r\n",
                            "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=x\r\nt=0 0\r\nm=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=control:track1\r\n")
                        "SETUP" -> reply(cseq, "Session: 7;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
                        "PLAY" -> {
                            reply(cseq, "Session: 7\r\n")
                            thread(isDaemon = true) {
                                try {
                                    var n = 0
                                    while (packets == null || n < packets) {
                                        val rtp = ByteArray(12 + 160) { 0xFF.toByte() }
                                        rtp[0] = 0x80.toByte(); rtp[1] = 0
                                        rtp[2] = (n shr 8).toByte(); rtp[3] = n.toByte()
                                        val ts = n * 160
                                        rtp[4] = (ts shr 24).toByte(); rtp[5] = (ts shr 16).toByte(); rtp[6] = (ts shr 8).toByte(); rtp[7] = ts.toByte()
                                        synchronized(out) {
                                            out.write(byteArrayOf('$'.code.toByte(), 0, (rtp.size shr 8).toByte(), rtp.size.toByte()) + rtp)
                                            out.flush()
                                        }
                                        n++
                                        Thread.sleep(20)
                                    }
                                    s.close()
                                } catch (_: Exception) {}
                            }
                        }
                        "TEARDOWN" -> { reply(cseq); break }
                        else -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("Не дождались: $what")
            Thread.sleep(10)
        }
    }

    /** Настоящий приём звука улицы: поток оборвался после нескольких пакетов — источник сообщает об этом, а не молчит. */
    @Test fun streetAudioReportsBreakAfterItStarted() {
        val camera = AudioCamera(packets = 15)
        val street = StreetAudio().apply { silent = true }
        val answers = CopyOnWriteArrayList<Boolean>()
        street.start(camera.url, "", null) { answers += it }
        await("звук пошёл: $answers") { answers.firstOrNull() == true }
        await("обрыв замечен: $answers") { answers.toList() == listOf(true, false) }
        assertEquals("после обрыва звук не идёт", 0, street.rate)
        // Тот же источник можно запустить заново (так делает разговор), и остановка обходится без ответа.
        val again = CopyOnWriteArrayList<Boolean>()
        street.start(AudioCamera(packets = null).url, "", null) { again += it }
        await("после перезапуска звук пошёл: $again") { again.firstOrNull() == true }
        street.stop()
        Thread.sleep(300)
        assertEquals("остановленный источник не сообщает об обрыве", listOf(true), again.toList())
    }

    /** «Завершить» и сразу «Ответить»: поток прежнего приёма, досматривая свой обрыв, не гасит новый приём. */
    @Test fun streetAudioRestartedAtOnceKeepsPlaying() {
        val street = StreetAudio().apply { silent = true }
        val first = CopyOnWriteArrayList<Boolean>()
        street.start(AudioCamera(packets = null).url, "", null) { first += it }
        await("первый приём: $first") { first.firstOrNull() == true }
        street.stop()
        val second = CopyOnWriteArrayList<Boolean>()
        street.start(AudioCamera(packets = null).url, "", null) { second += it }
        await("второй приём: $second") { second.firstOrNull() == true }
        Thread.sleep(500)
        assertEquals("второй приём идёт", 8_000, street.rate)
        assertEquals(listOf(true), second.toList())
        assertEquals("первый, остановленный, больше ничего не сообщает", listOf(true), first.toList())
        street.stop()
        assertEquals(0, street.rate)
    }

    /**
     * Канал и звук улицы — 16 кГц (Reolink), а микрофон открылся только на 8 кГц: своё эхоподавление у микрофона на такой
     * частоте не работает, значит, запасная защита «говорит кто-то один» должна остаться — пока улица звучит из
     * динамика, микрофон молчит. Раньше защита снималась по одной лишь частоте улицы (аудит 21.11.2026).
     */
    @Test fun echoGateStaysWhenMicrophoneOpensOnlyAtNarrowband() {
        class Wide : TalkChannel {
            val frames = CopyOnWriteArrayList<Int>() // наибольший по модулю отсчёт каждого кадра, ушедшего в домофон
            override fun open() = "тест 16 кГц"
            override fun beginTalkspurt() {}
            override fun send(pcm: ShortArray, count: Int) { frames += (0 until count).maxOfOrNull { abs(pcm[it].toInt()) } ?: 0 }
            override val wideband get() = true
            override val queued = 0
            override val alive = true
            override val sentPackets = 0
            override val sentVoice = 0
            override fun close() {}
        }
        // Улица 16 кГц звучит громко и не умолкает; эхоподавление она включает по своей частоте, как StreetAudio.
        class LoudStreet : StreetSource {
            override fun start(rtsp: String, user: String, password: String?, onSound: (Boolean) -> Unit) = onSound(true)
            override fun stop() {}
            override val rate get() = DuplexEcho.RATE
            override val lastLoudAt get() = System.nanoTime()
            override val playingRms get() = 3_000.0
            override fun duplex(echo: DuplexEcho?) { echo?.active = true }
        }
        // В микрофоне — громкий тон (как голос гостя из динамика планшета).
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
            object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
                var at = 0
                override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInShorts) audioData[offsetInShorts + i] = (8_000 * kotlin.math.sin(2 * Math.PI * 1_000 * (at++) / 8_000.0)).toInt().toShort()
                    return sizeInShorts
                }
            }
        }
        fun sent(micRates: (Int) -> Boolean): List<Int> {
            val wide = Wide()
            TalkChannels.forget()
            TalkChannels.reolink = { _, _, _ -> wide }
            val talk = call("rtsp://127.0.0.1:1/h264Preview_01_sub") { it.street = LoudStreet(); it.micRates(micRates) }
            talk.startCall()
            shadowOf(Looper.getMainLooper()).idle()
            // Звук улицы принят (ответ источника обработан) — дальше защита уже действует; кадры до этого не в счёт.
            wide.frames.clear()
            await("голос пошёл в канал") { shadowOf(Looper.getMainLooper()).idle(); wide.frames.size >= 25 }
            talk.endCall()
            talk.dispose()
            return wide.frames.drop(3)
        }
        try {
            val narrow = sent { it == 8_000 }
            assertTrue("микрофон 8 кГц: пока улица звучит, в домофон уходит тишина, а ушло до ${narrow.max()}", narrow.all { it == 0 })
            // Штатный случай 16/16: работает своё эхоподавление, микрофон защитой не закрывается.
            val wide = sent { true }
            assertTrue("микрофон 16 кГц: голос не глушится защитой «говорит кто-то один»", wide.any { it > 0 })
        } finally {
            org.robolectric.shadows.ShadowAudioRecord.clearSource()
            TalkChannels.reolink = { h, u, p -> BaichuanTalkSession(h, u, p) }
            TalkChannels.forget()
        }
    }
}
