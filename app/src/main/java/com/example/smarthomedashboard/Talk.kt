package com.example.smarthomedashboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Разговор через домофон (docs/talk-tz.md, этап 1 — «удерживайте и говорите»): голос с микрофона планшета —
 * в динамик домофона по ONVIF-обратному каналу RTSP (G.711, 8 кГц), напрямую с камерой.
 */
internal object G711 {
    /** μ-law (ITU-T G.711), 16 бит → 8 бит. */
    fun ulaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && s and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    /** A-law (ITU-T G.711), 16 бит → 8 бит. */
    fun alaw(sample: Int): Byte {
        var pcm = sample shr 3
        val mask: Int
        if (pcm >= 0) mask = 0xD5 else { mask = 0x55; pcm = -pcm - 1 }
        var seg = 0
        while (seg < 8 && pcm > SEG_END[seg]) seg++
        if (seg >= 8) return (0x7F xor mask).toByte()
        val value = (seg shl 4) or (if (seg < 2) (pcm shr 1) and 0x0F else (pcm shr seg) and 0x0F)
        return (value xor mask).toByte()
    }

    private val SEG_END = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)
}

internal class TalkException(message: String) : java.io.IOException(message)

/** Канал голоса в динамик камеры: ONVIF-обратный канал ([TalkSession]) или протокол Reolink ([BaichuanTalkSession]). */
internal interface TalkChannel : java.io.Closeable {
    /** Подключиться (блокирует; фоновый поток) и начать непрерывный поток. Возвращает описание кодека. */
    fun open(): String
    /** Начало фразы. */
    fun beginTalkspurt()
    /** 16-битные отсчёты 8 кГц в очередь отправки. */
    fun send(pcm: ShortArray, count: Int)
    /** Канал берёт голос 16 кГц (протокол Reolink) — микрофон пишет сразу в 16 кГц, без растягивания. */
    val wideband: Boolean get() = false
    /** Отсчёты с частотой [rate] (8 или 16 кГц). */
    fun sendAt(pcm: ShortArray, count: Int, rate: Int) {
        if (rate == 8_000) { send(pcm, count); return }
        val half = ShortArray(count / 2) { ((pcm[2 * it] + pcm[2 * it + 1]) / 2).toShort() }
        send(half, half.size)
    }
    /**
     * Слушать то, что реально уходит в динамик камеры (опорный сигнал эхоподавления, [DuplexEcho]); null — перестать.
     * Канал, который этого не умеет, ничего не делает — разговор остаётся с прежней защитой [EchoGate].
     */
    fun tap(sink: ((ShortArray, Int) -> Unit)?) {}
    /** Сколько кадров по 20 мс ещё не ушло. */
    val queued: Int
    val alive: Boolean
    val sentPackets: Int
    val sentVoice: Int
}

/**
 * Отправитель голоса жив, пока блоки уходят. Поток идёт непрерывно (тишина или голос), поэтому запись в сокет, не
 * вернувшаяся за [stallMs], значит одно: связь молча пропала, буфер отправки полон. Сам сокет об этом не сообщит
 * минутами; канал считается оборванным, и разговор открывает его заново ([DoorbellTalk]).
 */
internal class SendWatch(private val stallMs: Long = STALL_MS) {
    @Volatile private var lastAt = 0L

    /** Блок ушёл (или отправитель только что запущен). */
    fun sent() { lastAt = System.nanoTime() }

    val stuck get() = lastAt != 0L && System.nanoTime() - lastAt > stallMs * 1_000_000

    companion object {
        const val STALL_MS = 3_000L

        /**
         * Закрыть канал с прощальным сообщением камере ([close]), но не дольше [ms]: если запись в сокет застряла,
         * прощание ждало бы её вместе с потоком, который закрывает канал. Тогда сокет рвётся без прощания ([abort]).
         */
        fun closeWithin(ms: Long, abort: () -> Unit, close: () -> Unit) {
            val killer = Thread({
                try { Thread.sleep(ms); abort() } catch (_: InterruptedException) { /* закрылось само */ }
            }, "talk-close").apply { isDaemon = true; start() }
            try { close() } finally { killer.interrupt() }
        }
    }
}

/**
 * Выбор пути голоса: у камер Reolink — сначала их протокол (порт 9000; до динамика ~0,5 с вместо 2,2–2,4 с по ONVIF, и
 * только с ним разговор одновременный), не вышло — ONVIF, как раньше.
 *
 * Отказ протокола Reolink запоминается ненадолго и только если ONVIF при этом открылся — то есть камера на связи и дело
 * в самом протоколе: после первого отказа следующий разговор пробует его снова, после второго подряд — через 5 минут,
 * дальше — раз в 30 минут ([HOLD_MS]); удача сбрасывает счёт. Не открылось ничего — это сбой связи, о камере он ничего
 * не говорит. Раньше любой отказ (камера перезагружалась, моргнул Wi-Fi, «занято») запоминался до перезапуска
 * приложения, а оно работает сутками: все следующие разговоры шли по ONVIF (аудит 21.11.2026).
 */
internal object TalkChannels {
    /** Протокол Reolink у камеры не открылся [failures] раз подряд; до [until] он не пробуется. */
    private class Hold(val failures: Int, val until: Long)
    private val holds = java.util.concurrent.ConcurrentHashMap<String, Hold>()
    /** Для тестов: подключение по протоколу Reolink (по умолчанию — настоящий сеанс). */
    internal var reolink: (host: String, user: String, password: String) -> TalkChannel = { h, u, p -> BaichuanTalkSession(h, u, p) }
    /** Для тестов: подключение по ONVIF (по умолчанию — настоящий обратный канал RTSP). */
    internal var onvif: (rtsp: String, user: String, password: String, readTimeoutMs: Int) -> TalkChannel = { r, u, p, t ->
        TalkSession(RtspSession(r, u, p, connectTimeoutMs = 4_000, readTimeoutMs = t, require = TalkSession.BACKCHANNEL))
    }
    /** Часы для срока, на который протокол Reolink не пробуется (в тестах — свои). */
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    fun looksReolink(rtsp: String) = "Preview_0" in rtsp

    /** Открыть канал (блокирует; фоновый поток). */
    fun open(rtsp: String, user: String, password: String, readTimeoutMs: Int): TalkChannel {
        val host = runCatching { java.net.URI(rtsp.trim()).host }.getOrNull().orEmpty()
        var refused = false
        if (looksReolink(rtsp) && host.isNotEmpty() && !held(host)) {
            val talk = reolink(host, user, password)
            try {
                val codec = talk.open()
                holds.remove(host)
                Log.i("Talk", "Голос — по протоколу Reolink ($codec)")
                return talk
            } catch (e: BaichuanException) {
                talk.close()
                // Неверный пароль: ошибка сразу — второй вход с тем же паролем по ONVIF не делаем.
                if (e.auth) throw e
                refused = refusedBy(e)
            } catch (e: Exception) {
                talk.close()
                refused = refusedBy(e)
            }
        }
        val talk = onvif(rtsp, user, password, readTimeoutMs)
        try { Log.i("Talk", "Голос — по ONVIF (${talk.open()})") } catch (e: Exception) { talk.close(); throw e }
        if (refused) hold(host)
        return talk
    }

