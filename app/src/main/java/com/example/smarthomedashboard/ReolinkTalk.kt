package com.example.smarthomedashboard

import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Разговор с камерами Reolink по их собственному протоколу (Baichuan, порт 9000) — docs/talk-tz.md, «Протокол Reolink».
 *
 * Зачем: через ONVIF-обратный канал домофон Reolink проигрывает голос через 2,2–2,4 с (замер 20.11.2026), по своему
 * протоколу — через ~1,25 с, а если в начале не дослать ~1 с тишины, его очередь тает до ~0,5 с (прототип на домофоне
 * владельца, 8 замеров 0,52–0,56 с). Протокол закрытый; устройство взято из открытого проекта neolink.
 *
 * Сеанс: вход (камера присылает одноразовое число; имя и пароль уходят только как MD5 с ним, ключ AES — из него же),
 * TalkAbility → TalkConfig → непрерывный поток блоков IMA ADPCM (16 кГц, 1024 отсчёта = 64 мс) → TalkReset.
 * Голос приходит отсчётами 8 кГц (как для G.711) и удваивается по частоте. Без потока камера не играет — между
 * фразами идёт тишина; часть тишины в начале не отправляется ([DRAIN_MS]), чтобы очередь камеры растаяла.
 *
 * Очередь тает и во время разговора: если разговор начат раньше, чем набралось [DRAIN_MS] (ответили сразу после
 * звонка), остаток добирается на тихих блоках между словами (каждом; до 0.9.50 — каждом втором). Иначе голос доходил бы то через 0,5 с, то через 1,2 с —
 * смотря когда нажали «Ответить» (измерение стендом 20.11.2026: эхо возвращалось через 0,7 или 1,1–1,3 с).
 */
