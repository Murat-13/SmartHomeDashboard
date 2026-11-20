package com.example.smarthomedashboard

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Эхоподавление для одновременного разговора через домофон (docs/talk-tz.md, «Одновременный разговор»).
 *
 * Два контура, у каждого свой опорный сигнал:
 * - **ближний** — звук улицы из динамика планшета попадает в микрофон планшета; опора — то, что записано в динамик;
 * - **дальний** — наш голос из динамика домофона попадает в его же микрофон и возвращается в звуке улицы через
 *   0,5–1,3 с; опора — то, что реально отправлено в домофон.
 *
 * Движок свой, на Kotlin (решение владельца 20.11.2026: без нативных библиотек), по опубликованным алгоритмам того же
 * класса, что SpeexDSP: адаптивный фильтр в частотной области с разбиением на блоки (MDF, Soo & Pang 1990) с шагом
 * обучения, устойчивым к одновременной речи (Valin 2007), два фильтра — рабочий и обучаемый; после него — подавитель
 * остатка эха по спектру с «комфортным» шумом. Движок стоит за интерфейсом [EchoEngine]: его можно заменить (например,
 * на WebRTC AEC3), не трогая остальное.
 *
 * Выравнивание — по номерам отсчётов, а не по часам: домофон играет присланное подряд, поэтому сдвиг между потоком
 * «отправлено» и потоком «вернулось» постоянен, пока его очередь не опустела; сдвиг ищет [DelayFinder] по огибающим.
 * В этом файле нет зависимостей от Android — всё проверяется обычными тестами на модели тракта.
 */
internal class Fft(val size: Int) {
    private val bits = Integer.numberOfTrailingZeros(size)
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { sin(2 * PI * it / size) }

    init { require(size >= 4 && size and (size - 1) == 0) { "Размер БПФ — степень двойки, а не $size" } }

    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, -1.0)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        run(re, im, 1.0)
        val s = 1.0 / size
        for (i in 0 until size) { re[i] *= s; im[i] *= s }
    }

    private fun run(re: DoubleArray, im: DoubleArray, sign: Double) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) { val r = re[i]; re[i] = re[j]; re[j] = r; val m = im[i]; im[i] = im[j]; im[j] = m }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]; val wi = sign * sinT[k]
                    val a = i + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Опорный сигнал с абсолютной нумерацией отсчётов: кольцо на несколько секунд. Пишет один поток (отправка в домофон
 * или воспроизведение), читает другой (эхоподавление). Для каждого записанного куска помнится время записи — по нему
 * считается, через сколько эхо возвращается на самом деле.
 */
internal class RefTap(val rate: Int, seconds: Int = 8) {
    private val buf = ShortArray(rate * seconds)
    private val markEnd = LongArray(MARKS)
    private val markTime = LongArray(MARKS)
    private var markHead = 0
    internal var clock: () -> Long = System::nanoTime
    @Volatile var total = 0L
        private set

    @Synchronized fun write(pcm: ShortArray, count: Int) {
        var at = (total % buf.size).toInt()
        for (i in 0 until count) { buf[at] = pcm[i]; at++; if (at == buf.size) at = 0 }
        total += count
        markEnd[markHead] = total; markTime[markHead] = clock()
        markHead = (markHead + 1) % MARKS
    }

    /** Отсчёты с номера [from]; чего ещё нет или что уже затёрто — нули. */
    @Synchronized fun read(from: Long, out: DoubleArray, offset: Int, count: Int) {
        val oldest = max(0L, total - buf.size)
        for (i in 0 until count) {
            val index = from + i
            out[offset + i] = if (index < oldest || index >= total) 0.0 else buf[(index % buf.size).toInt()].toDouble()
        }
    }

    /** Когда был записан отсчёт с номером [index] (время [clock]); 0 — неизвестно. */
    @Synchronized fun timeOf(index: Long): Long {
        var best = 0L
        var bestEnd = Long.MAX_VALUE
        for (i in 0 until MARKS) {
            val end = markEnd[i]
            if (end > index && end < bestEnd) { bestEnd = end; best = markTime[i] }
        }
        return best
    }

    private companion object { const val MARKS = 512 }
}

/** Движок эхоподавления — «розетка»: блок за блоком, опорный сигнал уже выровнен (эхо отстаёт от него на 0…[taps]). */
internal interface EchoEngine {
    /** Длина блока в отсчётах. */
    val block: Int
    /** На сколько отсчётов выход отстаёт от входа. */
    val latency: Int
    /** Длина модели пути эха в отсчётах. */
    val taps: Int
    fun process(capture: DoubleArray, reference: DoubleArray, out: DoubleArray)
    /** Без опорного сигнала (сдвиг ещё не найден): пропустить с усилением [gain], сохранив ту же задержку. */
    fun bypass(capture: DoubleArray, gain: Double, out: DoubleArray)
    fun reset()
    /** Модель пути эха во времени ([taps] отсчётов) → [out]; false — модели нет (не сошлась или движок её не показывает). */
    fun model(out: DoubleArray): Boolean
    /**
     * На сколько отсчётов настоящее эхо в среднем отстаёт от модели (минус — опережает) с прошлого вызова; 0 — нет
     * данных. По этому числу контур подстраивает сдвиг и скорость его ухода (расхождение часов).
     */
    fun timing(): Double
    /** Выравнивание сдвинуто на [blocks] блоков (плюс — опора стала старше); [history] — опора за [taps] + блок до текущего места. */
    fun realign(blocks: Int, history: DoubleArray)
    /** В последнем блоке почти всё, что было на входе, — эхо (а не голос собеседника). */
    val echoDominant: Boolean
    /** Модель пути эха сошлась (линейная часть работает). */
    val converged: Boolean
    /** Модель была, но теперь добавляет ложное эхо: путь изменился (например, сдвиг скакнул). */
    val lost: Boolean
    /** Насколько вычитание (линейная часть) ослабляет эхо, когда собеседник молчит, дБ. */
    val linearDb: Double
}

/**
 * Линейная часть: адаптивный фильтр в частотной области из [parts] блоков по [n] отсчётов (MDF). Обучается «фоновый»
 * фильтр; «рабочий» получает его копию, только когда фоновый заметно лучше, — одновременная речь не портит рабочий.
 * Шаг обучения по частотам ≈ (остаток эха) / (ошибка): когда говорит собеседник, ошибка большая — шаг малый.
 */
internal class LinearStage(val n: Int, val parts: Int, rate: Int) {
    private val f = 2 * n
    private val k = n + 1
    private val fft = Fft(f)
    private val re = DoubleArray(f)
    private val im = DoubleArray(f)
    private val xTime = DoubleArray(f)
    private var xRe = Array(parts) { DoubleArray(k) }
    private var xIm = Array(parts) { DoubleArray(k) }
    private val fgRe = Array(parts) { DoubleArray(k) }
    private val fgIm = Array(parts) { DoubleArray(k) }
    private val bgRe = Array(parts) { DoubleArray(k) }
    private val bgIm = Array(parts) { DoubleArray(k) }
    private val eRe = DoubleArray(k)
    private val eIm = DoubleArray(k)
    private val power = DoubleArray(k)
    private val prop = DoubleArray(parts)
    private val eh = DoubleArray(k)
    private val yh = DoubleArray(k)
    private val rf = DoubleArray(k)
    private val yf = DoubleArray(k)
    private val step = DoubleArray(k)
    private val yBg = DoubleArray(n)
    private val eBg = DoubleArray(n)
    /** Оценка эха рабочим фильтром за последний блок. */
    val echo = DoubleArray(n)

    private val specAverage = n.toDouble() / rate
    private val beta0 = 2.0 * n / rate
    private val betaMax = 0.5 * n / rate
    private var peyAvg = 1.0
    private var pyyAvg = 1.0
    private var sumAdapt = 0.0
    private var davg1 = 0.0
    private var davg2 = 0.0
    private var dvar1 = 0.0
    private var dvar2 = 0.0
    private var broken = 0
    private var constrainAt = 0
    private var sddAct = 1.0
    private var sffAct = 1.0
    private var seeAct = 1.0
    private var failing = 0
    private var stalled = 0
    private var strength = 0.0
    private var current = 0.0
    private val usualRef = DoubleArray(k)
    private var clock = 0L
    private var poorRun = 0
    private var poorAt = 0L
    private var shallow = 0
    /**
     * Во сколько раз рабочий фильтр ослабляет эхо (по мощности), когда говорим только мы: растёт быстро, падает
     * медленно — одновременная речь (ошибка большая не из-за фильтра) оценку почти не трогает.
     */
    var gain = 1.0
        private set
    /**
     * В последнем блоке, судя по всему, только эхо (собеседник молчит): рабочий фильтр ослабил вход почти так же, как
     * умеет в лучшем случае. При голосе собеседника ошибка заметно больше — признак пропадает.
     */
    var echoOnly = false
        private set
    /** В последнем блоке вычитание не применено (сделало бы хуже): на выходе весь вход, эхо в нём целиком. */
    var raw = false
        private set
    /** Доля оценки эха, оставшаяся после вычитания в этом блоке, если блок — «громкий пик» (иначе 0). */
    var residue = 0.0
        private set
    /** Рабочий фильтр добавляет ложное эхо (путь изменился); снимается, когда фильтр снова помогает. */
    var lost = false
        private set
    /** Почему модель признана потерянной (для журнала). */
    var lostWhy = ""
        private set
    /** Доля эха, остающаяся после вычитания (0…1): по ней подавитель оценивает остаток. */
    var leak = 0.0
        private set
    var adapted = false
        private set
    /** Сколько раз рабочий фильтр получал копию обученного. */
    var updates = 0
        private set

