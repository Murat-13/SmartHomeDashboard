package com.example.smarthomedashboard

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters

/**
 * Подробности остановки живого видео — только для журнала, поведение плеера не меняется (проверка аудита 23.11.2026,
 * пункт F). Редкая остановка HD на ≈ 1,8 с при полном запасе плеера и без сброса декодера не объяснена (docs/doorbell.md,
 * «0.9.41»). Чтобы следующая такая остановка показала причину, в строку «Остановка видео…» добавляется:
 * - что делал декодер в начале остановки (сколько кадров в нём, когда отправлен и когда вышел последний);
 * - что произошло за остановку: сколько кадров отправлено в декодер, показано, выброшено и сколько раз плеер
 *   «прыгал» до ключевого кадра (кадр опоздал больше чем на 0,5 с — всё до следующего ключевого кадра выбрасывается);
 * - сколько миллисекунд назад камера прислала последний кадр (только через ретранслятор планшета);
 * - через сколько ответил главный поток.
 */
@OptIn(UnstableApi::class)
internal class StallProbe {
    private var counters: IntArray? = null
    private var begin = ""
    @Volatile private var mainLagMs = -1L

    /** Остановка началась: снять состояние. [cameraGapMs] — пауза камеры (null — смотрим напрямую, не знаем). */
    fun begin(counters: DecoderCounters?, decoder: String?, cameraGapMs: Long?, main: Handler) {
        this.counters = counters?.let { snapshot(it) }
        begin = listOfNotNull(decoder?.let { "декодер: $it" }, cameraGapMs?.let { "камера прислала кадр $it мс назад" })
            .joinToString("; ")
        mainLagMs = -1
        val posted = SystemClock.uptimeMillis()
        main.post { mainLagMs = SystemClock.uptimeMillis() - posted }
    }

    /** Остановка кончилась: что было в начале и что произошло за неё (одной строкой; пусто — нечего сказать). */
    fun end(counters: DecoderCounters?): String {
        val before = this.counters
        val after = counters?.let { snapshot(it) }
        this.counters = null
        val during = if (before != null && after != null) {
            val d = IntArray(after.size) { after[it] - before[it] }
            "за остановку: в декодер ${d[QUEUED]}, показано ${d[RENDERED]}, выброшено ${d[DROPPED]}" +
                (if (d[TO_KEYFRAME] > 0) ", прыжков до ключевого кадра ${d[TO_KEYFRAME]} (выброшено ${d[DROPPED_INPUT]})" else "") +
                if (d[SKIPPED] > 0) ", пропущено ${d[SKIPPED]}" else ""
        } else null
        val main = mainLagMs.takeIf { it >= 0 }?.let { "главный поток ответил через $it мс" }
        return listOfNotNull(begin.ifEmpty { null }, during, main).joinToString("; ")
    }

    private fun snapshot(c: DecoderCounters): IntArray {
        c.ensureUpdated()
        return intArrayOf(c.queuedInputBufferCount, c.renderedOutputBufferCount, c.droppedBufferCount, c.droppedToKeyframeCount,
            c.droppedInputBufferCount, c.skippedOutputBufferCount + c.skippedInputBufferCount)
    }

    private companion object {
        const val QUEUED = 0
        const val RENDERED = 1
        const val DROPPED = 2
        const val TO_KEYFRAME = 3
        const val DROPPED_INPUT = 4
        const val SKIPPED = 5
    }
}
