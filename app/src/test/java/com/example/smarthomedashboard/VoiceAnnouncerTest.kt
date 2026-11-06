package com.example.smarthomedashboard

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

// Очередь голоса: запоздавший ответ синтеза о прошлой фразе не обрывает новую; громкость будильника возвращается.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceAnnouncerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val made = mutableListOf<VoiceAnnouncer>()

    @Before fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale("ru", "RU"))
        context.getSharedPreferences(VoiceAnnouncer.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() = made.forEach { it.release() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun announcer() = VoiceAnnouncer(context).also { made += it }

    /**
     * Движок (здесь — тень Robolectric) сообщает о фразах через главный поток. Прерванная первая фраза
     * отвечает «готово» уже во время второй: этот ответ не должен завершить вторую.
     */
    @Test fun lateCallbackOfCancelledPhraseDoesNotEndTheNextOne() {
        val looper = shadowOf(Looper.getMainLooper())
        val voice = announcer()
        voice.speak(VoiceAnnouncer.Sound("Первая", null, 1f, "a"))
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        looper.runOneTask()                          // синтез готов → «Первая»
        assertEquals("Первая", tts.lastSpokenText)
        voice.speak(VoiceAnnouncer.Sound("Вторая", null, 1f, "b"))
        voice.cancel("a")                            // → «Вторая»
        assertEquals("Вторая", tts.lastSpokenText)
        looper.runOneTask()                          // начало «Первой» → её «готово» в очередь
        looper.runOneTask()                          // начало «Второй»
        looper.runOneTask()                          // запоздавшее «готово» о «Первой»
        looper.runOneTask()                          // «готово» о «Второй» в очередь
        looper.runOneTask()                          // обработка ответа о «Первой»
        assertTrue("Вторая фраза не оборвана ответом о первой", voice.busy)
        looper.idle()
        assertFalse("Вторая закончилась своим ответом", voice.busy)
    }

    @Test fun alarmVolumeSavedAndRestoredAfterRestart() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        val voice = announcer()
        voice.speak(VoiceAnnouncer.Sound("Фраза", null, 1f, "a"))
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        // Процесс убит во время фразы: release() не вызывался. Следующий запуск возвращает громкость.
        made.clear()
        announcer()
        assertEquals("Громкость будильника владельца возвращена", 3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
