package com.example.smarthomedashboard

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Мелодия звонка в самом домофоне. По нажатию кнопки домофон Reolink играет своим динамиком «динь-дон» (≈ 3,4 с,
 * ролик события 23.11.2026) и не прерывает его, когда отвечают; голос, пришедший по каналу разговора во время
 * мелодии, он не выбрасывает — копит и играет после неё. Разговор, начатый сразу после звонка, шёл с опозданием голоса
 * к гостю на ≈ 3 с до самого конца (свой голос возвращался через 3,6 → 3,0 с вместо 0,8–1,1 с — первый разговор с
 * человеком у домофона, 23.11.2026). Поэтому канал голоса к этому домофону открывается после мелодии ([DoorbellTalk]).
 *
 * Когда она кончилась — слышно: после звонка звук домофона принимается без вывода ([StreetAudio.silent]) и слушается
 * [ChimeDetector]. Мелодии нет (выключена в приложении Reolink, звонок пришёл через Home Assistant уже после неё) —
 * канал открывается через ≈ 1,3 с после звонка; есть — когда стихла (≈ 0,2 с после последнего удара: длина мелодии
 * известна — [DEFAULT_CHIME_MS], потом измеренная). Звук домофона не пришёл — ждём [MS] от звонка.
 */
internal object DoorChime {
    /** Домофон не слышно: мелодия ≈ 3,4 с от её начала, начало — через ≈ 0,6 с после прихода звонка, и запас. */
    const val MS = 4_500L
    /** Домофон слышно: дольше не ждём в любом случае (прослушивание — до [ChimeDetector.MAX_MS] звука). */
    const val HEARD_MS = 9_000L
    /** Мелодия начинается в пределах ≈ 0,9 с после прихода звонка (ролик 23.11.2026: 0,57 с низкий призвук, 0,85 с тон). */
    const val NONE_AFTER_RING_MS = 1_300L
    /**
     * Длина мелодии, пока её не измерили у этого домофона: «динь-дон» Reolink — 3,42 с от начала тона первого удара до
     * конца второго (ролик 23.11.2026). Без неё первый звонок ждал бы паузу после удара (2 с) — дольше прежних 4,5 с.
     */
    const val DEFAULT_CHIME_MS = 3_400L
    private const val MIN_AUDIO_MS = 300L
    private const val TAG = "Talk"

    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    /** Приём звука домофона для прослушивания; в тестах — подмена. */
    internal var source: () -> StreetSource = { StreetAudio().apply { silent = true; debug = false } }

    private class Ring(val at: Long) {
        @Volatile var heard = false
        @Volatile var endedAt = 0L
        @Volatile var src: StreetSource? = null
        var detector: ChimeDetector? = null
    }

    private val rings = ConcurrentHashMap<String, Ring>()
    private val learned = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "door-chime").apply { isDaemon = true } }

    /** Домофон — по адресу его потока: у канала голоса и у звонка он один и тот же. */
    fun host(rtsp: String): String = runCatching { java.net.URI(rtsp.trim()).host }.getOrNull().orEmpty().lowercase()

    /**
     * Звонок у домофона с потоком [rtsp]: его динамик сейчас занят мелодией. [password] — пароль камеры (асинхронно);
     * null — не слушать домофон, только ждать [MS].
     */
    fun rang(rtsp: String, user: String = "", password: (((String?) -> Unit) -> Unit)? = null) {
        val host = host(rtsp)
        if (host.isEmpty()) return
        val ring = Ring(clock())
        rings.put(host, ring)?.let { stopListening(it) }
        changed(host)
        if (password == null) return
        password { p ->
            if (rings[host] !== ring) return@password
            val src = try { source() } catch (e: Exception) { Log.w(TAG, "Мелодия домофона: не слушаю (${e.javaClass.simpleName})"); return@password }
            ring.src = src
            src.listen { pcm, n, rate -> hear(host, ring, pcm, n, rate) }
            src.start(rtsp, user, p) { ok ->
                if (!ok && ring.endedAt == 0L && rings[host] === ring) {
                    Log.i(TAG, "Мелодия домофона: звук домофона не пришёл или оборвался — жду ${MS / 1000.0} с от звонка")
                    ring.heard = false
                    stopListening(ring)
                    changed(host)
                }
            }
            timer.schedule({ if (ring.endedAt == 0L && rings[host] === ring) finish(host, ring, "дольше не слушаю") },
                HEARD_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Звук домофона (поток приёма). */
    private fun hear(host: String, ring: Ring, pcm: ShortArray, n: Int, rate: Int) {
        if (ring.endedAt != 0L || rings[host] !== ring) return
        // «Мелодии нет» — не раньше [NONE_AFTER_RING_MS] от звонка и не раньше, чем услышана хоть доля секунды звука.
        val noneAt = (NONE_AFTER_RING_MS - (clock() - ring.at)).coerceAtLeast(MIN_AUDIO_MS)
        val detector = ring.detector ?: ChimeDetector(rate, learned[host] ?: DEFAULT_CHIME_MS, noneAt).also {
            ring.detector = it
            ring.heard = true
            changed(host)
        }
        when (detector.push(pcm, n)) {
            ChimeDetector.Verdict.NONE -> finish(host, ring, "мелодии нет")
            ChimeDetector.Verdict.ENDED -> {
                if (detector.heardWhole && detector.chimeMs in LEARN_MS) learned[host] = detector.chimeMs
                finish(host, ring, "стихла, звучала ${detector.chimeMs} мс")
            }
            else -> Unit
        }
    }

    private fun finish(host: String, ring: Ring, why: String) {
        if (ring.endedAt != 0L) return
        ring.endedAt = clock()
        Log.i(TAG, "Мелодия домофона: $why — канал голоса можно открывать (через ${ring.endedAt - ring.at} мс после звонка)")
        stopListening(ring)
        changed(host)
    }

    private fun stopListening(ring: Ring) {
        val src = ring.src ?: return
        ring.src = null
        // Не из потока приёма, который сейчас нас и позвал.
        timer.execute { src.listen(null); src.stop() }
    }

    private fun changed(host: String) = listeners.forEach { it(host) }

    /** Сколько ещё (мс) ждать конца мелодии у домофона [rtsp]; 0 — не играет или уже стихла. */
    fun left(rtsp: String): Long {
        val ring = rings[host(rtsp)] ?: return 0L
        if (ring.endedAt != 0L) return 0L
        return (ring.at + (if (ring.heard) HEARD_MS else MS) - clock()).coerceAtLeast(0L)
    }

    /** [listener] получает домофон (см. [host]): позвонили, его стало слышно или мелодия стихла. Поток — любой. */
    fun listen(listener: (String) -> Unit) { listeners += listener }
    fun unlisten(listener: (String) -> Unit) { listeners -= listener }

    /** Правдоподобная длина мелодии, мс. */
    private val LEARN_MS = 500L..7_000L

    /** Для тестов. */
    internal fun reset() {
        rings.values.forEach { stopListening(it) }
        rings.clear()
        learned.clear()
    }
}
