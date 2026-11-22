package com.example.smarthomedashboard

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Кнопки «Открыть» в уведомлении о звонке на телефоне (docs/gate-tz.md, решение 5).
 *
 * Уведомление шлёт эта панель ([DoorbellAway.onRing]); в него — кнопка для каждого прохода плитки, у которого включено
 * «можно с телефона». Нажатие на телефоне приходит в Home Assistant событием `mobile_app_notification_action`, панель,
 * отправившая уведомление, исполняет его и шлёт «Калитку открыли». Защита: в действии — одноразовый случайный ключ
 * этого уведомления; исполняется только свежий ключ (не старше [TTL_MS]) и только один раз. Другие панели этого ключа
 * не знают и нажатие пропускают.
 */
internal object GatePhone {
    private const val TAG = "Gates"
    const val PREFIX = "SHD_GATE_"
    /** Сколько живёт кнопка: звонок давно прошёл — открывать уже некому. */
    const val TTL_MS = 5 * 60_000L

    private data class Issued(val gateId: String, val action: Gate.Action, val tileId: String, val service: String, val at: Long)

    private val random = SecureRandom()
    private val issued = HashMap<String, Issued>()
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /**
     * Кнопки для уведомления о звонке плитки [tileId], отправленного в [service]. Пусто — у проходов плитки телефон не
     * разрешён. Главный поток.
     */
    fun actions(tileId: String, config: DoorbellConfig, service: String): JSONArray {
        val now = clock()
        issued.entries.removeAll { now - it.value.at > TTL_MS }
        val array = JSONArray()
        val context = GateHub.context ?: return array
        val gates = config.gateIds(tileId).mapNotNull { GateHub.gate(it, context) }.filter { it.ready && it.phone }
        for (gate in gates) {
            val actions = GateHub.actions(gate)
            val action = actions.firstOrNull { it == Gate.Action.OPEN } ?: actions.firstOrNull { it == Gate.Action.STEP } ?: continue
            val key = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            issued[key] = Issued(gate.id, action, tileId, service, now)
            array.put(JSONObject().put("action", PREFIX + key).put("title", GateLogic.label(gate, action, GateHub.status(gate))))
        }
        return array
    }

    /** Нажатие кнопки в уведомлении (событие Home Assistant). true — наше и исполнено. Главный поток. */
    fun onAction(action: String): Boolean {
        if (!action.startsWith(PREFIX)) return false
        val key = action.removePrefix(PREFIX)
        // Один раз: ключ снимается сразу, повтор (двойное нажатие, повтор события) ничего не делает.
        val ticket = issued.remove(key) ?: run { Log.i(TAG, "Кнопка с телефона: ключ неизвестен или уже использован"); return false }
        if (clock() - ticket.at > TTL_MS) { Log.i(TAG, "Кнопка с телефона: ключ устарел"); return false }
        val gate = GateHub.gate(ticket.gateId) ?: return false
        if (!gate.phone || !gate.ready) return false
        Log.i(TAG, "${gate.name}: ${ticket.action.name.lowercase()} — с телефона")
        replyTo[gate.id] = ticket.service
        DoorbellHub.acknowledge(ticket.tileId)
        return GateHub.run(gate, ticket.action, GateLog.Origin.PHONE)
    }

    /** Куда ответить «Калитку открыли» — телефон, с которого нажали. */
    private val replyTo = HashMap<String, String>()

    /** Итог действия с телефона — тем же телефоном (из [GateHub], когда действие закончилось). */
    fun report(gate: Gate, result: String) {
        val service = replyTo.remove(gate.id) ?: return
        DoorbellAway.sender(service.substringBefore('.'), service.substringAfter('.'), mapOf(
            "title" to gate.name, "message" to result.replaceFirstChar { it.uppercase() },
            "data" to JSONObject().put("tag", "gate_${gate.id}").put("channel", "Калитки и ворота")
        )) { ok -> Log.i(TAG, if (ok) "Итог отправлен на телефон" else "Итог на телефон не отправлен") }
    }

    fun reset() {
        issued.clear(); replyTo.clear()
        clock = { SystemClock.uptimeMillis() }
    }
}