    init { reset() }

    fun reset() {
        for (p in 0 until parts) { fgRe[p].fill(0.0); fgIm[p].fill(0.0); bgRe[p].fill(0.0); bgIm[p].fill(0.0); xRe[p].fill(0.0); xIm[p].fill(0.0) }
        xTime.fill(0.0); power.fill(1.0); eh.fill(0.0); yh.fill(0.0); echo.fill(0.0)
        peyAvg = 1.0; pyyAvg = 1.0; sumAdapt = 0.0; leak = 0.0; adapted = false; updates = 0
        davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0; broken = 0
        sddAct = 1.0; sffAct = 1.0; seeAct = 1.0; failing = 0; stalled = 0; gain = 1.0; echoOnly = false; lost = false; strength = 0.0; current = 0.0; poorRun = 0; shallow = 0; raw = false; usualRef.fill(0.0)
        // До схождения шаг поровну по блокам: где главный отклик, заранее неизвестно.
        prop.fill(1.0 / parts)
    }

    /** Блок опоры [x] и блок входа [d] → ошибка рабочего фильтра в [e] (вход минус оценка эха), оценка эха — в [echo]. */
    fun process(d: DoubleArray, x: DoubleArray, e: DoubleArray) {
        clock++
        pushReference(x)
        val ss = 0.35 / parts
        val x0r = xRe[0]; val x0i = xIm[0]
        for (i in 0 until k) power[i] = (1 - ss) * power[i] + 1 + ss * (x0r[i] * x0r[i] + x0i[i] * x0i[i])
        filter(fgRe, fgIm, echo)
        filter(bgRe, bgIm, yBg)
        var sff = 0.0; var see = 0.0; var sdd = 0.0; var sxx = 0.0; var syy = 0.0; var sey = 0.0; var dbf = 10.0
        for (i in 0 until n) {
            e[i] = d[i] - echo[i]
            eBg[i] = d[i] - yBg[i]
            sff += e[i] * e[i]; see += eBg[i] * eBg[i]; sdd += d[i] * d[i]; sxx += x[i] * x[i]
            syy += yBg[i] * yBg[i]; sey += eBg[i] * yBg[i]
            val diff = echo[i] - yBg[i]
            dbf += diff * diff
        }
        raw = false; residue = 0.0
        if (!(sff.isFinite() && see.isFinite() && syy.isFinite())) { reset(); for (i in 0 until n) e[i] = d[i]; return }
        when (switchFilters(sff, see, dbf)) {
            1 -> { for (i in 0 until n) { e[i] = eBg[i]; echo[i] = yBg[i] }; sff = see; weigh(sdd, see, syy, sxx) }
            -1 -> {
                // Обученный фильтр заметно хуже рабочего (испорчен одновременной речью) — начать снова с рабочего.
                for (i in 0 until n) { eBg[i] = e[i]; yBg[i] = echo[i] }
                see = sff; syy = 0.0; sey = 0.0
                for (i in 0 until n) { syy += yBg[i] * yBg[i]; sey += eBg[i] * yBg[i] }
            }
        }
        if (sxx > n * ACTIVE) for (i in 0 until k) usualRef[i] += USUAL_RATE * (x0r[i] * x0r[i] + x0i[i] * x0i[i] - usualRef[i])
        echoOnly = sxx > n * ACTIVE && sdd > sff * max(ONLY_MIN, ONLY_PART * gain)
        if (sxx > n * ACTIVE) quality(sdd, sff, see)
        adapt(sxx, see, syy, sey)
        // Рабочий фильтр хуже, чем ничего (эхо изменилось, а обученный ещё не готов), — не портить вход.
        broken = if (sff > sdd + n * 10_000.0) broken + 1 else 0
        if (broken >= 50) { reset(); for (i in 0 until n) e[i] = d[i]; return }
        // Вычитание сделало бы громче (громкий пик — динамик исказил; путь изменился): вход не трогаем, но оценку эха
        // оставляем — подавитель должен знать, что эхо здесь есть, и целиком.
        raw = sff > sdd
        if (raw) System.arraycopy(d, 0, e, 0, n)
        // Громкий пик: по модели почти весь вход — эхо, а после вычитания осталась заметная часть (динамик на пиках
        // искажает, и вычитание там слабее обычного). Столько и считать остатком в этом блоке. При голосе собеседника
        // сравнимой с эхом громкости модель объясняет меньшую часть входа, и правило не срабатывает.
        val left = if (raw) sdd else sff
        var promised = 0.0
        for (i in 0 until n) promised += echo[i] * echo[i]
        residue = if (sxx > n * ACTIVE && promised > PEAK_ECHO * sdd && left > PEAK_LEFT * sdd) left / max(promised, 1.0) else 0.0
    }

    /**
     * Рабочий фильтр обновился: если модель разом «обмелела» (отклик стал втрое слабее привычного), значит эха на
     * прежнем месте больше нет — сдвиг скакнул. Модели не верим, пока она снова не начнёт ослаблять вход.
     */
    private fun weigh(sdd: Double, see: Double, syy: Double, sxx: Double) {
        // Сила отклика — усиление пути для обычного спектра опоры: частоты, где в опоре пусто (там коэффициенты
        // ничем не определены и гуляют), в счёт не идут.
        var now = 0.0; var weight = 1e-9
        for (i in 0 until k) weight += usualRef[i]
        for (p in 0 until parts) { val wr = fgRe[p]; val wi = fgIm[p]; for (i in 0 until k) now += (wr[i] * wr[i] + wi[i] * wi[i]) * usualRef[i] }
        now /= weight
        // Второй признак: рабочий фильтр меняется раз за разом (обученный каждый раз «значимо лучше»), модель обещает
        // заметное эхо, а вход всё равно не ослабляется. При голосе собеседника так не бывает — тогда обученный не
        // лучше и замен нет. Блоки, где эха по модели почти нет (паузы, один фон), в счёт не идут.
        if (sxx > n * ACTIVE && syy > 0.25 * sdd) {
            if (sdd > POOR_GAIN * see) poorRun = 0
            else { poorRun = if (clock - poorAt < POOR_WINDOW) poorRun + 1 else 1; poorAt = clock }
        }
        current = now
        // «Обмелела» — три замены подряд слабее привычного втрое (одна слабая замена после долгой одновременной речи
        // ещё ничего не значит: обученный фильтр просто не успел восстановиться).
        shallow = if (now < COLLAPSE * strength) shallow + 1 else 0
        if (gain > PROVEN && (shallow >= SHALLOW_RUN || poorRun >= POOR_RUN)) {
            lostWhy = if (poorRun >= POOR_RUN) "замены фильтра не помогают" else "отклик модели обмелел"
            lost = true; gain = 1.0; sddAct = 1.0; sffAct = 1.0; strength = now; poorRun = 0; shallow = 0
        }
    }

    /** Блок, в котором в опоре есть звук: насколько фильтры ослабляют вход. */
    private fun quality(sdd: Double, sff: Double, see: Double) {
        sddAct += 0.1 * (sdd - sddAct); sffAct += 0.1 * (sff - sffAct); seeAct += 0.1 * (see - seeAct)
        val now = sddAct / max(sffAct, 1.0)
        gain += (now - gain) * if (now > gain) 0.2 else FORGET
        // Рабочий фильтр добавляет ложное эхо (путь изменился): подряд дольше 0,2 с — модели больше не верим.
        failing = if (sffAct > 1.25 * sddAct) failing + 1 else 0
        if (failing >= 12) { if (gain > PROVEN) { lost = true; lostWhy = "фильтр добавляет ложное эхо" }; gain = 1.0 }
        if (gain > 2.0) lost = false
        // Привычная сила отклика — среднее за последние секунды речи (ранний «перелёт» обучения быстро забывается).
        strength += STRENGTH_RATE * (current - strength)
        // Оба фильтра ничего не дают дольше секунды — учить заново быстрым шагом (при одновременной речи рабочий цел).
        stalled = if (adapted && sddAct < 1.3 * seeAct && sddAct < 1.3 * sffAct) stalled + 1 else 0
        if (stalled >= 60) { adapted = false; sumAdapt = 0.0; stalled = 0; prop.fill(1.0 / parts) }
    }

