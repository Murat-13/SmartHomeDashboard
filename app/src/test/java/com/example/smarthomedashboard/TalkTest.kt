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
            override fun start(rtsp: String, user: String, password: String?, onStarted: (Boolean) -> Unit) {}
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
            assertTrue(pcm.all { it in -32_767..32_767 })
        }
        assertEquals(1.0, gain.gain, 0.05)
        assertTrue(VoiceGain.limit(100_000.0) <= 32_767)
        assertEquals("ниже колена — без изменений", 12_000, VoiceGain.limit(12_000.0).toInt())
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
            override fun start(rtsp: String, user: String, password: String?, onStarted: (Boolean) -> Unit) { events += "start"; onStarted(true) }
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
            override fun start(rtsp: String, user: String, password: String?, onStarted: (Boolean) -> Unit) { onStarted(false) }
            override fun stop() {}
        }
        talk.startCall()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), ducks)
        talk.dispose()
    }

    /** Запас воспроизведения звука с улицы не копится: больше 160 мс — сбрасывается до 60 мс. */
    @Test fun streetPlayoutTrimsBacklog() {
        assertEquals(0, StreetAudio.Playout.excess(16_000 * 100 / 1000, 16_000))
        assertEquals(0, StreetAudio.Playout.excess(16_000 * 160 / 1000, 16_000))
        assertEquals(16_000 * 240 / 1000, StreetAudio.Playout.excess(16_000 * 300 / 1000, 16_000))
    }
}
