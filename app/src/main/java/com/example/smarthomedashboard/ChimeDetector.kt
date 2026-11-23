package com.example.smarthomedashboard

import kotlin.math.sqrt

/**
 * Слушает микрофон домофона после звонка: играет ли его мелодия и когда она кончилась ([DoorChime]).
 *
 * Мелодия Reolink (ролик события 23.11.2026): удар «динь» — высокий чистый тон ≈ 2,5 кГц, держится 1,06 с, пауза
 * 1,5 с, удар «дон» — 0,90 с. Блок 32 мс считается тоном мелодии, если он громкий, высокий (переходов через ноль больше
 * 3000 в секунду) и периодичный (корреляция с собой на сдвиге в один-несколько периодов от 0,6). Шипящие в речи бывают
 * такими же, но короткими (до 0,45 с в том же разговоре); поэтому мелодия — только ровная цепочка таких блоков (4 из
 * любых 6 подряд) длиной от [MIN_RUN_MS]. На 12 минутах записи улицы (22–23.11, день и ночь) — ни одного срабатывания.
 *
 * Время — по отсчётам звука, не по часам: [push] — по порядку прихода. [noneAtMs] — с какой миллисекунды звука можно
 * решить «мелодии нет»: мелодия начинается в пределах ≈ 1 с после прихода звонка, а слушать начинаем чуть позже
 * звонка ([DoorChime] считает это от звонка).
 */
internal class ChimeDetector(private val rate: Int, private val learnedMs: Long = 0L, private val noneAtMs: Long = NONE_MS) {
    enum class Verdict { LISTENING, NONE, PLAYING, ENDED }

    private val block = rate * BLOCK_MS / 1000
    private val buf = ShortArray(block)
    private var fill = 0
    private var samples = 0L
    private val recent = BooleanArray(WINDOW)
    private var recentAt = 0
    private var runBlocks = 0
    private var lastLoudMs = -1_000_000L
    private var chimeRun = false
    /** Начало мелодии и конец последнего её удара, мс звука; -1 — не было. */
    var onsetMs = -1L
        private set
    var lastMs = -1L
        private set
    var verdict = Verdict.LISTENING
        private set

    /** Длина мелодии от начала первого удара до конца последнего (для [learnedMs] следующего звонка); 0 — не было. */
    val chimeMs get() = if (onsetMs >= 0 && lastMs > onsetMs) lastMs - onsetMs else 0L
    /** Мелодия началась в начале прослушивания — значит, услышана целиком, и её длине можно верить. */
    val heardWhole get() = onsetMs in 0..noneAtMs

    fun push(pcm: ShortArray, count: Int): Verdict {
        var i = 0
        while (i < count && verdict.ongoing) {
            val n = minOf(count - i, block - fill)
            System.arraycopy(pcm, i, buf, fill, n)
            fill += n; i += n
            if (fill == block) { step(); fill = 0 }
        }
        return verdict
    }

    private fun step() {
        samples += block
        val now = samples * 1000 / rate
        val (loud, tone) = tonal(buf)
        if (loud) lastLoudMs = now
        recent[recentAt] = tone
        recentAt = (recentAt + 1) % WINDOW
        val active = recent.count { it } >= NEED
        if (active) {
            runBlocks++
            val runStart = now - runBlocks * BLOCK_MS
            if (!chimeRun && runBlocks * BLOCK_MS >= MIN_RUN_MS) {
                chimeRun = true
                if (onsetMs < 0) onsetMs = runStart.coerceAtLeast(0)
            }
            if (chimeRun) lastMs = now
        } else {
            runBlocks = 0
            chimeRun = false
        }
        verdict = decide(now)
    }

    private fun decide(now: Long): Verdict {
        if (now >= MAX_MS) return Verdict.ENDED
        if (onsetMs < 0) {
            // Мелодии не слышно, и ничего похожего не звучит прямо сейчас — её нет. Громкий звук только что — подождать:
            // удар начинается с ≈ 0,3 с низкого призвука, а тон после него; но речь гостя ожидание не затягивает.
            val calm = now - lastLoudMs >= CALM_MS || now >= noneAtMs + SPEECH_GRACE_MS
            return if (now >= noneAtMs && runBlocks == 0 && recent.none { it } && calm) Verdict.NONE else Verdict.LISTENING
        }
        // Удар идёт или начинается что-то похожее (следующий удар подтвердится только через [MIN_RUN_MS]) — ждём.
        if (chimeRun || runBlocks > 0) return Verdict.PLAYING
        val quiet = now - lastMs
        // Длина уже известна по прошлому звонку и мелодия услышана с начала — кончилась вместе с последним ударом.
        if (learnedMs > 0 && heardWhole && now - onsetMs >= learnedMs - LEARNED_EARLY_MS && quiet >= LEARNED_QUIET_MS) return Verdict.ENDED
        // Иначе — после паузы длиннее, чем между ударами (1,5 с).
        return if (quiet >= HANG_MS) Verdict.ENDED else Verdict.PLAYING
    }

    /** Блок громкий; громкий и звучит тоном мелодии. */
    private fun tonal(x: ShortArray): Pair<Boolean, Boolean> {
        var e = 0.0
        var crossings = 0
        for (j in x.indices) {
            e += x[j].toDouble() * x[j]
            if (j > 0 && (x[j - 1] < 0) != (x[j] < 0)) crossings++
        }
        if (sqrt(e / x.size) < LOUD_RMS) return false to false
        if (crossings.toLong() * rate / x.size < MIN_CROSSINGS) return true to false
        // Периодичность: корреляция блока с собой на сдвигах от 0,2 до 2,5 мс (тон 0,4–5 кГц и его кратные периоды).
        val from = maxOf(2, rate / 5_000)
        val to = rate / 400
        var best = 0.0
        for (lag in from..to) {
            var c = 0.0; var a = 0.0; var b = 0.0
            for (j in 0 until x.size - lag) {
                val p = x[j].toDouble(); val q = x[j + lag].toDouble()
                c += p * q; a += p * p; b += q * q
            }
            if (a > 0 && b > 0) best = maxOf(best, c / sqrt(a * b))
            if (best >= MIN_CORR) return true to true
        }
        return true to false
    }

    private val Verdict.ongoing get() = this == Verdict.LISTENING || this == Verdict.PLAYING

    companion object {
        const val BLOCK_MS = 32
        private const val WINDOW = 6
        private const val NEED = 4
        /** Удар мелодии — от 0,9 с; похожие куски речи — до 0,45 с. */
        const val MIN_RUN_MS = 600L
        /** Мелодии не было: за столько звука ни одного похожего блока (если не сказано иное — [noneAtMs]). */
        const val NONE_MS = 1_000L
        /** Тишина перед решением «мелодии нет» — и сколько сверх [noneAtMs] её ждать, если у калитки говорят. */
        private const val CALM_MS = 400L
        private const val SPEECH_GRACE_MS = 1_500L
        /** Пауза после удара, после которой мелодия кончилась (между ударами — 1,47 с). */
        const val HANG_MS = 2_000L
        private const val LEARNED_EARLY_MS = 150L
        private const val LEARNED_QUIET_MS = 150L
        /** Дольше не слушаем в любом случае. */
        const val MAX_MS = 8_000L
        /** −38 дБ от шкалы: мелодия у микрофона домофона — −20…−35 дБ, фон улицы — около −48 дБ. */
        private const val LOUD_RMS = 400.0
        private const val MIN_CROSSINGS = 3_000L
        private const val MIN_CORR = 0.6
    }
}