    private fun pushReference(x: DoubleArray) {
        System.arraycopy(xTime, n, xTime, 0, n)
        System.arraycopy(x, 0, xTime, n, n)
        val lastRe = xRe[parts - 1]; val lastIm = xIm[parts - 1]
        for (p in parts - 1 downTo 1) { xRe[p] = xRe[p - 1]; xIm[p] = xIm[p - 1] }
        xRe[0] = lastRe; xIm[0] = lastIm
        spectrum(xTime, xRe[0], xIm[0])
    }

    private fun spectrum(time: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        System.arraycopy(time, 0, re, 0, f)
        im.fill(0.0)
        fft.forward(re, im)
        System.arraycopy(re, 0, outRe, 0, k)
        System.arraycopy(im, 0, outIm, 0, k)
    }

    /** Спектр блока, дополненного нулями спереди (как в MDF): ошибка и оценка эха для обучения. */
    private fun paddedSpectrum(block: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        re.fill(0.0, 0, n)
        System.arraycopy(block, 0, re, n, n)
        im.fill(0.0)
        fft.forward(re, im)
        System.arraycopy(re, 0, outRe, 0, k)
        System.arraycopy(im, 0, outIm, 0, k)
    }

    private fun filter(wRe: Array<DoubleArray>, wIm: Array<DoubleArray>, out: DoubleArray) {
        for (i in 0 until k) {
            var ar = 0.0; var ai = 0.0
            for (p in 0 until parts) {
                val wr = wRe[p][i]; val wi = wIm[p][i]; val xr = xRe[p][i]; val xi = xIm[p][i]
                ar += wr * xr - wi * xi
                ai += wr * xi + wi * xr
            }
            re[i] = ar; im[i] = ai
        }
        for (i in 1 until n) { re[f - i] = re[i]; im[f - i] = -im[i] }
        fft.inverse(re, im)
        System.arraycopy(re, n, out, 0, n)
    }

    /** Рабочий фильтр ← обученный, если тот значимо лучше (1); обученный ← рабочий, если он значимо хуже (−1); иначе 0. */
    private fun switchFilters(sff: Double, see: Double, dbf: Double): Int {
        val gain = sff - see
        davg1 = 0.6 * davg1 + 0.4 * gain
        davg2 = 0.85 * davg2 + 0.15 * gain
        dvar1 = 0.36 * dvar1 + 0.16 * sff * dbf
        dvar2 = 0.7225 * dvar2 + 0.0225 * sff * dbf
        val better = gain * abs(gain) > sff * dbf || davg1 * abs(davg1) > 0.5 * dvar1 || davg2 * abs(davg2) > 0.25 * dvar2
        if (better) {
            for (p in 0 until parts) { System.arraycopy(bgRe[p], 0, fgRe[p], 0, k); System.arraycopy(bgIm[p], 0, fgIm[p], 0, k) }
            davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0
            updates++
            return 1
        }
        val worse = -gain * abs(gain) > BACKTRACK * sff * dbf || -davg1 * abs(davg1) > BACKTRACK * dvar1 ||
            -davg2 * abs(davg2) > BACKTRACK * dvar2
        if (worse) {
            for (p in 0 until parts) { System.arraycopy(fgRe[p], 0, bgRe[p], 0, k); System.arraycopy(fgIm[p], 0, bgIm[p], 0, k) }
            davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0
            return -1
        }
        return 0
    }

    private fun adapt(sxx: Double, seeIn: Double, syy: Double, sey: Double) {
        val see = max(seeIn, n * 100.0)
        paddedSpectrum(eBg, eRe, eIm)
        for (i in 0 until k) rf[i] = eRe[i] * eRe[i] + eIm[i] * eIm[i]
        // Оценка эха обученным фильтром — в спектр (через общий буфер), только мощность.
        re.fill(0.0, 0, n); System.arraycopy(yBg, 0, re, n, n); im.fill(0.0)
        fft.forward(re, im)
        var pey = 0.0; var pyy = 0.0
        for (i in 0 until k) {
            yf[i] = re[i] * re[i] + im[i] * im[i]
            val eCur = rf[i] - eh[i]; val yCur = yf[i] - yh[i]
            pey += eCur * yCur; pyy += yCur * yCur
            eh[i] = (1 - specAverage) * eh[i] + specAverage * rf[i]
            yh[i] = (1 - specAverage) * yh[i] + specAverage * yf[i]
        }
        pyy = sqrt(pyy)
        pey = if (pyy > 0) pey / pyy else 0.0
        val alpha = min(beta0 * syy, betaMax * see) / see
        peyAvg = (1 - alpha) * peyAvg + alpha * pey
        pyyAvg = (1 - alpha) * pyyAvg + alpha * pyy
        if (pyyAvg < 1.0) pyyAvg = 1.0
        if (peyAvg < MIN_LEAK * pyyAvg) peyAvg = MIN_LEAK * pyyAvg
        if (peyAvg > pyyAvg) peyAvg = pyyAvg
        leak = peyAvg / pyyAvg

        var rer = (0.0001 * sxx + 3.0 * leak * syy) / see
        val bound = sey * sey / (1.0 + see * syy)
        if (rer < bound) rer = bound
        if (rer > 0.5) rer = 0.5
        // В рабочий режим — когда обученный фильтр уже заметно ослабляет вход (больше 4 дБ).
        if (!adapted && sumAdapt > parts && sddAct > 2.5 * seeAct) adapted = true
        val active = sxx > n * ACTIVE

        if (adapted) {
            val low = if (active) MIN_STEP else 0.0
            for (i in 0 until k) {
                val e = rf[i] + 1
                var r = leak * yf[i]
                if (r > 0.5 * e) r = 0.5 * e
                r = max(0.7 * r + 0.3 * rer * e, low * e)
                step[i] = r / (e * (power[i] + 10))
            }
            proportion()
        } else {
            // Быстрый старт: шаг тем меньше, чем ошибка громче опоры (говорит собеседник).
            var rate = 0.0
            if (active) rate = BOOT * min(sxx, see) / see
            for (i in 0 until k) step[i] = rate / (power[i] + 10)
            sumAdapt += rate
            // Как только в модели проступил отклик — больше шага тому блоку, где он.
            if (updates > 0) proportion()
        }
        for (p in 0 until parts) {
            val wr = bgRe[p]; val wi = bgIm[p]; val xr = xRe[p]; val xi = xIm[p]; val g = prop[p]
            for (i in 0 until k) {
                val s = g * step[i]
                wr[i] += s * (xr[i] * eRe[i] + xi[i] * eIm[i])
                wi[i] += s * (xr[i] * eIm[i] - xi[i] * eRe[i])
            }
        }
        // Ограничение: отклик каждого блока — только в первой половине окна (иначе циклическая свёртка даёт ложное эхо).
        // По два блока за раз по кругу: дёшево, а накопиться ошибке некогда.
        repeat(min(2, parts)) {
            constrain(bgRe[constrainAt], bgIm[constrainAt])
            constrainAt = (constrainAt + 1) % parts
        }
    }

    /** После схождения — больше шага тем блокам, где отклик сильнее. */
    private fun proportion() {
        var maxSum = 1.0
        for (p in 0 until parts) {
            var s = 1.0
            val wr = bgRe[p]; val wi = bgIm[p]
            for (i in 0 until k) s += wr[i] * wr[i] + wi[i] * wi[i]
            prop[p] = sqrt(s)
            if (prop[p] > maxSum) maxSum = prop[p]
        }
        var sum = 1.0
        for (p in 0 until parts) { prop[p] += 0.1 * maxSum; sum += prop[p] }
        for (p in 0 until parts) prop[p] = 0.99 * prop[p] / sum
    }

    private fun constrain(wr: DoubleArray, wi: DoubleArray) {
        System.arraycopy(wr, 0, re, 0, k); System.arraycopy(wi, 0, im, 0, k)
        for (i in 1 until n) { re[f - i] = re[i]; im[f - i] = -im[i] }
        fft.inverse(re, im)
        re.fill(0.0, n, f)
        im.fill(0.0)
        fft.forward(re, im)
        System.arraycopy(re, 0, wr, 0, k); System.arraycopy(im, 0, wi, 0, k)
    }

    /** Отклик рабочего фильтра во времени ([parts] × [n] отсчётов) — чтобы найти главный отклик. */
    fun impulse(out: DoubleArray) {
        for (p in 0 until parts) {
            System.arraycopy(fgRe[p], 0, re, 0, k); System.arraycopy(fgIm[p], 0, im, 0, k)
            for (i in 1 until n) { re[f - i] = re[i]; im[f - i] = -im[i] }
            fft.inverse(re, im)
            System.arraycopy(re, 0, out, p * n, n)
        }
    }

