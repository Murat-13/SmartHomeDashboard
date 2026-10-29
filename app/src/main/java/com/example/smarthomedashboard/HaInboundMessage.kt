package com.example.smarthomedashboard

import org.json.JSONObject
import java.util.concurrent.Semaphore

/** Подготовленное полное состояние одной сущности; принадлежит одному входящему сообщению. */
data class HaEntityState(val entityId: String, val state: String, val attributes: JSONObject)

/** Разбирается на последовательном потоке чтения OkHttp, до передачи на главный поток. */
internal data class HaInboundMessage(
    val json: JSONObject,
    val states: List<HaEntityState>,
    val entities: List<HaEntity>
) {
    companion object {
        fun parse(text: String, isCurrent: () -> Boolean = { true }): HaInboundMessage? {
            if (!isCurrent()) return null
            val json = try { JSONObject(text) } catch (_: Exception) { return null }
            val states = ArrayList<HaEntityState>()
            val entities = ArrayList<HaEntity>()
            // Назначение result проверяет владелец по ID; форма массива сама по себе не маршрут.
            val array = if (json.optString("type") == "result" && json.optBoolean("success", true)) {
                json.optJSONArray("result")
            } else null
            if (array != null) for (i in 0 until array.length()) {
                if (!isCurrent()) return null
                val item = array.optJSONObject(i) ?: continue
                val id = (item.opt("entity_id") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val attrs = item.optJSONObject("attributes") ?: JSONObject()
                val state = item.opt("state") as? String
                if (state != null) states.add(HaEntityState(id, state, attrs))
                entities.add(HaEntity(id, displayName(id, item, attrs), id.substringBefore(".")))
            }
            return if (isCurrent()) HaInboundMessage(json, states, entities) else null
        }
    }
}

/** Не более [capacity] доставок в Handler; значимые события не объединяются и не отбрасываются. */
internal class HaMessageGate(capacity: Int = 32) {
    private val slots = Semaphore(capacity)
    @Volatile private var cancelled = false

    // Вызывается только последовательным сетевым reader OkHttp, никогда UI-потоком.
    fun acquire(): Boolean {
        if (cancelled) return false
        try {
            slots.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (cancelled) {
            slots.release()
            return false
        }
        return true
    }

    fun release() = slots.release()

    fun cancel() {
        cancelled = true
        // У каждого соединения один reader; освобождаем его, если он ждёт свободное место.
        slots.release()
    }
}
