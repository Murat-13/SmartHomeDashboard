package com.example.smarthomedashboard

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Подписка на сеть не держит экран: сервис берётся у приложения (Android 12, утечка при повороте 25.11.2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternetMonitorTest {
    @Test fun connectivityBelongsToApplicationNotScreen() {
        val app: Context = RuntimeEnvironment.getApplication()
        val asked = mutableListOf<String>()
        val screen = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? { asked += name; return super.getSystemService(name) }
        }
        val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val before = manager.networkCallbacks.size
        val monitor = InternetMonitor(screen) {}
        monitor.start()
        assertTrue("у экрана сервис не берётся: $asked", asked.isEmpty())
        assertEquals("подписка есть", before + 1, manager.networkCallbacks.size)
        monitor.stop()
        assertEquals("подписка снята", before, manager.networkCallbacks.size)
    }
}