    private fun refusedBy(e: Exception): Boolean {
        Log.w("Talk", "Протокол Reolink не открылся (${e.javaClass.simpleName} ${e.message}) — голос по ONVIF")
        return true
    }

    private fun held(host: String) = holds[host]?.let { clock() < it.until } == true

    /** Камера на связи (ONVIF открылся), а протокол Reolink — нет: какое-то время его не пробуем. */
    private fun hold(host: String) {
        val failures = (holds[host]?.failures ?: 0) + 1
        val pause = HOLD_MS[minOf(failures, HOLD_MS.size) - 1]
        holds[host] = Hold(failures, clock() + pause)
        if (pause > 0) Log.i("Talk", "Протокол Reolink не открылся $failures раз подряд — следующая попытка через ${pause / 60_000} мин")
    }

    internal fun forget() = holds.clear()

    /** На сколько протокол Reolink не пробуется после отказа: первого, второго, третьего и дальше подряд. */
    val HOLD_MS = longArrayOf(0, 5 * 60_000L, 30 * 60_000L)
}

/**
 * Сессия обратного канала: RTSP с `Require: www.onvif.org/ver20/backchannel`, SETUP только дорожки `a=sendonly`
 * с G.711, PLAY. Пока сессия открыта, поток RTP идёт **непрерывно** каждые 20 мс: тишина, а во время разговора —
 * голос из очереди. Reolink молчащий после PLAY обратный канал не воспроизводит (проверено 19.11.2026 на планшете:
 * голос, отправленный через секунды тишины после PLAY, не был слышен; скрипт со звуком сразу после PLAY — был).
 */
internal class TalkSession(private val session: RtspSession) : TalkChannel {
    private lateinit var setup: RtspSession.Setup
    private var aLaw = false
    private val ssrc = java.util.Random().nextInt()
    private var seq = java.util.Random().nextInt(0xFFFF)
    private var timestamp = java.util.Random().nextInt().toLong() and 0xFFFFFFFFL
    @Volatile private var talkspurt = false
    private val pending = ByteArray(FRAME)
    private var filled = 0
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var open = false
    @Volatile private var reader: Thread? = null
    @Volatile override var sentPackets = 0
        private set
    @Volatile override var sentVoice = 0
        private set
    private val watch = SendWatch()

    /** Подключиться (блокирует; фоновый поток) и начать непрерывный поток. Кодек — «PCMU»/«PCMA». */
    override fun open(): String {
        val setups = try {
            session.open { it.isAudio && it.direction == "sendonly" && it.encoding in CODECS }
        } catch (e: RtspException) {
            if (e.message?.contains("Нет подходящих дорожек") == true) throw TalkException(NO_CHANNEL)
            throw e
        }
        setup = setups.first()
        aLaw = setup.track.encoding == "PCMA"
        open = true
        reader = Thread({
            try { session.read(object : RtspSession.Sink { override fun onRtp(setup: RtspSession.Setup, packet: ByteArray, length: Int) {} }) }
            catch (_: Exception) {}
            open = false
        }, "talk-keepalive").apply { isDaemon = true; start() }
        Thread(::stream, "talk-sender").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return setup.track.encoding
    }

    /** Начало фразы: первый пакет голоса — с маркером. */
    override fun beginTalkspurt() {
        filled = 0
        talkspurt = true
    }

    /** 16-битные отсчёты 8 кГц → G.711 → кадры по 160 байт в очередь отправки (не больше ~0,5 с). */
    override fun send(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            pending[filled++] = if (aLaw) G711.alaw(pcm[i].toInt()) else G711.ulaw(pcm[i].toInt())
            if (filled == FRAME) {
                queue.add(pending.copyOf())
                while (queue.size > MAX_QUEUE) queue.poll()
                filled = 0
            }
        }
    }

    /** Отправленное ещё не ушло (для «Проверить звук»: дождаться очереди перед закрытием). */
    override val queued get() = queue.size

    override val alive get() = open && reader?.isAlive == true && !watch.stuck

    /** Каждые 20 мс по часам: кадр голоса из очереди или тишина. */
    private fun stream() {
        val silence = ByteArray(FRAME) { if (aLaw) 0xD5.toByte() else 0xFF.toByte() }
        val packet = ByteArray(12 + FRAME)
        var next = System.nanoTime()
        watch.sent()
        try {
            while (open) {
                val voice = queue.poll()
                val marker = voice != null && talkspurt
                if (marker) talkspurt = false
                packet[0] = 0x80.toByte()
                packet[1] = ((if (marker) 0x80 else 0) or setup.track.payloadType).toByte()
                packet[2] = (seq shr 8).toByte(); packet[3] = seq.toByte()
                val ts = timestamp.toInt()
                packet[4] = (ts shr 24).toByte(); packet[5] = (ts shr 16).toByte(); packet[6] = (ts shr 8).toByte(); packet[7] = ts.toByte()
                packet[8] = (ssrc shr 24).toByte(); packet[9] = (ssrc shr 16).toByte(); packet[10] = (ssrc shr 8).toByte(); packet[11] = ssrc.toByte()
                System.arraycopy(voice ?: silence, 0, packet, 12, FRAME)
                session.sendRtp(setup.rtpChannel, packet, packet.size)
                watch.sent()
                sentPackets++
                if (voice != null) sentVoice++
                seq = (seq + 1) and 0xFFFF
                timestamp = (timestamp + FRAME) and 0xFFFFFFFFL
                next += FRAME_NS
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                else if (wait < -200_000_000) next = System.nanoTime() // после долгой паузы потока не догоняем рывком
            }
        } catch (e: Exception) {
            if (open) Log.w(TAG, "Отправка голоса прервалась: ${e.javaClass.simpleName}")
            open = false
        }
    }

    override fun close() {
        open = false
        reader = null
        SendWatch.closeWithin(CLOSE_MS, abort = session::abort) { if (watch.stuck) session.abort() else session.close() }
    }

    companion object {
        private const val CLOSE_MS = 1_000L
        const val BACKCHANNEL = "www.onvif.org/ver20/backchannel"
        const val FRAME = 160
        private const val FRAME_NS = 20_000_000L
        private const val MAX_QUEUE = 25
        private const val TAG = "Talk"
        private val CODECS = setOf("PCMU", "PCMA")
        const val NO_CHANNEL = "Камера не предлагает обратный звуковой канал — включите ONVIF в настройках камеры"
    }
}

/**
 * Автоусиление голоса для динамика домофона (проверка владельца 20.11.2026: у калитки «очень тихо» при максимальной
 * громкости домофона). Микрофон в режиме разговора Android с подавлением шума отдаёт тихий сигнал, а G.711 его
 * не усиливает. Усиление подстраивается по речи: громкость кадра выше [GATE] — тянем к [TARGET] (быстро вниз,
 * плавно вверх, в пределах 1–[MAX_GAIN]); тишину и фоновый шум не усиливаем сильнее, чем для последней речи.
 * Пики сверх [KNEE] мягко сжимаются до [CEILING] — без хрипа от обрезания.
 *
 * Уровни — по измерению стендом [TalkProbe] (20.11.2026, домофон Reolink на полной громкости): его динамик линеен
 * до ≈ −12 дБ от пика шкалы, выше громкость почти не растёт, а искажения растут (тон −3 дБ — 38–50 %; речь с RMS
 * −12 дБ совпадает с отправленной на 77 % против 90 % при −20 дБ). Поэтому речь — к RMS ≈ −18 дБ, пики — не выше −6 дБ.
 */