    /**
     * Выравнивание сдвинулось на [blocks] блоков: отклики блоков переезжают на соседние места (модель не теряется), а
     * спектры опоры пересчитываются по [history] — опоре за ([parts] + 1) блоков до текущего места при новом сдвиге.
     */
    fun realign(blocks: Int, history: DoubleArray) {
        for (w in listOf(fgRe, fgIm, bgRe, bgIm)) rotate(w, blocks)
        for (p in 0 until parts) {
            // Блок p назад: окно из двух блоков, кончающееся за p блоков до конца истории.
            System.arraycopy(history, (parts - 1 - p) * n, xTime, 0, f)
            spectrum(xTime, xRe[p], xIm[p])
        }
        System.arraycopy(history, (parts - 1) * n, xTime, 0, f)
    }

    private fun rotate(w: Array<DoubleArray>, blocks: Int) {
        if (blocks > 0) {
            // Опора стала старше — отклик в модели ближе к началу.
            for (p in 0 until parts) if (p + blocks < parts) System.arraycopy(w[p + blocks], 0, w[p], 0, k) else w[p].fill(0.0)
        } else if (blocks < 0) {
            for (p in parts - 1 downTo 0) if (p + blocks >= 0) System.arraycopy(w[p + blocks], 0, w[p], 0, k) else w[p].fill(0.0)
        }
    }

    private companion object {
        const val MIN_LEAK = 0.005
        const val BACKTRACK = 4.0
        /** Опора «звучит»: громче −50 дБ от шкалы (после подъёма верхних частот). */
        const val ACTIVE = 1_000.0
        const val BOOT = 0.5
        /** «Только эхо»: вход ослаблен не меньше чем на 2 дБ и не меньше 0,6 от лучшего. */
        /** «Громкий пик»: оценка эха — не меньше 0,75 входа, а после вычитания осталось больше 0,3 входа. */
        const val PEAK_ECHO = 0.75
        const val PEAK_LEFT = 0.3
        const val ONLY_MIN = 1.6
        const val ONLY_PART = 0.6
        /** Лучшее ослабление забывается медленно (десятки секунд речи): долгая одновременная речь его не сбивает. */
        const val FORGET = 0.0005
        /** Модель «была хорошей» — ослабляла эхо больше чем на 5 дБ: только тогда её обвал значит скачок сдвига. */
        const val PROVEN = 3.2
        const val COLLAPSE = 0.3
        const val SHALLOW_RUN = 3
        /** Шесть «пустых» замен рабочего фильтра подряд (вход после вычитания не тише), каждая не позже секунды после прошлой. */
        const val POOR_RUN = 6
        const val POOR_GAIN = 1.0
        /** Обычный спектр опоры — среднее примерно за 5 с речи. */
        const val USUAL_RATE = 0.003
        const val POOR_WINDOW = 62L
        /** Привычная сила отклика — среднее примерно за 3 с речи. */
        const val STRENGTH_RATE = 0.005
        /** Нижняя граница шага после схождения: модель успевает за медленным уходом пути. */
        const val MIN_STEP = 0.06
    }
}

/**
 * Подавитель остатка эха. Линейная часть убирает только то, что повторяет опору точно (у домофона — около 90 % эха:
 * остальное — искажения динамика и кодеков); остаток оценивается по спектру как доля от оценки эха ([LinearStage.leak])
 * и убирается усилением по частотам: где остаток сильнее полезного звука — тише, где голос собеседника сильнее — без
 * изменений. Пока модель не сошлась, остатком считается вся опора с запасом [preset] — в эти секунды разговор похож
 * на «говорит кто-то один». На месте убранного эха — шум той же громкости, что фон: тишина не «проваливается».
 */
internal class Suppressor(private val n: Int, rate: Int, private val preset: Double) {
    private val f = 2 * n
    private val k = n + 1
    private val fft = Fft(f)
    private val window = DoubleArray(f) { sin(PI * (it + 0.5) / f) }
    private val re = DoubleArray(f)
    private val im = DoubleArray(f)
    private val ePrev = DoubleArray(n)
    private val yPrev = DoubleArray(n)
    private val xPrev = DoubleArray(n)
    private val eRe = DoubleArray(k)
    private val eIm = DoubleArray(k)
    private val yRe = DoubleArray(k)
    private val yIm = DoubleArray(k)
    private var timeNum = 0.0
    private var timeDen = 0.0
    private val py = DoubleArray(k)
    private val px = DoubleArray(k)
    private val pxPrev = DoubleArray(k)
    private val smear = DoubleArray(k)
    private val resid = DoubleArray(k)
    private val gPrev = DoubleArray(k) { 1.0 }
    private val pePrev = DoubleArray(k)
    private val smooth = DoubleArray(k)
    private val minNow = DoubleArray(k) { Double.MAX_VALUE }
    private val minOld = DoubleArray(k) { Double.MAX_VALUE }
    private val noise = DoubleArray(k)
    private var noiseBlocks = 0
    private val pe = DoubleArray(k)
    private val peS = DoubleArray(k)
    private val rS = DoubleArray(k)
    private var broad = 1.0
    private val eh = DoubleArray(k)
    private val yh = DoubleArray(k)
    private var peyAvg = 0.0
    private var pyyAvg = 1.0
    private val specAverage = n.toDouble() / rate
    /** Какая доля оценки эха сейчас остаётся после вычитания — по тому, как громкость ошибки следует за громкостью эха. */
    var leak = 0.0
        private set
    private val shapeNum = DoubleArray(k)
    private val shapeBlur = DoubleArray(k)
    private var shapeDen = 0.0
    private val spread = DoubleArray(k)
    private val ola = DoubleArray(n)
    private var seed = 0x2545F491
    private val lowBin = (300.0 * f / rate).toInt()
    private val highBin = min(k - 1, (4_000.0 * f / rate).toInt())
    private val noisePeriod = max(1, (2.5 * rate / n).toInt())
    var echoDominant = false
        private set
    /** Комфортный шум (в тестах выключается, чтобы мерить остаток эха точно). */
    var comfort = true

    fun reset() {
        ePrev.fill(0.0); yPrev.fill(0.0); xPrev.fill(0.0); pxPrev.fill(0.0); smear.fill(0.0); resid.fill(0.0)
        gPrev.fill(1.0); pePrev.fill(0.0); echoDominant = false
        shapeNum.fill(0.0); shapeDen = 0.0; spread.fill(0.0); broad = 1.0
        eh.fill(0.0); yh.fill(0.0); peyAvg = 0.0; pyyAvg = 1.0; leak = 0.0; timeNum = 0.0; timeDen = 0.0
    }

    /** Сглаживание спектра мощности по частоте треугольным окном ±[BLUR] полос. */
    private fun blur(from: DoubleArray, to: DoubleArray) {
        for (i in 0 until k) {
            var sum = 0.0; var weight = 0.0
            for (j in -BLUR..BLUR) {
                val at = i + j
                if (at < 0 || at >= k) continue
                val w = (BLUR + 1 - abs(j)).toDouble()
                sum += w * from[at]; weight += w
            }
            to[i] = sum / weight
        }
    }

    private fun windowed(prev: DoubleArray, cur: DoubleArray) {
        for (i in 0 until n) { re[i] = prev[i] * window[i]; re[n + i] = cur[i] * window[n + i] }
        im.fill(0.0)
        fft.forward(re, im)
    }

