package com.example.smarthomedashboard

import android.content.Context
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** STAB-03 (аудит 25.11.2026): память планшета не пишет — журналы звонков и открытий пропускают запись, приложение живёт. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JournalStorageErrorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val before = Thread.getDefaultUncaughtExceptionHandler()
    @Volatile private var uncaught: Throwable? = null
    private val failed = CountDownLatch(1)

    @Before fun setUp() { Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e; failed.countDown() } }
    @After fun tearDown() { Thread.setDefaultUncaughtExceptionHandler(before) }

    /** Вместо папки — файл: любая запись внутрь даёт IOException, как при отказе памяти. */
    private fun blocked(dir: File, block: () -> Unit) {
        dir.deleteRecursively()
        dir.parentFile!!.mkdirs()
        dir.writeText("не папка")
        try { block() } finally { dir.delete() }
    }

    @Test fun gateLogSurvivesStorageError() = blocked(GateLog.dir(context)) {
        GateLog.add(context, GateLog.Entry(1_000, "g", "Калитка", Gate.Action.OPEN, GateLog.Origin.entries.first(), "ok"))
        failed.await(1_500, TimeUnit.MILLISECONDS)
        assertNull("приложение закрылось бы: $uncaught", uncaught)
        assertTrue(GateLog.list(context).isEmpty())
    }

    @Test fun ringLogSurvivesStorageError() = blocked(RingLog.dir(context, "tile").parentFile!!) {
        RingLog.onRing(context, "tile", 1_000) { null }
        RingLog.markAnswered(context, "tile", 1_000)
        failed.await(1_500, TimeUnit.MILLISECONDS)
        assertNull("приложение закрылось бы: $uncaught", uncaught)
    }
}
