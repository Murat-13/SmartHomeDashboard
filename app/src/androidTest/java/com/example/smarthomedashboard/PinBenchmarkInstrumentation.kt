package com.example.smarthomedashboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** Measures artificial data only; never opens preferences, application screens or network. */
class PinBenchmarkInstrumentation : Instrumentation() {
    private var compatibility = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        compatibility = Build.VERSION.SDK_INT < 26 || arguments?.getString("legacy") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val input = "135790".toCharArray()
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            val verifier = PinVerifier()
            val start = SystemClock.elapsedRealtimeNanos()
            val record = verifier.create(input, PinVerifier.Purpose.PIN, legacyApi24 = compatibility)
            val created = SystemClock.elapsedRealtimeNanos()
            check(verifier.verify(input, record.encoded, PinVerifier.Purpose.PIN) == PinVerifier.Verdict.MATCH)
            val verified = SystemClock.elapsedRealtimeNanos()
            result.putInt("sdk", Build.VERSION.SDK_INT)
            result.putBoolean("compatibilityAlgorithm", compatibility)
            result.putLong("createMillis", (created - start) / 1_000_000)
            result.putLong("verifyMillis", (verified - created) / 1_000_000)
            result.putBoolean("passed", true)
            finish(Activity.RESULT_OK, result)
        } catch (_: Exception) {
            result.putBoolean("passed", false)
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            input.fill(' ')
        }
    }
}
