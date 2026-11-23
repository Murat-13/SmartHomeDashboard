package com.example.smarthomedashboard

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Мелодия звонка в самом домофоне. По нажатию кнопки домофон Reolink играет своим динамиком «динь-дон» (≈ 3,7 с,
 * ролик события 23.11.2026), а голос, пришедший в это время по каналу разговора, не выбрасывает — копит и играет
 * после мелодии. Разговор, начатый сразу после звонка, шёл с опозданием голоса к гостю на ≈ 3 с до самого конца
 * (свой голос возвращался через 3,6 → 3,0 с вместо 0,8–1,1 с — первый разговор с человеком у домофона, 23.11.2026).
 * Поэтому канал голоса к этому домофону открывается только после мелодии ([DoorbellTalk]).
 */
internal object DoorChime {
    /** Мелодия и запас: от прихода звонка на планшет (вебхук — через ≈ 0,3 с после нажатия) до её конца ≈ 4,2 с. */
    const val MS = 4_500L
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    private val rang = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Домофон — по адресу его потока: у канала голоса и у звонка он один и тот же. */
    fun host(rtsp: String): String = runCatching { java.net.URI(rtsp.trim()).host }.getOrNull().orEmpty().lowercase()

    /** Звонок у домофона с потоком [rtsp]: его динамик сейчас занят мелодией. */
    fun rang(rtsp: String) {
        val host = host(rtsp)
        if (host.isEmpty()) return
        rang[host] = clock()
        listeners.forEach { it(host) }
    }

    /** Сколько ещё (мс) играет мелодия у домофона [rtsp]; 0 — не играет. */
    fun left(rtsp: String): Long {
        val at = rang[host(rtsp)] ?: return 0L
        return (at + MS - clock()).coerceAtLeast(0L)
    }

    /** [listener] получает домофон (см. [host]), у которого началась мелодия; поток — тот, что позвал [rang]. */
    fun listen(listener: (String) -> Unit) { listeners += listener }
    fun unlisten(listener: (String) -> Unit) { listeners -= listener }

    /** Для тестов. */
    internal fun reset() { rang.clear() }
}
