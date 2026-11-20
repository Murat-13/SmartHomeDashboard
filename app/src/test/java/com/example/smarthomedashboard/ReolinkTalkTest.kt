package com.example.smarthomedashboard

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Разговор по протоколу Reolink (docs/talk-tz.md, «Протокол Reolink»): шифрование, ADPCM, сеанс с поддельной камерой. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReolinkTalkTest {
    @After fun tearDown() {
        TalkChannels.forget()
        TalkChannels.reolink = { h, u, p -> BaichuanTalkSession(h, u, p) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Эталоны — из прототипа, чей AES проверен векторами FIPS-197 и NIST CFB (20.11.2026). */
    @Test fun cryptoMatchesReference() {
        assertEquals("21232F297A57A5A743894A0E4A801FC", BcCrypto.md5("admin"))
        val key = BcCrypto.aesKey("ABCDEF0123", "secret")
        assertEquals("C418FDD04F9470E6", String(key))
        val ext = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n<channelId>0</channelId>\n</Extension>\n".toByteArray()
        val ct = BcCrypto.aesCfb(key, ext, encrypt = true)
        assertArrayEquals(hex("bcaa264358c1115320a58ed6a6fba975c08c078b4c933dec8ceda073e65764b26b16c04f7dfa4435bd7de2dd1928b0062236757bc147dd08a15395f8ce53ba6bc5043c6709e0fcbb57f6cda4002154ba5f81f36418b9ebcb5f4e1ca08acb3a4b8da474286b0b75a7"), ct)
        assertArrayEquals(ext, BcCrypto.aesCfb(key, ct, encrypt = false))
        assertArrayEquals(hex("234f532f2357109a73415377750b179b6613"), BcCrypto.xor("<body>hello</body>".toByteArray(), 0))
    }

    private fun decode(block: ByteArray): ShortArray {
        var pred = ((block[1].toInt() shl 8) or (block[0].toInt() and 0xFF)).toShort().toInt()
        var index = block[2].toInt() and 0xFF
        val out = ArrayList<Short>()
        out += pred.toShort()
        for (i in 4 until block.size) for (code in listOf(block[i].toInt() and 0x0F, (block[i].toInt() shr 4) and 0x0F)) {
            val step = ImaAdpcm.STEPS[index]
            var diff = step shr 3
            if (code and 4 != 0) diff += step
            if (code and 2 != 0) diff += step shr 1
            if (code and 1 != 0) diff += step shr 2
            pred = (if (code and 8 != 0) pred - diff else pred + diff).coerceIn(-32768, 32767)
            index = (index + ImaAdpcm.INDEX[code and 7]).coerceIn(0, 88)
            out += pred.toShort()
        }
        return out.toShortArray()
    }

    @Test fun adpcmBlockDecodesBackToTheVoice() {
        val enc = ImaAdpcm()
        var signal = 0.0; var noise = 0.0
        repeat(5) { b ->
            val pcm = ShortArray(1025) { i -> (8_000 * sin(2 * PI * 440 * (b * 1025 + i) / 16_000.0)).toInt().toShort() }
            val block = enc.encode(pcm)
            assertEquals("заголовок 4 байта + 512 байт", 516, block.size)
            val back = decode(block)
            for (i in 0 until 1025) { signal += pcm[i].toDouble() * pcm[i]; val d = pcm[i] - back[i]; noise += d.toDouble() * d }
        }
        val snr = 10 * log10(signal / noise)
        assertTrue("ADPCM SNR $snr дБ", snr > 20)
        val wrapped = BaichuanTalkSession.wrap(ByteArray(516))
        val b = ByteBuffer.wrap(wrapped).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x62773130, b.int); assertEquals(520, b.short.toInt()); assertEquals(520, b.short.toInt())
        assertEquals(0x0100, b.short.toInt()); assertEquals(256, b.short.toInt())
        assertEquals("12 + 516 + 4 до кратного 8", 532, wrapped.size)
    }

    /** Поддельная камера: вход с AES, возможности, настройка разговора, приём блоков, сброс. */
    private class FakeCamera(val password: String = "secret") : AutoCloseable {
        val server = ServerSocket(0)
        val blocks = AtomicInteger()
        val resets = AtomicInteger()
        val badBlocks = CopyOnWriteArrayList<String>()
        @Volatile var loginOk = false
        private val nonce = "0F1E2D3C4B5A6978"
        private val key = BcCrypto.aesKey(nonce, password)

        init { thread(isDaemon = true) { try { serve() } catch (_: Exception) {} } }

        private fun header(out: OutputStream, msgId: Int, body: ByteArray, num: Int, code: Int, cls: Int, offset: Int?) {
            val h = ByteBuffer.allocate(if (offset != null) 24 else 20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x0ABCDEF0).putInt(msgId).putInt(body.size).put(0).put(0).putShort(num.toShort()).putShort(code.toShort()).putShort(cls.toShort())
            if (offset != null) h.putInt(offset)
            out.write(h.array()); out.write(body); out.flush()
        }

        private fun serve() {
            val s = server.accept()
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val h = ByteArray(20); input.readFully(h)
                val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                b.int; val msgId = b.int; val len = b.int; b.get(); b.get(); val num = b.short.toInt() and 0xFFFF
                val code = b.short.toInt() and 0xFFFF; val cls = b.short.toInt() and 0xFFFF
                val off = if (cls == 0x6414) ByteArray(4).also { input.readFully(it) }.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int } else 0
                val body = ByteArray(len).also { input.readFully(it) }
                val ext = body.copyOfRange(0, off); val pay = body.copyOfRange(off, len)
                when {
                    msgId == 1 && cls == 0x6514 -> {
                        assertEquals(0xDC12, code)
                        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Encryption version=\"1.1\">\n<type>md5</type>\n<nonce>$nonce</nonce>\n</Encryption>\n</body>\n"
                        header(out, 1, BcCrypto.xor(xml.toByteArray(), 0), num, 0xDD12, 0x6614, null)
                    }
                    msgId == 1 -> {
                        val xml = String(BcCrypto.xor(pay, 0))
                        loginOk = BaichuanTalkSession.tag(xml, "userName") == BcCrypto.md5("admin$nonce") &&
                            BaichuanTalkSession.tag(xml, "password") == BcCrypto.md5(password + nonce)
                        header(out, 1, ByteArray(0), num, if (loginOk) 200 else 400, 0x6414, 0)
                        if (!loginOk) { s.close(); return }
                    }
                    msgId == 10 -> {
                        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkAbility version=\"1.1\">\n<duplexList>\n<duplex>FDX</duplex>\n</duplexList>\n" +
                            "<audioStreamModeList>\n<audioStreamMode>followVideoStream</audioStreamMode>\n</audioStreamModeList>\n<audioConfigList>\n<audioConfig>\n" +
                            "<audioType>adpcm</audioType>\n<sampleRate>16000</sampleRate>\n<samplePrecision>16</samplePrecision>\n<lengthPerEncoder>1024</lengthPerEncoder>\n" +
                            "<soundTrack>mono</soundTrack>\n</audioConfig>\n</audioConfigList>\n</TalkAbility>\n</body>\n"
                        header(out, 10, BcCrypto.aesCfb(key, xml.toByteArray(), true), num, 200, 0x6414, 0)
                    }
                    msgId == 201 -> {
                        val xml = String(BcCrypto.aesCfb(key, pay, false))
                        header(out, 201, ByteArray(0), num, if ("<lengthPerEncoder>1024</lengthPerEncoder>" in xml) 200 else 400, 0x6414, 0)
                    }
                    msgId == 202 -> {
                        if ("<binaryData>1</binaryData>" !in String(BcCrypto.aesCfb(key, ext, false))) badBlocks += "расширение"
                        if (ByteBuffer.wrap(pay).order(ByteOrder.LITTLE_ENDIAN).int != 0x62773130 || pay.size != 532) badBlocks += "блок ${pay.size}"
                        blocks.incrementAndGet()
                    }
                    msgId == 11 -> { resets.incrementAndGet(); header(out, 11, ByteArray(0), num, 200, 0x6414, 0) }
                }
            }
        }

        override fun close() = server.close()
    }

    /** Поток идёт непрерывно, без голоса в начале часть тишины не досылается (очередь камеры тает), потом — реальное время. */
    @Test fun sessionLogsInWithAesStreamsBlocksAndDrainsCameraQueue() {
        FakeCamera().use { cam ->
            val talk = BaichuanTalkSession("127.0.0.1", "admin", "secret", port = cam.server.localPort)
            assertTrue(talk.open().contains("Reolink"))
            assertTrue(cam.loginOk)
            Thread.sleep(3_200)
            val sent = cam.blocks.get()
            // 3,2 с реального времени = 50 блоков; четверть пустых не отправлена, пока не набрали 1 с.
            assertTrue("блоков за 3,2 с: $sent", sent in 32..44)
            assertTrue("очередь камеры осушена: ${talk.droppedMs}", talk.droppedMs in 700.0..1_000.0)
            // Голос: 8 кГц → блоки 16 кГц без пропусков.
            talk.beginTalkspurt()
            val before = talk.sentVoice
            repeat(25) { talk.send(ShortArray(160) { i -> (5_000 * sin(2 * PI * 300 * i / 8_000.0)).toInt().toShort() }, 160); Thread.sleep(20) }
            Thread.sleep(300)
            assertTrue("голосовых блоков ${talk.sentVoice - before}", talk.sentVoice - before in 7..9)
            // Микрофон сразу в 16 кГц (канал широкополосный): 0,5 с голоса — те же ~8 блоков, без растягивания.
            assertTrue(talk.wideband)
            val before16 = talk.sentVoice
            repeat(25) { talk.sendAt(ShortArray(320) { i -> (5_000 * sin(2 * PI * 300 * i / 16_000.0)).toInt().toShort() }, 320, 16_000); Thread.sleep(20) }
            Thread.sleep(300)
            assertTrue("голосовых блоков 16 кГц ${talk.sentVoice - before16}", talk.sentVoice - before16 in 7..9)
            assertTrue(cam.badBlocks.toString(), cam.badBlocks.isEmpty())
            assertTrue(talk.alive)
            talk.close()
            Thread.sleep(200)
            assertEquals("в конце — TalkReset", 1, cam.resets.get())
        }
    }

    @Test fun wrongPasswordIsAuthErrorAndNoOnvifRetryWithSamePassword() {
        FakeCamera(password = "other").use { cam ->
            val talk = BaichuanTalkSession("127.0.0.1", "admin", "secret", port = cam.server.localPort)
            try { talk.open(); fail("вход должен не пройти") } catch (e: BaichuanException) { assertTrue(e.auth) }
            assertFalse(cam.loginOk)
        }
    }

    /** Нет протокола Reolink у этой камеры — голос по ONVIF; второй раз протокол Reolink не пробуется. */
    @Test fun fallsBackToOnvifAndRemembersCamera() {
        var tries = 0
        TalkChannels.reolink = { _, _, _ ->
            tries++
            object : TalkChannel {
                override fun open(): String = throw java.net.ConnectException("refused")
                override fun beginTalkspurt() {}
                override fun send(pcm: ShortArray, count: Int) {}
                override val queued = 0
                override val alive = false
                override val sentPackets = 0
                override val sentVoice = 0
                override fun close() {}
            }
        }
        val url = "rtsp://127.0.0.1:1/h264Preview_01_sub" // ONVIF тоже недоступен — важен только выбор пути
        repeat(2) { runCatching { TalkChannels.open(url, "admin", "x", 2_000) } }
        assertEquals("протокол Reolink пробуется один раз", 1, tries)
        assertFalse("у Hikvision протокол Reolink не пробуется", TalkChannels.looksReolink("rtsp://h/Streaming/Channels/101"))
    }
}
