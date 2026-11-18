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

    // Медленные вызовы движка в тесте выполняются сразу: порядок задач главного потока детерминирован.
    private fun announcer() = VoiceAnnouncer(context) { it.run() }.also { made += it }

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
        looper.runOneTask()                          // ответ движка: язык и голос (в тесте сразу)
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

    /**
     * Чечня, 18.11: при пропаже и появлении сети звучал сигнал, а фразы не было. Синтез запускается секундами; раньше
     * фраза после сигнала ждала его 3 с и пропадала, а поздняя готовность синтеза её не возвращала (только без сигнала).
     */
    @Test fun phraseAfterToneWaitsForSlowSpeechEngine() {
        val looper = shadowOf(Looper.getMainLooper())
        val voice = announcer()
        // Сигнал, который Robolectric не проигрывает: сразу переход к фразе — как по окончании настоящего сигнала.
        voice.speak(VoiceAnnouncer.Sound("Сеть пропала", android.net.Uri.parse("android.resource://test/1"), 1f, "grid"))
        idle()
        looper.idleFor(java.time.Duration.ofSeconds(5))   // синтез всё ещё запускается
        assertTrue("Фраза ждёт синтез", voice.busy)
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals("Фраза произнесена, когда синтез готов", "Сеть пропала", tts.lastSpokenText)
    }

    @Test fun phraseIsDroppedOnlyAfterLongWait() {
        val looper = shadowOf(Looper.getMainLooper())
        val voice = announcer()
        voice.speak(VoiceAnnouncer.Sound("Сеть пропала", null, 1f, "grid"))
        idle()
        looper.idleFor(java.time.Duration.ofMillis(VoiceAnnouncer.WAIT_MS + 100))
        assertFalse("Синтез так и не готов: очередь не зависает", voice.busy)
        assertTrue(voice.status, voice.status.contains("не запустился"))
    }

    /** Неудачный запуск синтеза больше не держится до перезапуска панели: следующий сигнал пробует снова. */
    @Test fun failedSpeechEngineIsRetried() {
        val looper = shadowOf(Looper.getMainLooper())
        val voice = announcer()
        voice.speak(VoiceAnnouncer.Sound("Сеть пропала", null, 1f, "grid"))
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(first).onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertFalse("Без синтеза — только сигнал, очередь свободна", voice.busy)
        assertTrue(voice.status, voice.status.contains("не запустился"))
        looper.idleFor(java.time.Duration.ofMillis(VoiceAnnouncer.RETRY_MS + 100))
        voice.speak(VoiceAnnouncer.Sound("Сеть появилась", null, 1f, "grid"))
        val second = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame("Синтез запущен заново", first, second)
        shadowOf(second).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals("Сеть появилась", shadowOf(second).lastSpokenText)
    }
}
