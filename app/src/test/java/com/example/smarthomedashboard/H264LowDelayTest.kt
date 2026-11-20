package com.example.smarthomedashboard

import androidx.media3.container.NalUnitUtil
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Живое видео без задержки декодера (docs/doorbell.md, «Задержка живого видео»): в SPS дописывается «перестановки
 * кадров нет». Результат проверяется разбором SPS из Media3 — тем же, чем его читает плеер.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class H264LowDelayTest {
    /** SPS High, уровень 5.1, 640×480 — как у субпотока Reolink; [vui] — содержимое VUI (null — VUI нет). */
    private fun sps(refs: Int = 1, vui: (H264LowDelay.BitWriter.() -> Unit)? = null): ByteArray {
        val w = H264LowDelay.BitWriter()
        w.u(100, 8); w.u(0, 8); w.u(51, 8)
        w.ue(0) // seq_parameter_set_id
        w.ue(1); w.ue(0); w.ue(0); w.bit(0); w.bit(0) // цветность 4:2:0, 8 бит, без матриц
        w.ue(0) // log2_max_frame_num_minus4
        w.ue(0); w.ue(0) // pic_order_cnt_type 0
        w.ue(refs)
        w.bit(0)
        w.ue(39); w.ue(29) // 640×480
        w.bit(1); w.bit(1); w.bit(0)
        if (vui == null) w.bit(0) else { w.bit(1); w.vui() }
        w.trailing()
        return byteArrayOf(0x67) + H264LowDelay.escape(w.bytes())
    }

    private fun parse(nal: ByteArray) = NalUnitUtil.parseSpsNalUnit(nal, 0, nal.size)

    private fun H264LowDelay.BitWriter.restriction(reorder: Int, buffering: Int) {
        bit(1); bit(1); ue(2); ue(1); ue(16); ue(16); ue(reorder); ue(buffering)
    }

    @Test fun spsWithoutVuiGetsNoReorder() {
        val original = sps()
        assertTrue("без VUI декодер ждёт перестановку: ${parse(original).maxNumReorderFrames}", parse(original).maxNumReorderFrames > 0)
        val fixed = H264LowDelay.rewriteSps(original)!!
        val d = parse(fixed)
        assertEquals(0, d.maxNumReorderFrames)
        assertEquals(640, d.width); assertEquals(480, d.height)
        assertEquals(100, d.profileIdc); assertEquals(51, d.levelIdc); assertEquals(1, d.maxNumRefFrames)
        assertNull("уже исправленный SPS не меняется", H264LowDelay.rewriteSps(fixed))
    }

    @Test fun vuiWithoutRestrictionKeepsItsFields() {
        val original = sps(vui = {
            bit(1); u(1, 8) // соотношение сторон 1:1
            bit(0)
            bit(1); u(5, 3); bit(1); bit(1); u(1, 8); u(1, 8); u(1, 8) // тип сигнала: полный диапазон, BT.709
            bit(0)
            bit(1); u(0, 16); u(1, 16); u(0, 16); u(20, 16); bit(1) // время: 10 к/с
            bit(1); ue(0); u(0, 4); u(0, 4); ue(999); ue(999); bit(0); u(23, 5); u(23, 5); u(23, 5); u(24, 5) // NAL HRD
            bit(0)
            bit(0) // low_delay_hrd_flag
            bit(0) // pic_struct_present_flag
            bit(0) // bitstream_restriction_flag
        })
        val before = parse(original)
        val after = parse(H264LowDelay.rewriteSps(original)!!)
        assertEquals(0, after.maxNumReorderFrames)
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        assertEquals(before.colorSpace, after.colorSpace); assertEquals(before.colorRange, after.colorRange)
        assertEquals(before.colorTransfer, after.colorTransfer)
        assertEquals(before.pixelWidthHeightRatio, after.pixelWidthHeightRatio, 0f)
    }

    @Test fun declaredReorderIsReplacedOnlyWithOneReferenceFrame() {
        val reorder2 = sps(vui = { repeat(8) { bit(0) }; restriction(2, 4) })
        assertEquals(2, parse(reorder2).maxNumReorderFrames)
        assertEquals(0, parse(H264LowDelay.rewriteSps(reorder2)!!).maxNumReorderFrames)
        assertNull("перестановки уже нет", H264LowDelay.rewriteSps(sps(vui = { repeat(8) { bit(0) }; restriction(0, 1) })))
        assertNull("два опорных кадра — перестановка возможна, не трогаем", H264LowDelay.rewriteSps(sps(refs = 2)))
        assertNull("не SPS", H264LowDelay.rewriteSps(byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(), 0)))
        assertNull("обрезанный SPS", H264LowDelay.rewriteSps(sps().copyOf(6)))
    }

    /** Настоящие SPS домофона Reolink Video Doorbell PoE (20.11.2026): субпоток — с VUI без ограничений, основной — без VUI. */
    @Test fun realReolinkStreamsGetNoReorder() {
        fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        for ((text, size) in listOf("67640033ac1514a0a03da1000004f6000063380400" to (640 to 480), "67640033ac1514a02800f190" to (2560 to 1920))) {
            val original = hex(text)
            assertEquals("декодер ждал бы перестановку", 16, parse(original).maxNumReorderFrames)
            val d = parse(H264LowDelay.rewriteSps(original)!!)
            assertEquals(0, d.maxNumReorderFrames)
            assertEquals(size.first, d.width); assertEquals(size.second, d.height)
            assertEquals(1, d.maxNumRefFrames); assertEquals(51, d.levelIdc)
        }
    }

    @Test fun emulationPreventionRoundTrip() {
        val rbsp = byteArrayOf(0, 0, 0, 1, 0, 0, 3, 0, 0, 2, 5, 0, 0)
        val escaped = H264LowDelay.escape(rbsp)
        for (i in 0 until escaped.size - 2) {
            assertFalse("стартовый код внутри NAL", escaped[i].toInt() == 0 && escaped[i + 1].toInt() == 0 && escaped[i + 2] in 0..2)
        }
        assertArrayEquals(rbsp, H264LowDelay.unescape(escaped, 0, escaped.size))
    }

    @Test fun accessUnitGetsFixedSpsAndKeepsTheRest() {
        val original = sps()
        val start = byteArrayOf(0, 0, 0, 1)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0x7F, 0x00, 0x00, 0x03, 0x00, 0x7F)
        val unit = start + original + start + pps + idr
        val fixed = H264LowDelay.rewriteAccessUnit(unit)!!
        assertArrayEquals(start + H264LowDelay.rewriteSps(original)!! + start + pps + idr, fixed)
        assertTrue(H264LowDelay.spsAt(ByteBuffer.wrap(unit), unit.size))
        // Начальные данные декодера (csd-0): только SPS.
        assertArrayEquals(start + H264LowDelay.rewriteSps(original)!!, H264LowDelay.rewriteAccessUnit(start + original))
        // Обычный кадр: без копирования и без изменений.
        val frame = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x24, 0x6C, 0x41)
        assertFalse(H264LowDelay.spsAt(ByteBuffer.wrap(frame), frame.size))
        assertNull(H264LowDelay.rewriteAccessUnit(frame))
        assertNotNull(parse(H264LowDelay.rewriteSps(original)!!))
    }

    @Test fun softwareDecoderAlsoGetsLowDelayAndShowsLateFramesUnlessHoldIsRequested() {
        LiveTuning.mode(h264 = true, softwareOnly = false, softwareLowDelay = true).let { assertTrue(it.lowDelay); assertFalse(it.freshest) }
        LiveTuning.mode(h264 = true, softwareOnly = true, softwareLowDelay = true).let { assertTrue(it.lowDelay); assertTrue(it.freshest) }
        // Проверочный выключатель: программный декодер как раньше; аппаратного он не касается.
        LiveTuning.mode(h264 = true, softwareOnly = true, softwareLowDelay = false).let { assertFalse(it.lowDelay); assertFalse(it.freshest) }
        LiveTuning.mode(h264 = true, softwareOnly = false, softwareLowDelay = false).let { assertTrue(it.lowDelay); assertFalse(it.freshest) }
        LiveTuning.mode(h264 = false, softwareOnly = true, softwareLowDelay = true).let { assertFalse(it.lowDelay); assertFalse(it.freshest) }
    }

    @Test fun rendererStaysReadyWhileDecoderIsStillWorkingOnFreshFrame() {
        assertTrue("кадр в декодере, отправлен только что — показ не останавливается", LiveTuning.decoding(inCodec = 1, sinceQueuedMs = 120))
        assertFalse("в декодере пусто — ждать нечего", LiveTuning.decoding(inCodec = 0, sinceQueuedMs = 10))
        assertFalse("кадров давно не было — поток встал, это настоящая остановка", LiveTuning.decoding(inCodec = 3, sinceQueuedMs = 900))
    }

    @Test fun holdSwitchIsAFileInTheAppFolderOnSharedStorage() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(LiveTuning.softwareHold(context))
        val dir = java.io.File(context.getExternalFilesDir(null), "video_debug").apply { mkdirs() }
        java.io.File(dir, "sw_hold").writeText("")
        assertTrue(LiveTuning.softwareHold(context))
        assertFalse("у каждого выключателя свой файл", LiveTuning.off(context, "no_golive"))
        java.io.File(dir, "no_golive").writeText("")
        assertTrue(LiveTuning.off(context, "no_golive"))
        assertFalse(LiveTuning.off(context, "no_retime"))
        assertFalse(LiveTuning.off(context, "player_audio"))
    }
}
