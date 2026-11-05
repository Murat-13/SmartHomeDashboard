package com.example.smarthomedashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Только главный поток.
 */
internal class VoiceAnnouncer(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val queue = ArrayDeque<Sound>()
    private var playing: Sound? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null
    private var released = false

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
        tts = TextToSpeech(app) { status ->
            main.post {
                val engine = tts ?: return@post
                ttsReady = status == TextToSpeech.SUCCESS &&
                    engine.setLanguage(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE
                if (!ttsReady) Log.w(TAG, "Синтез речи недоступен или без русского: только сигналы")
                engine.setAudioAttributes(attributes)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { finished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { finished() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished() } }
                })
                // Если очередь ждала готовности синтеза — продолжить.
                if (playing?.let { it.tone == null && it.phrase != null } == true) sayPhrase(playing!!)
            }
        }
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
            // Синтез ещё готовится: подождём до 3 с, иначе фраза пропускается (сигнал уже прозвучал).
            main.postDelayed({ if (playing === sound && !ttsReady) finished() }, 3_000)
            return
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, sound.volume) }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        if (playing == null) return
        playing = null
        next()
    }

    private fun stopCurrent() {
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
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            // «Не беспокоить» на некоторых прошивках запрещает менять громкость: играем на текущей.
            savedAlarmVolume = null
        }
    }

    private val restore = Runnable { if (playing == null && queue.isEmpty()) restoreVolume() }

    private fun restoreVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        try { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) } catch (_: SecurityException) {}
    }

    companion object {
        private const val TAG = "VoiceAnnouncer"

        fun resource(context: Context, id: Int): Uri = Uri.parse("android.resource://${context.packageName}/$id")
    }
}
