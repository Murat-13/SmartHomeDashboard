package com.example.smarthomedashboard

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * «Панель сейчас занята» — для того, что закрывает приложение само (ночная установка обновления). Решение владельца 25.11.2026:
 * установка только когда свободно — не при разговоре, звонке, команде проходу, открытом архиве и касаниях за последние 10 минут;
 * иначе следующей ночью (второй аудит, AUD-24).
 */
internal object PanelBusy {
    const val QUIET_MS = 10 * 60_000L
    /** Забытый разговор (экран уничтожен без «конца») не держит установку вечно. */
    private const val TALK_MAX_MS = 15 * 60_000L

    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile private var touchedAt = 0L
    @Volatile private var everTouched = false
    private val talks = ConcurrentHashMap<Any, Long>()

    /** Касание панели или экрана поверх неё. */
    fun touched() { touchedAt = clock(); everTouched = true }

    /** Разговор через домофон или камеру начался / закончился. */
    fun talk(owner: Any, on: Boolean) {
        if (on) { talks[owner] = clock(); return }
        if (talks.remove(owner) != null && talks.isEmpty()) onTalkEnd?.invoke()
    }

    /** Сейчас идёт разговор (забытый — не дольше 15 минут). */
    val talking: Boolean get() { val now = clock(); talks.values.removeAll { now - it > TALK_MAX_MS }; return talks.isNotEmpty() }

    /** Последний разговор закончился (поток того, кто его закончил). */
    @Volatile var onTalkEnd: (() -> Unit)? = null

    /** Чем занята панель; null — свободна. Читает состояние экранов — вызывать в главном потоке. */
    fun reason(): String? {
        val now = clock()
        return when {
            talking -> "идёт разговор"
            DoorbellHub.RingUi.current?.isShowing == true -> "открыто окно звонка"
            GateHub.commandRunning -> "идёт команда проходу"
            PanelIdle.showing is ArchiveActivity -> "открыт архив"
            everTouched && now - touchedAt < QUIET_MS -> "панели недавно касались"
            else -> null
        }
    }

    internal fun resetForTests() { talks.clear(); everTouched = false; touchedAt = 0L; clock = { SystemClock.elapsedRealtime() } }
}
