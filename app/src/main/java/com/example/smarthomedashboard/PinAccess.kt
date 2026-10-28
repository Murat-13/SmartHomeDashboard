package com.example.smarthomedashboard

/** Serialized credential transactions. Store contains verifiers only, never entered credentials. */
internal class PinAccess(
    private val storage: Storage,
    private val legacy: Legacy,
    private val verifier: PinVerifier = PinVerifier(),
    private val clock: Clock,
    private val legacyApi24: Boolean = false,
    private val credentialsChanged: () -> Unit = {},
    private val worker: () -> Unit
) {
    class State(val pin: String?, val recovery: String?, val failures: Int = 0,
                val cooldownEnd: Long = 0, val cooldownDuration: Long = 0, val boot: String = "") {
        override fun toString() = "PinState"
    }
    interface Storage { fun read(): State?; fun write(state: State): Boolean }
    interface Legacy { fun read(): String?; fun remove(): Boolean }
    interface Clock { fun elapsed(): Long; fun boot(): String }
    enum class Status { SUCCESS, DISABLED, WRONG, WAIT, INVALID_NEW_PIN, NO_RECOVERY, ERROR }
    class Result(val status: Status, val retryAfterMillis: Long = 0, val recoveryCode: CharArray? = null) {
        override fun toString() = "PinResult(status=$status)"
    }
    fun status(): Result = serial {
        val state=load()
        val delay=remaining(state)
        if(delay>0) Result(Status.WAIT,delay) else Result(if(state.pin==null) Status.DISABLED else Status.SUCCESS)
    }
    fun verify(pin: CharArray): Result = serial {
        val state=load(); val delay=remaining(state)
        if(delay>0) return@serial Result(Status.WAIT,delay)
        if(state.pin==null) return@serial Result(Status.DISABLED)
        when(verifier.verify(pin,state.pin,PinVerifier.Purpose.PIN)) {
            PinVerifier.Verdict.MATCH -> if(storage.write(State(state.pin,state.recovery))) Result(Status.SUCCESS) else Result(Status.ERROR)
            PinVerifier.Verdict.MISMATCH -> failed(state)
            PinVerifier.Verdict.INVALID_RECORD -> Result(Status.ERROR)
        }
    }
    /** Enabling from disabled requires no old PIN. Otherwise always a fresh PIN, never a session token. */
    fun change(current: CharArray, newPin: CharArray): Result = serial {
        if(!PinVerifier.validNewPin(newPin)) return@serial Result(Status.INVALID_NEW_PIN)
        val state=load(); val gate=authorize(state,current,false)
        if(gate!=null) return@serial gate
        replace(newPin)
    }
    fun disable(current: CharArray): Result = serial {
        val state=load();val gate=authorize(state,current,false)
        if(gate!=null) return@serial gate
        if(storage.write(State(null,null))) {credentialsChanged();Result(Status.DISABLED)} else Result(Status.ERROR)
    }
    /** Recovery succeeds only with the replacement PIN and new code committed in the same record. */
    fun recover(code: CharArray,newPin: CharArray): Result = serial {
        if(!PinVerifier.validNewPin(newPin)) return@serial Result(Status.INVALID_NEW_PIN)
        val state=load();val gate=authorize(state,code,true)
        if(gate!=null) return@serial gate
        replace(newPin)
    }
    fun replaceRecovery(current: CharArray): Result = serial {
        val state=load()
        if(state.pin==null) return@serial Result(Status.DISABLED)
        val gate=authorize(state,current,false);if(gate!=null)return@serial gate
        val code=verifier.newRecoveryCode()
        val record=verifier.create(code,PinVerifier.Purpose.RECOVERY,legacyApi24).encoded
        if(storage.write(State(state.pin,record))) {credentialsChanged();Result(Status.SUCCESS,recoveryCode=code)}
        else {code.fill(' ');Result(Status.ERROR)}
    }
    private fun authorize(state: State,input: CharArray,recovery:Boolean):Result? {
        val delay=remaining(state);if(delay>0)return Result(Status.WAIT,delay)
        val record=if(recovery)state.recovery else state.pin
        if(record==null)return if(recovery)Result(Status.NO_RECOVERY) else null
        return when(verifier.verify(input,record,if(recovery)PinVerifier.Purpose.RECOVERY else PinVerifier.Purpose.PIN)) {
            PinVerifier.Verdict.MATCH -> null
            PinVerifier.Verdict.MISMATCH -> failed(state)
            PinVerifier.Verdict.INVALID_RECORD -> Result(Status.ERROR)
        }
    }
    private fun replace(pin:CharArray):Result {
        val pinRecord=verifier.create(pin,PinVerifier.Purpose.PIN,legacyApi24).encoded
        val code=verifier.newRecoveryCode()
        val recoveryRecord=verifier.create(code,PinVerifier.Purpose.RECOVERY,legacyApi24).encoded
        return if(storage.write(State(pinRecord,recoveryRecord))){credentialsChanged();Result(Status.SUCCESS,recoveryCode=code)}
        else {code.fill(' ');Result(Status.ERROR)}
    }
    private fun failed(state:State):Result {
        val failures=(state.failures+1).coerceAtMost(35)
        val delay=if(failures<5)0L else (30_000L shl (failures-5).coerceAtMost(5)).coerceAtMost(900_000L)
        val next=State(state.pin,state.recovery,failures,clock.elapsed()+delay,delay,clock.boot())
        return if(storage.write(next))Result(if(delay>0)Status.WAIT else Status.WRONG,delay) else Result(Status.ERROR)
    }
    private fun remaining(state:State):Long {
        if(state.cooldownDuration==0L)return 0
        if(state.boot!=clock.boot()) {
            // A reboot cannot clear the penalty; restart its bounded duration on the new boot.
            val next=State(state.pin,state.recovery,state.failures,clock.elapsed()+state.cooldownDuration,state.cooldownDuration,clock.boot())
            check(storage.write(next))
            return state.cooldownDuration
        }
        return (state.cooldownEnd-clock.elapsed()).coerceAtLeast(0)
    }
    private fun load():State {
        val existing=storage.read()
        if(existing!=null) {check(legacy.remove());return existing}
        val pin=legacy.read()
        val record=if(pin.isNullOrEmpty())null else {
            val chars=pin.toCharArray()
            try {verifier.create(chars,PinVerifier.Purpose.PIN,legacyApi24).encoded}finally{chars.fill(' ')}
        }
        val state=State(record,null)
        check(storage.write(state))
        val read=checkNotNull(storage.read())
        check(read.pin==state.pin && read.recovery==null)
        check(legacy.remove())
        return read
    }
    private fun serial(action:()->Result):Result {
        worker()
        return synchronized(LOCK) {
            try {action()}catch(_:Exception){Result(Status.ERROR)}
        }
    }
    companion object {private val LOCK=Any()}
}
