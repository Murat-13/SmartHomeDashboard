package com.example.smarthomedashboard

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object PinSecurityUi {
    val session = PinSession { android.os.SystemClock.elapsedRealtime() }

    fun message(result: PinAccess.Result): String =
        when (result.status) {
            PinAccess.Status.SUCCESS -> "PIN подтверждён"
            PinAccess.Status.DISABLED -> "Защита PIN отключена"
            PinAccess.Status.WRONG -> "Неверный PIN или код восстановления"
            PinAccess.Status.WAIT -> "Повторите через ${(result.retryAfterMillis+999)/1000} сек."
            PinAccess.Status.INVALID_NEW_PIN -> "Новый PIN должен содержать 6 цифр"
            PinAccess.Status.NO_RECOVERY ->
                "Код восстановления ещё не создан. Используйте действующий PIN"
            PinAccess.Status.ERROR ->
                "Защита PIN недоступна. Доступ не предоставлен; данные не удалялись"
        }

    fun authorize(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {},
        access: PinAccess = AndroidPinAccess.create(activity),
    ): kotlinx.coroutines.Job {
        return activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { access.status() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val minutes =
                try {
                    activity
                        .getSharedPreferences("dashboard_prefs", 0)
                        .getInt("pin_session_minutes", 60)
                        .coerceAtLeast(5)
                } catch (_: ClassCastException) {
                    0
                }
            when {
                result.status == PinAccess.Status.DISABLED -> onSuccess()
                result.status == PinAccess.Status.SUCCESS && session.isValid(minutes) -> onSuccess()
                result.status == PinAccess.Status.SUCCESS ||
                    result.status == PinAccess.Status.WAIT -> {
                    val dialog = PinDialog(activity, onSuccess)
                    dialog.setOnCancelListener { onCancel() }
                    dialog.show()
                }
                else -> {
                    Toast.makeText(activity, message(result), Toast.LENGTH_LONG).show()
                    onCancel()
                }
            }
        }
    }

    private fun input(activity: AppCompatActivity, hint: String, numeric: Boolean = false) =
        EditText(activity).apply {
            this.hint = hint
            inputType =
                if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (numeric) 6 else 256))
        }

    fun manage(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { AndroidPinAccess.create(activity).status() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (
                state.status !in
                    listOf(
                        PinAccess.Status.SUCCESS,
                        PinAccess.Status.DISABLED,
                        PinAccess.Status.WAIT,
                    )
            ) {
                Toast.makeText(activity, message(state), Toast.LENGTH_LONG).show()
                return@launch
            }
            val disabled = state.status == PinAccess.Status.DISABLED
            val items =
                if (disabled) arrayOf("Включить PIN")
                else arrayOf("Изменить PIN", "Создать новый код восстановления", "Отключить PIN")
            AlertDialog.Builder(activity)
                .setTitle(if (disabled) "Защита отключена — можно включить PIN" else "Защита PIN")
                .setItems(items) { _, which ->
                    edit(
                        activity,
                        if (disabled) "enable" else listOf("change", "code", "disable")[which],
                    )
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    fun recover(activity: AppCompatActivity, onSuccess: () -> Unit) =
        edit(activity, "recover", onSuccess)

    private fun edit(activity: AppCompatActivity, operation: String, onSuccess: () -> Unit = {}) {
        val current =
            input(activity, if (operation == "recover") "Код восстановления" else "Действующий PIN")
        val newPin = input(activity, "Новый PIN — 6 цифр", true)
        val confirm = input(activity, "Повторите новый PIN", true)
        val fields =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }
        if (operation != "enable") fields.addView(current)
        val needsNew = operation in listOf("enable", "change", "recover")
        if (needsNew) {
            fields.addView(newPin)
            fields.addView(confirm)
        }
        val status = TextView(activity)
        fields.addView(status)
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(
                    when (operation) {
                        "recover" -> "Восстановление доступа"
                        "disable" -> "Отключить защиту PIN"
                        "code" -> "Новый код восстановления"
                        else -> "Новый PIN"
                    },
                )
                .setView(fields)
                .setPositiveButton("Подтвердить", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (
                    needsNew &&
                        (newPin.text.toString() != confirm.text.toString() ||
                            !PinVerifier.validNewPin(newPin.text.toString().toCharArray()))
                ) {
                    status.text = "Введите два одинаковых PIN из 6 цифр"
                    return@setOnClickListener
                }
                val old =
                    current.text
                        .toString()
                        .let {
                            if (operation == "recover") it.trim().uppercase(java.util.Locale.ROOT)
                            else it
                        }
                        .toCharArray()
                val next = newPin.text.toString().toCharArray()
                button.isEnabled = false
                current.isEnabled = false
                newPin.isEnabled = false
                confirm.isEnabled = false
                status.text = "Проверка…"
                activity.lifecycleScope.launch {
                    val result =
                        try {
                            withContext(Dispatchers.IO) {
                                val access = AndroidPinAccess.create(activity)
                                when (operation) {
                                    "disable" -> access.disable(old)
                                    "code" -> access.replaceRecovery(old)
                                    "recover" -> access.recover(old, next)
                                    else -> access.change(old, next)
                                }
                            }
                        } finally {
                            old.fill(' ')
                            next.fill(' ')
                        }
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) {
                        result.recoveryCode?.fill(' ')
                        return@launch
                    }
                    button.isEnabled = true
                    current.isEnabled = true
                    newPin.isEnabled = true
                    confirm.isEnabled = true
                    status.text = message(result)
                    if (
                        result.status == PinAccess.Status.SUCCESS ||
                            result.status == PinAccess.Status.DISABLED
                    ) {
                        session.revoke()
                        current.text.clear()
                        newPin.text.clear()
                        confirm.text.clear()
                        dialog.dismiss()
                        val code = result.recoveryCode
                        if (code != null)
                            showCode(activity, code) {
                                if (operation == "recover") {
                                    session.grant()
                                    onSuccess()
                                }
                            }
                        else onSuccess()
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            current.text.clear()
            newPin.text.clear()
            confirm.text.clear()
        }
        dialog.show()
    }

    private fun showCode(activity: AppCompatActivity, code: CharArray, onSaved: () -> Unit) {
        val text =
            TextView(activity).apply {
                text =
                    "Сохраните этот одноразовый код вне планшета. Он больше не будет показан.\n\n" +
                        String(code)
                setPadding(32, 24, 32, 24)
                isSaveEnabled = false
                isSaveFromParentEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        code.fill(' ')
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle("Код восстановления")
                .setView(text)
                .setCancelable(false)
                .setPositiveButton("Я сохранил код") { _, _ ->
                    text.text = ""
                    onSaved()
                }
                .create()
        dialog.setOnDismissListener { text.text = "" }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
