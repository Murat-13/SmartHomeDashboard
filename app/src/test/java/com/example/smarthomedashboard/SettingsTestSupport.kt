package com.example.smarthomedashboard

import android.widget.EditText
import org.robolectric.util.ReflectionHelpers

/** Settings tests authorize their fixture explicitly without opening a production PIN dialog. */
internal fun allowSettingsTestAccess(activity: SettingsActivity) {
    ReflectionHelpers.setField(activity, "pinAuthorized", true)
    ReflectionHelpers.setField(activity, "pinRequired", false)
    val input = ReflectionHelpers.getField<EditText>(activity, "etPinSessionMinutes")
    if (input.text.isEmpty()) input.setText("60")
}
