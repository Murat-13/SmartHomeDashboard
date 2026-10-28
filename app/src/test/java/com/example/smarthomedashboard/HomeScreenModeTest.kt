package com.example.smarthomedashboard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeScreenModeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        val relative = "src/main/AndroidManifest.xml"
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(root: Element, tag: String) = root.getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun homeAliasIsDeclaredButDisabledByDefault() {
        val alias = elements(manifest(), "activity-alias").single { it.getAttributeNS(ns, "name") == ".HomeLauncher" }
        // Установка не должна подменять рабочий стол без явного согласия пользователя.
        assertEquals("false", alias.getAttributeNS(ns, "enabled"))
        assertEquals(".SplashActivity", alias.getAttributeNS(ns, "targetActivity"))
        val categories = elements(alias, "category").map { it.getAttributeNS(ns, "name") }
        assertTrue("android.intent.category.HOME" in categories)
        assertTrue("android.intent.category.DEFAULT" in categories)
    }

    @Test fun panelIsSingleTaskSoHomeDoesNotCreateSecondConnection() {
        val main = elements(manifest(), "activity").single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(ns, "launchMode"))
    }

    @Test fun switchEnablesAndDisablesOnlyTheAlias() {
        assertFalse(HomeScreenMode.isEnabled(context))
        HomeScreenMode.setEnabled(context, true)
        assertTrue(HomeScreenMode.isEnabled(context))
        val alias = ComponentName(context, "com.example.smarthomedashboard.HomeLauncher")
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(alias))
        HomeScreenMode.setEnabled(context, false)
        assertFalse(HomeScreenMode.isEnabled(context))
    }

    @Test fun homeLaunchIsRecognisedOnlyByHomeCategory() {
        assertTrue(HomeScreenMode.isHomeLaunch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertFalse(HomeScreenMode.isHomeLaunch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(HomeScreenMode.isHomeLaunch(null))
    }

    @Test fun homeButtonWhilePanelIsAliveOpensPanelWithoutSplashDelay() {
        context.getSharedPreferences("dashboard_prefs", 0).edit().putBoolean("setup_completed", true).commit()
        setPanelAlive(true)
        try {
            val splash = Robolectric.buildActivity(SplashActivity::class.java,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).create().get()
            val next = shadowOf(splash).nextStartedActivity
            assertEquals(MainActivity::class.java.name, next.component?.className)
            assertTrue("Заставка закрывается сразу", splash.isFinishing)
        } finally {
            setPanelAlive(false)
        }
    }

    // У свойства companion object поле хранится статически в MainActivity; сеттер приватный.
    private fun setPanelAlive(value: Boolean) {
        MainActivity::class.java.getDeclaredField("isAlive").apply { isAccessible = true }.setBoolean(null, value)
    }
}
