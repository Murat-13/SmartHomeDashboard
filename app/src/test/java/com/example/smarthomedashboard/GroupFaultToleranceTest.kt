package com.example.smarthomedashboard

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * ТЗ «Отказоустойчивое групповое управление» (18.11.2026, docs/group-fault-tolerance.md): одно молчащее реле
 * не мешает управлять остальными, повторные нажатия переворачивают цель группы, ожидание и «не подтверждено» —
 * только у проблемного реле, после восстановления — не больше одной догоняющей команды к последней цели.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GroupFaultToleranceTest {
    private val state = DashboardDeviceState()
    private val sent = mutableListOf<Pair<String, String>>()   // сервис, entity_id
    private val callbacks = mutableListOf<(ServiceCommandResult) -> Unit>()
    private val errors = mutableListOf<String>()
    private var online = true
    /** false — транспорт не принял запрос (нет связи): команда не уходит в HA. */
    private var transport = true
    private val commands = DashboardDeviceCommands(state, { _, service, id, callback ->
        if (transport) {
            sent.add(service to id)
            callbacks.add(callback)
        }
        transport
    }, {}, error = { errors.add(it) }, connected = { online })

    private val five = listOf("switch.r1", "switch.r2", "switch.r3", "switch.r4", "switch.far")
    private val healthy = five.dropLast(1)

    @After fun cleanup() = commands.disconnected()

    /** Событие state_changed от HA. */
    private fun event(id: String, value: String) {
        state.entityChanged(id, value, null)
        commands.entityChanged(id)
    }

    /** Строка снимка get_states после (пере)подключения. */
    private fun snapshot(id: String, value: String) {
        state.entityChanged(id, value, null)
        commands.entityChanged(id, fromEvent = false)
    }

    private fun all(value: String, ids: List<String> = five) = ids.forEach { event(it, value) }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun tap() = commands.toggleGroup("g", five)
    private fun sentSince(from: Int) = sent.drop(from)

    @Test fun allMembersRespondImmediately() {
        all("off")
        tap()
        assertEquals("Команды всем пятерым в одном цикле нажатия", five.map { "turn_on" to it }, sent)
        all("on")
        assertTrue(commands.pendingCommands.isEmpty())
        assertNull("Все подтвердили: группа в покое", commands.groupTarget("g"))
        assertTrue(errors.isEmpty())
    }

    @Test fun oneSilentRelayDoesNotBlockTheOtherFourOnAnyTap() {
        all("off")
        repeat(4) { round ->
            val from = sent.size
            tap()
            val target = if (round % 2 == 0) "on" else "off"
            assertEquals("Нажатие $round принято и отправлено всем", five.map { "turn_$target" to it }, sentSince(from))
            all(target, healthy)
            assertEquals(listOf("switch.far"), commands.pendingCommands.keys.toList())
            // Цель «on» ещё не подтверждена молчащим реле (его старый факт — off): родитель показывает цель.
            if (target == "on") assertEquals("on", commands.groupTarget("g"))
        }
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        assertTrue(commands.pendingCommands.isEmpty())
        assertEquals("Не подтверждено только у молчащего", ConfirmedState.UNAVAILABLE, state.confirmedState("switch.far"))
        healthy.forEach { assertEquals(ConfirmedState.OFF, state.confirmedState(it)) }
        assertTrue("Без общей плашки ошибки", errors.isEmpty())
    }

    @Test fun stuckOnRelayDoesNotRepeatTheOffCommand() {
        all("on")
        tap()
        assertTrue(sent.all { it.first == "turn_off" })
        all("off", healthy)   // switch.far застряло в on
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        tap()
        assertEquals("Следующее нажатие включает, а не повторяет «выключить» из-за застрявшего on",
            five.map { "turn_on" to it }, sentSince(from))
    }

    @Test fun firstTapWithUnknownAndUnavailableMembersUsesKnownStates() {
        all("off", five.take(3))
        event("switch.r4", "unknown")
        event("switch.far", "unavailable")
        tap()
        assertEquals(five.map { "turn_on" to it }, sent)
        assertEquals("unknown не записан как off", "unknown", state.singleStates["switch.r4"])
        all("on", five.take(3))
        assertEquals(setOf("switch.r4", "switch.far"), commands.pendingCommands.keys)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        assertEquals(5, sent.size)   // никаких скрытых повторов
    }

    @Test fun acceptedCallWithoutStateMarksOnlyThatEntityAfterFiveSeconds() {
        all("off")
        tap()
        callbacks.forEach { it(ServiceCommandResult.ACKNOWLEDGED) }
        all("on", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS - 200)
        assertNotNull("До 5 с ещё ждём", commands.pendingFor("switch.far"))
        advance(300)
        assertNull(commands.pendingFor("switch.far"))
        assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState("switch.far"))
        healthy.forEach { assertEquals(ConfirmedState.ON, state.confirmedState(it)) }
    }

    @Test fun lateEventsAfterFastTapsDoNotOverwriteTheNewTarget() {
        all("off")
        tap()            // on
        tap()            // off
        tap()            // on
        assertEquals("on", commands.groupTarget("g"))
        all("off")       // запоздалые события второго нажатия
        five.forEach { assertEquals("on", commands.pendingFor(it)?.expectedState) }
        all("on")
        assertTrue(commands.pendingCommands.isEmpty())
        assertNull(commands.groupTarget("g"))
    }

    @Test fun recoveredRelayInTargetStateGetsNoCommand() {
        all("off")
        tap()
        all("on", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        advance(30_000)
        event("switch.far", "on")
        assertTrue(sentSince(from).isEmpty())
        assertNull(commands.groupTarget("g"))
    }

    @Test fun recoveredRelayInWrongStateGetsExactlyOneCorrection() {
        all("on")
        tap()                          // цель off
        all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        advance(30_000)
        event("switch.far", "on")      // реле вернулось на связь и сообщило старое on
        assertEquals(listOf("turn_off" to "switch.far"), sentSince(from))
        // Не ответило и на корректировку: больше ничего не шлём.
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        event("switch.far", "on")
        event("switch.far", "on")
        assertEquals(1, sentSince(from).size)
    }

    @Test fun manyTapsWhileRelayAbsentReplayNothingButOneCorrectionToLastTarget() {
        all("off")
        event("switch.far", "unavailable")
        repeat(4) { round ->
            tap()                                   // on, off, on, off
            all(if (round % 2 == 0) "on" else "off", healthy)
        }
        tap()                                       // последняя цель — on
        all("on", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        event("switch.far", "off")                  // вернулось
        assertEquals("Только одна команда к последней цели", listOf("turn_on" to "switch.far"), sentSince(from))
    }

    @Test fun noLateCorrectionAfterWindowRestartOrIndividualControl() {
        // Окно 5 минут истекло.
        all("on"); tap(); all("off", healthy)
        advance(DashboardDeviceCommands.CORRECTION_WINDOW_MS + 1_000)
        var from = sent.size
        event("switch.far", "on")
        assertTrue(sentSince(from).isEmpty())
        // Индивидуальное управление реле отменяет план группы.
        all("on"); tap(); all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        commands.toggleMember("g", "switch.far")
        event("switch.far", "off")
        from = sent.size
        event("switch.far", "on")
        assertTrue(sentSince(from).isEmpty())
        // Перезапуск приложения: новая память — никаких целей.
        all("on"); tap(); all("off", healthy)
        commands.reset()
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        from = sent.size
        event("switch.far", "on")
        assertTrue(sentSince(from).isEmpty())
    }

    @Test fun snapshotsDisconnectsAndOfflineNeverSendGhostCommands() {
        all("on"); tap(); all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        commands.disconnected()
        snapshot("switch.far", "on")         // get_states после переподключения — не доказательство связи
        assertTrue(sentSince(from).isEmpty())
        online = false
        event("switch.far", "on")            // соединение не авторизовано — тоже нет
        assertTrue(sentSince(from).isEmpty())
        online = true
        event("switch.far", "on")
        assertEquals(listOf("turn_off" to "switch.far"), sentSince(from))
    }

    @Test fun sharedRelayFollowsTheNewestGroupCommand() {
        val other = listOf("switch.far", "switch.x")
        all("on"); event("switch.x", "on")
        tap()                                          // группа g: off
        all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        commands.toggleGroup("h", other)                // группа h (общее реле): off — новее
        assertEquals("off", commands.pendingFor("switch.far")?.expectedState)
        assertEquals("h", commands.pendingFor("switch.far")?.groupId)
        event("switch.x", "off")
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        event("switch.far", "on")
        assertEquals("Одна корректировка от новейшей группы, а не по каждой", 1, sentSince(from).size)
    }

    @Test fun allRelaysUnavailableNeitherFreezesNorFakesConfirmation() {
        all("unavailable")
        tap()
        assertEquals(5, sent.size)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        assertTrue(commands.pendingCommands.isEmpty())
        five.forEach { assertEquals(ConfirmedState.UNAVAILABLE, state.confirmedState(it)) }
        advance(TimeUnit.HOURS.toMillis(1))
        assertEquals("Скрытых повторов нет", 5, sent.size)
        tap()
        assertEquals("Новое нажатие принимается сразу", 10, sent.size)
    }

    @Test fun staleHaStateWithoutEventsIsNotCorrectedEndlessly() {
        all("on"); tap(); all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        repeat(5) { snapshot("switch.far", "on") }   // HA держит старое on, событий от реле нет
        assertTrue(sentSince(from).isEmpty())
        repeat(5) { event("switch.far", "on"); advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50) }
        assertEquals("Не больше одной корректировки", 1, sentSince(from).size)
    }

    // Аудит 18.11 (до выпуска 0.9.20): догоняющая команда — только после тайм-аута РЕАЛЬНО отправленной команды.

    @Test fun rejectedCommandIsNeverCorrected() {
        all("on")
        tap()                                              // цель off
        callbacks[4](ServiceCommandResult.REJECTED)        // HA отказал дальнему реле
        all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        event("switch.far", "on")                          // событие со старым состоянием
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        assertEquals("Отказ HA не повторяется догоняющей командой", five.map { "turn_off" to it }, sent)
    }

    @Test fun commandThatNeverLeftWhileOfflineIsNeverCorrected() {
        all("on")
        transport = false
        tap()                                              // нажали без связи: ничего не ушло
        assertTrue(sent.isEmpty())
        transport = true
        all("off", healthy)                                // остальные выключили, например, из HA
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        event("switch.far", "on")                          // связь есть, реле прислало событие
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        assertEquals("Неотправленное нажатие не досылается автоматически", emptyList<Pair<String, String>>(), sent)
    }

    @Test fun singleTileCommandCancelsGroupPlanForThatRelay() {
        all("on", healthy)
        event("switch.far", "unavailable")
        tap()                                              // цель off; дальнее недоступно и не ответит
        all("off", healthy)
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        val from = sent.size
        assertEquals("on", commands.toggleSingle("switch.far"))   // пользователь включает отдельной плиткой
        event("switch.far", "on")                          // HA подтвердил «вкл»
        advance(DashboardDeviceCommands.GROUP_DEADLINE_MS + 50)
        event("switch.far", "on")
        assertEquals("Группа не спорит с пользователем: без turn_off", listOf("turn_on" to "switch.far"), sentSince(from))
    }

    @Test fun singleTileReplacesPendingGroupCommandOfThatRelay() {
        all("on")
        tap()                                              // цель off, все ждут
        all("off", healthy)
        // Нажатие плитки во время ожидания группы не игнорируется: индивидуальная команда заменяет групповую.
        assertEquals("off", commands.toggleSingle("switch.far"))
        assertNull(commands.pendingFor("switch.far")?.groupId)
        event("switch.far", "off")
        advance(DashboardDeviceCommands.CORRECTION_WINDOW_MS)
        assertEquals(five.map { "turn_off" to it } + ("turn_off" to "switch.far"), sent)
    }
}
