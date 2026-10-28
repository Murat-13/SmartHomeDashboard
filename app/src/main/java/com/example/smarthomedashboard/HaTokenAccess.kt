package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** Только HA. Все чтения legacy, миграция и явная замена выполняются на worker под одним lock. */
internal class HaTokenAccess(
    private val secure: HaTokenSlot,
    private val legacy: LegacyHaToken,
    private val checkWorker: () -> Unit
) {
    enum class Status { READY, ABSENT, SECURE_ERROR, WRITE_FAILED, VERIFY_FAILED, CLEANUP_FAILED, LEGACY_ERROR, STALE, EMPTY_INPUT }
    class Result internal constructor(
        val status: Status, val value: String? = null,
        val secureStatus: SecretStore.Status? = null, val revision: Long = REVISION.get()
    ) {
        override fun toString(): String = "HaTokenResult(status=$status, secureStatus=$secureStatus)"
        val message: String get() = when (status) {
            Status.READY -> "Токен HA сохранён"
            Status.ABSENT -> "Токен HA отсутствует. Введите его в настройках"
            Status.SECURE_ERROR -> "Защищённый токен HA недоступен. Автоматическое восстановление не реализовано"
            Status.WRITE_FAILED -> "Не удалось сохранить токен HA. Перенос не завершён"
            Status.VERIFY_FAILED -> "Не удалось проверить сохранение токена HA. Перенос не завершён"
            Status.CLEANUP_FAILED -> "Не удалось подтвердить удаление открытой копии HA. Повторите попытку"
            Status.LEGACY_ERROR -> "Не удалось прочитать прежний токен HA. Повторите попытку"
            Status.STALE -> "Токен HA изменён другим действием. Повторите попытку"
            Status.EMPTY_INPUT -> "Введите новый токен HA"
        }
    }

    fun read(): Result = serialized {
        val saved = secure.read()
        when (saved.status) {
            SecretStore.Status.SUCCESS -> {
                val value = saved.value ?: return@serialized Result(Status.VERIFY_FAILED)
                when (legacy.phase()) {
                    "migration" -> {
                        if (legacy.read() != value) return@serialized Result(Status.VERIFY_FAILED)
                        if (!legacy.mark("verified")) return@serialized Result(Status.CLEANUP_FAILED)
                    }
                    // После прерванной замены исходный ввод уже неизвестен. Нужен явный повтор ввода,
                    // а не догадка о том, что непроверенная запись подтверждена.
                    "replacement" -> return@serialized Result(Status.VERIFY_FAILED)
                    null, "verified" -> Unit
                    else -> return@serialized Result(Status.VERIFY_FAILED)
                }
                finish(value)
            }
            SecretStore.Status.ABSENT -> {
                val old = legacy.read()
                if (old.isNullOrBlank()) Result(Status.ABSENT) else storeAndVerify(old, "migration")
            }
            else -> Result(Status.SECURE_ERROR, secureStatus = saved.status)
        }
    }

    fun replace(value: String, expectedRevision: Long = revision): Result = serialized {
        if (expectedRevision != revision) return@serialized Result(Status.STALE)
        if (value.isBlank()) return@serialized Result(Status.EMPTY_INPUT)
        val current = secure.read()
        if (current.status != SecretStore.Status.SUCCESS && current.status != SecretStore.Status.ABSENT) {
            return@serialized Result(Status.SECURE_ERROR, secureStatus = current.status)
        }
        // Проверяем доступность legacy до изменения secure. Единственную старую копию не стираем.
        legacy.read()
        storeAndVerify(value, "replacement")
    }

    private fun storeAndVerify(value: String, phase: String): Result {
        val previous = legacy.phase()
        // Журнал содержит только фазу, не токен и не его отпечаток. commit предшествует secure write.
        if (!legacy.mark(phase)) return Result(Status.CLEANUP_FAILED)
        val status = secure.write(value)
        if (status != SecretStore.Status.SUCCESS) {
            if (!legacy.mark(previous)) return Result(Status.CLEANUP_FAILED)
            return Result(Status.WRITE_FAILED, secureStatus = status)
        }
        REVISION.incrementAndGet()
        val verified = secure.read()
        if (verified.status != SecretStore.Status.SUCCESS || verified.value != value) {
            return Result(Status.VERIFY_FAILED, secureStatus = verified.status)
        }
        if (!legacy.mark("verified")) return Result(Status.CLEANUP_FAILED)
        return finish(value)
    }

    private fun finish(value: String): Result {
        val old = legacy.read()
        // Даже при отсутствии ключа повторный commit подтверждает уборку после прежнего commit=false:
        // SharedPreferences мог уже удалить ключ из памяти, не записав удаление на диск.
        if (!legacy.removeIfUnchanged(old)) return Result(Status.CLEANUP_FAILED)
        return Result(Status.READY, value)
    }

    private fun serialized(block: () -> Result): Result {
        checkWorker()
        return synchronized(LOCK) {
            try { block() } catch (_: LegacyTokenException) { Result(Status.LEGACY_ERROR) }
        }
    }

    companion object {
        private val LOCK = Any()
        private val REVISION = AtomicLong()
        val revision: Long get() = REVISION.get()
        fun isCurrent(result: Result): Boolean = result.revision == revision

        fun create(context: Context): HaTokenAccess {
            val app = context.applicationContext
            val store = AndroidSecretStore.create(app)
            return HaTokenAccess(object : HaTokenSlot {
                override fun read() = store.read(SecretStore.Slot.HA_TOKEN)
                override fun write(value: String) = store.write(SecretStore.Slot.HA_TOKEN, value)
            }, PreferenceLegacyHaToken { app.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE) },
                { check(Looper.myLooper() != Looper.getMainLooper()) { "HA token requires a worker thread" } })
        }
    }
}

internal interface HaTokenSlot {
    fun read(): SecretStore.ReadResult
    fun write(value: String): SecretStore.Status
}
internal interface LegacyHaToken {
    fun read(): String?
    fun phase(): String?
    fun mark(phase: String?): Boolean
    fun removeIfUnchanged(expected: String?): Boolean
}
internal class LegacyTokenException : RuntimeException("Legacy HA token unavailable")

internal class PreferenceLegacyHaToken(
    private val preferences: () -> android.content.SharedPreferences
) : LegacyHaToken {
    override fun read(): String? = try {
        preferences().getString("ha_token", null)
    } catch (_: ClassCastException) { throw LegacyTokenException()
    } catch (_: SecurityException) { throw LegacyTokenException() }

    override fun phase(): String? = try {
        preferences().getString("ha_token_transfer_phase", null)
    } catch (_: ClassCastException) { throw LegacyTokenException()
    } catch (_: SecurityException) { throw LegacyTokenException() }

    override fun mark(phase: String?): Boolean = try {
        preferences().edit().putString("ha_token_transfer_phase", phase).commit()
    } catch (_: SecurityException) { false }

    override fun removeIfUnchanged(expected: String?): Boolean = try {
        val prefs = preferences()
        if (prefs.getString("ha_token", null) != expected) false
        else prefs.edit().remove("ha_token").remove("ha_token_transfer_phase").commit() && !prefs.contains("ha_token")
    } catch (_: ClassCastException) { false
    } catch (_: SecurityException) { false }
}
