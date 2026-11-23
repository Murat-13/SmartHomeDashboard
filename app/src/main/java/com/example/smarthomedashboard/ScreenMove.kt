package com.example.smarthomedashboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Поворот экрана без переподключения видео и без обрыва разговора (решение владельца 23.11.2026: «при переворачивании…
 * плитка домофон в раскрытом виде переподключается… переделай всё, что необходимо»).
 *
 * При повороте Android строит экран панели заново. Раньше вместе со старым экраном закрывалось всё, что на нём было:
 * плеер плитки и раскрытой карточки (новые подключались заново — секунда «Подключение…»), разговор («Завершить» без
 * нажатия), окно звонка (после поворота не возвращалось). Теперь карточки домофона и камер и окно звонка — те же самые
 * объекты до и после поворота: старый экран отдаёт их сюда, новый забирает и ставит на своё место. Плееры, канал
 * голоса, таймеры, увеличение видео — всё продолжает работать, пока карточка в пути.
 *
 * Чтобы вид мог сменить экран, он создаётся с контекстом [Movable.context]: у обычного вида контекст — его экран
 * навсегда (старый экран остался бы в памяти, а окна PIN открывались бы на закрытом экране).
 */
internal object Movable {
    /** Контекст для вида, который переживает пересоздание экрана. */
    fun context(activity: Activity): Context = MutableContextWrapper(activity)

    /** Вид переехал на экран [activity]: его контекст теперь ведёт туда. Обычный вид — без изменений. */
    fun rebase(view: View, activity: Activity) {
        (view.context as? MutableContextWrapper)?.baseContext = activity
    }

    /** Экран, на котором сейчас живёт вид с этим контекстом (для запроса разрешений и окон). */
    fun activityOf(context: Context?): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * Карточки домофона и камер в пути между старым и новым экраном. Только главный поток. Кого новый экран не забрал за
 * [HOLD_MS] (плитки больше нет на этой странице, экран закрыли), тот закрывается как обычно: плеер и разговор не
 * остаются работать без экрана.
 */
internal object DoorbellMove {
    private val main = Handler(Looper.getMainLooper())
    private val compact = HashMap<String, DoorbellCard>()
    private var expanded: Pair<String, DoorbellCard>? = null
    private val expire = Runnable { clear("новый экран их не забрал") }

    val waiting get() = compact.size + if (expanded != null) 1 else 0

    /** Старый экран отдаёт карточку плитки [tileId]: она снимается с экрана, но не закрывается. */
    fun park(tileId: String, card: DoorbellCard) {
        card.prepareMove()
        (card.parent as? ViewGroup)?.removeView(card)
        if (card.expanded) {
            expanded?.second?.takeIf { it !== card }?.dispose()
            expanded = tileId to card
        } else {
            compact.put(tileId, card)?.takeIf { it !== card }?.dispose()
        }
        main.removeCallbacks(expire)
        main.postDelayed(expire, HOLD_MS)
    }

    /** Новый экран забирает карточку плитки [tileId]; null — её нет (обычный запуск, другая плитка). */
    fun take(tileId: String, expanded: Boolean, camera: Boolean, activity: Activity): DoorbellCard? {
        val card = if (expanded) this.expanded?.takeIf { it.first == tileId }?.second?.also { this.expanded = null }
            else compact.remove(tileId)
        if (waiting == 0) main.removeCallbacks(expire)
        card ?: return null
        if (card.camera != camera) { card.dispose(); return null }
        (card.parent as? ViewGroup)?.removeView(card)
        Movable.rebase(card, activity)
        return card
    }

    /**
     * Панель построена: плитки, которые она не взяла (плитку удалили, она на другой странице), закрываются сразу.
     * Раскрытая карточка ждёт дальше — после поворота панель раскрывает её чуть позже, после первых кадров.
     */
    fun closeUnusedTiles() {
        if (compact.isEmpty()) return
        val left = compact.values.toList()
        compact.clear()
        if (expanded == null) main.removeCallbacks(expire)
        left.forEach { it.dispose() }
    }

    /** Закрыть всех, кто ждёт (в том числе из тестов). */
    fun clear(reason: String = "") {
        main.removeCallbacks(expire)
        val left = compact.values.toList() + listOfNotNull(expanded?.second)
        compact.clear()
        expanded = null
        if (left.isNotEmpty() && reason.isNotEmpty()) Log.i(TAG, "Карточки видео после поворота закрыты (${left.size}): $reason")
        left.forEach { it.dispose() }
    }

    private const val TAG = "DoorbellMove"
    /** Сколько карточка ждёт новый экран: поворот занимает доли секунды, запас — на медленный планшет. */
    const val HOLD_MS = 5_000L
}
