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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private var camera: FakeCamera? = null

    @After fun tearDown() {
        camera?.close()
        root.deleteRecursively()
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
        val camera = FakeCamera().also { camera = it }
        val url = camera.url
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
            assertEquals("к камере одно подключение (запись + плеер)", 1, camera.connections.get())
            // Второй раз (плитка свернулась и раскрылась) — снова без нового подключения к камере.
            assertTrue(firstFrame(relay).first != null)
            assertEquals(1, camera.connections.get())
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
}
