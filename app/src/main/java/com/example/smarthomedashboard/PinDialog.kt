// File: PinDialog.kt
package com.example.smarthomedashboard

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class PinDialog(context: Context, private val onSuccess: () -> Unit) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_pin)

        val pinInput = findViewById<EditText>(R.id.pinInput)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)

        btnConfirm.setOnClickListener {
            val prefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
            val savedPin = prefs.getString("admin_pin", "")

            // Если PIN не задан в настройках, пускаем сразу (или можно реализовать логику "задай при первом входе")
            // Но по вашему запросу: если пустой в настройках - защиты нет.
            if (savedPin.isNullOrEmpty() || pinInput.text.toString() == savedPin) {
                if (!savedPin.isNullOrEmpty()) {
                    Toast.makeText(context, "Верный PIN", Toast.LENGTH_SHORT).show()
                }
                onSuccess()
                dismiss()
            } else {
                Toast.makeText(context, "Неверный PIN", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dismiss()
        }
    }
}