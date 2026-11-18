package com.example.smarthomedashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * Голос и звуки панели: мягкий сигнал или мелодия, затем фраза синтезом речи Android на русском.
 *
 * - Очередь: фразы не накладываются, следующая — после окончания предыдущей.
 * - Громкость независима от системной (решение владельца): на время звучания поток будильника ставится на максимум,
 *   уровень задаёт множитель [Sound.volume]; после очереди прежний уровень возвращается. Поток будильника не
 *   глушится режимом «без звука» и не зависит от громкости медиа.
 * - Синтез речи может отсутствовать или не знать русский: тогда звучит только сигнал (честно, без падений).
 *   Неудачный запуск синтеза повторяется не чаще раза в [RETRY_MS]; голос выбирается русский без интернета, если он
 *   установлен (иначе при пропаже интернета фраза не прозвучит). Что с синтезом — [status], видно в настройках голоса.
 * Только главный поток.
 */
internal class VoiceAnnouncer(
    context: Context,
    /**
     * Где выполнять медленные вызовы движка синтеза (язык, перебор голосов — запросы к другому процессу, на эмуляторе
     * до 0,8 с): не в главном потоке, иначе панель подвисает. Тесты подставляют немедленное выполнение.
     */
    private val background: (Runnable) -> Unit = defaultBackground()
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailedAt = 0L
    /** Фраза ждёт запуска синтеза (сигнал уже прозвучал). */
    private var waiting: Sound? = null
    /** Состояние синтеза речи для экрана настроек: что услышит владелец и почему. */
    var status: String = "Синтез речи ещё не запускался"
        private set
    private val queue = ArrayDeque<Sound>()
    private var playing: Sound? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null
    private var released = false
    /** Идентификатор текущей фразы: ответы синтеза о прошлых (прерванных) фразах игнорируются. */
    private var utterance: String? = null
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Процесс прервался во время фразы: громкость будильника владельца осталась на максимуме — вернуть.
        if (prefs.contains(KEY_SAVED_ALARM)) {
            val saved = prefs.getInt(KEY_SAVED_ALARM, -1)
            if (saved >= 0) try { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) } catch (_: SecurityException) {}
            prefs.edit().remove(KEY_SAVED_ALARM).apply()
        }
    }

    /** Один элемент очереди: звук (ресурс или файл владельца), затем фраза. */
    data class Sound(val phrase: String?, val tone: Uri?, val volume: Float, val tag: String = "")

    val busy get() = playing != null || queue.isNotEmpty()

    fun speak(sound: Sound) {
        if (released) return
        ensureTts()
        queue.addLast(sound)
        if (playing == null) next()
    }

    /** Остановить всё с этим тегом (например, звонок домофона закрыт). */
    fun cancel(tag: String) {
        queue.removeAll { it.tag == tag }
        if (playing?.tag == tag) {
            stopCurrent()
            next()
        }
    }

    /** Запустить синтез заранее: первая фраза после старта панели не ждёт его запуска. */
    fun warmUp() {
        if (!released) ensureTts()
    }

    fun release() {
        released = true
        queue.clear()
        stopCurrent()
        tts?.shutdown()
        tts = null
        restoreVolume()
    }

    private fun ensureTts() {
        if (tts != null) return
        if (ttsFailedAt != 0L && SystemClock.uptimeMillis() - ttsFailedAt < RETRY_MS) return
        status = "Синтез речи запускается…"
        tts = TextToSpeech(app) { result -> main.post { onTtsInit(result) } }
    }

    private fun onTtsInit(result: Int) {
        val engine = tts ?: return
        // Язык и выбор голоса — запросы к процессу синтеза: в фоне, затем результат — в главный поток.
        background(Runnable {
            val language = try {
                if (result == TextToSpeech.SUCCESS) engine.setLanguage(Locale("ru", "RU")) else TextToSpeech.ERROR
            } catch (e: RuntimeException) { TextToSpeech.ERROR }
            val ready = result == TextToSpeech.SUCCESS && language >= TextToSpeech.LANG_AVAILABLE
            val voiceStatus = if (ready) chooseOfflineVoice(engine) else null
            main.post { applyTtsInit(engine, result, ready, voiceStatus) }
        })
    }

    private fun applyTtsInit(engine: TextToSpeech, result: Int, ready: Boolean, voiceStatus: String?) {
        if (tts !== engine || released) return
        ttsReady = ready
        if (!ttsReady) {
            status = if (result != TextToSpeech.SUCCESS) "Синтез речи на планшете не запустился — звучат только сигналы"
                else "В синтезе речи нет русского языка — звучат только сигналы (установите русский голос в настройках Android)"
            Log.w(TAG, "Синтез речи недоступен или без русского: только сигналы")
            // Движок отпускается: следующий сигнал попробует снова (раньше неудача держалась до перезапуска панели).
            engine.shutdown()
            tts = null
            ttsFailedAt = SystemClock.uptimeMillis()
            waiting?.let { if (playing === it) { waiting = null; finished() } }
            return
        }
        ttsFailedAt = 0L
        status = voiceStatus ?: "Синтез речи готов (русский)"
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finished(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { failed(utteranceId, TextToSpeech.ERROR) } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { failed(utteranceId, errorCode) } }
        })
        // Фраза ждала запуска синтеза (в том числе после сигнала) — произнести сейчас.
        waiting?.let { if (playing === it) { waiting = null; sayPhrase(it) } }
    }

    /**
     * Русский голос без интернета, если установлен: у пропавшей сети 220 В часто пропадает и интернет, а голос «из
     * облака» тогда молчит. Нет такого — остаётся голос движка, а в [status] — подсказка скачать русский голос.
     */
    private fun chooseOfflineVoice(engine: TextToSpeech): String = try {
        val russian = engine.voices.orEmpty().filter {
            it.locale.language == "ru" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        val current = engine.voice
        val offline = if (current != null && current.locale.language == "ru" && !current.isNetworkConnectionRequired) current
            else russian.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
        when {
            offline != null -> { if (offline != current) engine.voice = offline; "Синтез речи готов: русский голос работает без интернета" }
            russian.isNotEmpty() -> "Синтез речи готов, но русский голос работает только с интернетом — без него будут " +
                "звучать только сигналы. Скачайте русский голос в настройках синтеза речи Android"
            else -> "Синтез речи готов (русский)"
        }
    } catch (e: Exception) {
        "Синтез речи готов (русский)"
    }

    private fun next() {
        val sound = queue.pollFirst()
        playing = sound
        if (sound == null) {
            main.postDelayed(restore, 600)
            return
        }
        main.removeCallbacks(restore)
        raiseVolume()
        if (sound.tone != null) playTone(sound) else sayPhrase(sound)
    }

    private fun playTone(sound: Sound) {
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(app, sound.tone!!)
                setVolume(sound.volume, sound.volume)
                setOnCompletionListener { main.post { if (player === it) { releasePlayer(); sayPhrase(sound) } } }
                setOnErrorListener { mp, _, _ -> main.post { if (player === mp) { releasePlayer(); sayPhrase(sound) } }; true }
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Звук не воспроизводится: ${e.javaClass.simpleName}")
            null
        }
        if (mp == null) { sayPhrase(sound); return }
        player = mp
        mp.start()
    }

    private fun sayPhrase(sound: Sound) {
        if (playing !== sound) return
        val text = sound.phrase
        val engine = tts
        if (text.isNullOrBlank()) { finished(); return }
        if (engine == null || !ttsReady) {
            ensureTts()
            if (tts == null) { finished(); return } // синтеза нет, повтор запуска позже: только сигнал
            // Синтез ещё запускается: фраза прозвучит, как только он будет готов, но ждём не дольше WAIT_MS.
            waiting = sound
            main.postDelayed({
                if (playing === sound && waiting === sound) {
                    waiting = null
                    status = "Синтез речи не запустился за ${WAIT_MS / 1000} с — фраза пропущена"
                    finished()
                }
            }, WAIT_MS)
            return
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, sound.volume) }
        val id = "u" + System.nanoTime()
        utterance = id
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finished()
    }

    /** Ответ синтеза: только о текущей фразе (запоздавший ответ о прерванной не обрывает новую). */
    private fun finished(utteranceId: String?) {
        if (utteranceId != utterance) return
        finished()
    }

    /** Ошибка синтеза о текущей фразе: причина — в [status] (чаще всего голос «из облака» без интернета). */
    private fun failed(utteranceId: String?, errorCode: Int) {
        if (utteranceId != utterance) return
        status = when (errorCode) {
            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
                "Фраза не прозвучала: русскому голосу нужен интернет. Скачайте русский голос в настройках синтеза речи Android"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Фраза не прозвучала: русский голос ещё скачивается"
            else -> "Фраза не прозвучала: ошибка синтеза речи ($errorCode)"
        }
        Log.w(TAG, "Ошибка синтеза речи: $errorCode")
        finished()
    }

    private fun finished() {
        if (playing == null) return
        utterance = null
        playing = null
        next()
    }

    private fun stopCurrent() {
        utterance = null
        waiting = null
        releasePlayer()
        tts?.stop()
        playing = null
    }

    private fun releasePlayer() {
        player?.let { p -> player = null; try { p.stop() } catch (_: Exception) {}; p.release() }
    }

    private fun raiseVolume() {
        if (savedAlarmVolume != null) return
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            // Записано до повышения: если процесс прервётся во время фразы, следующий запуск вернёт громкость.
            // apply(), не commit(): синхронный fsync в главном потоке подвешивал панель на 2+ с в начале звонка
            // (ночной soak 18.11.2026). Значение сразу в памяти, на диск — фоном за миллисекунды.
            prefs.edit().putInt(KEY_SAVED_ALARM, savedAlarmVolume!!).apply()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            // «Не беспокоить» на некоторых прошивках запрещает менять громкость: играем на текущей.
            savedAlarmVolume = null
            prefs.edit().remove(KEY_SAVED_ALARM).apply()
        }
    }

    private val restore = Runnable { if (playing == null && queue.isEmpty()) restoreVolume() }

    private fun restoreVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        try { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) } catch (_: SecurityException) {}
        prefs.edit().remove(KEY_SAVED_ALARM).apply()
    }

    companion object {
        private const val TAG = "VoiceAnnouncer"

        /** Один фоновый поток на процесс: новые экземпляры (после пересоздания панели) не плодят потоки. */
        private val ttsWorker by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "voice-tts").apply { isDaemon = true } }
        }

        private fun defaultBackground(): (Runnable) -> Unit = { ttsWorker.execute(it) }
        const val PREFS = "dashboard_prefs"
        private const val KEY_SAVED_ALARM = "voice_saved_alarm_volume"
        /** Сколько фраза ждёт запуска синтеза после сигнала. */
        const val WAIT_MS = 10_000L
        /** Неудачный запуск синтеза повторяется не чаще. */
        const val RETRY_MS = 60_000L

        fun resource(context: Context, id: Int): Uri = Uri.parse("android.resource://${context.packageName}/$id")
    }
}
