package com.example.smarthomedashboard.deep

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smarthomedashboard.CameraHub
import com.example.smarthomedashboard.ContinuousRecorder
import com.example.smarthomedashboard.RecFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Этап 4 (docs/recording.md): плеер плитки (Media3 RTSP, как в DoorbellVideoView) показывает поток камеры через
 * локальный ретранслятор, пока его держит запись, — к камере одно подключение. Камера — поддельная, в этом же
 * процессе (настоящий H.264 из образца ffmpeg, по кругу), без сети и камер владельца; плюс то же на RTSP-стенде CI.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@LargeTest
class CameraRelayDeepTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.cacheDir, "relay-deep-${System.nanoTime()}").apply { mkdirs() }
    private var server: ServerSocket? = null
    private val connections = AtomicInteger()

    @After fun tearDown() {
        server?.close()
        root.deleteRecursively()
    }

    /** Поддельная камера: H.264 160×120, 10 кадров/с, ключевой раз в секунду, бесконечно. */
    private fun fakeCamera(): String {
        val sample = instrumentation.context.assets.open("recording/sample.h264").use { it.readBytes() }
        val units = accessUnits(nals(sample))
        val sps = units[0].first { (it[0].toInt() and 0x1F) == 7 }
        val pps = units[0].first { (it[0].toInt() and 0x1F) == 8 }
        val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
        val s = ServerSocket(0)
        server = s
        val url = "rtsp://127.0.0.1:${s.localPort}/cam"
        val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=fake\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=1\r\n" +
            "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=${b64(sps)},${b64(pps)}\r\n"
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (_: Exception) { break }
                connections.incrementAndGet()
                thread(isDaemon = true) { try { serve(socket, url, sdp, units) } catch (_: Exception) {} finally { socket.close() } }
            }
        }
        return url
    }

    private fun serve(s: Socket, url: String, sdp: String, units: List<List<ByteArray>>) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        fun reply(cseq: String, headers: String = "", body: String = "") = synchronized(out) {
            val b = body.toByteArray()
            out.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" + (if (b.isNotEmpty()) "Content-Length: ${b.size}\r\n" else "") + "\r\n").toByteArray() + b)
            out.flush()
        }
        while (true) {
            val first = reader.readLine() ?: return
            if (first.isEmpty()) continue
            val h = mutableMapOf<String, String>()
            while (true) { val l = reader.readLine() ?: return; if (l.isEmpty()) break; h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            val cseq = h["cseq"].orEmpty()
            when (first.substringBefore(' ')) {
                "OPTIONS" -> reply(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                "DESCRIBE" -> reply(cseq, "Content-Type: application/sdp\r\nContent-Base: $url/\r\n", sdp)
                "SETUP" -> reply(cseq, "Session: 77;timeout=60\r\nTransport: ${h["transport"]}\r\n")
                "PLAY" -> {
                    reply(cseq, "Session: 77\r\n")
                    thread(isDaemon = true) {
                        var seq = 0
                        var frame = 0
                        try {
                            while (!s.isClosed) {
                                val au = units[frame % units.size]
                                for (p in packetize(au, frame * 9_000L, seq)) {
                                    synchronized(out) { out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p) }
                                    seq = (seq + 1) and 0xFFFF
                                }
                                synchronized(out) { out.flush() }
                                frame++
                                Thread.sleep(100)
                            }
                        } catch (_: Exception) {}
                    }
                }
                "TEARDOWN" -> { reply(cseq); return }
                else -> reply(cseq)
            }
        }
    }

    /** Первый кадр в плеере Media3 (RTSP по TCP, как в плитке); вернуть время до него или ошибку. */
    private fun firstFrame(url: String, timeoutS: Long = 20): Pair<Long?, String?> {
        val rendered = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val player = AtomicReference<ExoPlayer>()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val started = System.nanoTime()
        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context).build()
            player.set(exo)
            exo.setVideoSurface(surface)
            exo.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() = rendered.countDown()
                override fun onPlayerError(e: PlaybackException) { error.set(e.errorCodeName); rendered.countDown() }
            })
            exo.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(6_000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))))
            exo.playWhenReady = true
            exo.prepare()
        }
        val ok = rendered.await(timeoutS, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - started) / 1_000_000
        instrumentation.runOnMainSync { player.get().release() }
        surface.release(); texture.release()
        return (if (ok && error.get() == null) ms else null) to (error.get() ?: if (!ok) "нет первого кадра за $timeoutS с" else null)
    }

    private fun recorder(url: String) = ContinuousRecorder("relay", url, "", null, audio = true, root = { root }, canWrite = { true },
        onSegment = { _: RecFile -> }, onStatus = { _, _ -> })

    @Test fun livePlayerShowsRecordedStreamThroughTabletWithOneCameraConnection() {
        val url = fakeCamera()
        val r = recorder(url)
        r.start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            var relay: String? = null
            while (relay == null && System.currentTimeMillis() < deadline) { relay = CameraHub.relayUrl(url, ""); Thread.sleep(50) }
            assertNotNull("поток записи пошёл", relay)
            val (ms, err) = firstFrame(relay!!)
            assertTrue("плеер через ретранслятор: ${err ?: "кадр за $ms мс"}", ms != null)
            android.util.Log.i("DeepTest", "Живое видео через планшет: первый кадр за $ms мс")
            assertEquals("к камере одно подключение (запись + плеер)", 1, connections.get())
            // Второй раз (плитка свернулась и раскрылась) — снова без нового подключения к камере.
            assertTrue(firstFrame(relay).first != null)
            assertEquals(1, connections.get())
        } finally {
            r.stop()
        }
    }

    @Test fun standSubstreamThroughRelay() {
        val host = InstrumentationRegistry.getArguments().getString("rtspHost")
        assumeTrue("Нужен аргумент rtspHost (RTSP-стенд CI)", host != null)
        val url = "rtsp://$host:8554/sub"
        val r = recorder(url)
        r.start()
        try {
            val deadline = System.currentTimeMillis() + 15_000
            var relay: String? = null
            while (relay == null && System.currentTimeMillis() < deadline) { relay = CameraHub.relayUrl(url, ""); Thread.sleep(100) }
            assertNotNull(relay)
            val (ms, err) = firstFrame(relay!!, 30)
            assertTrue("стенд через ретранслятор: ${err ?: "кадр за $ms мс"}", ms != null)
            android.util.Log.i("DeepTest", "Стенд через планшет: первый кадр за $ms мс")
        } finally {
            r.stop()
        }
    }

    // ---- H.264 → RTP (как в RecordingTestSupport JVM-тестов) ----

    private fun nals(annexB: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 <= annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                if (annexB[i + 2].toInt() == 1) { starts += (i + 3) to 3; i += 3; continue }
                if (i + 4 <= annexB.size && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) { starts += (i + 4) to 4; i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { index, (pos, _) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first - starts[index + 1].second else annexB.size
            annexB.copyOfRange(pos, end)
        }.filter { it.isNotEmpty() }
    }

    private fun accessUnits(nals: List<ByteArray>): List<List<ByteArray>> {
        val out = mutableListOf<MutableList<ByteArray>>()
        for (n in nals) {
            val aud = (n[0].toInt() and 0x1F) == 9
            if (aud || out.isEmpty()) out += mutableListOf<ByteArray>()
            if (!aud) out.last() += n
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun packetize(au: List<ByteArray>, ts: Long, seqStart: Int, mtu: Int = 1_200): List<ByteArray> {
        val payloads = mutableListOf<ByteArray>()
        for (nal in au) {
            if (nal.size <= mtu) { payloads += nal; continue }
            var pos = 1
            var first = true
            while (pos < nal.size) {
                val chunk = minOf(mtu, nal.size - pos)
                val last = pos + chunk >= nal.size
                val type = nal[0].toInt() and 0x1F
                payloads += byteArrayOf(((nal[0].toInt() and 0xE0) or 28).toByte(),
                    ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()) + nal.copyOfRange(pos, pos + chunk)
                pos += chunk
                first = false
            }
        }
        return payloads.mapIndexed { i, p ->
            val seq = (seqStart + i) and 0xFFFF
            val h = ByteArray(12)
            h[0] = 0x80.toByte()
            h[1] = ((if (i == payloads.lastIndex) 0x80 else 0) or 96).toByte()
            h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
            h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
            h[8] = 0x12; h[9] = 0x34; h[10] = 0x56; h[11] = 0x78
            h + p
        }
    }
}
