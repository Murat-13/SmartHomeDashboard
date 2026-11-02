package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Версия из сборки: «мажор.минор.патч», versionCode выводится из неё и больше прежнего 1 (этап 09а). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppVersionTest {
    @Test fun versionCodeFollowsVersionName() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName.orEmpty()
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(name)
        assertNotNull("versionName вида мажор.минор.патч: $name", match)
        val (major, minor, patch) = match!!.destructured.toList().map { it.toInt() }
        assertTrue(minor in 0..99 && patch in 0..99)
        assertEquals(major * 10000 + minor * 100 + patch, info.longVersionCode.toInt())
        assertTrue("versionCode должен быть больше прежнего 1", info.longVersionCode > 1)
    }
}