internal class BaichuanTalkSession(
    private val host: String,
    private val user: String,
    private val password: String,
    private val port: Int = PORT,
    private val connectTimeoutMs: Int = 4_000,
    stallMs: Long = SendWatch.STALL_MS
) : TalkChannel {
    private val watch = SendWatch(stallMs)
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var aes: ByteArray? = null
    private var xor = false
    private var num = 0
    private val replies = ConcurrentHashMap<Int, LinkedBlockingQueue<Reply>>()
    @Volatile private var open = false
    @Volatile private var reader: Thread? = null
    @Volatile var droppedMs = 0.0
        private set
    /** Сжатие очереди по замеру своего эха ([QueueTrim]): сколько пауз не досылать сверх [DRAIN_MS] и сколько уже не дослано. */
    @Volatile private var trimTarget = 0.0
    @Volatile override var trimmedMs = 0.0
        private set
    override fun trim(totalMs: Double) { if (totalMs > trimTarget) trimTarget = totalMs }
    @Volatile private var sentTap: ((ShortArray, Int) -> Unit)? = null

    /** Слушатель того, что реально ушло в домофон (как он это услышит — после кодека); блоки, которые не отправлены, сюда не попадают. */
    override fun tap(sink: ((ShortArray, Int) -> Unit)?) { sentTap = sink }

    /** Голос 16 кГц, ещё не отправленный. */
    private val fifo = ShortFifo(16_000)
    private var lastIn: Short = 0
    @Volatile private var lastVoiceAt = 0L
    private var rate = 16_000
    private var lengthPerEncoder = 1024

    @Volatile override var sentPackets = 0
        private set
    @Volatile override var sentVoice = 0
        private set

    override val queued get() = fifo.size / 320
    override val alive get() = open && reader?.isAlive == true && !watch.stuck

    internal class Reply(val code: Int, val payload: ByteArray)

    /** Подключиться, войти, договориться о разговоре и начать поток. Блокирует (фоновый поток). */
    override fun open(): String {
        val address = InetSocketAddress(host, port) // вне apply: внутри `port` — порт самого сокета (0)
        socket = Socket().apply {
            connect(address, connectTimeoutMs)
            tcpNoDelay = true
            soTimeout = 8_000
        }
        input = DataInputStream(socket.getInputStream().buffered())
        output = socket.getOutputStream()
        try {
            login()
            open = true
            reader = Thread(::readLoop, "talk-bc-reader").apply { isDaemon = true; start() }
            val ability = request(MSG_TALK_ABILITY, ext = extension()) ?: throw TalkException("Камера не ответила о разговоре")
            val xml = String(ability.payload, Charsets.UTF_8)
            rate = tag(xml, "sampleRate")?.toIntOrNull() ?: 16_000
            lengthPerEncoder = tag(xml, "lengthPerEncoder")?.toIntOrNull() ?: 1024
            val type = tag(xml, "audioType") ?: "adpcm"
            if (type != "adpcm" || rate != 16_000) throw TalkException("Камера просит звук $type $rate Гц")
            val duplex = tag(xml, "duplex") ?: "FDX"
            val mode = tag(xml, "audioStreamMode") ?: "followVideoStream"
            var config = request(MSG_TALK_CONFIG, ext = extension(), payload = talkConfig(duplex, mode))
            if (config?.code == 422) { // камера занята прошлым разговором — сбросить и повторить
                request(MSG_TALK_RESET, ext = extension())
                config = request(MSG_TALK_CONFIG, ext = extension(), payload = talkConfig(duplex, mode))
            }
            if (config?.code != 200) throw TalkException(if (config?.code == 422) BUSY else "Камера не начала разговор (${config?.code})")
            Thread(::stream, "talk-sender").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
            return "Reolink ADPCM ${rate / 1000} кГц"
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun beginTalkspurt() { lastVoiceAt = System.nanoTime() }

    override val wideband get() = true

    /** Голос 16 кГц (микрофон пишет сразу в 16 кГц) — без растягивания. */
    override fun sendAt(pcm: ShortArray, count: Int, rate: Int) {
        if (rate != 16_000) { send(pcm, count); return }
        if (count <= 0) return
        fifo.push(pcm, count, MAX_FIFO)
        lastIn = pcm[count - 1]
        lastVoiceAt = System.nanoTime()
    }

    /** Отсчёты 8 кГц → 16 кГц (линейно) в очередь отправки; держим не больше ~0,5 с. */
    override fun send(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        val up = ShortArray(count * 2)
        for (i in 0 until count) {
            val s = pcm[i]
            up[2 * i] = ((lastIn + s) / 2).toShort()
            up[2 * i + 1] = s
            lastIn = s
        }
        fifo.push(up, up.size, MAX_FIFO)
        lastVoiceAt = System.nanoTime()
    }

    private fun stream() {
        // Блок — ровно lengthPerEncoder новых отсчётов (64 мс): домофон проигрывает столько же ([ImaAdpcm]).
        val samples = lengthPerEncoder
        val blockNs = samples * 1_000_000_000L / rate
        val encoder = ImaAdpcm()
        val block = ShortArray(samples)
        val heard = ShortArray(samples)
        val blockMs = blockNs / 1e6
        var next = System.nanoTime()
        var silentRun = 0
        var quietRun = 0
        watch.sent()
        try {
            while (open) {
                var voice = 0
                if (System.nanoTime() - lastVoiceAt < VOICE_HOLD_NS || fifo.size > 0) {
                    // Голос: ждём целый блок (микрофон даёт по 20 мс), чтобы не дробить речь тишиной.
                    val until = System.nanoTime() + WAIT_BLOCK_NS
                    while (open && fifo.size < samples && System.nanoTime() < until) Thread.sleep(5)
                    voice = fifo.pop(block, samples)
                    if (voice in 1 until samples) block.fill(0, voice, samples) // конец фразы — дополнить тишиной
                }
                if (voice == 0) {
                    block.fill(0)
                    silentRun++
                    // Очередь камеры: каждый второй пустой блок не отправляем, пока не «съели» DRAIN_MS — она тает за
                    // ≈ 2 с (раньше каждый четвёртый — за ≈ 4 с: «Ответить» сразу после мелодии звонка заставал её почти
                    // целой, и первые ≈ 20 с голос шёл через 1,5 с — разговор 23.11.2026). Предел тот же — без провалов.
                    if (droppedMs + blockMs <= DRAIN_MS && silentRun % 2 == 0) {
                        droppedMs += blockMs
                        next += blockNs
                        if (!sleepUntil(next)) next = System.nanoTime()
                        continue
                    }
                    // Очередь камеры выросла сверх обычной (замер своего эха): каждый второй пустой блок не отправляем.
                    if (trimmedMs + blockMs <= trimTarget && silentRun % 2 == 0) {
                        trimmedMs += blockMs
                        next += blockNs
                        if (!sleepUntil(next)) next = System.nanoTime()
                        continue
                    }
                } else {
                    silentRun = 0
                    // Разговор идёт, а очередь камеры ещё не растаяла: тихие блоки (паузы между словами, фон комнаты) не
                    // отправляем, пока не наберётся DRAIN_MS. Речь не трогаем.
                    quietRun = if (quiet(block, samples)) quietRun + 1 else 0
                    if (droppedMs + blockMs <= DRAIN_MS && quietRun > 0) {
                        droppedMs += blockMs
                        continue
                    }
                    // Очередь выросла сверх обычной — не отправляем ни одного тихого блока, пока не сожмётся.
                    if (quietRun > 0 && trimmedMs + blockMs <= trimTarget) {
                        trimmedMs += blockMs
                        continue
                    }
                }
                val encoded = encoder.encode(block)
                sentTap?.let { sink -> ImaAdpcm.decode(encoded, heard); sink(heard, samples) }
                sendBlock(wrap(encoded))
                watch.sent()
                sentPackets++
                if (voice > 0) {
                    sentVoice++
                    next = System.nanoTime() // голос идёт в темпе микрофона; часы тишины — от последнего блока голоса
                }
                next += blockNs
                if (voice == 0 && !sleepUntil(next)) next = System.nanoTime()
            }
        } catch (e: Exception) {
            if (open) Log.w(TAG, "Отправка голоса (Reolink) прервалась: ${e.javaClass.simpleName}")
            open = false
        }
    }

    private fun quiet(block: ShortArray, count: Int): Boolean {
        var sum = 0.0
        for (i in 0 until count) sum += block[i].toDouble() * block[i]
        return sum < count * QUIET_RMS * QUIET_RMS
    }

    /** false — сильно отстали (поток стоял); догонять рывком не нужно. */
    private fun sleepUntil(at: Long): Boolean {
        val wait = at - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        return wait > -200_000_000
    }

    private fun sendBlock(data: ByteArray) = send(MSG_TALK, ext = extension(binary = true), payload = data, binary = true)

    override fun close() {
        val wasOpen = open
        open = false
        if (::socket.isInitialized) {
            val abort = { try { socket.close() } catch (_: Exception) {} }
            // Прощальный сброс разговора шлём, только если запись в сокет не застряла; и в любом случае не дольше секунды.
            SendWatch.closeWithin(CLOSE_MS, abort) {
                if (wasOpen && !watch.stuck) try { send(MSG_TALK_RESET, ext = extension()) } catch (_: Exception) {}
                abort()
            }
        }
        reader = null
    }

    // ---- Протокол ----

    private fun login() {
        // 1) пустой «старый» вход с просьбой о шифровании AES — камера отвечает одноразовым числом
        writeHeader(MSG_LOGIN, 0, 0, CODE_WANT_AES, CLASS_LEGACY, null)
        output.flush()
        val first = readMessage()
        val xml = String(first.payload.let { if (looksXml(it)) it else BcCrypto.xor(it, first.channel) }, Charsets.UTF_8)
        val nonce = tag(xml, "nonce") ?: throw TalkException("Камера не ответила на вход по протоколу Reolink")
        when (first.code and 0xFF) {
            0x00 -> {}
            0x01 -> xor = true
            0x02, 0x12 -> { xor = true; aes = BcCrypto.aesKey(nonce, password) }
            else -> throw TalkException("Неизвестное шифрование камеры (${first.code})")
        }
        val body = (XML_HEAD + "<body>\n<LoginUser version=\"1.1\">\n<userName>${BcCrypto.md5(user + nonce)}</userName>\n" +
            "<password>${BcCrypto.md5(password + nonce)}</password>\n<userVer>1</userVer>\n</LoginUser>\n" +
            "<LoginNet version=\"1.1\">\n<type>LAN</type>\n<udpPort>0</udpPort>\n</LoginNet>\n</body>\n").toByteArray()
        send(MSG_LOGIN, payload = body)
        val reply = readMessage()
        if (reply.code != 200) throw BaichuanException("Камера не приняла логин или пароль", auth = true)
    }

    private fun request(msgId: Int, ext: ByteArray = ByteArray(0), payload: ByteArray = ByteArray(0)): Reply? {
        val queue = LinkedBlockingQueue<Reply>(1)
        val n = nextNum()
        replies[n] = queue
        try {
            send(msgId, ext, payload, num = n)
            return queue.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            replies.remove(n)
        }
    }

    @Synchronized private fun nextNum(): Int { num = (num + 1) and 0xFFFF; return num }

    @Synchronized
    private fun send(msgId: Int, ext: ByteArray = ByteArray(0), payload: ByteArray = ByteArray(0), binary: Boolean = false,
                     num: Int = -1, code: Int = 0, channel: Int = 0) {
        val e = crypt(ext, msgId, channel)
        val p = if (binary) payload else crypt(payload, msgId, channel)
        writeHeader(msgId, e.size + p.size, if (num >= 0) num else nextNum(), code, CLASS_MODERN, e.size, channel)
        output.write(e)
        output.write(p)
        output.flush()
    }

    private fun crypt(data: ByteArray, msgId: Int, channel: Int): ByteArray = when {
        data.isEmpty() -> data
        aes != null && msgId != MSG_LOGIN -> BcCrypto.aesCfb(aes!!, data, encrypt = true)
        xor || aes != null -> BcCrypto.xor(data, channel)
        else -> data
    }

    private fun decrypt(data: ByteArray, msgId: Int, channel: Int): ByteArray = when {
        data.isEmpty() || looksXml(data) -> data
        aes != null && msgId != MSG_LOGIN -> BcCrypto.aesCfb(aes!!, data, encrypt = false)
        xor || aes != null -> BcCrypto.xor(data, channel)
        else -> data
    }

    private fun writeHeader(msgId: Int, bodyLen: Int, num: Int, code: Int, cls: Int, payloadOffset: Int?, channel: Int = 0) {
        val head = java.nio.ByteBuffer.allocate(if (payloadOffset != null) 24 else 20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        head.putInt(MAGIC).putInt(msgId).putInt(bodyLen).put(channel.toByte()).put(0).putShort(num.toShort())
            .putShort(code.toShort()).putShort(cls.toShort())
        if (payloadOffset != null) head.putInt(payloadOffset)
        output.write(head.array())
    }

    private class Message(val msgId: Int, val channel: Int, val num: Int, val code: Int, val ext: ByteArray, val payload: ByteArray)

    private fun readMessage(): Message {
        val h = ByteArray(20)
        input.readFully(h)
        val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val magic = b.int
        if (magic != MAGIC && magic != MAGIC_REV) throw TalkException("Камера ответила не по протоколу Reolink")
        val msgId = b.int
        val bodyLen = b.int
        val channel = b.get().toInt() and 0xFF
        b.get()
        val n = b.short.toInt() and 0xFFFF
        val code = b.short.toInt() and 0xFFFF
        val cls = b.short.toInt() and 0xFFFF
        val offset = if (cls == CLASS_MODERN || cls == 0) {
            val o = ByteArray(4); input.readFully(o)
            java.nio.ByteBuffer.wrap(o).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        } else 0
        if (bodyLen < 0 || bodyLen > MAX_BODY || offset < 0 || offset > bodyLen) throw TalkException("Повреждённый ответ камеры")
        val body = ByteArray(bodyLen)
        input.readFully(body)
        return Message(msgId, channel, n, code, body.copyOfRange(0, offset), body.copyOfRange(offset, bodyLen))
    }

    private fun readLoop() {
        try {
            while (open) {
                val m = try { readMessage() } catch (_: SocketTimeoutException) { continue }
                if (m.msgId == MSG_KEEPALIVE) { send(MSG_KEEPALIVE, num = m.num, code = 200, channel = m.channel); continue }
                replies[m.num]?.offer(Reply(m.code, decrypt(m.payload, m.msgId, m.channel)))
            }
        } catch (e: Exception) {
            if (open) Log.w(TAG, "Связь с камерой (Reolink) прервалась: ${e.javaClass.simpleName}")
        }
        open = false
    }

    private fun extension(binary: Boolean = false) = (XML_HEAD + "<Extension version=\"1.1\">\n<channelId>0</channelId>\n" +
        (if (binary) "<binaryData>1</binaryData>\n" else "") + "</Extension>\n").toByteArray()

    private fun talkConfig(duplex: String, mode: String) = (XML_HEAD + "<body>\n<TalkConfig version=\"1.1\">\n" +
        "<channelId>0</channelId>\n<duplex>$duplex</duplex>\n<audioStreamMode>$mode</audioStreamMode>\n<audioConfig>\n" +
        "<audioType>adpcm</audioType>\n<sampleRate>$rate</sampleRate>\n<samplePrecision>16</samplePrecision>\n" +
        "<lengthPerEncoder>$lengthPerEncoder</lengthPerEncoder>\n<soundTrack>mono</soundTrack>\n</audioConfig>\n" +
        "</TalkConfig>\n</body>\n").toByteArray()

    companion object {
        const val PORT = 9000
        /** Сколько тишины в начале не досылать: очередь камеры ≈ 1,25 с тает до ≈ 0,5 с (1,2 с — уже провалы). */
        const val DRAIN_MS = 1_000.0
        const val BUSY = "Сейчас говорит другое устройство"
        private const val MAGIC = 0x0ABCDEF0
        private const val MAGIC_REV = 0x0FEDCBA0
        private const val CLASS_LEGACY = 0x6514
        private const val CLASS_MODERN = 0x6414
        private const val CODE_WANT_AES = 0xDC12
        private const val MSG_LOGIN = 1
        private const val MSG_TALK_ABILITY = 10
        private const val MSG_TALK_RESET = 11
        private const val MSG_TALK_CONFIG = 201
        private const val MSG_TALK = 202
        private const val MSG_KEEPALIVE = 234
        private const val REPLY_TIMEOUT_MS = 5_000L
        private const val CLOSE_MS = 1_000L
        private const val MAX_BODY = 1 shl 20
        private const val MAX_FIFO = 8_000 // 0,5 с при 16 кГц
        /** Тише −47 дБ от шкалы — не речь (речь после усиления идёт около −18 дБ). */
        private const val QUIET_RMS = 150.0
        private const val VOICE_HOLD_NS = 150_000_000L
        private const val WAIT_BLOCK_NS = 100_000_000L
        private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
        private const val TAG = "Talk"

        fun tag(xml: String, name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()
        private fun looksXml(data: ByteArray) = data.firstOrNull { it.toInt().toChar() !in " \r\n\t" }?.toInt() == '<'.code

        /** Блок ADPCM в обёртке Reolink (как в neolink): заголовок 12 байт, данные, дополнение до кратного 8. */
        fun wrap(block: ByteArray): ByteArray {
            val n = block.size
            val pad = (8 - n % 8) % 8
            return java.nio.ByteBuffer.allocate(12 + n + pad).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x62773130).putShort((n + 4).toShort()).putShort((n + 4).toShort()).putShort(0x0100)
                .putShort(((n - 4) / 2).toShort()).put(block).array()
        }
    }
}

internal class BaichuanException(message: String, val auth: Boolean = false) : java.io.IOException(message)

/** Шифрование протокола Reolink: XOR («BCEncrypt») для входа, AES-128-CFB для остальных сообщений. */
internal object BcCrypto {
    private val KEY = intArrayOf(0x1F, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0xFF)
    private val IV = "0123456789abcdef".toByteArray()

    fun xor(data: ByteArray, offset: Int) = ByteArray(data.size) { i ->
        (data[i].toInt() xor KEY[(offset + i) % 8] xor (offset and 0xFF)).toByte()
    }

    /** Первые 31 знак MD5 в верхнем регистре (так камера сверяет имя и пароль). */
    fun md5(text: String) = hex(MessageDigest.getInstance("MD5").digest(text.toByteArray())).take(31)

    /** Ключ AES: первые 16 знаков MD5 от «одноразовое число-пароль». */
    fun aesKey(nonce: String, password: String) = hex(MessageDigest.getInstance("MD5").digest("$nonce-$password".toByteArray()))
        .take(16).toByteArray()

    /** Каждое сообщение шифруется с начального вектора заново (как у камеры). */
    fun aesCfb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray = Cipher.getInstance("AES/CFB/NoPadding").run {
        init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        doFinal(data)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
}

/**
 * ADPCM для домофона Reolink — DVI4 по RFC 3551 (п. 4.5.1), а не «IMA ADPCM как в WAV». Три отличия:
 * 1) заголовок 4 байта — **состояние** кодера (предсказанное значение и индекс шага после предыдущего блока), а не
 *    первый отсчёт; 2) в блоке чётное число отсчётов (1024 = 64 мс), все закодированы; 3) в байте первый отсчёт —
 *    в старших четырёх битах.
 * Раскладка «как в WAV» (так в neolink/GStreamer: первый отсчёт в заголовке, 1025 на блок, младшие биты первыми)
 * давала хрип: домофон терял отсчёт на блок (тон 1000 Гц звучал как 1000,98 Гц) и декодировал пары отсчётов в
 * обратном порядке (шум на высоких частотах) — измерено стендом [TalkProbe] 20.11.2026.
 */
internal class ImaAdpcm {
    private var predictor = 0
    private var index = 0

    fun encode(samples: ShortArray): ByteArray {
        val out = ByteArray(4 + (samples.size + 1) / 2)
        out[0] = predictor.toByte(); out[1] = (predictor shr 8).toByte(); out[2] = index.toByte(); out[3] = 0
        var o = 4
        var i = 0
        while (i < samples.size) {
            // DVI4 (RFC 3551): первый отсчёт пары — в старших четырёх битах (в IMA WAV — наоборот).
            val first = nibble(samples[i].toInt())
            val second = if (i + 1 < samples.size) nibble(samples[i + 1].toInt()) else 0
            out[o++] = ((first shl 4) or second).toByte()
            i += 2
        }
        return out
    }

    private fun nibble(sample: Int): Int {
        var step = STEPS[index]
        var diff = sample - predictor
        var code = 0
        if (diff < 0) { code = 8; diff = -diff }
        var delta = step shr 3
        if (diff >= step) { code = code or 4; diff -= step; delta += step }
        step = step shr 1
        if (diff >= step) { code = code or 2; diff -= step; delta += step }
        step = step shr 1
        if (diff >= step) { code = code or 1; delta += step }
        predictor = (if (code and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
        index = (index + INDEX[code and 7]).coerceIn(0, 88)
        return code
    }

    companion object {
        /** Как блок услышит домофон: декодирование DVI4 (состояние — из заголовка блока) в [out]. */
        fun decode(block: ByteArray, out: ShortArray) {
            var predictor = ((block[1].toInt() shl 8) or (block[0].toInt() and 0xFF)).toShort().toInt()
            var index = (block[2].toInt() and 0xFF).coerceIn(0, 88)
            var o = 0
            for (i in 4 until block.size) {
                for (half in 0..1) {
                    if (o >= out.size) return
                    val code = if (half == 0) (block[i].toInt() shr 4) and 0x0F else block[i].toInt() and 0x0F
                    val step = STEPS[index]
                    var diff = step shr 3
                    if (code and 4 != 0) diff += step
                    if (code and 2 != 0) diff += step shr 1
                    if (code and 1 != 0) diff += step shr 2
                    predictor = (if (code and 8 != 0) predictor - diff else predictor + diff).coerceIn(-32768, 32767)
                    index = (index + INDEX[code and 7]).coerceIn(0, 88)
                    out[o++] = predictor.toShort()
                }
            }
        }

        val STEPS = intArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73,
            80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026,
            4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
            22385, 24623, 27086, 29794, 32767)
        val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    }
}

/** Очередь отсчётов с потолком (лишнее старое отбрасывается — голос не копит задержку). */
internal class ShortFifo(capacity: Int) {
    private val buf = ShortArray(capacity)
    private var head = 0
    var size = 0
        private set

    @Synchronized fun push(data: ShortArray, count: Int, limit: Int) {
        for (i in 0 until count) {
            if (size == buf.size || size >= limit) { head = (head + 1) % buf.size; size-- }
            buf[(head + size) % buf.size] = data[i]
            size++
        }
    }

    /** Взять до [count] отсчётов; сколько взяли. */
    @Synchronized fun pop(out: ShortArray, count: Int): Int {
        val n = minOf(count, size)
        for (i in 0 until n) out[i] = buf[(head + i) % buf.size]
        head = (head + n) % buf.size
        size -= n
        return n
    }
}