    /**
     * [e] — вход после линейной части, [y] — оценка эха, [x] — опора; [trust] 0…1 — насколько модель сошлась;
     * [share] — какая доля оценки эха остаётся после вычитания; [echoOnly] — в блоке только эхо (собеседник молчит).
     * Очищенный блок — в [out] (на блок позже входа).
     */
    fun process(e: DoubleArray, y: DoubleArray, x: DoubleArray, trust: Double, share: Double, echoOnly: Boolean, out: DoubleArray) {
        windowed(yPrev, y)
        System.arraycopy(re, 0, yRe, 0, k); System.arraycopy(im, 0, yIm, 0, k)
        var echoNow = 0.0
        for (i in 0 until k) { py[i] = re[i] * re[i] + im[i] * im[i]; echoNow += py[i] }
        windowed(xPrev, x)
        for (i in 0 until k) px[i] = re[i] * re[i] + im[i] * im[i]
        windowed(ePrev, e)
        System.arraycopy(re, 0, eRe, 0, k); System.arraycopy(im, 0, eIm, 0, k)
        System.arraycopy(e, 0, ePrev, 0, n); System.arraycopy(y, 0, yPrev, 0, n); System.arraycopy(x, 0, xPrev, 0, n)

        var pey = 0.0; var pyy = 0.0; var errNow = 0.0
        for (i in 0 until k) {
            pe[i] = eRe[i] * eRe[i] + eIm[i] * eIm[i]
            errNow += pe[i]
            // Колебания громкости ошибки и оценки эха по частотам (без средних): эхо, которое фильтр не убрал,
            // колеблется вместе с оценкой; голос собеседника — сам по себе и в среднем в оценку не попадает.
            val eCur = pe[i] - eh[i]; val yCur = py[i] - yh[i]
            pey += eCur * yCur; pyy += yCur * yCur
            eh[i] += specAverage * (pe[i] - eh[i]); yh[i] += specAverage * (py[i] - yh[i])
        }
        // «Ошибка времени»: если настоящее эхо чуть позже модели, ошибка — это оценка эха, повёрнутая по фазе тем
        // сильнее, чем выше частота. Голос собеседника с оценкой не связан и в сумме даёт ноль.
        for (i in lowBin..highBin) {
            val w = 2 * PI * i / f
            timeNum -= w * (eIm[i] * yRe[i] - eRe[i] * yIm[i])
            timeDen += w * w * py[i]
        }
        pyy = sqrt(pyy)
        if (pyy > 0 && errNow > 0) {
            val alpha = min(LEAK_RATE * echoNow, 0.25 * LEAK_RATE * errNow) / errNow
            peyAvg += alpha * (pey / pyy - peyAvg)
            pyyAvg += alpha * (pyy - pyyAvg)
            if (pyyAvg < 1.0) pyyAvg = 1.0
            peyAvg = peyAvg.coerceIn(0.0, MAX_SHARE * pyyAvg)
            leak = peyAvg / pyyAvg
        }
        val part = max(share, leak).coerceIn(MIN_SHARE, MAX_SHARE)
        // «Разлитый» остаток: искажения динамика и кодеков попадают и на частоты, где в самой оценке эха пусто.
        // Сколько остатка на каждой частоте приходится на единицу громкости эха, запоминается по блокам, где звучит
        // только эхо (голос собеседника в оценку не попадает).
        if (echoOnly && echoNow > 0) {
            for (i in 0 until k) shapeNum[i] += SHAPE * (max(pe[i] - noise[i], 0.0) - shapeNum[i])
            shapeDen += SHAPE * (echoNow - shapeDen)
        }
        blur(shapeNum, shapeBlur)
        val perEcho = if (shapeDen > 0) echoNow / shapeDen else 0.0
        for (i in 0 until k) {
            spread[i] = shapeBlur[i] * perEcho
            smear[i] = max(max(px[i], pxPrev[i]), SMEAR * smear[i])
            pxPrev[i] = px[i]
            val r = max(max(max(part * py[i], spread[i]), (1 - trust) * preset * smear[i]), HOLD * resid[i])
            resid[i] = r
        }
        // Усиление считается по спектрам, сглаженным по частоте (≈ ±100 Гц): у отдельной частоты громкость случайна,
        // и без сглаживания остаток то и дело «выскакивал» бы выше своей оценки.
        blur(pe, peS)
        blur(resid, rS)
        var total = 0.0; var echoPart = 0.0; var echoAll = 0.0
        for (i in lowBin..highBin) { total += peS[i]; echoAll += OVER_SHUT * rS[i]; echoPart += min(OVER_SHUT * rS[i], peS[i]) }
        // Общий замок: если всё слышимое объясняется остатком эха (собеседник молчит), приглушить целиком; голос
        // собеседника открывает сразу, закрывается плавно.
        val ratio = total / (echoAll + 1e-9)
        val wanted = ((ratio - GATE_SHUT) / (GATE_OPEN - GATE_SHUT)).coerceIn(GATE_FLOOR, 1.0)
        broad = if (wanted > broad) wanted else broad + (wanted - broad) * 0.3
        // Запас оценки: собеседник молчит — больше (эхо не проскочит), говорит — меньше (его голос целее).
        val over = OVER_OPEN + (OVER_SHUT - OVER_OPEN) * (1 - broad) / (1 - GATE_FLOOR)
        for (i in 0 until k) {
            val rEff = over * rS[i] + 1e-9
            val post = peS[i] / rEff
            val prior = DD * gPrev[i] * gPrev[i] * pePrev[i] / rEff + (1 - DD) * max(post - 1, 0.0)
            val g = max(prior / (1 + prior) * broad, FLOOR)
            gPrev[i] = g; pePrev[i] = peS[i]
            trackNoise(i, pe[i])
            var cr = 0.0; var ci = 0.0
            if (comfort && g < 0.99 && noise[i] > 0) {
                // Шум на месте убранного — не громче фона и не громче того, что убрано.
                val c = sqrt((1 - g * g) * min(noise[i], peS[i]))
                seed = seed * 1_103_515_245 + 12_345
                val phase = 2 * PI * ((seed ushr 8) and 0xFFFF) / 65_536.0
                cr = c * cos(phase); ci = if (i == 0 || i == n) 0.0 else c * sin(phase)
            }
            re[i] = g * eRe[i] + cr; im[i] = g * eIm[i] + ci
        }
        noiseBlocks++
        if (noiseBlocks >= noisePeriod) {
            noiseBlocks = 0
            System.arraycopy(minNow, 0, minOld, 0, k)
            minNow.fill(Double.MAX_VALUE)
        }
        echoDominant = total > 0 && echoPart > 0.6 * total
        synthesize(out)
    }

    /** На сколько отсчётов эхо отстаёт от модели, в среднем с прошлого вызова (0 — эха не было). */
    fun timing(): Double {
        val t = if (timeDen > TIMING_MIN) timeNum / timeDen else 0.0
        timeNum = 0.0; timeDen = 0.0
        return t
    }

    /** Без подавления, с общим усилением [gain] — тем же путём (та же задержка, без щелчка при переключении). */
    fun bypass(e: DoubleArray, gain: Double, out: DoubleArray) {
        windowed(ePrev, e)
        System.arraycopy(e, 0, ePrev, 0, n)
        yPrev.fill(0.0); xPrev.fill(0.0)
        for (i in 0 until k) {
            val pe = re[i] * re[i] + im[i] * im[i]
            trackNoise(i, pe)
            gPrev[i] = gain; pePrev[i] = pe
            re[i] *= gain; im[i] *= gain
        }
        echoDominant = gain < 0.5
        synthesize(out)
    }

    private fun trackNoise(i: Int, pe: Double) {
        smooth[i] = 0.85 * smooth[i] + 0.15 * pe
        if (smooth[i] < minNow[i]) minNow[i] = smooth[i]
        val m = min(minNow[i], minOld[i])
        noise[i] = if (m == Double.MAX_VALUE) 0.0 else NOISE_BIAS * m
    }

    private fun synthesize(out: DoubleArray) {
        for (i in 1 until n) { re[f - i] = re[i]; im[f - i] = -im[i] }
        fft.inverse(re, im)
        for (i in 0 until n) {
            out[i] = ola[i] + re[i] * window[i]
            ola[i] = re[n + i] * window[n + i]
        }
    }

    companion object {
        /** Нижняя граница усиления по частоте: −40 дБ. */
        const val FLOOR = 0.01
        /**
         * Остаток считается не меньше этой доли оценки эха (−10 дБ), даже если модель кажется идеальной: маленький
         * динамик на громких местах искажает, и вычитанию больше чем на 10 дБ верить нельзя (так же устроен AEC3).
         */
        const val MIN_SHARE = 0.1
        /** И не больше учетверённой оценки (фильтр устарел: настоящего эха больше, чем он думает). */
        const val MAX_SHARE = 4.0
        private const val LEAK_RATE = 0.064
        private const val TIMING_MIN = 1e6
        /** Запас по оценке остатка: +4 дБ, пока собеседник молчит, +1 дБ, когда говорит. */
        const val OVER_SHUT = 2.5
        const val OVER_OPEN = 1.25
        private const val DD = 0.6
        /** Остаток гаснет на 1,8 дБ за блок — хвост комнаты после слова. */
        private const val HOLD = 0.66
        private const val SMEAR = 0.5
        private const val NOISE_BIAS = 1.5
        /** Среднее для оценки «разлитого» остатка: около 0,8 с звучания эха. */
        private const val SHAPE = 0.02
        private const val BLUR = 3
        /** Общий замок: слышимое не больше 0,75 оценки остатка — закрыт (до −20 дБ), больше 1,5 — открыт. */
        private const val GATE_SHUT = 0.75
        private const val GATE_OPEN = 1.5
        private const val GATE_FLOOR = 0.1
    }
}

/** Свой движок: [LinearStage] + [Suppressor]. */
internal class KotlinEcho(override val block: Int, private val parts: Int, private val rate: Int, preset: Double) : EchoEngine {
    private val linear = LinearStage(block, parts, rate)
    internal val suppressor = Suppressor(block, rate, preset)
    private val error = DoubleArray(block)
    private val capPre = DoubleArray(block)
    private val refPre = DoubleArray(block)
    private var capLast = 0.0
    private var refLast = 0.0
    private var outLast = 0.0
    private var trust = 0.0
    override val latency get() = block
    override val taps get() = parts * block
    override val echoDominant get() = suppressor.echoDominant
    override val converged get() = trust > 0.5
    override val lost get() = linear.lost
    internal val lostWhy get() = linear.lostWhy
    override fun timing() = suppressor.timing()
    override val linearDb get() = 10 * kotlin.math.log10(linear.gain)

