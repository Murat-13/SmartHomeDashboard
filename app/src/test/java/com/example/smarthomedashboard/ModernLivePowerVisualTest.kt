package com.example.smarthomedashboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Синтетические плитки: артефакт для проверки компоновки, без данных и подключения дома. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernLivePowerVisualTest {
    @Test fun renderCompactAndLongTitlesWithOptionalPower() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bitmap = Bitmap.createBitmap(900, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(0xFF09131F.toInt())
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
        val powers = listOf(null, "0 Вт", "648 Вт", "1,82 кВт", "— Вт")
        powers.forEachIndexed { i, power ->
            listOf(120, 160).forEachIndexed { row, size ->
                val view = ModernTileView(host, ModernTileView.Kind.SINGLE)
                view.bind(ModernTileView.Model(if (row == 0) "Бойлер" else "Кондиционер спальни",
                    ModernIcon.BOILER, if (i == 1) ConfirmedState.OFF else ConfirmedState.ON, powerText = power), false)
                view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, size, size)
                assertEquals(size, view.width); assertEquals(size, view.height)
                canvas.save(); canvas.translate(10f + i * 178f, 32f + row * 190f); view.draw(canvas); canvas.restore()
                if (row == 0) canvas.drawText(power ?: "Выключена опция", 12f + i * 178f, 20f, label)
            }
        }
        val path = File("build/reports/modern-power-visual/buttons.png")
        path.parentFile!!.mkdirs()
        path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(path.length() > 5000)
        bitmap.recycle()
    }
}
