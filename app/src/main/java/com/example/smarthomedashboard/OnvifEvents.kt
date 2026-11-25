package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.smarthomedashboard.data.TileEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * События камер по ONVIF (docs/camera-tz.md, часть 2): у каждой плитки «Камера» с адресом — своя подписка PullPoint
 * в своём потоке. События (человек, машина, животное, движение) идут туда же, куда события вебхука домофона
 * ([DirectEvents.external]): значки на плитке, запись событий в HD, охрана. Пока событие длится (камера сообщила
 * «началось», но ещё не «кончилось»), оно подтверждается раз в [REFRESH_MS] — значок и запись не гаснут, охрана
 * видит, что человек всё ещё стоит. Только пока панель открыта (как прямые события домофона).
 */
internal object OnvifEvents {
    const val REFRESH_MS = 4_000L
    private const val RENEW_MS = 60_000L
    /** Не чаще: камера, которая отвечает сразу, а не держит запрос, опрашивалась бы без остановки (аудит 25.11.2026, REC-05). */
    private const val MIN_PULL_MS = 700L
    private const val TAG = "OnvifEvents"

    data class Status(val ok: Boolean, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val workers = ConcurrentHashMap<String, Worker>()
    private val statuses = ConcurrentHashMap<String, Status>()
    /** Тип → длится ли сейчас (по камере). */
    private val active = ConcurrentHashMap<String, MutableSet<CameraEventType>>()

    /** Куда уходят события (в тестах — запись вызовов). */
    internal var sink: (tileId: String, events: List<CameraEventType>) -> Unit = { tileId, events -> DirectEvents.external(tileId, events, "ONVIF") }
    internal var password: (Context, String) -> String? = { context, tileId -> CameraPasswords.blocking(context, tileId) }

    fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        main.postDelayed(refresh, REFRESH_MS)
    }

    fun detach() {
        main.removeCallbacks(refresh)
        workers.values.forEach { it.stop() }
        workers.clear()
        active.clear()
        app = null
    }

    /** Включены ли события ONVIF для камеры на этом планшете (по умолчанию да). */
    fun enabled(context: Context, tileId: String) = prefs(context).getBoolean("$tileId.onvif", true)
    fun setEnabled(context: Context, tileId: String, on: Boolean) = prefs(context).edit().putBoolean("$tileId.onvif", on).apply()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(DirectEvents.PREFS, Context.MODE_PRIVATE)

    fun status(tileId: String): Status? = statuses[tileId]

    /** Плитки панели: подписки для камер с адресом; лишние и изменившиеся — остановить. */
    fun onTiles(tiles: List<TileEntity>) {
        val context = app ?: return
        val wanted = tiles.filter { it.type == DoorbellConfig.CAMERA_TYPE && enabled(context, it.id) }.mapNotNull { tile ->
            val c = DoorbellConfig.from(tile.config)
            val host = c.host.ifBlank { DirectEvents.cameraHost(c).orEmpty() }
            if (host.isBlank() || c.rtspUser.isBlank()) null else tile.id to Target(host, c.onvifPort, c.rtspUser)
        }.toMap()
        for ((id, w) in workers.entries.toList()) {
            if (wanted[id] != w.target) { w.stop(); workers.remove(id); active.remove(id); statuses.remove(id) }
        }
        for ((id, target) in wanted) {
            if (!workers.containsKey(id)) workers[id] = Worker(context, id, target).also { it.start() }
        }
    }

    /** Повтор длящихся событий: значки, запись и охрана видят, что событие ещё идёт. */
    private val refresh = object : Runnable {
        override fun run() {
            for ((id, types) in active) synchronized(types) { if (types.isNotEmpty()) sink(id, types.toList()) }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * События одной выборки: «началось» — сразу в [sink] и в длящиеся, «кончилось» — убрать из длящихся, разовое —
     * только в [sink]. Звонок (у камер его нет) пропускается. Чистая логика для тестов.
     */
    internal fun apply(tileId: String, events: List<Onvif.Event>): List<CameraEventType> {
        val set = active.getOrPut(tileId) { mutableSetOf() }
        val started = mutableListOf<CameraEventType>()
        for (e in events) {
            val type = Onvif.typeOf(e.topic)?.takeIf { it != CameraEventType.RING } ?: continue
            synchronized(set) {
                when (e.state) {
                    true -> { if (set.add(type)) started += type }
                    false -> set.remove(type)
                    null -> started += type
                }
            }
        }
        val distinct = started.distinct()
        if (distinct.isNotEmpty()) main.post { sink(tileId, distinct) }
        return distinct
    }

    internal fun activeTypes(tileId: String): Set<CameraEventType> = active[tileId]?.let { synchronized(it) { it.toSet() } }.orEmpty()

    data class Target(val host: String, val port: Int, val user: String)

    private class Worker(val context: Context, val tileId: String, val target: Target) {
        @Volatile private var running = true
        private var thread: Thread? = null

        fun start() {
            thread = Thread({ loop() }, "onvif-events").apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            thread?.interrupt()
        }

        private fun loop() {
            var backoff = 5_000L
            while (running) {
                var sub: Onvif.Subscription? = null
                var pause = 0L
                try {
                    val pass = password(context, tileId)
                    if (pass.isNullOrEmpty()) { statuses[tileId] = Status(false, "Пароль камеры не задан"); sleep(300_000); continue }
                    sub = Onvif.subscribe(target.host, target.port, target.user, pass)
                    statuses[tileId] = Status(true, "События камеры (ONVIF) приходят")
                    Log.i(TAG, "Подписка на события ONVIF открыта")
                    backoff = 5_000L
                    var renewed = System.currentTimeMillis()
                    while (running) {
                        val started = System.nanoTime()
                        apply(tileId, Onvif.pull(sub))
                        if (System.currentTimeMillis() - renewed > RENEW_MS) { Onvif.renew(sub); renewed = System.currentTimeMillis() }
                        val left = MIN_PULL_MS - (System.nanoTime() - started) / 1_000_000
                        if (left > 0) sleep(left)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Onvif.OnvifException) {
                    statuses[tileId] = Status(false, e.message.orEmpty())
                    Log.w(TAG, "События ONVIF: ${e.message}")
                    active.remove(tileId)
                    pause = if (e.auth) 300_000 else backoff
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } catch (e: Exception) {
                    statuses[tileId] = Status(false, "Камера не отвечает — события по ONVIF не приходят")
                    Log.w(TAG, "События ONVIF: ${e.javaClass.simpleName}")
                    active.remove(tileId)
                    pause = backoff
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } finally {
                    // И после сбоя: иначе каждая новая попытка оставляла на камере ещё одну подписку.
                    sub?.let { s -> Thread { Onvif.unsubscribe(s) }.apply { isDaemon = true }.start() }
                }
                if (pause > 0) sleep(pause)
            }
        }

        private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false }
    }
}