    /** Подъём верхних частот на входе (и обратный на выходе): басы и гул не заслоняют речь в оценках громкости. */
    private fun emphasize(from: DoubleArray, to: DoubleArray, last: Double): Double {
        var prev = last
        for (i in 0 until block) { val v = from[i]; to[i] = v - PRE * prev; prev = v }
        return prev
    }

    private fun deemphasize(out: DoubleArray) {
        for (i in 0 until block) { outLast = out[i] + PRE * outLast; out[i] = outLast }
    }

    override fun process(capture: DoubleArray, reference: DoubleArray, out: DoubleArray) {
        capLast = emphasize(capture, capPre, capLast)
        refLast = emphasize(reference, refPre, refLast)
        linear.process(capPre, refPre, error)
        // Доверие модели — по тому, насколько рабочий фильтр ослабляет эхо: 2 дБ — нет, 5 дБ — полное.
        val db = 10 * kotlin.math.log10(linear.gain)
        val wanted = ((db - TRUST_FROM_DB) / (TRUST_FULL_DB - TRUST_FROM_DB)).coerceIn(0.0, 1.0)
        trust = if (wanted > trust) min(wanted, trust + TRUST_UP) else max(wanted, trust - TRUST_DOWN)
        // Остаток после вычитания — доля от оценки эха: при ослаблении в G раз остаётся 1 / (G − 1).
        val usual = if (linear.gain > 1.0) 1.0 / (linear.gain - 1.0) else 1.0
        val share = if (linear.raw) 1.0 else max(usual, linear.residue)
        suppressor.process(error, linear.echo, refPre, trust, share, linear.echoOnly, out)
        deemphasize(out)
    }

    override fun bypass(capture: DoubleArray, gain: Double, out: DoubleArray) {
        capLast = emphasize(capture, capPre, capLast)
        suppressor.bypass(capPre, gain, out)
        deemphasize(out)
    }

    override fun reset() { linear.reset(); suppressor.reset(); trust = 0.0; refLast = 0.0 }

    internal fun debug() = "остаток %.2f, вычитание %.1f дБ, доверие %.2f, обучен %s, замен %d".format(suppressor.leak,
        linearDb, trust, linear.adapted, linear.updates)

    override fun model(out: DoubleArray): Boolean {
        // Только сошедшаяся модель: по недоученной главный отклик легко принять не за тот, и сдвиг уедет.
        if (!converged) return false
        linear.impulse(out)
        return true
    }

    override fun realign(blocks: Int, history: DoubleArray) {
        var prev = 0.0
        for (i in history.indices) { val v = history[i]; history[i] = v - PRE * prev; prev = v }
        refLast = prev
        linear.realign(blocks, history)
    }

    private companion object {
        const val PRE = 0.9
        const val TRUST_FROM_DB = 2.0
        const val TRUST_FULL_DB = 5.0
        const val TRUST_UP = 0.04
        const val TRUST_DOWN = 0.25
    }
}

/**
 * Поиск сдвига между опорой и входом по огибающим: громкость по полосам для каждого блока (логарифм — усиление пути
 * не важно), сравнение рисунка громкости за последние секунды при всех допустимых сдвигах. Огибающая переживает
 * кодеки и искажения динамика, а голос собеседника во входе с опорой не связан и максимум не сдвигает. Точность —
 * около половины блока; дальше модель пути эха уточняет сама.
 */
internal class DelayFinder(private val n: Int, rate: Int, private val ref: RefTap, minDelayMs: Int, maxDelayMs: Int) {
    private val fft = Fft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val edges = IntArray(BANDS + 1) { (300.0 * Math.pow(4_000.0 / 300.0, it.toDouble() / BANDS) * n / rate).toInt().coerceIn(1, n / 2) }
    /** Пол громкости полосы: тише (общая громкость около −60 дБ) — рисунок не различается, шум в сравнение не идёт. */
    private val floor = n.toDouble() * n * FLOOR_RMS * FLOOR_RMS / BANDS
    private val minLag = minDelayMs * rate / 1000 / n
    private val maxLag = maxDelayMs * rate / 1000 / n
    private val size = Integer.highestOneBit(maxLag + WINDOW + 64) * 2
    private val cap = Array(BANDS) { FloatArray(size) }
    private val quiet = ln(floor).toFloat()
    private val refF = Array(BANDS) { FloatArray(size) { quiet } }
    private val refPower = FloatArray(size)
    private var capBlocks = 0L
    private var refBlocks = 0L
    private var windowStart = 0L
    private val block = DoubleArray(n)
    private val capSeg = FloatArray(BANDS * WINDOW)
    private val scores = FloatArray(maxLag - minLag + 3)
    /** Последняя оценка: сдвиг в отсчётах (номер во входе минус номер в опоре) и её надёжность 0…1. */
    var estimate = 0L
        private set
    var score = 0.0
        private set
    /** В опоре был звук в те мгновения, эхо от которых может приходить сейчас. */
    var echoPossible = false
        private set
    /** При последней оценке в опоре было что сравнивать (речь, а не тишина). */
    var refActive = false
        private set

    private fun features(samples: DoubleArray, into: Array<FloatArray>, at: Int): Double {
        var total = 0.0
        for (i in 0 until n) { re[i] = samples[i]; total += samples[i] * samples[i] }
        im.fill(0.0)
        fft.forward(re, im)
        for (b in 0 until BANDS) {
            var e = 0.0
            for (i in edges[b] until max(edges[b] + 1, edges[b + 1])) e += re[i] * re[i] + im[i] * im[i]
            into[b][at] = ln(e + floor).toFloat()
        }
        return total / n
    }

    /**
     * Очередной блок входа. true — посчитана новая оценка ([estimate], [score]).
     */
    fun push(capture: DoubleArray): Boolean {
        features(capture, cap, (capBlocks % size).toInt())
        capBlocks++
        val available = ref.total / n
        if (available - refBlocks > size) refBlocks = available - size
        while (refBlocks < available) {
            ref.read(refBlocks * n, block, 0, n)
            val at = (refBlocks % size).toInt()
            refPower[at] = features(block, refF, at).toFloat()
            refBlocks++
        }
        var possible = false
        var i = max(0L, refBlocks - 1 - maxLag - TAIL)
        val last = refBlocks - 1 - minLag
        while (i <= last && !possible) { possible = refPower[(i % size).toInt()] > ACTIVE_POWER; i++ }
        echoPossible = possible
        if (capBlocks % EVERY != 0L || capBlocks - windowStart < MIN_WINDOW) return false
        return evaluate()
    }

    /** Сравнивать только то, что придёт после этого мгновения: прежний сдвиг больше не действует, старое мешает. */
    fun restart() { windowStart = capBlocks; score = 0.0 }

    private fun evaluate(): Boolean {
        val window = min(WINDOW.toLong(), capBlocks - windowStart).toInt()
        val lastCap = capBlocks - 1
        // Сдвиг в блоках между номерами: сейчас во входе блок lastCap, в опоре готов блок refBlocks − 1.
        val now = lastCap - (refBlocks - 1)
        // Вход: окно без среднего по каждой полосе.
        var capNorm = 0.0
        for (b in 0 until BANDS) {
            var mean = 0.0
            for (t in 0 until window) mean += cap[b][((lastCap - t) % size).toInt()]
            mean /= window
            for (t in 0 until window) {
                val v = cap[b][((lastCap - t) % size).toInt()] - mean.toFloat()
                capSeg[b * WINDOW + t] = v
                capNorm += v * v
            }
        }
        var best = -1; var bestScore = 0.0
        var anyLoud = false
        for (lag in minLag..maxLag) {
            val refLast = refBlocks - 1 - lag
            var s = 0.0
            // До начала опоры — тишина (ничего не отправлялось); затёртое кольцом не сравниваем.
            if (refLast - window + 1 >= refBlocks - size) {
                var dot = 0.0; var norm = 0.0; var loud = 0
                for (t in 0 until window) { val at = refLast - t; if (at >= 0 && refPower[(at % size).toInt()] > ACTIVE_POWER) loud++ }
                if (loud >= MIN_LOUD) {
                    anyLoud = true
                    for (b in 0 until BANDS) {
                        val r = refF[b]
                        var mean = 0.0
                        for (t in 0 until window) { val at = refLast - t; mean += if (at >= 0) r[(at % size).toInt()] else quiet }
                        mean /= window
                        for (t in 0 until window) {
                            val at = refLast - t
                            val v = (if (at >= 0) r[(at % size).toInt()] else quiet) - mean
                            dot += v * capSeg[b * WINDOW + t]
                            norm += v * v
                        }
                    }
                    if (norm > 0 && capNorm > 0) s = dot / sqrt(norm * capNorm)
                }
            }
            scores[lag - minLag + 1] = s.toFloat()
            if (s > bestScore) { bestScore = s; best = lag }
        }
        refActive = anyLoud
        if (best < 0) { score = 0.0; return true }
        // Уточнение положения максимума параболой по соседям.
        val c = scores[best - minLag + 1].toDouble()
        val l = if (best > minLag) scores[best - minLag].toDouble() else c
        val r = if (best < maxLag) scores[best - minLag + 2].toDouble() else c
        val denom = l - 2 * c + r
        val frac = if (denom < -1e-9) (0.5 * (l - r) / denom).coerceIn(-0.5, 0.5) else 0.0
        estimate = Math.round((now + best + frac) * n)
        score = bestScore
        return true
    }

