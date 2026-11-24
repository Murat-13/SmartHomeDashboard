package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Запись прихода кадров (разбор неровности прихода): только по выключателю, строки — как описано в [ArrivalTrace]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArrivalTraceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun withoutSwitchNothingIsWritten() {
        val dir = tmp.newFolder("video_debug")
        assertNull(ArrivalTrace.open(dir, "rtsp://cam/h264Preview_01_main"))
        assertNull(ArrivalTrace.open(null, "rtsp://cam/h264Preview_01_main"))
        assertEquals(false, File(dir, "trace").exists())
    }

    @Test fun framesAndSenderReportsGoToOwnFilePerStream() {
        val dir = tmp.newFolder("video_debug")
        File(dir, "arrival_trace").writeText("")
        val opened = ArrivalTrace.open(dir, "rtsp://cam/h264Preview_01_sub")
        assertNotNull(opened)
        val trace = opened!!
        trace.packet(9_000, 1_400, 1_000_000)
        trace.busy(30_000)
        trace.packet(9_000, 600, 3_000_000)
        trace.busy(20_000)
        trace.frame(9_000, 3_000_000, key = true)
        trace.senderReport(4_000_000, 9_000, 1_763_490_000_123)
        trace.packet(13_500, 900, 70_000_000)
        trace.frame(13_500, 70_000_000, key = false)
        repeat(ArrivalTrace.PACKETS_LISTED) { trace.packet(18_000, 1_000, 100_000_000L + it * 1_500_000L) }
        trace.frame(18_000, 100_000_000L + (ArrivalTrace.PACKETS_LISTED - 1) * 1_500_000L, key = true)
        trace.close()
        Thread.sleep(200)
        val files = File(dir, "trace").listFiles()!!.toList()
        assertEquals(1, files.size)
        assertTrue(files[0].name, files[0].name.startsWith("sub-") && files[0].name.endsWith(".csv"))
        assertEquals(listOf(
            "f,3000,9000,2000,2000,2,1,50",
            "sr,4000,9000,1763490000123",
            "f,70000,13500,0,900,1,0,0",
            "f,116500,18000,16500,12000,12,1,0",
            "p,18000," + (0 until ArrivalTrace.PACKETS_LISTED).joinToString(";") { (it * 150).toString() }
        ), files[0].readLines())
    }
}
