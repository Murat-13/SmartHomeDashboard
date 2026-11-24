package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Освобождение плеера, пока камера не отвечает (ночной Deep tests 23.11.2026, docs/doorbell.md): Media3 подключается к
 * камере в потоке плеера, а `release()` ждёт этот поток в главном до 0,5 с. Подключение, которое ещё ждёт камеру,
 * обрывается перед освобождением; подключённые сокеты остаются плееру.
 */
class RtspConnectAbortTest {
    /** Сокет «камера молчит»: подключение висит до таймаута, закрытие из другого потока его обрывает (как в Android). */
    private class SilentCameraSocket : Socket() {
        val entered = CountDownLatch(1)
        private val closedLatch = CountDownLatch(1)
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            entered.countDown()
            if (!closedLatch.await(timeout.toLong(), TimeUnit.MILLISECONDS)) throw SocketTimeoutException("камера не ответила")
            throw SocketException("Socket closed")
        }
        override fun close() { closedLatch.countDown(); super.close() }
    }

    /** Сокет, к которому камера ответила сразу. */
    private class AnsweredSocket : Socket() {
        var connected = false
        override fun connect(endpoint: SocketAddress?, timeout: Int) { connected = true }
    }

    @Test fun abortFreesPlayerThreadWaitingForCamera() {
        val socket = SilentCameraSocket()
        val factory = ConnectTimeoutSocketFactory(4_000, newSocket = { socket })
        val failure = AtomicReference<Throwable?>()
        val playerThread = thread { try { factory.createSocket("127.0.0.1", 554) } catch (e: Throwable) { failure.set(e) } }
        assertTrue(socket.entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertTrue("без обрыва поток плеера ждёт камеру", playerThread.isAlive)
        val t = System.nanoTime()
        factory.abort()
        playerThread.join(2_000)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertFalse("поток плеера свободен", playerThread.isAlive)
        assertTrue("сразу после обрыва, а не через 4 с: $ms мс", ms < 500)
        assertTrue("плеер получает ошибку подключения: ${failure.get()}", failure.get() is IOException)
        assertTrue(socket.isClosed)
    }

    @Test fun afterAbortNoNewConnection() {
        val socket = SilentCameraSocket()
        val factory = ConnectTimeoutSocketFactory(4_000, newSocket = { socket })
        factory.abort()
        val t = System.nanoTime()
        try {
            factory.createSocket("127.0.0.1", 554)
            throw AssertionError("подключение после освобождения плеера")
        } catch (_: IOException) {
        }
        assertTrue("без ожидания камеры", (System.nanoTime() - t) / 1_000_000 < 500)
        assertEquals("к камере не подключались", 1L, socket.entered.count)
        assertTrue(socket.isClosed)
    }

    @Test fun connectedSocketIsLeftToPlayer() {
        val socket = AnsweredSocket()
        val factory = ConnectTimeoutSocketFactory(4_000, newSocket = { socket })
        assertTrue(factory.createSocket("127.0.0.1", 554) === socket)
        factory.abort()
        assertTrue(socket.connected)
        assertFalse("подключённый сокет закрывает сам плеер, как раньше", socket.isClosed)
    }
}
