package com.example.smarthomedashboard

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Settings are hidden until a current credential decision, including direct internal Intent entry. */
abstract class PinProtectedActivity : AppCompatActivity() {
    private var pinAuthorized = false
    private var pinRequired = true
    private var pinGateGeneration = 0
    private var pinGateAccess: PinAccess? = null
    private var pinGateJob: Job? = null

    protected fun isPinAccessAllowed(): Boolean =
        pinAuthorized && (!pinRequired || PinSecurityUi.session.isValid(sessionMinutes()))

    private fun sessionMinutes(): Int =
        try {
            getSharedPreferences("dashboard_prefs", 0)
                .getInt("pin_session_minutes", 60)
                .coerceAtLeast(5)
        } catch (_: ClassCastException) {
            0
        }

    private fun hide() {
        pinAuthorized = false
        window.decorView.findViewById<View>(android.R.id.content)?.visibility = View.INVISIBLE
    }

    override fun onResume() {
        super.onResume()
        requestPinAccess()
    }

    override fun onPause() {
        pinGateGeneration++
        pinGateJob?.cancel()
        hide()
        super.onPause()
    }

    private fun requestPinAccess() {
        if (isFinishing || isDestroyed || pinGateJob?.isActive == true) return
        hide()
        val generation = ++pinGateGeneration
        val access = pinGateAccess ?: AndroidPinAccess.create(this).also { pinGateAccess = it }
        pinGateJob = lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { access.status() }
            if (generation != pinGateGeneration || isFinishing || isDestroyed) return@launch
            pinRequired = state.status != PinAccess.Status.DISABLED
            fun allowed() {
                if (generation != pinGateGeneration || isFinishing || isDestroyed) return
                if (pinRequired && !PinSecurityUi.session.touch(sessionMinutes())) return
                pinAuthorized = true
                window.decorView.findViewById<View>(android.R.id.content)?.visibility = View.VISIBLE
            }
            if (!pinRequired) allowed()
            else
                PinSecurityUi.authorize(
                        this@PinProtectedActivity,
                        ::allowed,
                        { if (generation == pinGateGeneration) finish() },
                        access,
                    )
                    .join()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isPinAccessAllowed()) {
            if (event.action == MotionEvent.ACTION_DOWN) requestPinAccess()
            return true
        }
        if (
            pinRequired &&
                event.action in
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
        )
            PinSecurityUi.session.touch(sessionMinutes())
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isPinAccessAllowed() && event.keyCode != KeyEvent.KEYCODE_BACK) {
            requestPinAccess()
            return true
        }
        if (pinRequired && pinAuthorized && event.action == KeyEvent.ACTION_DOWN)
            PinSecurityUi.session.touch(sessionMinutes())
        return super.dispatchKeyEvent(event)
    }
}