    companion object {
        private const val BANDS = 8
        /** Окно сравнения: 2,6 с. */
        private const val WINDOW = 160
        private const val MIN_WINDOW = 60L
        private const val EVERY = 4L
        private const val FLOOR_RMS = 30.0
        private const val ACTIVE_POWER = 150f * 150f
        private const val MIN_LOUD = 8
        /** Хвост комнаты после слова, блоков. */
        private const val TAIL = 12L
    }
}

/**
 * Один контур эхоподавления: принимает вход кусками любой длины, сам находит сдвиг опоры ([DelayFinder]), держит его
 * (главный отклик модели — в начале, сдвиг подправляется целыми блоками без потери модели) и чистит вход движком.
 * Выход отстаёт от входа ровно на 2 блока (32 мс при 16 кГц).
 *
 * Чего движок сам не умеет:
 * - **пока сдвиг не найден** (первые слова), вход приглушается, когда в опоре был звук, эхо от которого может идти
 *   сейчас, — как прежняя защита «говорит кто-то один», но только на секунду-две; если опора звучала, а её рисунка
 *   во входе так и нет, эха нет вовсе — вход открыт;
 * - **сдвиг скакнул** (очередь домофона опустела и набралась заново): модель добавляет ложное эхо или поиск уверенно
 *   показывает другое место — до перезахвата вход снова приглушается;
 * - **часы расходятся** (домофон подстраивает воспроизведение под наш поток, а записывает по своим часам): сдвиг
 *   плавно уезжает на десятки отсчётов в минуту. Скорость ухода видна по тому, как смещается модель; опора читается
 *   с дробным сдвигом, растущим с той же скоростью, — для модели путь эха стоит на месте.
 */
