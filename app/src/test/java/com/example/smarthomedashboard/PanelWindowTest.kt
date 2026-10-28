package com.example.smarthomedashboard

import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.xml.parsers.DocumentBuilderFactory

// XML/onCreate не запускаются: проверяем настройку окна и затемнение напрямую.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PanelWindowTest {
    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).get() }

    private fun invoke(name: String) = try {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    private fun flags() = activity.window.attributes.flags

    @Suppress("DEPRECATION")
    @Test fun panelWindowKeepsScreenOnAndStaysFullscreen() {
        invoke("setupPanelWindow")
        assertNotEquals(0, flags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertNotEquals(0, flags() and WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    @Test fun dimmingLowersBrightnessWithoutReleasingScreen() {
        invoke("setupPanelWindow")
        invoke("dimScreen")
        assertEquals(0.01f, activity.window.attributes.screenBrightness, 0f)
        assertNotEquals(0, flags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        invoke("wakeScreen")
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            activity.window.attributes.screenBrightness, 0f)
        assertNotEquals(0, flags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test fun manifestDoesNotRequestUnusedWakeLock() {
        val relative = "src/main/AndroidManifest.xml"
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element)
                .getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }
        assertFalse(permissions.contains("android.permission.WAKE_LOCK"))
    }
}