internal class VoiceGain {
    var gain = START_GAIN
        private set
    private var sumGain = 0.0
    private var frames = 0

    private var open = 1.0

    /**
     * Усилить кадр на месте; [rms] — громкость кадра до усиления. [speech] = false (фон между словами) — усиление не
     * подстраивается, а фон приглушается до [QUIET] плавно за кадр: шум комнаты не шипит в динамике домофона.
     */
    fun apply(pcm: ShortArray, count: Int, rms: Double, speech: Boolean = true) {
        if (speech && rms > GATE) {
            val wanted = (TARGET / rms).coerceIn(1.0, MAX_GAIN)
            gain = if (wanted < gain) gain + (wanted - gain) * ATTACK else gain + (wanted - gain) * RELEASE
        }
        sumGain += gain
        frames++
        val from = open
        val to = if (speech) 1.0 else maxOf(QUIET, open * 0.7)
        open = to
        for (i in 0 until count) pcm[i] = limit(pcm[i] * gain * (from + (to - from) * i / count.coerceAtLeast(1)))
    }

    /** Среднее усиление за разговор (для журнала). */
    val average get() = if (frames == 0) gain else sumGain / frames

    companion object {
        /** Громкость речи на выходе (RMS из 32 767): около −18 дБ от предела. */
        const val TARGET = 4_000.0
        /** Ниже — тишина или шум: усиление не подстраивается. */
        const val GATE = 100.0
        /** Микрофон без обработки пишет речь с 1–2 м тихо (RMS 150–500 после среза басов): до ×24 (+28 дБ). */
        const val MAX_GAIN = 24.0
        /** Фон между словами — тише в столько раз (−20 дБ): ни шум комнаты, ни улица из динамика не уходят обратно. */
        const val QUIET = 0.1
        const val START_GAIN = 2.0
        private const val ATTACK = 0.5
        private const val RELEASE = 0.05
        const val KNEE = 10_000.0
        /** Потолок пиков: −6 дБ от шкалы — выше динамик домофона только искажает. */
        const val CEILING = 16_000.0

        fun limit(x: Double): Short {
            val a = kotlin.math.abs(x)
            val y = if (a <= KNEE) a else KNEE + (CEILING - KNEE) * kotlin.math.tanh((a - KNEE) / (CEILING - KNEE))
            return (if (x < 0) -y else y).toInt().coerceIn(-32_767, 32_767).toShort()
        }
    }
}

/** Микрофон 8 кГц моно (источник для разговора); если 8 кГц не поддержан — 16 кГц с прореживанием. */
internal class MicCapture(private val onFrame: (ShortArray, Int) -> Unit, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    /** Частота отсчётов, которые получает [onFrame]: 8 кГц, или 16 кГц при [start] с `wideband`. */
    @Volatile var rate = 8_000
        private set
    /** Громкость того, что сейчас звучит из динамика планшета (звук с улицы); 0 — ничего. */
    @Volatile var speaker: () -> Double = { 0.0 }
    /** В последнем кадре — речь (заметно громче шума комнаты), а не фон: она вернётся из домофона эхом. */
    @Volatile var speaking = false
        private set
    /** У этого планшета есть аппаратное/системное подавление эха (для подсказки в разговоре). */
    @Volatile var echoCancel = false
        private set
    /** Эхоподавление разговора: из микрофона вычитается звук улицы, сыгранный динамиком планшета ([DuplexEcho.near]). */
    @Volatile var echo: DuplexEcho? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // разрешение проверяется в start() перед созданием AudioRecord
    /**
     * [duplex] — разговор как по телефону: источник «разговор» Android с подавлением эха и шума (звук с улицы из
     * динамика планшета не уходит обратно). Симплекс (как рация) — улица на это время приглушена, эха нет: микрофон
     * без телефонной обработки (громче и естественнее; решение владельца 20.11.2026).
     */
    fun start(context: Context, duplex: Boolean = true, wideband: Boolean = false): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (running) return true
        val source = if (duplex) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val record = (if (wideband) open(16_000, source) else null) ?: open(8_000, source) ?: open(16_000, source) ?: return false
        rate = if (wideband && record.sampleRate == 16_000) 16_000 else 8_000
        Log.i(TAG, "Микрофон: ${record.sampleRate} Гц" + if (duplex) "" else ", без обработки Android")
        val effects = if (!duplex) emptyList() else listOfNotNull(
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull(),
            runCatching { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        )
        echoCancel = effects.any { it is android.media.audiofx.AcousticEchoCanceler }
        if (duplex) Log.i(TAG, "Подавление эха: " + if (echoCancel) "есть" else "нет на этом планшете")
        running = true
        thread = Thread({
            val decimate = record.sampleRate / rate
            val frame = rate / 50
            val raw = ShortArray(frame * decimate)
            val out = ShortArray(frame)
            val voiceGain = VoiceGain()
            val shape = SpeechFilter(rate)
            val floor = NoiseFloor()
            val pace = MicPace(frame * 1_000_000_000L / rate)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(raw, 0, raw.size)
                    // Микрофон не может отдать звука больше, чем прошло времени (плюс его буфер). Если отдаёт (источник
                    // без ожидания — так ведёт себя поддельный микрофон в тестах) или не отдаёт ничего, не крутиться
                    // вхолостую: иначе поток завалил бы главный сообщениями об уровне.
                    if (n <= 0 || pace.ahead(System.nanoTime())) Thread.sleep(MicPace.WAIT_MS)
                    if (n <= 0) continue
                    val count = n / decimate
                    for (i in 0 until count) {
                        var acc = 0
                        for (k in 0 until decimate) acc += raw[i * decimate + k]
                        out[i] = (acc / decimate).toShort()
                    }
                    TalkDebug.write("mic", out, count, rate)
                    // Голос — разборчивым для маленького динамика домофона: без гула и басов (на них он хрипит), с
                    // подъёмом 1,5–4 кГц. Без обработки Android микрофон этого планшета пишет речь очень глухо: 1–4 кГц
                    // на 25–30 дБ ниже басов (запись разговора 20.11.2026).
                    shape.apply(out, count)
                    // Эхоподавление — до усиления: из микрофона убирается то, что играет динамик планшета.
                    val duplex = echo?.takeIf { it.active && rate == DuplexEcho.RATE }
                    if (duplex != null) {
                        duplex.near.process(out, count)
                        TalkDebug.write("mic_aec", out, count, rate)
                    }
                    var sum = 0.0
                    for (i in 0 until count) sum += out[i] * out[i].toDouble()
                    val rms = sqrt(sum / count.coerceAtLeast(1))
                    // Речь — не остаток эха (с эхоподавлением это видно по спектру) и, без эхоподавления, только заметно
                    // громче того, что сейчас звучит из динамика планшета: иначе уличный фон из динамика принимался за
                    // голос, уходил в домофон и снова глушил улицу («пинг-понг» каждые 2,5 с, запись разговора 20.11.2026).
                    speaking = floor.speech(rms) &&
                        if (duplex != null) !duplex.near.echoDominant else rms > EchoGate.OVER_SPEAKER * speaker()
                    voiceGain.apply(out, count, rms, speaking)
                    onFrame(out, count)
                    // Уровень — до усиления: по нему определяется тишина (автозавершение разговора); речь — всегда «голос».
                    val level = (rms / 8_000.0).toFloat().coerceIn(0f, 1f)
                    onLevel(if (speaking) maxOf(level, SPEECH_LEVEL) else minOf(level, SPEECH_LEVEL / 2))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Микрофон: ${e.javaClass.simpleName}")
            } finally {
                Log.i(TAG, "Микрофон: усиление голоса в среднем ×%.1f".format(voiceGain.average))
                try { record.stop() } catch (_: Exception) {}
                effects.forEach { runCatching { it.release() } }
                record.release()
            }
        }, "talk-mic").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        thread = null
    }

    /** Для тестов: частоты, на которых микрофон «открывается» (на устройстве это решает Android). */
    internal var rates: (Int) -> Boolean = { true }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int, source: Int): AudioRecord? {
        if (!rates(rate)) return null
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        } catch (_: Exception) { return null }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
        return record
    }

    private companion object {
        const val TAG = "Talk"
        /** Уровень «голос» для кадра речи — выше порога тишины разговора (VOICE_LEVEL = 0,04). */
        const val SPEECH_LEVEL = 0.06f
    }
}

