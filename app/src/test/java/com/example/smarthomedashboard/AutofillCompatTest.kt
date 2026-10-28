package com.example.smarthomedashboard

import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

// minSdk 24: на Android 7.x у View нет setImportantForAutofill, прямой вызов падает.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24, 25, 26, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class AutofillCompatTest {
    private fun content(dialog: PinDialog) {
        val root = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(EditText(dialog.context).apply { id = R.id.pinInput })
        root.addView(Button(dialog.context).apply { id = R.id.btnConfirm })
        root.addView(Button(dialog.context).apply { id = R.id.btnCancel })
        dialog.setContentView(root)
    }

    private fun access() = PinAccess(
        object : PinAccess.Storage {
            override fun read() = PinAccess.State(null, null)
            override fun write(state: PinAccess.State) = true
        },
        object : PinAccess.Legacy {
            override fun read(): String? = null
            override fun remove() = true
        },
        clock = object : PinAccess.Clock {
            override fun elapsed() = 0L
            override fun boot() = "test"
        },
        worker = {}
    )

    @Test fun pinDialogOpensOnEverySupportedApiLevel() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val dialog = PinDialog(activity, {}, access(), ::content)
        dialog.show()
        try {
            val input = dialog.findViewById<EditText>(R.id.pinInput)
            assertFalse(input.isSaveEnabled)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
            }
        } finally {
            dialog.dismiss()
        }
    }
}
