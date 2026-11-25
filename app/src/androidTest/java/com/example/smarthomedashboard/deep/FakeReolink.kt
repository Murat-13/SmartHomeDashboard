package com.example.smarthomedashboard.deep

import com.example.smarthomedashboard.BcCrypto
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Поддельная камера Reolink (порт 9000, AES) в процессе теста: то же видео, что у [FakeCamera], по протоколу Reolink. [units] — кадры
 * (NAL без стартовых кодов), 10 в секунду.
 */
internal class FakeReolink(private val units: List<List<ByteArray>>) : Closeable {
    private val server = ServerSocket(9000, 8, InetAddress.getByName("127.0.0.1"))
    private val open = CopyOnWriteArrayList<Socket>()
    val connections = AtomicInteger()
    val streams = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                open += s
                connections.incrementAndGet()
                thread(isDaemon = true) { try { serve(s) } catch (_: Exception) {} finally { s.close() } }
            }
        }
    }

    /** Камера пропала: связи рвутся, новых нет. */
    override fun close() { server.close(); open.forEach { runCatching { it.close() } } }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun write(s: Socket, msgId: Int, num: Int, code: Int, ext: ByteArray, payload: ByteArray, modern: Boolean = true) = synchronized(s) {
        val h = le(if (modern) 24 else 20).putInt(0x0ABCDEF0).putInt(msgId).putInt(ext.size + payload.size).put(0).put(0).putShort(num.toShort())
            .putShort(code.toShort()).putShort((if (modern) 0x6414 else 0x6514).toShort())
        if (modern) h.putInt(ext.size)
        s.getOutputStream().write(h.array() + ext + payload)
        s.getOutputStream().flush()
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        val key = BcCrypto.aesKey(NONCE, FakeCamera.PASSWORD)
        while (true) {
            val h = ByteArray(20)
            input.readFully(h)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            b.int
            val msgId = b.int
            val len = b.int
            b.short
            val num = b.short.toInt() and 0xFFFF
            b.short
            val cls = b.short.toInt() and 0xFFFF
            if (cls == 0x6414) input.readFully(ByteArray(4))
            val body = ByteArray(len)
            input.readFully(body)
            when {
                msgId == 1 && cls == 0x6514 -> write(s, 1, 0, 0xDD02, ByteArray(0),
                    BcCrypto.xor("<body><Encryption version=\"1.1\"><nonce>$NONCE</nonce></Encryption></body>".toByteArray(), 0), modern = false)
                msgId == 1 -> write(s, 1, num, 200, ByteArray(0), ByteArray(0))
                msgId == 3 -> {
                    streams += if (String(BcCrypto.aesCfb(key, body, encrypt = false)).contains("subStream")) "sub" else "main"
                    thread(isDaemon = true) { try { play(s, num, key) } catch (_: Exception) {} }
                }
                msgId == 93 -> write(s, 93, num, 200, ByteArray(0), ByteArray(0))
            }
        }
    }

    private fun play(s: Socket, num: Int, key: ByteArray) {
        val ext = BcCrypto.aesCfb(key, "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n<binaryData>1</binaryData>\n</Extension>\n".toByteArray(), encrypt = true)
        write(s, 3, num, 200, ext, le(32).putInt(0x32303031).putInt(32).array())
        var us = 1_000_000
        var i = 0
        while (!s.isClosed) {
            val unit = units[i % units.size]
            val data = unit.fold(ByteArray(0)) { a, n -> a + byteArrayOf(0, 0, 0, 1) + n }
            val isKey = unit.any { (it[0].toInt() and 0x1F) == 5 }
            val head = le(24).putInt(if (isKey) 0x63643030 else 0x63643130).put("H264".toByteArray()).putInt(data.size).putInt(0).putInt(us).putInt(0).array()
            write(s, 3, num, 0, ext, head + data + ByteArray((8 - data.size % 8) % 8))
            us += 100_000
            i++
            Thread.sleep(100)
        }
    }

    private companion object { const val NONCE = "ABCDEF0123" }
}
