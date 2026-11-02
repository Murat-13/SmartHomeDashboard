package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Манифест для самообновления: разрешения и неэкспортируемые приёмники результата и замены приложения. */
class UpdateManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val manifest: Element = run {
        val relative = "src/main/AndroidManifest.xml"
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }
    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test fun declaresInstallPermissions() {
        val permissions = elements("uses-permission").map { it.getAttributeNS(ns, "name") }
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(permissions.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
    }

    @Test fun receiversAreNotExportedAndReplacedListensForOwnPackage() {
        val receivers = elements("receiver").associateBy { it.getAttributeNS(ns, "name") }
        listOf(".UpdateInstaller\$StatusReceiver", ".UpdateInstaller\$ReplacedReceiver").forEach {
            assertEquals("$it не экспортируется", "false", receivers.getValue(it).getAttributeNS(ns, "exported"))
        }
        val actions = receivers.getValue(".UpdateInstaller\$ReplacedReceiver").getElementsByTagName("action")
        assertEquals("android.intent.action.MY_PACKAGE_REPLACED", (actions.item(0) as Element).getAttributeNS(ns, "name"))
    }
}
