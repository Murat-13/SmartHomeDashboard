package com.example.smarthomedashboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttManager(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    private val brokerUrl: String
        get() {
            val host = prefs.getString("mqtt_host", "192.168.1.253") ?: "192.168.1.253"
            val port = prefs.getInt("mqtt_port", 1883)
            return "tcp://$host:$port"
        }

    private val username: String
        get() = prefs.getString("mqtt_username", "murat") ?: "murat"

    private val password: String
        get() = prefs.getString("mqtt_password", "019137-smS") ?: "019137-smS"

    private val clientId = "AndroidDashboard_${System.currentTimeMillis()}"
    private var mqttClient: MqttClient? = null

    private var onVoltageUpdate: ((String) -> Unit)? = null
    private var onPowerUpdate: ((String) -> Unit)? = null
    private var onCurrentUpdate: ((String) -> Unit)? = null
    private var onEnergyUpdate: ((String) -> Unit)? = null
    private var onFrequencyUpdate: ((String) -> Unit)? = null
    private var onPowerFactorUpdate: ((String) -> Unit)? = null
    private var onTemperatureUpdate: ((String) -> Unit)? = null

    fun connect() {
        try {
            mqttClient?.disconnect()
        } catch (_: Exception) {
        }

        try {
            mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

            val pwd = password
            val options = MqttConnectOptions().apply {
                userName = username
                password = pwd.toCharArray()
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
            }

            mqttClient?.connect(options)
            Log.d("MqttManager", "Connected to MQTT broker: $brokerUrl")

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w("MqttManager", "Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    when (topic) {
                        "esp32/pzem/voltage" -> onVoltageUpdate?.invoke(payload)
                        "esp32/pzem/power" -> onPowerUpdate?.invoke(payload)
                        "esp32/pzem/current" -> onCurrentUpdate?.invoke(payload)
                        "esp32/pzem/energy" -> onEnergyUpdate?.invoke(payload)
                        "esp32/pzem/frequency" -> onFrequencyUpdate?.invoke(payload)
                        "esp32/pzem/power_factor" -> onPowerFactorUpdate?.invoke(payload)
                        "esp32/pzem/temperature" -> onTemperatureUpdate?.invoke(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            subscribeToTopics()

        } catch (e: MqttException) {
            Log.e("MqttManager", "Connection error: ${e.message}")
        }
    }

    private fun subscribeToTopics() {
        subscribe("esp32/pzem/voltage")
        subscribe("esp32/pzem/power")
        subscribe("esp32/pzem/current")
        subscribe("esp32/pzem/energy")
        subscribe("esp32/pzem/frequency")
        subscribe("esp32/pzem/power_factor")
        subscribe("esp32/pzem/temperature")
    }

    private fun subscribe(topic: String) {
        try {
            mqttClient?.subscribe(topic, 0)
            Log.d("MqttManager", "Subscribed to $topic")
        } catch (e: MqttException) {
            Log.e("MqttManager", "Failed to subscribe to $topic: ${e.message}")
        }
    }

    fun setOnVoltageUpdate(callback: (String) -> Unit) {
        onVoltageUpdate = callback
    }

    fun setOnPowerUpdate(callback: (String) -> Unit) {
        onPowerUpdate = callback
    }

    fun setOnCurrentUpdate(callback: (String) -> Unit) {
        onCurrentUpdate = callback
    }

    fun setOnEnergyUpdate(callback: (String) -> Unit) {
        onEnergyUpdate = callback
    }

    fun setOnFrequencyUpdate(callback: (String) -> Unit) {
        onFrequencyUpdate = callback
    }

    fun setOnPowerFactorUpdate(callback: (String) -> Unit) {
        onPowerFactorUpdate = callback
    }

    fun setOnTemperatureUpdate(callback: (String) -> Unit) {
        onTemperatureUpdate = callback
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
        } catch (e: MqttException) {
            Log.e("MqttManager", "Disconnect error: ${e.message}")
        }
    }

    fun reconnect() {
        disconnect()
        connect()
    }
}