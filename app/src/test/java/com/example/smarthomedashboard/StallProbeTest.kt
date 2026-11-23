package com.example.smarthomedashboard

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.DecoderCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Подробности остановки живого видео в журнале (пункт F аудита 23.11.2026): только текст, плеер не меняется. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StallProbeTest {
    @Test fun stallLineTellsWhatDecoderCameraAndMainThreadDid() {
        val counters = DecoderCounters().apply { queuedInputBufferCount = 100; renderedOutputBufferCount = 95 }
        val probe = StallProbe()
        probe.begin(counters, "в декодере кадров 3, последний отправлен 40 мс назад, последний вышел 900 мс назад, программный",
            cameraGapMs = 35, main = Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        // За остановку: 36 кадров в декодер, 1 показан, один «прыжок до ключевого кадра» выбросил 30.
        counters.queuedInputBufferCount += 36
        counters.renderedOutputBufferCount += 1
        counters.droppedToKeyframeCount += 1
        counters.droppedInputBufferCount += 30
        counters.droppedBufferCount += 30
        val line = probe.end(counters)
        assertTrue(line, line.startsWith("декодер: в декодере кадров 3, последний отправлен 40 мс назад, последний вышел 900 мс назад"))
        assertTrue(line, "камера прислала кадр 35 мс назад" in line)
        assertTrue(line, "за остановку: в декодер 36, показано 1, выброшено 30, прыжков до ключевого кадра 1 (выброшено 30)" in line)
        assertTrue(line, "главный поток ответил через" in line)
    }

    @Test fun directStreamWithoutCountersSaysOnlyWhatIsKnown() {
        val probe = StallProbe()
        probe.begin(null, null, cameraGapMs = null, main = Handler(Looper.getMainLooper()))
        assertEquals("", probe.end(null))
    }
}
