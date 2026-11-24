package com.example.smarthomedashboard.deep

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smarthomedashboard.ConnectTimeoutSocketFactory
import com.example.smarthomedashboard.DoorbellVideoView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Освобождение плеера, пока камера не отвечает (ночной Deep tests 23.11.2026, docs/doorbell.md): Media3 подключается к
 * камере в потоке плеера, а `release()` ждёт этот поток в главном до 0,5 с — панель замирает. Настоящий Media3 и сеть
 * Android: подключение, оборванное перед освобождением, отпускает поток плеера сразу.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerReleaseDeepTest {
    private fun releaseMs(abort: Boolean): Long {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sockets = ConnectTimeoutSocketFactory(DoorbellVideoView.RTSP_CONNECT_TIMEOUT_MS)
        val exo = onMain {
            ExoPlayer.Builder(context).build().apply {
                setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setSocketFactory(sockets)
                    .createMediaSource(MediaItem.fromUri(Uri.parse("rtsp://$UNREACHABLE:554/none"))))
                prepare()
            }
        }
        Thread.sleep(1_000) // поток плеера ждёт камеру (таймаут подключения — 4 с)
        return onMain {
            val t = SystemClock.elapsedRealtime()
            if (abort) sockets.abort()
            exo.release()
            SystemClock.elapsedRealtime() - t
        }
    }

    @Test fun releaseWhileCameraDoesNotAnswerDoesNotHoldMainThread() {
        val before = releaseMs(abort = false)
        val after = releaseMs(abort = true)
        log("Освобождение плеера, пока камера не отвечает: без обрыва подключения $before мс, с обрывом $after мс")
        assumeTrue("Адрес $UNREACHABLE в этой сети сразу отвечает отказом — проверять нечего ($before мс)", before >= 300)
        assertTrue("С обрывом подключения главный поток не ждёт поток плеера: $after мс (без обрыва $before мс)", after < 150)
    }

    private companion object {
        /** Адрес, который эмулятор не маршрутизирует: подключение висит до таймаута, как к выключенной камере. */
        const val UNREACHABLE = "10.255.255.1"
    }
}
