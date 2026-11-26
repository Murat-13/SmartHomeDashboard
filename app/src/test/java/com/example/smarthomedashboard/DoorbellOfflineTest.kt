package com.example.smarthomedashboard

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

// Домофон исчез (выключили камеру): без зависаний, без лишних попыток, честное «Нет сигнала».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DoorbellOfflineTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /**
     * Причина лагов 0.9.9: Media3 подключает RTSP-сокет без таймаута; недоступный адрес держал поток плеера
     * минутами, а освобождение плеера ждёт этот поток в главном. Фабрика с таймаутом отпускает за заданное время.
     * (10.255.255.1 — немаршрутизируемый адрес: подключение к нему «висит», как к выключенной камере в чужой сети.)
     */
    @Test fun rtspConnectGivesUpWithinTimeout() {
        val started = System.nanoTime()
        try {
            ConnectTimeoutSocketFactory(300).createSocket("10.255.255.1", 554).close()
            fail("Подключение к недоступному адресу не должно удаться")
        } catch (_: java.io.IOException) {
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("Сдались за $ms мс (таймаут 300 мс)", ms < 2_000)
    }

    @Test fun rtspConnectStillWorksForReachableServer() {
        java.net.ServerSocket(0).use { server ->
            ConnectTimeoutSocketFactory(1_000).createSocket("127.0.0.1", server.localPort).use { assertTrue(it.isConnected) }
            ConnectTimeoutSocketFactory(1_000).createSocket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
                .use { assertTrue(it.isConnected) }
        }
    }

    private fun card(config: DoorbellConfig, values: Map<String, String>): DoorbellCard {
        val card = DoorbellCard(activity, expanded = false)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(card, ViewGroup.LayoutParams(480, 360))
        advance(50)
        val readings = BatteryReadings(HaSession(true, true), values::get, { false }, { null })
        card.bind("Калитка", config, DoorbellModel.snapshot(config, readings), false, 0, null, System.currentTimeMillis(), false)
        return card
    }

    @Test fun haSaysOfflineNoConnectionAttempts() {
        val config = DoorbellConfig(camera = "camera.door", rtspSub = "rtsp://10.255.255.1/sub")
        val card = card(config, mapOf("camera.door" to "unavailable"))
        assertEquals("HA знает, что домофона нет — видео не подключается", DoorbellVideoView.Status.OFFLINE, card.video.status)
        assertEquals(View.VISIBLE, card.findViewWithTag<View>("door_offline").visibility)
        assertFalse(card.video.hasPlayer)
        card.dispose()
    }

    /**
     * Камера на связи, но пароль не принимает (25.11.2026): общий поток записи получил отказ — плитка пишет причину, а видео к камере
     * само не подключается; поток подключился — видео идёт дальше как обычно.
     */
    @Test fun refusedPasswordIsShownAndVideoDoesNotKnockItself() {
        val url = "rtsp://10.255.255.1/h264Preview_01_sub"
        val refuse = java.util.concurrent.atomic.AtomicBoolean(true)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val factory = { _: String, _: String, _: String? ->
            attempts.incrementAndGet()
            object : FeedSession {
                override val sdp = ""
                override fun open(wanted: (SdpTrack) -> Boolean): List<RtspSession.Setup> =
                    throw if (refuse.get()) RtspException("Неверный логин или пароль камеры", 401) else java.io.IOException("нет связи")
                override fun read(sink: RtspSession.Sink) = Unit
                override fun close() = Unit
            }
        }
        val hold = CameraHub.hold(CameraHub.Key(url, "", null), factory)
        // В тестовой среде живое видео выключено по отпечатку сборки — на время теста включаем: до плеера дело не дойдёт.
        val fingerprint = android.os.Build.FINGERPRINT
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "FINGERPRINT", "test")
        try {
            val until = System.currentTimeMillis() + 5_000
            while (!CameraHub.refusing(url, "") && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue(CameraHub.refusing(url, ""))
            val card = card(DoorbellConfig(rtspSub = url), emptyMap())
            advance(6_000)
            assertTrue(card.video.passwordRefused)
            assertFalse("видео само к камере не стучится", card.video.hasPlayer)
            assertEquals("Камера не принимает пароль", card.findViewWithTag<TextView>("door_status").text.toString())
            assertEquals("Камера не принимает логин или пароль · проверьте в редакторе",
                card.findViewWithTag<TextView>("door_connecting").text.toString())
            card.dispose()
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "FINGERPRINT", fingerprint)
            hold.release()
            val until = System.currentTimeMillis() + 3_000
            while (CameraHub.has(url, "") && System.currentTimeMillis() < until) Thread.sleep(20)
        }
    }

    /** Плитка только с адресом RTSP: HA о домофоне не знает — «Нет сигнала» по самому видео, после нескольких неудач. */
    @Test fun rtspOnlyTileShowsNoSignalAfterRepeatedFailures() {
        val config = DoorbellConfig(rtspSub = "rtsp://10.255.255.1/sub")
        val card = card(config, emptyMap())
        val veil = card.findViewWithTag<View>("door_offline")
        assertEquals(View.GONE, veil.visibility)
        card.video.failed("тест")
        assertEquals("Одна неудача — ещё не «нет сигнала»", View.GONE, veil.visibility)
        assertEquals("Нет видео · переподключение…", card.findViewWithTag<TextView>("door_connecting").text.toString())
        repeat(DoorbellVideoView.UNREACHABLE_AFTER - 1) { card.video.failed("тест") }
        assertTrue(card.video.unreachable)
        assertEquals(View.VISIBLE, veil.visibility)
        assertEquals(DoorbellFrame.OFFLINE, card.accent)
        advance(400)
        assertEquals("Застывшая подложка приглушена", 0.4f, card.video.findViewWithTag<View>("door_snapshot").alpha, 0.01f)
        card.dispose()
    }
}
