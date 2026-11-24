package com.example.smarthomedashboard

import android.content.Context
import android.util.Log
import com.example.smarthomedashboard.data.TileManager
import java.util.concurrent.Executors

/**
 * Постоянное слежение диагностики (docs/diagnostics-tz.md, часть 1, решение владельца 1): раз в минуту, пока открыта
 * панель, — проверки каждой камеры без сети ([CameraDiagnosis.passive]). Находка, которая держится [HOLD_MS], видна в
 * боковой панели и в Настройках строкой «Камеры: есть советы (N)»; ушла и не возвращалась [FORGET_MS] — строка гаснет.
 * На самой панели и плитках ничего не появляется. Только в памяти.
 */
internal object CameraWatch {
    data class Advice(val tileId: String, val checkId: String, val title: String)

    const val HOLD_MS = 10 * 60_000L
    const val FORGET_MS = 10 * 60_000L
    /** Проверки планшета, а не камеры: одна строка на все камеры. */
    private val TABLET_CHECKS = setOf("С4", "П2")

    private class Seen(val first: Long, var last: Long, var tileId: String, var title: String)
    private val seen = HashMap<String, Seen>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-watch").apply { isDaemon = true } }

    @Volatile var advice: List<Advice> = emptyList()
        private set
    internal var now: () -> Long = System::currentTimeMillis

    /** Строка состояния; null — советов нет. */
    fun text(): String? = advice.size.takeIf { it > 0 }?.let { "Камеры: есть советы ($it)" }

    /** Раз в минуту с панели: проверить все плитки с видео в фоне, [done] — на главном потоке. */
    fun tick(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        io.execute {
            try {
                val tiles = TileManager(app).loadTiles().filter { DoorbellConfig.isVideo(it.type) }
                val results = tiles.map { tile ->
                    val config = DoorbellConfig.from(tile.config)
                    tile.id to CameraDiagnosis.passive(app, CameraDiagnosis.target(config, null, tile.id)).checks
                }
                update(results)
            } catch (e: Exception) {
                Log.w(TAG, "Слежение за камерами: ${e.javaClass.simpleName}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
    }

    /** Итоги проверок по плиткам ([tileId] → проверки) в момент [now]. */
    @Synchronized
    internal fun update(results: List<Pair<String, List<CameraDiagnostics.Check>>>) {
        val t = now()
        for ((tileId, checks) in results) for (c in checks) {
            if (c.level != CameraDiagnostics.Level.BAD && c.level != CameraDiagnostics.Level.IMPROVE) continue
            val key = if (c.id in TABLET_CHECKS) "|${c.id}" else "$tileId|${c.id}"
            val s = seen.getOrPut(key) { Seen(t, t, tileId, c.title) }
            s.last = t; s.tileId = tileId; s.title = c.title
        }
        seen.entries.removeAll { t - it.value.last > FORGET_MS }
        advice = seen.filter { (_, s) -> s.last - s.first >= HOLD_MS }
            .map { (key, s) -> Advice(s.tileId, key.substringAfter('|'), s.title) }
            .sortedBy { it.checkId }
    }

    @Synchronized
    internal fun clear() { seen.clear(); advice = emptyList() }

    private const val TAG = "CameraWatch"
}
