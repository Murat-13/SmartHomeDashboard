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

/**
 * Сессия обратного канала: RTSP с `Require: www.onvif.org/ver20/backchannel`, SETUP только дорожки `a=sendonly`
 * с G.711, PLAY. Пока сессия открыта, поток RTP идёт **непрерывно** каждые 20 мс: тишина, а во время разговора —
 * голос из очереди. Reolink молчащий после PLAY обратный канал не воспроизводит (проверено 19.11.2026 на планшете:
 * голос, отправленный через секунды тишины после PLAY, не был слышен; скрипт со звуком сразу после PLAY — был).
 */
internal class TalkSession(private val session: RtspSession) : java.io.Closeable {
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
    @Volatile var sentPackets = 0
        private set
    @Volatile var sentVoice = 0
        private set

    /** Подключиться (блокирует; фоновый поток) и начать непрерывный поток. Кодек — «PCMU»/«PCMA». */
    fun open(): String {
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
    fun beginTalkspurt() {
        filled = 0
        talkspurt = true
    }

    /** 16-битные отсчёты 8 кГц → G.711 → кадры по 160 байт в очередь отправки (не больше ~0,5 с). */
    fun send(pcm: ShortArray, count: Int) {
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
    val queued get() = queue.size

    val alive get() = open && reader?.isAlive == true

    /** Каждые 20 мс по часам: кадр голоса из очереди или тишина. */
    private fun stream() {
        val silence = ByteArray(FRAME) { if (aLaw) 0xD5.toByte() else 0xFF.toByte() }
        val packet = ByteArray(12 + FRAME)
        var next = System.nanoTime()
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
        session.close()
    }

    companion object {
        const val BACKCHANNEL = "www.onvif.org/ver20/backchannel"
        const val FRAME = 160
        private const val FRAME_NS = 20_000_000L
        private const val MAX_QUEUE = 25
        private const val TAG = "Talk"
        private val CODECS = setOf("PCMU", "PCMA")
        const val NO_CHANNEL = "Камера не предлагает обратный звуковой канал — включите ONVIF в настройках камеры"
    }
}

/** Микрофон 8 кГц моно (источник для разговора); если 8 кГц не поддержан — 16 кГц с прореживанием. */
internal class MicCapture(private val onFrame: (ShortArray, Int) -> Unit, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    /** У этого планшета есть аппаратное/системное подавление эха (для подсказки в разговоре). */
    @Volatile var echoCancel = false
        private set
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // разрешение проверяется в start() перед созданием AudioRecord
    fun start(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (running) return true
        val record = open(8_000) ?: open(16_000) ?: return false
        Log.i(TAG, "Микрофон: ${record.sampleRate} Гц")
        // Разговор как по телефону (этап 2): подавление эха и шума Android — звук с улицы из динамика планшета
        // не уходит обратно в домофон. Есть не на всех планшетах; нет — работает без него.
        val effects = listOfNotNull(
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull(),
            runCatching { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        )
        echoCancel = effects.any { it is android.media.audiofx.AcousticEchoCanceler }
        Log.i(TAG, "Подавление эха: " + if (echoCancel) "есть" else "нет на этом планшете")
        running = true
        thread = Thread({
            val decimate = record.sampleRate / 8_000
            val raw = ShortArray(160 * decimate)
            val out = ShortArray(160)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(raw, 0, raw.size)
                    if (n <= 0) continue
                    val count = n / decimate
                    var sum = 0.0
                    for (i in 0 until count) {
                        var acc = 0
                        for (k in 0 until decimate) acc += raw[i * decimate + k]
                        val v = (acc / decimate).toShort()
                        out[i] = v
                        sum += v * v.toDouble()
                    }
                    onFrame(out, count)
                    onLevel((sqrt(sum / count.coerceAtLeast(1)) / 8_000.0).toFloat().coerceIn(0f, 1f))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Микрофон: ${e.javaClass.simpleName}")
            } finally {
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

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        } catch (_: Exception) { return null }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
        return record
    }

    private companion object { const val TAG = "Talk" }
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
                else -> main.postDelayed(this, 1_000)
            }
        }
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
        main.removeCallbacks(callWatch)
        mic.stop()
        communication(false)
        onLevel?.invoke(0f)
        onCall?.invoke(false)
        session?.let { Log.i(TAG, "Разговор: голосовых пакетов ${it.sentVoice}") }
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

    private fun startCallAudio(s: TalkSession) {
        s.beginTalkspurt()
        if (!mic.start(context)) { endCall(); set(State.ERROR, "Микрофон недоступен"); return }
        set(State.TALKING, if (mic.echoCancel) "Идёт разговор — говорите" else "Идёт разговор — держитесь подальше от динамика планшета")
    }

    var onState: ((State, String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    /** Голос пошёл/закончился — приглушить звук с улицы (эхо). */
    var onDuck: ((Boolean) -> Unit)? = null
    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "talk").apply { isDaemon = true } }
    @Volatile private var session: TalkSession? = null
    private var url = ""
    private var user = ""
    private var disposed = false
    private var pressed = false
    private val mic = MicCapture({ pcm, n ->
        val s = session ?: return@MicCapture
        try { s.send(pcm, n) } catch (e: Exception) { main.post { failed(reason(e)) } }
    }, { level ->
        if (level > VOICE_LEVEL) lastVoiceAt = clock()
        main.post { if (pressed || inCall) onLevel?.invoke(level) }
    })

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

    private fun startClip(s: TalkSession, pcm: ShortArray) {
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
                main.postDelayed({ if (!pressed && !playing) onDuck?.invoke(false) }, 300)
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
        main.postDelayed({ if (!pressed) onDuck?.invoke(false) }, 300)
        if (state == State.TALKING) set(State.READY, READY_TEXT)
    }

    fun dispose() {
        if (disposed) return
        if (inCall) { inCall = false; communication(false); onCall?.invoke(false) }
        disposed = true
        pressed = false
        playing = false
        mic.stop()
        onDuck?.invoke(false)
        closeSession()
        io.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    private fun startTalking(s: TalkSession) {
        s.beginTalkspurt()
        if (!mic.start(context)) { pressed = false; Log.w(TAG, "Микрофон не открылся"); set(State.ERROR, "Микрофон недоступен"); return }
        Log.i(TAG, "Говорим")
        onDuck?.invoke(true)
        set(State.TALKING, "Говорите — вас слышно у калитки")
    }

    private fun connect() {
        if (url.isBlank()) { set(State.ERROR, "Нужен прямой поток (RTSP) домофона"); return }
        set(State.CONNECTING, "Подключение к домофону…")
        Log.i(TAG, "Подключение обратного канала")
        val target = url; val login = user
        password(context) { password ->
            if (disposed) return@password
            io.execute {
                val talk = TalkSession(RtspSession(target, login, password, connectTimeoutMs = 4_000, readTimeoutMs = 30_000,
                    require = TalkSession.BACKCHANNEL))
                try {
                    val codec = talk.open()
                    Log.i(TAG, "Обратный канал открыт ($codec)")
                    main.post {
                        if (disposed || target != url) { io.execute { talk.close() }; return@post }
                        session = talk
                        set(State.READY, READY_TEXT)
                        val clip = pendingClip
                        pendingClip = null
                        if (inCall) startCallAudio(talk) else if (pressed) startTalking(talk) else if (clip != null) startClip(talk, clip)
                    }
                } catch (e: Exception) {
                    talk.close()
                    Log.w(TAG, "Обратный канал не открыт: ${e.javaClass.simpleName} ${e.message}")
                    main.post { if (!disposed) failed(reason(e)) }
                }
            }
        }
    }

    private fun failed(text: String) {
        failedAt = System.currentTimeMillis()
        pendingClip = null
        playing = false
        if (inCall) { inCall = false; main.removeCallbacks(callWatch); communication(false); onCall?.invoke(false) }
        mic.stop()
        if (pressed) onDuck?.invoke(false)
        pressed = false
        closeSession()
        set(State.ERROR, text)
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        try { io.execute { s.close() } } catch (_: Exception) { s.close() }
    }

    private fun set(next: State, text: String) {
        state = next
        onState?.invoke(next, text)
    }

    private fun reason(e: Exception) = when {
        e is TalkException -> e.message.orEmpty()
        e is RtspException && e.status == 401 -> "Камера не приняла логин или пароль"
        e is RtspException && e.status in setOf(453, 503) -> "Сейчас говорит другое устройство"
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
            "Домофон не отвечает — разговор работает только в домашней сети"
        else -> "Связь с домофоном прервалась"
    }

    companion object {
        const val REQUEST_MIC = 4711
        const val CALL_SILENCE_MS = 120_000L
        const val CALL_MAX_MS = 10 * 60_000L
        /** Уровень микрофона (0–1), выше которого считается, что говорят (не тишина). */
        private const val VOICE_LEVEL = 0.04f
        private const val RETRY_MS = 15_000L
        private const val TAG = "Talk"
        private const val READY_TEXT = "Удерживайте и говорите"
    }
}

/** «Проверить звук в домофоне» (редактор): короткий «динь-дон» в обратный канал. Результат — на главном потоке. */
internal object TalkCheck {
    fun chime(context: Context, rtsp: String, user: String, secretKey: String = "", done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        CameraPasswords.read(context, secretKey) { password ->
            Thread({
                val talk = TalkSession(RtspSession(rtsp, user, password, connectTimeoutMs = 4_000, readTimeoutMs = 10_000,
                    require = TalkSession.BACKCHANNEL))
                val result = try {
                    val codec = talk.open()
                    Log.i("Talk", "Проверка звука: обратный канал открыт ($codec)")
                    Thread.sleep(500) // немного тишины: камера начинает воспроизводить поток
                    talk.beginTalkspurt()
                    val pcm = tone()
                    val frame = ShortArray(TalkSession.FRAME)
                    var i = 0
                    while (i < pcm.size) {
                        val n = minOf(TalkSession.FRAME, pcm.size - i)
                        System.arraycopy(pcm, i, frame, 0, n)
                        talk.send(frame, n)
                        i += n
                        while (talk.queued > 5) Thread.sleep(10)
                    }
                    while (talk.queued > 0) Thread.sleep(10)
                    Thread.sleep(1_500) // камера доигрывает свой буфер — TEARDOWN раньше обрезал бы звук
                    Log.i("Talk", "Проверка звука: отправлено ${talk.sentPackets} пакетов, из них звука ${talk.sentVoice}")
                    "Сигнал отправлен ($codec) — его должно быть слышно у домофона"
                } catch (e: TalkException) {
                    Log.w("Talk", "Проверка звука не удалась: ${e.message}")
                    e.message.orEmpty()
                } catch (e: RtspException) {
                    Log.w("Talk", "Проверка звука не удалась: RTSP ${e.status}")
                    if (e.status == 401) "Камера не приняла логин или пароль" else "Домофон не отвечает (RTSP ${e.status})"
                } catch (e: Exception) {
                    Log.w("Talk", "Проверка звука не удалась: ${e.javaClass.simpleName} ${e.message}")
                    "Домофон не отвечает (${e.javaClass.simpleName})"
                } finally {
                    talk.close()
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