/**
 * Разговор одной карточки (окно звонка или раскрытый домофон). Сессия обратного канала открывается заранее
 * ([prepare]), нажатие сразу шлёт голос; карточка закрылась — [dispose]. Колбэки — на главном потоке.
 */
internal class DoorbellTalk(
    private val context: Context,
    private val password: (Context, (String?) -> Unit) -> Unit = { c, result -> DoorbellHub.cameraPassword(c, result) }
) {
    /** Камера с паролем плитки ([CameraPasswords]); пусто — домофон. */
    constructor(context: Context, secretKey: String) : this(context, { c, r -> CameraPasswords.read(c, secretKey, r) })

    enum class State { IDLE, CONNECTING, READY, TALKING, ERROR }

    /**
     * Разговор как по телефону (этап 2, решение владельца 20.11.2026): «Ответить» — микрофон открыт, звук с улицы не
     * приглушается (эхо гасит система), «Завершить» — конец. Сам завершается после [CALL_SILENCE_MS] тишины в
     * микрофоне и в любом случае через [CALL_MAX_MS].
     */
    var inCall = false
        private set
    /** Разговор начался/кончился — владелец карточки держит окно открытым и переключает звук плеера в «разговорный». */
    var onCall: ((Boolean) -> Unit)? = null
    private var callStarted = 0L
    @Volatile private var lastVoiceAt = 0L
    internal var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    private val callWatch = object : Runnable {
        override fun run() {
            if (!inCall) return
            val now = clock()
            when {
                now - lastVoiceAt > CALL_SILENCE_MS -> { Log.i(TAG, "Разговор завершён: 2 мин тишины"); endCall() }
                now - callStarted > CALL_MAX_MS -> { Log.i(TAG, "Разговор завершён: 10 мин"); endCall() }
                else -> { watchChannel(now); main.postDelayed(this, 1_000) }
            }
        }
    }

    /**
     * Канал «планшет → домофон» оборвался посреди разговора: с какого момента ([clock]; 0 — канал в порядке) и сколько
     * попыток открыть его заново не удалось. Раньше за каналом в разговоре никто не следил — очередь отправки молча
     * принимала голос дальше: на экране «Идёт разговор — говорите», а гость нас не слышит до «Завершить» (аудит 21.11.2026).
     */
    private var channelLostAt = 0L
    private var channelRetries = 0
    /** Разговор идёт, но канал голоса оборван и открывается заново: гость нас сейчас не слышит (кнопка — «Нет связи…»). */
    val reconnecting get() = inCall && channelLostAt != 0L

    /** Проверка восстановления (файл `talk_debug/drop_talk`): один раз оборвать канал голоса через 8 с разговора. */
    private val testBreak = Runnable {
        if (inCall) session?.let { s -> Log.i(TAG, "Канал голоса: проверочный обрыв"); closeLater(s) }
    }

    /** Раз в секунду, пока идёт разговор: канал оборвался (или запись в сеть застряла) — открыть заново в этом же разговоре. */
    private fun watchChannel(now: Long) {
        val s = session ?: return // канал ещё открывается — или уже открывается заново
        if (s.alive) return
        Log.w(TAG, "Канал голоса оборвался посреди разговора — открываю заново")
        channelLostAt = now
        channelRetries = 0
        stopDuplex() // опора эхоподавления — то, что уходило в оборванный канал
        closeSession()
        connect(RECONNECT_TEXT)
    }

    /** «Ответить». false — нужно разрешение на микрофон (запрошено). */
    fun startCall(): Boolean {
        if (disposed || inCall) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }
            set(State.ERROR, "Разрешите микрофон и нажмите «Ответить» ещё раз")
            return false
        }
        inCall = true
        playing = false
        callStarted = clock()
        lastVoiceAt = callStarted
        channelLostAt = 0
        Log.i(TAG, "Разговор начат")
        communication(true)
        onCall?.invoke(true)
        main.postDelayed(callWatch, 1_000)
        val s = session
        if (s == null || !s.alive) { if (state != State.CONNECTING) connect() } else startCallAudio(s)
        return true
    }

    /** «Завершить» (и закрытие окна/карточки). */
    fun endCall() {
        if (!inCall) return
        inCall = false
        channelLostAt = 0
        main.removeCallbacks(callWatch)
        main.removeCallbacks(testBreak)
        mic.stop()
        stopStreet()
        stopDuplex()
        TalkDebug.end()
        communication(false)
        onLevel?.invoke(0f)
        onCall?.invoke(false)
        session?.let { Log.i(TAG, "Разговор: голосовых пакетов ${it.sentVoice}, микрофон молчал из-за звука с улицы ${gatedFrames * 20 / 1000} с") }
        if (state == State.TALKING) set(State.READY, READY_TEXT)
    }

    private var savedMode: Int? = null

    /** Режим «разговор» звука Android на время разговора (подавление эха работает в нём лучше); потом — как было. */
    private fun communication(on: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            if (on) { if (savedMode == null) savedMode = audio.mode; audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }
            else savedMode?.let { audio.mode = it; savedMode = null }
        } catch (_: Exception) {}
    }

    /** Звук с улицы на время разговора — отдельно от видео ([StreetAudio]); в тестах — подмена. */
    internal var street: StreetSource = StreetAudio()
    @Volatile private var streetOn = false
    /** Номер запуска звука улицы: ответ прежнего запуска (прежнего разговора) не трогает нынешний. */
    private var streetRun = 0
    /** Сколько раз подряд звук улицы не пошёл или оборвался (пауза перед следующей попыткой — [STREET_RETRY_MS]). */
    private var streetFailures = 0
    /** Запуск звука улицы отправлен, ответа ещё нет. */
    private var streetStarting = false
    private val restartStreet = Runnable { if (inCall && !disposed) startStreet() }

    private fun startStreet() {
        // Уже играет или запускается: повторный вызов не должен обесценить ответ идущего запуска.
        if (streetOn || streetStarting) return
        streetStarting = true
        val target = url; val login = user
        val run = ++streetRun
        password(context) { p ->
            if (disposed || !inCall || run != streetRun) return@password
            street.start(target, login, p) { ok ->
                main.post {
                    if (run != streetRun || !inCall || disposed) return@post
                    streetStarting = false
                    if (ok) {
                        // Звук плееров — выключить (все: иначе второй, запоздалый, слышен как эхо).
                        streetFailures = 0
                        streetOn = true
                        DoorbellVideoView.streetAudio = true
                        onDuck?.invoke(true)
                    } else streetLost()
                }
            }
        }
    }

    /**
     * Отдельный звук улицы не пошёл или оборвался посреди разговора (камера переподключилась — ретранслятор закрыл
     * слушателей; сбой Wi-Fi): гостя снова играет плеер, а приём запускается заново, пока идёт разговор. Раньше об
     * обрыве никто не узнавал, и плееры оставались заглушёнными до «Завершить» (аудит 21.11.2026).
     */
    private fun streetLost() {
        street.stop()
        if (streetOn) {
            streetOn = false
            DoorbellVideoView.streetAudio = false
            onDuck?.invoke(false)
            Log.i(TAG, "Звук с улицы оборвался — играет плеер, отдельный приём запускается заново")
        }
        val pause = STREET_RETRY_MS[minOf(streetFailures, STREET_RETRY_MS.lastIndex)]
        streetFailures++
        main.removeCallbacks(restartStreet)
        main.postDelayed(restartStreet, pause)
    }

    private fun stopStreet() {
        streetRun++
        streetStarting = false
        streetFailures = 0
        main.removeCallbacks(restartStreet)
        street.stop()
        if (streetOn) { streetOn = false; DoorbellVideoView.streetAudio = false; onDuck?.invoke(false) }
    }

    /**
     * Эхоподавление этого разговора (одновременный разговор, docs/talk-tz.md): есть, только если канал отдаёт то, что
     * реально ушло в домофон, на 16 кГц (протокол Reolink) и звук улицы пришёл на той же частоте. Иначе — null или
     * [DuplexEcho.active] = false, и работает прежняя защита «говорит кто-то один» ([EchoGate]).
     */
    @Volatile private var duplex: DuplexEcho? = null
    /**
     * Своё эхоподавление работает в обе стороны: улица пришла на 16 кГц ([DuplexEcho.active] ставит источник звука) и
     * микрофон открылся на 16 кГц. Микрофон только на 8 кГц — вычитание у него не считается ([MicCapture]), и защита
     * «говорит кто-то один» остаётся; раньше она снималась по одной частоте улицы (аудит 21.11.2026).
     */
    private val cancelling get() = duplex?.active == true && mic.rate == DuplexEcho.RATE

    private fun startDuplex(s: TalkChannel) {
        if (!s.wideband || TalkDebug.flag(context, "no_aec")) return
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
        val d = DuplexEcho(key = host)
        d.far.log = { Log.i(TAG, it) }
        d.near.log = { Log.i(TAG, it) }
        s.tap { pcm, n -> d.sent.write(pcm, n); TalkDebug.write("sent_ref", pcm, n, DuplexEcho.RATE) }
        duplex = d
        mic.echo = d
        street.duplex(d)
    }

    private fun stopDuplex() {
        val d = duplex ?: return
        duplex = null
        mic.echo = null
        street.duplex(null)
        session?.tap(null)
        d.remember()
        Log.i(TAG, "Разговор: " + d.far.summary())
        Log.i(TAG, "Разговор: " + d.near.summary())
    }

    private fun startCallAudio(s: TalkChannel) {
        // Канал открыт заново посреди разговора: микрофон и звук улицы уже работают, проверочная запись продолжается.
        val resumed = channelLostAt != 0L
        if (resumed) {
            Log.i(TAG, "Канал голоса восстановлен через ${clock() - channelLostAt} мс")
            channelLostAt = 0
            channelRetries = 0
        }
        s.beginTalkspurt()
        if (!resumed) {
            gatedFrames = 0
            TalkDebug.begin(context)
            if (TalkDebug.flag(context, "drop_talk")) main.postDelayed(testBreak, STREET_TEST_BREAK_MS)
        }
        startDuplex(s)
        // Микрофон без «телефонной» обработки Android: на Galaxy Tab A9+ (SM-X21x) встроенное подавление эха вырезает
        // и сам голос (у калитки «слышно отвратительно», 20.11.2026; то же у разработчиков на форуме Samsung). Эхо
        // убирает своё эхоподавление ([DuplexEcho]); где оно не работает (канал ONVIF, другая частота звука) — прежние
        // защиты: пока говорит улица — микрофон молчит, пока говорим мы — улица приглушена ([EchoGate]).
        if (!mic.start(context, duplex = false, wideband = s.wideband)) { endCall(); set(State.ERROR, "Микрофон недоступен"); return }
        (street as? StreetAudio)?.breakAfterMs = if (TalkDebug.flag(context, "drop_street")) STREET_TEST_BREAK_MS else 0
        startStreet()
        set(State.TALKING, "Идёт разговор — говорите")
    }

    var onState: ((State, String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    /** Голос пошёл/закончился — приглушить звук с улицы (эхо). */
    var onDuck: ((Boolean) -> Unit)? = null
    /** Звук улицы после паузы играет новый плеер без старого запаса ([DoorbellVideoView.soundSeparate]) — пауза короче. */
    var freshStreetSound: (() -> Boolean)? = null
    /** Сколько улица молчит после нашей речи в симплексе ([EchoGate.tailMs]). */
    private fun tailMs() = EchoGate.tailMs(session?.wideband == true && freshStreetSound?.invoke() == true)
    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "talk").apply { isDaemon = true } }
    @Volatile private var session: TalkChannel? = null
    private var url = ""
    private var user = ""
    @Volatile private var disposed = false
    private var pressed = false
    @Volatile private var gatedFrames = 0
    private val micRate get() = mic.rate
    private val mic: MicCapture = MicCapture({ pcm, n ->
        val s = session ?: return@MicCapture
        // Защита от эха (дуплекс): пока из динамика планшета звучит улица, микрофон молчит — её голос не уходит обратно
        // в домофон (системное подавление эха этого планшета не справлялось, проверка 20.11.2026).
        if (inCall && streetOn && !cancelling && EchoGate.closed(street.lastLoudAt, System.nanoTime())) { pcm.fill(0, 0, n); gatedFrames++ }
        TalkDebug.write("sent", pcm, n, micRate)
        try { s.sendAt(pcm, n, micRate) } catch (e: Exception) { main.post { failed(reason(e)) } }
    }, { level ->
        if (level > VOICE_LEVEL) lastVoiceAt = clock()
        // Вы говорите (микрофон не закрыт защитой) — улицу приглушить на путь «планшет → домофон → обратно».
        if (inCall && streetOn && !cancelling && mic.speaking) {
            val now = System.nanoTime()
            if (!EchoGate.closed(street.lastLoudAt, now)) street.muteUntil(now + EchoGate.RETURN_NS)
        }
        main.post { if (pressed || inCall) onLevel?.invoke(level) }
    })

    /** Для тестов: какие частоты микрофон может открыть ([MicCapture.rates]). */
    internal fun micRates(allow: (Int) -> Boolean) { mic.rates = allow }

    init {
        // Что сейчас звучит из динамика (звук с улицы в разговоре): речь в микрофоне должна быть заметно громче.
        mic.speaker = { if (inCall && streetOn && !cancelling) street.playingRms else 0.0 }
    }

    /**
     * Адрес RTSP и логин; открыть сессию заранее. Вызывается при каждом обновлении карточки (раз в секунду и чаще):
     * та же камера — ничего не делаем (иначе разговор рвался бы каждую секунду); после ошибки — повтор не чаще
     * [RETRY_MS].
     */
    fun prepare(rtsp: String, login: String) {
        if (disposed) return
        val same = rtsp == url && login == user
        if (same && state != State.ERROR && state != State.IDLE) return
        if (same && state == State.ERROR && System.currentTimeMillis() - failedAt < RETRY_MS) return
        url = rtsp; user = login
        closeSession()
        connect()
    }

    private var failedAt = 0L

    /** Нажатие «Говорить». false — нужно разрешение на микрофон (запрошено). */
    fun press(): Boolean {
        if (disposed || inCall) return true
        Log.i(TAG, "Нажато «Говорить» (состояние $state)")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }
            set(State.ERROR, "Разрешите микрофон и нажмите «Говорить» ещё раз")
            return false
        }
        pressed = true
        playing = false
        val s = session
        if (s == null || !s.alive) { if (state != State.CONNECTING) connect(); return true }
        startTalking(s)
        return true
    }

    /**
     * Быстрая фраза своим голосом ([DoorbellPhrases]): записанный голос уходит в домофон в реальном темпе. Канал ещё
     * подключается — фраза прозвучит, как только он откроется. false — фраза не принята (уже говорят или звучит).
     */
    fun play(pcm: ShortArray): Boolean {
        if (disposed || pressed || playing || inCall || pcm.isEmpty()) return false
        val s = session
        if (s == null || !s.alive) {
            pendingClip = pcm
            if (state != State.CONNECTING) connect()
            return true
        }
        startClip(s, pcm)
        return true
    }

    @Volatile private var playing = false
    private var pendingClip: ShortArray? = null

    private fun startClip(s: TalkChannel, pcm: ShortArray) {
        playing = true
        onDuck?.invoke(true)
        set(State.TALKING, "Фраза звучит у калитки")
        Log.i(TAG, "Быстрая фраза: ${pcm.size / 8} мс")
        io.execute {
            try {
                s.beginTalkspurt()
                var i = 0
                while (i < pcm.size && playing && !disposed && s.alive) {
                    // Очередь отправки держит ~0,5 с — подаём кадры по мере ухода, не обгоняя реальный темп.
                    while (s.queued > 8 && playing && s.alive) Thread.sleep(10)
                    val n = minOf(TalkSession.FRAME, pcm.size - i)
                    s.send(pcm.copyOfRange(i, i + n), n)
                    i += n
                }
                while (s.queued > 0 && playing && s.alive) Thread.sleep(10)
            } catch (_: Exception) {}
            main.post {
                playing = false
                if (disposed) return@post
                main.postDelayed({ if (!pressed && !playing) onDuck?.invoke(false) }, tailMs())
                if (state == State.TALKING && !pressed) set(State.READY, READY_TEXT)
            }
        }
    }

    fun release() {
        if (!pressed) return
        pressed = false
        mic.stop()
        session?.let { Log.i(TAG, "Отпустили: голосовых пакетов ${it.sentVoice}, всего ${it.sentPackets}") }
        onLevel?.invoke(0f)
        // Домофон доигрывает очередь (≈ 0,5 с) и его микрофон возвращает её в звук с улицы (плеер HD ещё ≈ 1,4 с):
        // улица молчит, пока хвост фразы не вернётся, иначе её конец слышен эхом (проверка владельца 20.11.2026).
        main.postDelayed({ if (!pressed && !playing) onDuck?.invoke(false) }, tailMs())
        main.postDelayed({ TalkDebug.end() }, tailMs())
        if (state == State.TALKING) set(State.READY, READY_TEXT)
    }

    fun dispose() {
        if (disposed) return
        if (inCall) { inCall = false; communication(false); onCall?.invoke(false) }
        stopStreet()
        stopDuplex()
        disposed = true
        pressed = false
        playing = false
        mic.stop()
        onDuck?.invoke(false)
        closeSession()
        io.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    private fun startTalking(s: TalkChannel) {
        s.beginTalkspurt()
        TalkDebug.begin(context)
        if (!mic.start(context, duplex = false, wideband = s.wideband)) { pressed = false; Log.w(TAG, "Микрофон не открылся"); set(State.ERROR, "Микрофон недоступен"); return }
        Log.i(TAG, "Говорим")
        onDuck?.invoke(true)
        set(State.TALKING, "Говорите — вас слышно у калитки")
    }

    /** Открыть канал голоса. [text] — подпись на время подключения; [delayMs] — пауза перед попыткой (повтор после неудачи). */
    private fun connect(text: String = "Подключение к домофону…", delayMs: Long = 0) {
        if (url.isBlank()) { set(State.ERROR, "Нужен прямой поток (RTSP) домофона"); return }
        set(State.CONNECTING, text)
        Log.i(TAG, "Подключение обратного канала")
        val target = url; val login = user
        password(context) { password ->
            if (disposed) return@password
            io.execute {
                var opened: TalkChannel? = null
                try {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    if (disposed) return@execute
                    val talk = TalkChannels.open(target, login, password.orEmpty(), readTimeoutMs = 30_000)
                    opened = talk
                    Log.i(TAG, "Обратный канал открыт")
                    main.post {
                        // Карточку закрыли (или сменили камеру), пока канал открывался: он лишний. Очередь фоновой работы
                        // после закрытия карточки задач уже не берёт — раньше приложение на этом падало.
                        if (disposed || target != url) { closeLater(talk); return@post }
                        session = talk
                        set(State.READY, READY_TEXT)
                        val clip = pendingClip
                        pendingClip = null
                        if (inCall) startCallAudio(talk) else if (pressed) startTalking(talk) else if (clip != null) startClip(talk, clip)
                    }
                } catch (e: Exception) {
                    opened?.close()
                    Log.w(TAG, "Обратный канал не открыт: ${e.javaClass.simpleName} ${e.message}")
                    main.post { if (!disposed) connectFailed(e) }
                }
            }
        }
    }

    /**
     * Канал не открылся. Если он оборвался посреди разговора и время на восстановление ([CHANNEL_GIVE_UP_MS]) не вышло —
     * ещё попытка после паузы, разговор продолжается (звук улицы и видео идут своим путём). Иначе — ошибка на экране:
     * разговор не изображает связь, которой нет. Неверный пароль не повторяем.
     */
    private fun connectFailed(e: Exception) {
        val auth = (e is BaichuanException && e.auth) || (e is RtspException && e.status == 401)
        if (inCall && channelLostAt != 0L && !auth && clock() - channelLostAt < CHANNEL_GIVE_UP_MS) {
            val pause = STREET_RETRY_MS[minOf(channelRetries, STREET_RETRY_MS.lastIndex)]
            channelRetries++
            connect(RECONNECT_TEXT, pause)
            return
        }
        failed(reason(e))
    }

    private fun failed(text: String) {
        failedAt = System.currentTimeMillis()
        channelLostAt = 0
        pendingClip = null
        playing = false
        if (inCall) { inCall = false; main.removeCallbacks(callWatch); communication(false); onCall?.invoke(false) }
        main.removeCallbacks(testBreak)
        stopStreet()
        stopDuplex()
        mic.stop()
        if (pressed) onDuck?.invoke(false)
        pressed = false
        closeSession()
        set(State.ERROR, text)
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        closeLater(s)
    }

    /** Закрыть канал не на главном потоке (прощание с камерой — запись в сеть); карточка уже закрыта — своим потоком. */
    private fun closeLater(s: TalkChannel) {
        try { io.execute { s.close() } }
        catch (_: java.util.concurrent.RejectedExecutionException) { Thread({ s.close() }, "talk-close").apply { isDaemon = true; start() } }
    }

    private fun set(next: State, text: String) {
        state = next
        onState?.invoke(next, text)
    }

    private fun reason(e: Exception) = when {
        e is TalkException -> e.message.orEmpty()
        e is BaichuanException -> e.message.orEmpty()
        e is RtspException && e.status == 401 -> "Камера не приняла логин или пароль"
        e is RtspException && e.status in setOf(453, 503) -> "Сейчас говорит другое устройство"
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
            "Домофон не отвечает — разговор работает только в домашней сети"
        else -> "Связь с домофоном прервалась"
    }

    companion object {
        const val REQUEST_MIC = 4711
        const val CALL_SILENCE_MS = 120_000L
        /** Через сколько повторять запуск отдельного звука улицы, если он не пошёл или оборвался посреди разговора. */
        val STREET_RETRY_MS = longArrayOf(1_000, 2_000, 4_000)
        private const val STREET_TEST_BREAK_MS = 8_000L
        const val CALL_MAX_MS = 10 * 60_000L
        /** Уровень микрофона (0–1), выше которого считается, что говорят (не тишина). */
        private const val VOICE_LEVEL = 0.04f
        private const val RETRY_MS = 15_000L
        /** Сколько канал голоса, оборвавшийся посреди разговора, открывается заново, прежде чем разговор завершится с ошибкой. */
        const val CHANNEL_GIVE_UP_MS = 20_000L
        private const val TAG = "Talk"
        private const val READY_TEXT = "Удерживайте и говорите"
        private const val RECONNECT_TEXT = "Связь с домофоном прервалась — восстанавливаю…"
    }
}

