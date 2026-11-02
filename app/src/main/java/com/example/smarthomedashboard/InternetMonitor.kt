package com.example.smarthomedashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Доступ в интернет по проверке самого Android (NET_CAPABILITY_VALIDATED), отдельно от связи с HA.
 * Наличие Wi-Fi само по себе интернетом не считается. null — состояние ещё неизвестно.
 */
internal class InternetMonitor(context: Context, private val onChange: (Boolean?) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    var available: Boolean? = null
        private set

    fun start() {
        val manager = connectivity ?: return publish(null)
        if (callback != null) return
        publish(manager.activeNetwork?.let { validated(manager.getNetworkCapabilities(it)) } ?: false)
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                main.post { publish(validated(capabilities)) }
            }
            override fun onLost(network: Network) {
                main.post { publish(false) }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(next)
            callback = next
        } catch (_: RuntimeException) {
            publish(null)
        }
    }

    fun stop() {
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
        main.removeCallbacksAndMessages(null)
    }

    private fun publish(value: Boolean?) {
        if (available == value) return
        available = value
        onChange(value)
    }

    companion object {
        fun validated(capabilities: NetworkCapabilities?) = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
