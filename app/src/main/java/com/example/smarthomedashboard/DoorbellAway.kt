package com.example.smarthomedashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Звонок без хозяина (docs/ring-away-tz.md): уведомление на телефон через `notify.mobile_app_…` и автоответ своей
 * фразой, если к звонку на этом планшете никто не подошёл. Обе настройки — только этого планшета
 * (`doorbell_away.xml`, не в плитке и не в резервной копии): иначе уведомление и фраза шли бы с каждого планшета.
 * Главный поток.
 */
internal object DoorbellAway {
    const val PREFS = "doorbell_away"
    const val DEFAULT_DELAY_S = 20
    val DELAY_RANGE = 5..120
    /** После автоответа следующий — не раньше чем через минуту (звонят подряд — фраза не повторяется на каждый). */
    const val REPEAT_MS = 60_000L
    private const val TAG = "DoorbellAway"

    data class Settings(val notifyService: String = "", val autoReply: Boolean = false, val phraseId: String = "",
                        val delaySeconds: Int = DEFAULT_DELAY_S)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context, tileId: String): Settings {
        val p = prefs(context)
        return Settings(
            notifyService = p.getString("$tileId.notify", "").orEmpty().takeIf { isNotifyService(it) }.orEmpty(),
            autoReply = p.getBoolean("$tileId.auto", false),
            phraseId = p.getString("$tileId.phrase", "").orEmpty(),
            delaySeconds = p.getInt("$tileId.delay", DEFAULT_DELAY_S).coerceIn(DELAY_RANGE)
        )
    }

    fun save(context: Context, tileId: String, s: Settings) {
        prefs(context).edit()
            .putString("$tileId.notify", s.notifyService.takeIf { isNotifyService(it) }.orEmpty())
            .putBoolean("$tileId.auto", s.autoReply)
            .putString("$tileId.phrase", s.phraseId)
            .putInt("$tileId.delay", s.delaySeconds.coerceIn(DELAY_RANGE))
            .apply()
    }

    /**
     * Фраза автоответа: выбранная, а если её нет (не выбирали или удалили) — первая записанная. Иначе автоответ при
     * включённом переключателе молчал бы (найдено на планшете 19.11.2026: «Фраза: выберите»).
     */
    fun phrase(context: Context, tileId: String): DoorbellPhrases.Phrase? {
        val ready = DoorbellPhrases.ready(context, tileId)
        return ready.firstOrNull { it.id == settings(context, tileId).phraseId } ?: ready.firstOrNull()
    }

    /** Автоответ готов на этом планшете: включён и есть записанная фраза. */
    fun autoReplyReady(context: Context, tileId: String) = settings(context, tileId).autoReply && phrase(context, tileId) != null

    /** Есть записанная фраза (кнопка «Автоответ» в раскрытом домофоне видна). */
    fun autoReplyConfigured(context: Context, tileId: String) = phrase(context, tileId) != null

    fun isNotifyService(name: String) = name.matches(Regex("^notify\\.[a-z0-9_]{1,80}$"))

    /** Службы уведомлений телефонов из ответа `get_services`: `notify.mobile_app_*`. */
    fun phoneServices(services: Any?): List<String> {
        val notify = (services as? JSONObject)?.optJSONObject("notify") ?: return emptyList()
        return notify.keys().asSequence().filter { it.startsWith("mobile_app_") }.map { "notify.$it" }.sorted().toList()
    }

    /** Поля вызова `notify`: заголовок, текст, снимок камеры из HA (если камера задана), канал звонков. */
    fun notification(title: String, cameraEntity: String, atMs: Long, tileId: String): Map<String, Any> {
        val data = JSONObject()
            .put("tag", "doorbell_$tileId")
            .put("channel", "Домофон")
            .put("importance", "high")
            .put("priority", "high")
            .put("ttl", 0)
        if (cameraEntity.startsWith("camera.")) data.put("image", "/api/camera_proxy/$cameraEntity")
        // iPhone (у владельца): звук и «срочное» — звонок проходит через режим «Фокус». Android эти поля пропускает.
        data.put("push", JSONObject().put("sound", "default").put("interruption-level", "time-sensitive"))
        return mapOf(
            "title" to title.ifBlank { DoorbellConfig.DEFAULT_TITLE },
            "message" to "Звонят в калитку · " + SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(atMs)),
            "data" to data
        )
    }

    // ---- Звонок ----

    /** Отправка `notify` (domain, service, data) → принята ли HA. Подменяется в тестах. */
    internal var sender: (String, String, Map<String, Any>, (Boolean) -> Unit) -> Unit = { domain, service, data, done ->
        val sent = DoorbellHub.actions?.call(domain, service, "", data) { done(it == ServiceCommandResult.ACKNOWLEDGED) } == true
        if (!sent) done(false)
    }

    /** Сказать фразу в домофон (pcm 8 кГц). Подменяется в тестах. */
    internal var speaker: (Context, DoorbellConfig, ShortArray) -> Unit = { context, config, pcm ->
        val talk = DoorbellTalk(context.applicationContext)
        talk.prepare(config.talkRtsp, config.rtspUser)
        talk.play(pcm)
        // Сессия закрывается после фразы (подключение ≤ 4 с, сама фраза ≤ 10 с).
        main.postDelayed({ talk.dispose() }, pcm.size / 8L + 15_000L)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()
    private val lastReply = mutableMapOf<String, Long>()
    internal var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** Звонок (из HA или вебхука, уже без дублей): уведомление и отсчёт автоответа. */
    fun onRing(context: Context, tileId: String, title: String, config: DoorbellConfig, atMs: Long = System.currentTimeMillis()) {
        val s = settings(context, tileId)
        if (s.notifyService.isNotEmpty()) {
            val domain = s.notifyService.substringBefore('.')
            val service = s.notifyService.substringAfter('.')
            sender(domain, service, notification(title, config.camera, atMs, tileId)) { ok ->
                Log.i(TAG, if (ok) "Уведомление о звонке отправлено" else "Уведомление о звонке не отправлено")
            }
        }
        if (!s.autoReply || !config.canTalk) return
        val app = context.applicationContext
        pending.remove(tileId)?.let(main::removeCallbacks)
        val task = Runnable {
            pending.remove(tileId)
            val now = clock()
            if (lastReply[tileId]?.let { now - it < REPEAT_MS } == true) { Log.i(TAG, "Автоответ был меньше минуты назад — пропущен"); return@Runnable }
            val current = settings(app, tileId)
            val pcm = if (current.autoReply) phrase(app, tileId)?.let { DoorbellPhrases.load(app, tileId, it.id) } else null
            if (pcm == null) { Log.i(TAG, "Автоответ: фраза не записана или выключен"); return@Runnable }
            lastReply[tileId] = now
            Log.i(TAG, "Автоответ: к звонку не подошли за ${current.delaySeconds} с — фраза в домофон")
            speaker(app, DoorbellHub.config(tileId) ?: config, pcm)
        }
        pending[tileId] = task
        main.postDelayed(task, s.delaySeconds * 1000L)
    }

    /** К звонку подошли (окно звонка, раскрытая плитка, калитка): автоответ не нужен. */
    fun answered(tileId: String) {
        pending.remove(tileId)?.let { main.removeCallbacks(it); Log.i(TAG, "Автоответ отменён: к звонку подошли") }
    }

    fun waiting(tileId: String) = tileId in pending

    internal fun reset() {
        pending.values.forEach(main::removeCallbacks)
        pending.clear()
        lastReply.clear()
    }
}
