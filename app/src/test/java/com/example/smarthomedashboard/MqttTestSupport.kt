package com.example.smarthomedashboard

import android.content.Context
import android.os.Looper
import android.widget.*
import org.robolectric.util.ReflectionHelpers

// Настоящий JCA, но test key/file provider и только искусственные defaults.
internal fun mqttTestAccess(context: Context, store: SecretStore = weatherTestStore()): MqttCredentialAccess =
    MqttCredentialAccess(object : MqttPasswordSlot {
        override fun read() = store.read(SecretStore.Slot.MQTT_PASSWORD)
        override fun write(value: String) = store.write(SecretStore.Slot.MQTT_PASSWORD, value)
    }, PreferenceMqttLegacy({ context.getSharedPreferences("dashboard_prefs", 0) },
        { "mqtt.example.invalid" }, { "FAKE-USER" }, { "FAKE-DEFAULT-MQTT" }),
        { check(Looper.myLooper() != Looper.getMainLooper()) })

internal fun attachMqttSettingsFields(activity: SettingsActivity, access: MqttCredentialAccess = mqttTestAccess(activity)) {
    org.robolectric.util.ReflectionHelpers.setField(activity,"pinAuthorized",true)
    org.robolectric.util.ReflectionHelpers.setField(activity,"pinRequired",false)
    val sessionInput=ReflectionHelpers.getField<android.widget.EditText>(activity,"etPinSessionMinutes")
    if(sessionInput.text.isEmpty())sessionInput.setText("60")
    ReflectionHelpers.setField(activity, "mqttCredentials", access)
    ReflectionHelpers.setField(activity, "tvMqttPasswordStatus", TextView(activity))
    weatherInvoke(activity, "prepareMqttInput")
    weatherInvoke(activity, "loadMqttConfiguration")
    weatherAwait { !ReflectionHelpers.getField<Boolean>(activity, "mqttLoading") }
}
