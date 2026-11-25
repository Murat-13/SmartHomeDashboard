package com.example.smarthomedashboard

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Ночные задачи строго ночью (копия настроек 03:00, ночная установка 03:30, копия истории мощности 04:00).
 * Периодическая задача WorkManager ставит следующий запуск через 24 ч от прошлого и со временем уезжает в день
 * (найдено 19.11.2026: на московском планшете задачи шли около 15:10 — копирование роликов и сжатие истории днём
 * нагружали бы планшет, пока им пользуются). Поэтому каждая задача — разовая: в конце работы ставит следующую на
 * своё время следующей ночи. Прежняя периодическая задача с тем же назначением отменяется.
 */
internal object NightlyWork {
    /** Сколько ждать до ближайших [hour]:[minute] (сегодня, если ещё не наступило, иначе завтра). */
    fun delayMs(hour: Int, minute: Int, now: Calendar = Calendar.getInstance()): Long {
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    /** Часы — подменяются в тестах. */
    @Volatile internal var now: () -> Calendar = { Calendar.getInstance() }

    /**
     * Ночное окно 02:00–05:00 (решение владельца 25.11.2026). WorkManager обещает запуск «не раньше»: планшет был выключен в 03:00
     * и включён днём, часы перевели — задача вышла бы днём, а установка обновления закрыла бы панель посреди дня (второй аудит, AUD-18).
     */
    fun isNight(at: Calendar = now()): Boolean = at.get(Calendar.HOUR_OF_DAY) in NIGHT_FROM until NIGHT_TO

    /**
     * Тело ночной задачи: вне ночного окна ничего не делает; следующая ночь ставится всегда — и когда работа упала
     * (раньше исключение рвало цепочку до следующего запуска панели — аудит 25.11.2026, STAB-08).
     */
    inline fun run(name: String, reschedule: () -> Unit, work: () -> Unit) {
        try {
            if (isNight()) work() else android.util.Log.i(TAG, "$name: сейчас не ночь — отложено до следующей ночи")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "$name: ${e.javaClass.simpleName}")
        } finally {
            reschedule()
        }
    }

    const val NIGHT_FROM = 2
    const val NIGHT_TO = 5
    const val TAG = "NightlyWork"

    /**
     * Поставить задачу [worker] на ближайшие [hour]:[minute]. [legacy] — имя прежней периодической задачи (отменяется).
     * [fromWorker] — вызов из самой задачи в конце работы: следующая встаёт в цепочку после текущей; при запуске
     * приложения (false) уже поставленная задача не трогается.
     */
    fun schedule(context: Context, name: String, legacy: String, worker: Class<out ListenableWorker>, hour: Int, minute: Int,
                 fromWorker: Boolean = false) {
        try {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(legacy)
            val request = OneTimeWorkRequest.Builder(worker).setInitialDelay(delayMs(hour, minute), TimeUnit.MILLISECONDS).build()
            manager.enqueueUniqueWork(name, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        } catch (_: IllegalStateException) {
            // WorkManager не инициализирован (тестовое окружение без манифеста): задачи не планируются.
        }
    }
}
