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
    /** На этом экране доступ уже был: при истечении сессии есть несохранённые правки, которые нельзя терять. */
    private var wasAuthorized = false
    private var expiredDialog: android.app.AlertDialog? = null

    /** PIN включён (после первой проверки на этом экране). */
    protected val pinEnabled get() = pinRequired

    /**
     * Проверка перед сохранением/удалением: если сессия истекла после входа, ничего не записывается и
     * предлагается «Ввести PIN» или «Выйти без сохранения».
     */
    internal fun requireAdmin(): Boolean {
        if (!wasAuthorized || isPinAccessAllowed()) return true
        showExpired()
        return false
    }

    private fun showExpired() {
        if (isFinishing || isDestroyed || expiredDialog?.isShowing == true) return
        expiredDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Сессия администратора истекла")
            .setMessage("Изменения на экране не потеряны. Чтобы продолжить или сохранить их, введите PIN.")
            .setCancelable(false)
            .setPositiveButton("Ввести PIN") { _, _ -> requestPinAccess() }
            .setNegativeButton("Выйти без сохранения") { _, _ -> finish() }
            .create().also { it.show() }
    }

    protected fun isPinAccessAllowed(): Boolean =
        pinAuthorized && (!pinRequired || PinSecurityUi.session.isValid(sessionMinutes()))

    private fun sessionMinutes(): Int = PinSecurityUi.sessionMinutes(this)

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
                wasAuthorized = true
                expiredDialog?.dismiss()
                expiredDialog = null
                window.decorView.findViewById<View>(android.R.id.content)?.visibility = View.VISIBLE
            }
            if (!pinRequired) allowed()
            else
                PinSecurityUi.authorize(
                        this@PinProtectedActivity,
                        ::allowed,
                        // Отказ от PIN после истечения сессии не закрывает экран молча: черновик остаётся.
                        { if (generation == pinGateGeneration) { if (wasAuthorized) showExpired() else finish() } },
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
        if (!isPinAccessAllowed()) {
            // «Назад» без доступа не открывает диалоги экрана (в них есть «Сохранить»): только выход
            // или, если правки уже были, выбор «Ввести PIN / Выйти без сохранения».
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) { if (wasAuthorized && pinRequired) showExpired() else finish() }
                return true
            }
            requestPinAccess()
            return true
        }
        if (pinRequired && pinAuthorized && event.action == KeyEvent.ACTION_DOWN)
            PinSecurityUi.session.touch(sessionMinutes())
        return super.dispatchKeyEvent(event)
    }
}
