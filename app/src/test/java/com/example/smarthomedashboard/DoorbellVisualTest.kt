package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.smarthomedashboard.data.TileEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

// Нативная графика Robolectric: плитка «Домофон» и окно звонка. Рендеры — build/reports/doorbell/*.png.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoorbellVisualTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val config = DoorbellConfig(camera = "camera.door", visitor = "binary_sensor.v", person = "binary_sensor.p",
        animal = "binary_sensor.a", lock = "lock.gate", light = "light.entrance", quickReply = "select.reply")
    private val values = mutableMapOf("camera.door" to "idle", "binary_sensor.v" to "off", "binary_sensor.p" to "off",
        "binary_sensor.a" to "off", "lock.gate" to "locked", "light.entrance" to "off", "select.reply" to "off", "sun.sun" to "below_horizon")
    private val attrs = mapOf("select.reply" to JSONObject().put("options", JSONArray(listOf("off", "Сейчас подойду", "Оставьте у калитки"))))
    private fun readings() = BatteryReadings(HaSession(true, true), { values[it] }, { false }, { attrs[it] })

    @After fun cleanup() {
        DoorbellHub.actions = null
        DoorbellHub.detach()
    }

    /** Условная «картинка с камеры»: небо, забор, дорожка — чтобы видеть подписи поверх кадра. */
    private fun fakeFrame(): Bitmap {
        val b = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 960f, Color.rgb(120, 150, 175), Color.rgb(60, 70, 60), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1280f, 960f, p)
        p.shader = null
        p.color = Color.rgb(96, 72, 52)
        for (x in 0 until 1280 step 90) c.drawRect(x.toFloat(), 300f, x + 70f, 720f, p)
        p.color = Color.rgb(150, 145, 135)
        c.drawRect(480f, 640f, 800f, 960f, p)
        p.color = Color.rgb(40, 40, 46)
        c.drawCircle(640f, 420f, 70f, p)
        c.drawRect(560f, 490f, 720f, 800f, p)
        return b
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val s = minOf(16L, left); shadowOf(Looper.getMainLooper()).idleFor(s, TimeUnit.MILLISECONDS); left -= s }
    }

    private fun save(view: View, w: Int, h: Int, name: String, settle: Long = 100) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        advance(settle)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        advance(settle)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(5, 8, 12))
        view.draw(canvas)
        val file = File("build/reports/doorbell/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun compact(name: String, missed: Int = 0, offline: Boolean = false, w: Int = 480, h: Int = 360) {
        val host = FrameLayout(activity)
        val card = DoorbellCard(activity, expanded = false)
        host.addView(card, FrameLayout.LayoutParams(w, h))
        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(host, ViewGroup.LayoutParams(w, h))
        card.findViewWithTag<ImageView>("door_snapshot").setImageBitmap(fakeFrame())
        card.bind("Калитка", config, DoorbellModel.snapshot(config, readings()), offline, missed,
            System.currentTimeMillis() - 7 * 60_000, System.currentTimeMillis(), animate = false)
        save(host, w, h, name)
        (host.parent as ViewGroup).removeView(host)
        card.dispose()
    }

    @Test fun rendersCompactStates() {
        compact("compact_idle")
        values["binary_sensor.p"] = "on"; compact("compact_person")
        values["binary_sensor.v"] = "on"; values["binary_sensor.a"] = "on"; compact("compact_ring", missed = 2)
        values["camera.door"] = "unavailable"; compact("compact_offline")
        values["camera.door"] = "idle"; values["binary_sensor.v"] = "off"; values["binary_sensor.p"] = "off"; values["binary_sensor.a"] = "off"
        compact("compact_small", w = 320, h = 240)
        compact("compact_large", w = 800, h = 600)
    }

    @Test fun rendersExpandedWithHd() {
        val host = FrameLayout(activity)
        val card = DoorbellCard(activity, expanded = true)
        host.addView(card, FrameLayout.LayoutParams(1000, 750))
        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(host, ViewGroup.LayoutParams(1000, 750))
        card.findViewWithTag<ImageView>("door_snapshot").setImageBitmap(fakeFrame())
        card.bind("Домофон", config, DoorbellModel.snapshot(config, readings()), false, 0, null, System.currentTimeMillis(), animate = false)
        card.showHd(true)
        save(host, 1000, 750, "expanded_hd", 400)
        card.dispose()
    }

    @Test fun rendersRingWindow() {
        val app = activity.application
        DoorbellHub.attach(app)
        DoorbellHub.actions = object : DoorbellHub.Actions {
            override fun call(domain: String, service: String, entityId: String, data: Map<String, Any>,
                              onResult: (ServiceCommandResult) -> Unit) = true
            override fun prepareForRing() {}
            override fun tileBounds(tileId: String) = null
        }
        val tile = TileEntity("door", DoorbellConfig.TYPE, "grid", "Калитка", 0, 0, 1, 1, config = config.writeTo("{}"))
        DoorbellHub.onState(listOf(tile), readings())
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val window = DoorbellRingWindow.show(activity, "door", null) {}
        window.card.findViewWithTag<ImageView>("door_snapshot").setImageBitmap(fakeFrame())
        values["binary_sensor.v"] = "on"; values["binary_sensor.p"] = "on"
        DoorbellHub.onState(listOf(tile), readings())
        root.rootView.let { save(it, 1920, 1200, "ring_window", 700) }
        window.findViewWithTag<View>("door_reply").performClick()
        root.rootView.let { save(it, 1920, 1200, "ring_window_replies", 300) }
        window.closeNow()
        activity.resources.displayMetrics.widthPixels = 1200
        val portrait = DoorbellRingWindow.show(activity, "door", null) {}
        portrait.card.findViewWithTag<ImageView>("door_snapshot").setImageBitmap(fakeFrame())
        root.rootView.let { save(it, 1200, 1920, "ring_window_portrait", 700) }
        portrait.closeNow()
    }
}
