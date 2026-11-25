package com.example.smarthomedashboard

import android.content.Context
import com.example.smarthomedashboard.TalkSelfCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Самопроверка звука разговора (docs/talk-tz.md) — на модели комнаты: разные планшеты, разные решения. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalkSelfCheckTest {
    private val rate = TalkSelfCheck.RATE

    /** Планшет: [knee] — с какой громкости динамик искажает, [gain] — насколько громко его слышит микрофон. */
    private inner class Room(private val knee: Double = 20_000.0, private val gain: Double = 0.8, private val noise: Double = 25.0, private val wander: Boolean = false,
                             private val system: Double = 1.0, override val volume: Pair<Int, Int>? = 10 to 15, private val grit: Double = 0.1) : TalkSelfCheck.Rig {
        val takes = mutableListOf<Pair<Boolean, Int>>()

        override fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean): TalkSelfCheck.Take {
            takes += system to signal.maxOf { kotlin.math.abs(it.toInt()) }
            val played = EchoSim.doubles(signal)
            val path = EchoSim.convolve(EchoSim.squash(EchoSim.highPass(played, 300.0), knee), EchoSim.room(11, direct = 30, gain = gain, rt60Ms = 400, reverbDb = -10.0, length = 4_800))
            var echo = EchoSim.delay(EchoSim.grit(path, grit, 12), rate * 180 / 1000)
            // Эхо гуляет по времени — эхоподавлению не за что зацепиться.
            if (wander) { val from = echo.copyOf(); val rnd = java.util.Random(5); var shift = 0
                for (i in echo.indices) { if (i % (rate / 3) == 0) shift = rnd.nextInt(rate / 6); echo[i] = if (i >= shift) from[i - shift] else 0.0 } }
            if (system) echo = DoubleArray(echo.size) { echo[it] * this.system }
            val lead = rate / 2
            val n = (signal.size + lead) / TalkSelfCheck.FRAME * TalkSelfCheck.FRAME
            val room = EchoSim.noise(n, noise, 3)
            val pre = ShortArray(n) { i -> ((if (i >= lead && i - lead < echo.size) echo[i - lead] else 0.0) + room[i]).coerceIn(-32_768.0, 32_767.0).toInt().toShort() }
            val duplex = DuplexEcho()
            val refAt = LongArray(n / TalkSelfCheck.FRAME)
            val post = ShortArray(n)
            val buf = ShortArray(TalkSelfCheck.FRAME)
            var written = 0
            for (f in refAt.indices) {
                val until = ((f + 1) * TalkSelfCheck.FRAME - lead).coerceIn(0, signal.size)
                if (until > written) { duplex.played.write(signal.copyOfRange(written, until), until - written); written = until }
                refAt[f] = written.toLong()
                System.arraycopy(pre, f * TalkSelfCheck.FRAME, buf, 0, TalkSelfCheck.FRAME)
                duplex.near.process(buf, TalkSelfCheck.FRAME)
                System.arraycopy(buf, 0, post, f * TalkSelfCheck.FRAME, TalkSelfCheck.FRAME)
            }
            return TalkSelfCheck.Take(rate, pre, post, refAt, duplex.near.alignment, duplex.near.locked, 180, duplex.near.linearDb, pre.maxOf { kotlin.math.abs(it.toInt()) })
        }
    }

    private fun check(rig: TalkSelfCheck.Rig, cancelled: () -> Boolean = { false }) = TalkSelfCheck.check(rig, 1_000, {}, cancelled)

    @Test fun ordinaryTabletIsGoodAtOnce() {
        val room = Room()
        val r = check(room)
        assertEquals(r.text, Verdict.GOOD, r.verdict)
        assertEquals("одной попытки хватило", 1, room.takes.size)
        val p = r.profile!!
        assertEquals(TalkTablet.Mode.OWN, p.mode); assertEquals(1f, p.gain, 0f)
        assertTrue("${p.reductionDb} дБ", p.reductionDb >= 25)
        assertTrue("голос проходит: ${p.voicePercent} %", p.voicePercent in 40..100)
    }

    @Test fun overloadedSpeakerIsMadeQuieter() {
        // Динамик хрипит на полной громкости: искажения сильнее самого эха, вычитать нечего.
        var first = true
        val inner = Room()
        val bad = Room(wander = true)
        val rig = object : TalkSelfCheck.Rig {
            override val volume = 10 to 15
            override fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean) =
                if (first) { first = false; bad.take(signal, system, cancelled) } else inner.take(signal, system, cancelled)
        }
        val r = check(rig)
        assertEquals(r.text, Verdict.GOOD_QUIETER, r.verdict)
        assertEquals(0.5f, r.profile!!.gain, 0f)
        assertTrue(r.text, "убавлен вдвое" in r.text)
        assertTrue("вторая попытка тише", inner.takes.single().second < bad.takes.single().second * 0.6)
    }

    @Test fun androidEchoCancellerIsAddedWhenOwnIsNotEnough() {
        val room = Room(wander = true, system = 0.002, noise = 30.0)
        val r = check(room)
        assertEquals(r.text, Verdict.GOOD_SYSTEM, r.verdict)
        assertEquals(TalkTablet.Mode.SYSTEM, r.profile!!.mode)
        assertEquals("своё ×1, ×0,5, затем вместе с Android", listOf(false, false, true), room.takes.map { it.first })
    }

    @Test fun hopelessTabletTalksInTurns() {
        val room = Room(wander = true)
        val r = check(room)
        assertEquals(r.text, Verdict.ECHO, r.verdict)
        assertEquals(TalkTablet.Mode.TURNS, r.profile!!.mode)
        assertEquals("все четыре способа", 4, room.takes.size)
        assertTrue(r.text, "по очереди" in r.text)
    }

    @Test fun quietSpeakerAndNoisyRoom() {
        val mute = check(Room(gain = 0.0005, volume = 1 to 15))
        assertEquals(Verdict.DEAF, mute.verdict); assertNull(mute.profile)
        assertTrue(mute.text, "1 из 15" in mute.text)
        val far = check(Room(gain = 0.0005, volume = 12 to 15))
        assertEquals("микрофон не слышит динамик — эха нет", Verdict.GOOD, far.verdict)
        val noisy = check(Room(wander = true, noise = 3_000.0))
        assertEquals(Verdict.NOISY, noisy.verdict); assertNull(noisy.profile)
    }

    @Test fun microphoneProblemsAndCancel() {
        val rig = object : TalkSelfCheck.Rig { override val volume = null; override fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean) = null }
        assertEquals(Verdict.NO_MIC, check(rig).verdict)
        val narrow = object : TalkSelfCheck.Rig { override val volume = null; override fun take(signal: ShortArray, system: Boolean, cancelled: () -> Boolean) =
            TalkSelfCheck.Take(8_000, ShortArray(0), ShortArray(0), LongArray(0), 0, false, 0, 0.0, 0) }
        assertEquals(Verdict.NARROW, check(narrow).verdict)
        val cancelled = check(Room()) { true }
        assertEquals(Verdict.CANCELLED, cancelled.verdict); assertNull(cancelled.profile)
    }

    @Test fun decisionIsRememberedOnThisTabletOnly() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertNull(TalkTablet.profile(context))
        val profile = TalkTablet.Profile(TalkTablet.Mode.SYSTEM, 0.5f, 31, 180, 77, 1_000)
        TalkTablet.save(context, profile)
        assertEquals(profile, TalkTablet.profile(context))
        // Испорченная запись — безопасные значения.
        context.getSharedPreferences("tablet_talk", Context.MODE_PRIVATE).edit().putFloat("gain", -3f).commit()
        assertEquals(TalkSelfCheck.MIN_GAIN, TalkTablet.profile(context)!!.gain, 0f)
        context.getSharedPreferences("tablet_talk", Context.MODE_PRIVATE).edit().putString("mode", "НЕТ").commit()
        assertNull(TalkTablet.profile(context))
        TalkTablet.clear(context)
        assertNull(TalkTablet.profile(context))
    }

    @Test fun signalIsSpeechLikeAndNotTooLoud() {
        val s = TalkSelfCheck.signal(1f)
        assertTrue("тишина в начале", TalkSelfCheck.rms(s, 0, rate) == 0.0)
        assertTrue("пики ${s.maxOf { kotlin.math.abs(it.toInt()) }}", s.maxOf { kotlin.math.abs(it.toInt()) } in 8_000..32_000)
        val half = TalkSelfCheck.signal(0.5f)
        assertTrue(half.maxOf { kotlin.math.abs(it.toInt()) } <= s.maxOf { kotlin.math.abs(it.toInt()) } / 2 + 1)
    }

    // ---- Экран «Планшет» ----

    private fun screen(): TabletActivity {
        val context: Context = RuntimeEnvironment.getApplication()
        val activity = org.robolectric.Robolectric.buildActivity(TabletActivity::class.java, android.content.Intent(context, TabletActivity::class.java)).setup().get()
        val end = System.currentTimeMillis() + 10_000
        while (activity.view("tablet_talk_state") == null) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); assertTrue(System.currentTimeMillis() < end); Thread.sleep(20) }
        return activity
    }

    private fun TabletActivity.view(tag: String): android.view.View? = window.decorView.findViewWithTag(tag)
    private fun TabletActivity.talk() = texts(view("tablet_talk_state")!!)
    private fun texts(v: android.view.View): String = when (v) {
        is android.widget.TextView -> v.text.toString()
        is android.view.ViewGroup -> (0 until v.childCount).joinToString(" ") { texts(v.getChildAt(it)) }
        else -> ""
    }

    @Test fun tabletScreenShowsDecisionAndAsksForMicrophone() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fresh = screen()
        assertTrue(fresh.talk(), "Не проверялся" in fresh.talk())
        assertNull("забывать нечего", fresh.view("tablet_talk_forget"))
        // Без разрешения проверка не начинается, а просит его.
        fresh.checkTalk()
        assertEquals(android.Manifest.permission.RECORD_AUDIO, org.robolectric.Shadows.shadowOf(fresh).lastRequestedPermission.requestedPermissions.single())
        assertTrue(!TalkSelfCheck.busy)
        fresh.finish()
        TalkTablet.save(context, TalkTablet.Profile(TalkTablet.Mode.TURNS, 1f, 9, 200, 0, System.currentTimeMillis()))
        val known = screen()
        assertTrue(known.talk(), "по очереди" in known.talk())
        known.view("tablet_talk_forget")!!.performClick()
        assertNull(TalkTablet.profile(context))
        assertTrue(known.talk(), "Не проверялся" in known.talk())
    }
}
