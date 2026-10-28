package com.example.smarthomedashboard

/** Only process memory: no persisted authorization can survive a process restart. */
internal class PinSession(private val elapsed: () -> Long) {
    private var grantedAt: Long? = null

    @Synchronized
    fun grant() {
        grantedAt = elapsed()
    }

    /** Only an already authenticated, unexpired session can be extended by Settings input/entry. */
    @Synchronized
    fun touch(minutes: Int): Boolean {
        if (!isValid(minutes)) return false
        grantedAt = elapsed()
        return true
    }

    @Synchronized
    fun revoke() {
        grantedAt = null
    }

    @Synchronized
    fun isValid(minutes: Int): Boolean {
        val start = grantedAt ?: return false
        val now = elapsed()
        if (minutes <= 0 || now < start) {
            grantedAt = null
            return false
        }
        if (now - start >= minutes.toLong() * 60_000L) {
            grantedAt = null
            return false
        }
        return true
    }
}