internal class EchoLoop(
    private val name: String,
    private val ref: RefTap,
    private val rate: Int,
    minDelayMs: Int,
    maxDelayMs: Int,
    parts: Int,
    preset: Double,
    /** Следить за расхождением часов (у дальнего контура часы разные; у ближнего динамик и микрофон — одно устройство). */
    private val followClock: Boolean = true,
    private val engine: EchoEngine = KotlinEcho(BLOCK, parts, rate, preset)
) {
    private val n = engine.block
    private val finder = DelayFinder(n, rate, ref, minDelayMs, maxDelayMs)
    private val pending = DoubleArray(n)
    private var filled = 0
    private val refBlock = DoubleArray(n)
    private val outBlock = DoubleArray(n)
    private val history = DoubleArray(engine.taps + n)
    private val fetch = DoubleArray(engine.taps + n + SINC)
    private val taps = DoubleArray(SINC)
    private val model = DoubleArray(engine.taps)
    private val ready = ShortFifo(rate)
    private val one = ShortArray(n)
    private var blocks = 0L
    private var offset = 0L
    private var slip = 0.0
    private var peak = PRE_ROLL * n
    private var votes = 0
    private var candidate = 0L
    private var sinceCheck = 0
    private var gate = 1.0
    private var doubt = false
    private var lostSeen = false
    private var confirmed = false
    private var silentEvals = 0
    private var noEcho = false
    private var inSum = 0.0
    private var outSum = 0.0
    private var activeBlocks = 0L
    internal var log: (String) -> Unit = {}
    /** Сдвиг найден, движок работает. */
    @Volatile var locked = false
        private set
    @Volatile var relocks = 0
        private set
    /** Через сколько эхо возвращается на самом деле, мс (по времени записи опоры); 0 — ещё неизвестно. */
    @Volatile var echoDelayMs = 0
        private set
    @Volatile var echoDominant = false
        private set
    /** Уход часов: на сколько отсчётов сдвиг растёт за один отсчёт (60 миллионных — отсчёт в секунду при 16 кГц). */
    @Volatile var drift = 0.0

    init { ready.push(ShortArray(n), n, Int.MAX_VALUE) }

    /** Насколько вход стал тише в блоках, где слышно только эхо, дБ. */
    val suppressionDb: Double get() = if (activeBlocks < 20 || outSum <= 0) 0.0 else 10 * kotlin.math.log10(inSum / outSum)
    val linearDb: Double get() = engine.linearDb
    /** Модель пути эха сошлась — вычитание работает. */
    val converged: Boolean get() = locked && engine.converged

    /** Очистить [count] отсчётов на месте. Задержка постоянная: 2 блока. */
    fun process(pcm: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val take = min(n - filled, count - i)
            for (j in 0 until take) pending[filled + j] = pcm[i + j].toDouble()
            filled += take; i += take
            if (filled == n) { step(); filled = 0 }
        }
        val got = ready.pop(pcm, count)
        if (got < count) pcm.fill(0, got, count)
    }

    private fun step() {
        val start = blocks * n
        blocks++
        // Модель потеряна (сдвиг скакнул): искать заново только по тому, что придёт теперь.
        if (locked && engine.lost && !lostSeen) {
            lostSeen = true; confirmed = false; votes = 0
            finder.restart()
            log("Эхо ($name): модель потеряна (${(engine as? KotlinEcho)?.lostWhy.orEmpty()}) — сдвиг ищется заново")
        }
        if (!engine.lost) lostSeen = false
        if (finder.push(pending)) follow()
        val before = gate
        if (locked) {
            readAligned(start - offset, refBlock, n)
            engine.process(pending, refBlock, outBlock)
            echoDominant = engine.echoDominant
            measure()
            // Модель потеряна или поиск показывает другое место: пока не разобрались, возможное эхо не выпускать.
            val unsure = ((engine.lost && !confirmed) || doubt) && finder.echoPossible
            gate += ((if (unsure) SEARCH_GAIN else 1.0) - gate) * 0.5
            if (before < 0.999 || gate < 0.999) {
                for (j in 0 until n) outBlock[j] *= before + (gate - before) * j / n
                if (unsure) echoDominant = true
            }
            if (++sinceCheck >= CHECK_BLOCKS) { sinceCheck = 0; check(start + n) }
            slip += drift * n
            while (slip >= 1.0) { slip -= 1.0; offset++ }
            while (slip < 0.0) { slip += 1.0; offset-- }
        } else {
            // Сдвиг ещё неизвестен: когда в опоре недавно был звук, эхо может быть где угодно — приглушить.
            val closed = finder.echoPossible && !noEcho
            gate += ((if (closed) SEARCH_GAIN else 1.0) - gate) * 0.5
            engine.bypass(pending, gate, outBlock)
            echoDominant = closed
        }
        for (j in 0 until n) one[j] = outBlock[j].coerceIn(-32_768.0, 32_767.0).toInt().toShort()
        ready.push(one, n, Int.MAX_VALUE)
    }

    /**
     * Опора с места [from] с дробным сдвигом [slip] назад: интерполяция по [SINC] отсчётам (sinc с окном) — верхние
     * частоты не «дышат» вместе с дробной частью сдвига, как было бы при простой интерполяции.
     */
    private fun readAligned(from: Long, out: DoubleArray, count: Int) {
        if (slip == 0.0) { ref.read(from, out, 0, count); return }
        val half = SINC / 2
        ref.read(from - half, fetch, 0, count + SINC - 1)
        var sum = 0.0
        for (j in 0 until SINC) {
            // Отсчёт fetch[i + j] стоит на (j − half) от места from + i; нужное место — на slip раньше.
            val x = (j - half) + slip
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val window = 0.5 + 0.5 * cos(PI * x / half).coerceIn(-1.0, 1.0)
            taps[j] = if (abs(x) >= half) 0.0 else sinc * window
            sum += taps[j]
        }
        for (j in 0 until SINC) taps[j] /= sum
        for (i in 0 until count) {
            var acc = 0.0
            for (j in 0 until SINC) acc += taps[j] * fetch[i + j]
            out[i] = acc
        }
    }

    /** Новая оценка сдвига: первая — захват; уверенно расходится с текущей несколько раз подряд — перезахват. */
    private fun follow() {
        val found = finder.estimate
        val searching = !locked || lostSeen
        val sure = finder.score >= if (searching) LOCK_SCORE else MIN_SCORE
        if (!locked && finder.refActive && !sure && ++silentEvals >= NO_ECHO_EVALS && !noEcho) {
            noEcho = true
            log("Эхо ($name): опора звучала, а во входе её нет — эха нет, вход открыт")
        }
        val agrees = locked && abs(found - (offset + peak)) <= TOLERANCE * n
        if (!sure || (agrees && !lostSeen)) { votes = 0; doubt = false; return }
        if (votes > 0 && abs(found - candidate) <= n) votes++ else { votes = 1; candidate = found }
        if (locked && !agrees && votes >= DOUBT_VOTES) doubt = true
        val need = if (searching) LOCK_VOTES else if (engine.converged) RELOCK_VOTES_GOOD else RELOCK_VOTES
        if (votes < need) return
        votes = 0; doubt = false
        // Эхо там же, где было: модель переучится сама, сдвиг трогать незачем.
        if (agrees) { confirmed = true; return }
        if (locked) relocks++
        offset = candidate - PRE_ROLL.toLong() * n
        slip = 0.0
        peak = PRE_ROLL * n
        engine.reset()
        locked = true
        sinceCheck = 0; lostSeen = false; confirmed = false
        silentEvals = 0; noEcho = false
        log("Эхо ($name): сдвиг найден, ${candidate * 1000 / rate} мс по отсчётам, совпадение %.2f".format(finder.score))
    }

    /**
     * Раз в полсекунды. Где главный отклик модели: он держится в начале (от четверти блока до блока с четвертью) —
     * сдвиг подправляется целыми блоками. И «ошибка времени» от движка: эхо отстаёт от модели — сдвиг чуть больше и
     * уходит чуть быстрее ([drift]), опережает — наоборот; так контур держится за эхо при расходящихся часах.
     */
    private fun check(next: Long) {
        val late = engine.timing()
        // Подстройка — только пока модель жива и поиск с ней согласен: иначе «ошибка времени» — случайное число.
        if (followClock && late != 0.0 && abs(late) < MAX_MOVE && !engine.lost && !doubt) {
            slip += TIME_GAIN * late
            val change = (DRIFT_GAIN * late / (CHECK_BLOCKS * n)).coerceIn(-DRIFT_STEP, DRIFT_STEP)
            drift = (drift + change).coerceIn(-MAX_DRIFT, MAX_DRIFT)
        }
        if (!engine.model(model)) return
        var best = 0; var bestValue = 0.0; var energy = 0.0
        for (i in model.indices) {
            val v = model[i] * model[i]
            energy += v
            if (v > bestValue) { bestValue = v; best = i }
        }
        // Отклик без выраженного пика — модель ещё ничего не значит.
        if (bestValue < PEAK_PART * energy) return
        val shift = when {
            best >= n + n / 4 -> best / n - (if (best % n < n / 4) 1 else 0)
            best < n / 4 -> -1
            else -> 0
        }
        if (shift != 0) {
            offset += shift.toLong() * n
            readAligned(next - offset - history.size, history, history.size)
            engine.realign(shift, history)
        }
        peak = best - shift * n
        val heard = ref.timeOf(next - offset - peak)
        if (heard != 0L) echoDelayMs = ((ref.clock() - heard) / 1_000_000).toInt()
    }

    private fun measure() {
        var x2 = 0.0; var d2 = 0.0; var o2 = 0.0
        for (j in 0 until n) { x2 += refBlock[j] * refBlock[j]; d2 += pending[j] * pending[j]; o2 += outBlock[j] * outBlock[j] }
        // Только блоки, где слышно одно эхо: при голосе собеседника вход и не должен становиться тише.
        if (x2 > n * ACTIVE_POWER && engine.echoDominant) { inSum += d2; outSum += o2; activeBlocks++ }
    }

    /** Состояние контура одной строкой — для разбора в тестах и на стенде. */
    internal fun debug() = "захват $locked, сдвиг $offset+%.2f, отклик $peak, уход %.0f, поиск ${finder.estimate} (%.2f), голосов $votes, потеря ${engine.lost}, сомнение $doubt; "
        .format(slip, drift * 1e6, finder.score) + ((engine as? KotlinEcho)?.debug() ?: "")

    /** Сводка для журнала: что получилось за разговор. */
    fun summary(): String = when {
        locked -> "$name: эхо тише на %.0f дБ (вычитанием %.0f дБ), возвращалось через %d мс, уход часов %+.0f млн⁻¹, перезахватов %d"
            .format(suppressionDb, linearDb, echoDelayMs, drift * 1e6, relocks)
        noEcho -> "$name: эха не было"
        else -> "$name: сдвиг не найден (опора молчала)"
    }

    companion object {
        const val BLOCK = 256
        /** Запас перед найденным сдвигом, блоков: оценка по огибающей неточна на блок-полтора. */
        private const val PRE_ROLL = 2
        /**
         * Совпадение рисунка громкости: у настоящего эха 0,9 и выше (под голосом собеседника 0,5–0,75), у двух
         * разных голосов случайно бывает до 0,55 — и держится несколько оценок подряд (окно сдвигается медленно).
         * Поэтому захват — от 0,7 шесть оценок подряд (0,4 с), спор с текущим сдвигом — от 0,6.
         */
        private const val MIN_SCORE = 0.6
        private const val LOCK_SCORE = 0.7
        private const val LOCK_VOTES = 6
        private const val RELOCK_VOTES = 8
        private const val RELOCK_VOTES_GOOD = 20
        /** Столько раз подряд поиск показал другое место — вход приглушается до выяснения. */
        private const val DOUBT_VOTES = 3
        /** Столько оценок подряд (2,6 с речи в опоре) без её рисунка во входе — эха нет. */
        private const val NO_ECHO_EVALS = 40
        private const val TOLERANCE = 3
        private const val CHECK_BLOCKS = 32
        /** Пока сдвиг ищется, вход при звуке в опоре тише в 30 раз (−30 дБ). */
        private const val SEARCH_GAIN = 0.03
        private const val ACTIVE_POWER = 150.0 * 150.0
        /** Главный отклик — не меньше двадцатой доли всей энергии модели. */
        private const val PEAK_PART = 0.05
        /** Подстройка по «ошибке времени»: половина — сразу в сдвиг, четверть — в скорость ухода. */
        private const val MAX_MOVE = 1.5
        private const val DRIFT_STEP = 15e-6
        private const val TIME_GAIN = 0.5
        private const val DRIFT_GAIN = 0.25
        /** Больше 300 миллионных между часами двух устройств не бывает. */
        private const val MAX_DRIFT = 300e-6
        private const val SINC = 8
    }
}

/**
 * Эхоподавление одного разговора: оба контура и их опоры. Работает при 16 кГц с обеих сторон (домофон Reolink по своему
 * протоколу); иначе разговор остаётся с прежней защитой [EchoGate].
 */
internal class DuplexEcho(rate: Int = RATE, private val key: String = "") {
    /** Что реально ушло в домофон (после кодека) — опора дальнего контура. */
    val sent = RefTap(rate)
    /** Что записано в динамик планшета — опора ближнего контура. */
    val played = RefTap(rate)
    /** Дальний контур: наш голос, вернувшийся в звуке улицы (через 0,3–3 с). */
    val far = EchoLoop("свой голос с улицы", sent, rate, FAR_MIN_MS, FAR_MAX_MS, FAR_PARTS, FAR_PRESET)
    /** Ближний контур: звук улицы из динамика планшета в его микрофоне (до 0,7 с — запас динамика и микрофона). */
    val near = EchoLoop("улица в микрофоне", played, rate, 0, NEAR_MAX_MS, NEAR_PARTS, NEAR_PRESET, followClock = false)
    /** Звук улицы пошёл с нужной частотой — контуры работают. */
    @Volatile var active = false

    init { far.drift = drifts[key] ?: 0.0 }

    /**
     * Запомнить расхождение часов с этой камерой (до перезапуска панели): оно почти не меняется, и следующий разговор
     * начнётся уже с подстройкой, без нескольких секунд привыкания.
     */
    fun remember() {
        if (key.isNotEmpty() && far.converged) drifts[key] = far.drift
    }

    companion object {
        private val drifts = java.util.concurrent.ConcurrentHashMap<String, Double>()
        const val RATE = 16_000
        const val FAR_MIN_MS = 250
        const val FAR_MAX_MS = 3_000
        const val NEAR_MAX_MS = 700
        /** Длина модели: 6 блоков = 96 мс (динамик и микрофон домофона в одном корпусе), 8 блоков = 128 мс (комната). */
        const val FAR_PARTS = 6
        const val NEAR_PARTS = 8
        /** Пока модель не сошлась: эхо не громче опоры (домофон возвращает на 12 дБ тише, с запасом на пики 2–3 кГц). */
        const val FAR_PRESET = 1.0
        /** Динамик планшета рядом с микрофоном: эхо может быть громче записанного в динамик. */
        const val NEAR_PRESET = 4.0
    }
}