/** «Проверить звук в домофоне» (редактор): короткий «динь-дон» в обратный канал. Результат — на главном потоке. */
internal object TalkCheck {
    fun chime(context: Context, rtsp: String, user: String, secretKey: String = "", done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        CameraPasswords.read(context, secretKey) { password ->
            Thread({
                var talk: TalkChannel? = null
                val result = try {
                    val opened = TalkChannels.open(rtsp, user, password.orEmpty(), readTimeoutMs = 10_000)
                    talk = opened
                    val codec = if (opened is BaichuanTalkSession) "протокол Reolink" else "ONVIF"
                    Log.i("Talk", "Проверка звука: обратный канал открыт ($codec)")
                    Thread.sleep(500) // немного тишины: камера начинает воспроизводить поток
                    opened.beginTalkspurt()
                    val pcm = tone()
                    val frame = ShortArray(TalkSession.FRAME)
                    var i = 0
                    while (i < pcm.size) {
                        val n = minOf(TalkSession.FRAME, pcm.size - i)
                        System.arraycopy(pcm, i, frame, 0, n)
                        opened.send(frame, n)
                        i += n
                        while (opened.queued > 5) Thread.sleep(10)
                    }
                    while (opened.queued > 0) Thread.sleep(10)
                    Thread.sleep(1_500) // камера доигрывает свой буфер — TEARDOWN раньше обрезал бы звук
                    Log.i("Talk", "Проверка звука: отправлено ${opened.sentPackets} пакетов, из них звука ${opened.sentVoice}")
                    "Сигнал отправлен ($codec) — его должно быть слышно у домофона"
                } catch (e: TalkException) {
                    Log.w("Talk", "Проверка звука не удалась: ${e.message}")
                    e.message.orEmpty()
                } catch (e: BaichuanException) {
                    Log.w("Talk", "Проверка звука не удалась: ${e.message}")
                    e.message.orEmpty()
                } catch (e: RtspException) {
                    Log.w("Talk", "Проверка звука не удалась: RTSP ${e.status}")
                    if (e.status == 401) "Камера не приняла логин или пароль" else "Домофон не отвечает (RTSP ${e.status})"
                } catch (e: Exception) {
                    Log.w("Talk", "Проверка звука не удалась: ${e.javaClass.simpleName} ${e.message}")
                    "Домофон не отвечает (${e.javaClass.simpleName})"
                } finally {
                    talk?.close()
                }
                main.post { done(result) }
            }, "talk-check").apply { isDaemon = true; start() }
        }
    }

    /** «Динь-дон» 0,8 с, 8 кГц. */
    internal fun tone(): ShortArray {
        val out = ArrayList<Short>()
        for ((freq, dur) in listOf(880.0 to 0.35, 660.0 to 0.45)) {
            val n = (8_000 * dur).toInt()
            for (i in 0 until n) {
                val env = minOf(1.0, (n - i) / 800.0)
                out += (12_000 * env * kotlin.math.sin(2 * Math.PI * freq * i / 8_000)).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}

/** Кнопка «Говорить»: удержание — голос в домофон. Уровень микрофона — подсветкой фона. */
internal class TalkButton(context: Context, private val talk: DoorbellTalk, private val scale: () -> Float = { 1f }) :
    androidx.appcompat.widget.AppCompatTextView(context) {
    /**
     * Режим кнопки: разговор как по телефону («Ответить» / «Завершить», по умолчанию) или «удерживайте и говорите»
     * (запасной, выбирается в редакторе).
     */
    var callMode = true
        set(value) { field = value; label(talk.state) }

    private fun label(state: DoorbellTalk.State) {
        text = when {
            // Подписи состояния на экране нет — о том, что гость нас сейчас не слышит, говорит сама кнопка
            // (нажатие по-прежнему завершает разговор).
            callMode && talk.reconnecting -> "Нет связи…"
            callMode && talk.inCall -> "Завершить"
            callMode && state == DoorbellTalk.State.CONNECTING -> "Подключение…"
            callMode -> "Ответить"
            state == DoorbellTalk.State.TALKING -> "● Говорите…"
            state == DoorbellTalk.State.CONNECTING -> "Говорить…"
            else -> "Говорить"
        }
        contentDescription = if (callMode) (if (talk.inCall) "Завершить разговор" else "Ответить: разговор с калиткой") else "Говорить: удерживайте"
    }
    /** Касание кнопки — для таймеров автозакрытия владельца. */
    var onActivity: (() -> Unit)? = null
    private val density = resources.displayMetrics.density

    init {
        tag = "door_talk"
        text = "Говорить"
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        contentDescription = "Говорить: удерживайте"
        minHeight = (56 * density).toInt()
        setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
        paint(TalkState(DoorbellTalk.State.IDLE, 0f))
        talk.onState = { state, text ->
            label(state)
            paint(TalkState(state, 0f))
            onStatus?.invoke(text)
        }
        label(DoorbellTalk.State.IDLE)
        talk.onLevel = { level -> paint(TalkState(talk.state, level)) }
    }

    /** Подпись состояния для владельца (окно звонка показывает её под кнопками). */
    var onStatus: ((String) -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility") // удержание, а не нажатие; доступность — через contentDescription
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (callMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { onActivity?.invoke(); parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_UP -> {
                    onActivity?.invoke()
                    if (talk.inCall) talk.endCall() else talk.startCall()
                    label(talk.state)
                    paint(TalkState(talk.state, 0f))
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { onActivity?.invoke(); parent?.requestDisallowInterceptTouchEvent(true); talk.press() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onActivity?.invoke(); talk.release() }
        }
        return true
    }

    private data class TalkState(val state: DoorbellTalk.State, val level: Float)

    private fun paint(s: TalkState) {
        val talking = s.state == DoorbellTalk.State.TALKING
        val accent = when {
            // «Завершить» — красная, как отбой у телефона; уровень голоса — подсветкой.
            callMode && talk.inCall -> Color.rgb(230, 80, 70)
            else -> when (s.state) {
            DoorbellTalk.State.TALKING -> Color.rgb(80, 210, 120)
            DoorbellTalk.State.ERROR -> Color.rgb(255, 120, 110)
            else -> Color.rgb(95, 168, 211)
            }
        }
        val alpha = if (talking) (90 + 140 * s.level).toInt().coerceIn(90, 230) else 60
        background = GradientDrawable().apply {
            cornerRadius = 18f * density * scale()
            setColor(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke((1.5f * density).toInt(), Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }
}

/**
 * Темп микрофона: сколько звука получено против прошедшего времени. Настоящий микрофон опережать время не может больше
 * чем на свой буфер (до 0,4 с); опережение больше [LIMIT_NS] значит, что чтение не ждёт звука.
 */
internal class MicPace(private val frameNs: Long) {
    private var startedAt = 0L
    private var got = 0L

    /** Учесть ещё один кадр, полученный в момент [now]; true — получено больше, чем могло быть записано. */
    fun ahead(now: Long): Boolean {
        if (startedAt == 0L) startedAt = now
        got += frameNs
        return got - (now - startedAt) > LIMIT_NS
    }

    companion object {
        const val LIMIT_NS = 600_000_000L
        const val WAIT_MS = 20L
    }
}

/** Защита от эха в дуплексе: после громкого звука с улицы микрофон молчит [HOLD_NS] (динамик + комната + запас). */
internal object EchoGate {
    const val HOLD_NS = 350_000_000L
    /** Речь в микрофоне должна быть громче звука из динамика в столько раз (динамик рядом: слышен ≈ 1 : 1). */
    const val OVER_SPEAKER = 3.0
    /**
     * Через сколько наш голос возвращается эхом из микрофона домофона: до динамика ≈ 0,5 с (протокол Reolink), обратно
     * ≈ 0,05 с + запас воспроизведения 0,15 с + слова в конце фразы; с запасом — 2 с; столько улица после нашей речи молчит.
     */
    const val RETURN_NS = 2_000_000_000L
    /**
     * Симплекс: после отпускания кнопки улица молчит столько — пока домофон доигрывает очередь, а его микрофон
     * возвращает конец фразы в звук улицы. Обычно — 2,5 с: динамик по ONVIF отстаёт на 2,2–2,4 с, а звук в плеере
     * видео — ещё до 1,4 с. [quick] — голос ушёл по протоколу Reolink (эхо возвращается через 0,7–1,06 с: стенд и
     * четыре разговора 20.11.2026), а звук улицы после паузы начинает новый плеер без старого запаса: хватает 1,5 с.
     */
    fun tailMs(quick: Boolean) = if (quick) QUICK_TAIL_MS else TAIL_MS
    const val TAIL_MS = 2_500L
    const val QUICK_TAIL_MS = 1_500L
    fun closed(lastLoudAt: Long, now: Long) = lastLoudAt != 0L && now - lastLoudAt < HOLD_NS
}

/**
 * Голос для динамика домофона: срез ниже [CUT_HZ] (Баттерворт 2-го порядка). По измерению стендом [TalkProbe]
 * (20.11.2026) динамик домофона Reolink отдаёт 300–700 Гц на 17–24 дБ слабее, чем 1–3 кГц: басы у калитки не
 * слышны, но занимают запас громкости и уводят автоусиление. Высокие не поднимаем — динамик сам отдаёт 2–3 кГц
 * на 9–11 дБ сильнее 1 кГц.
 */
internal class SpeechFilter(rate: Int) {
    private val hp = Biquad.highPass(rate, CUT_HZ)

    companion object { const val CUT_HZ = 350.0 }

    fun apply(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            val y = hp.step(pcm[i].toDouble())
            pcm[i] = y.coerceIn(-32_767.0, 32_767.0).toInt().toShort()
        }
    }

    internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double,
                          private val a1: Double, private val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            fun highPass(rate: Int, f: Double): Biquad {
                val w = 2 * Math.PI * f / rate; val c = kotlin.math.cos(w); val al = kotlin.math.sin(w) / (2 * 0.707)
                val a0 = 1 + al
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
        }
    }
}

/**
 * Уровень шума комнаты (медленно растёт, сразу опускается) и речь — кадр громче шума в [RATIO] раз. Шум не
 * подтягивает усиление и не считается нашим голосом (иначе улица молчала бы весь разговор — запись 20.11.2026).
 */
internal class NoiseFloor {
    var floor = 0.0
        private set

    fun speech(rms: Double): Boolean {
        floor = if (floor == 0.0 || rms < floor) rms.coerceAtLeast(MIN) else floor * RISE
        return rms > floor * RATIO && rms > MIN_SPEECH
    }

    companion object {
        const val RATIO = 3.0
        private const val RISE = 1.003 // ≈ +15 % в секунду при кадрах 20 мс
        private const val MIN = 5.0
        const val MIN_SPEECH = 60.0
    }
}
