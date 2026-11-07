package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.fail
import java.io.File
import kotlin.math.abs

/**
 * Скриншот-сравнение (0.9.13, docs/deep-testing.md): рендеры визуальных тестов сверяются с эталонами в
 * `app/src/test/golden/<группа>/<имя>.png`. Любое неслучайное изменение вида плиток (ореол, отступы, бейджи)
 * видно как расхождение. Намеренное изменение дизайна — новые эталоны: `./gradlew testDebugUnitTest -Pgolden.record`.
 * Допуск — антиалиасинг: пиксель «другой», если канал отличается больше чем на [CHANNEL_TOLERANCE], и
 * рендер «другой», если таких пикселей больше [PIXEL_TOLERANCE]. Карта отличий — build/reports/golden-diff.
 */
internal object VisualGolden {
    private const val CHANNEL_TOLERANCE = 40
    private const val PIXEL_TOLERANCE = 0.004
    /** Рендеры с настоящими часами и данными «от текущего момента» (подписи времени меняются) — без эталона. */
    private val LIVE_CLOCK = setOf("home-climate-visual", "power-graph")

    fun verify(rendered: File, bitmap: Bitmap) {
        val group = rendered.parentFile?.name ?: "misc"
        if (group in LIVE_CLOCK) return
        // Эталоны сняты на Linux (как в CI): на macOS шрифты и сглаживание Robolectric другие — сравнение только на Linux,
        // чтобы проверка перед выпуском на Mac владельца не падала на расхождениях платформы.
        if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
        val golden = File("src/test/golden/$group/${rendered.name}")
        val record = System.getProperty("golden.record") != null
        if (record || !golden.isFile) {
            // В CI эталон не создаётся сам: отсутствие эталона — ошибка, а не молчаливый «успех».
            if (!record && System.getenv("CI") != null) fail("Нет эталона ${golden.path}: запишите его -Pgolden.record и закоммитьте")
            golden.parentFile?.mkdirs()
            golden.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val expected = BitmapFactory.decodeFile(golden.path) ?: fail("Эталон ${golden.path} не читается").let { return }
        if (expected.width != bitmap.width || expected.height != bitmap.height) {
            fail("${golden.path}: размер ${bitmap.width}×${bitmap.height}, эталон ${expected.width}×${expected.height}")
        }
        val w = bitmap.width
        val h = bitmap.height
        val actualPixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val expectedPixels = IntArray(w * h).also { expected.getPixels(it, 0, w, 0, 0, w, h) }
        var different = 0
        val diff = IntArray(w * h)
        for (i in actualPixels.indices) {
            val a = actualPixels[i]
            val e = expectedPixels[i]
            val delta = maxOf(abs(Color.red(a) - Color.red(e)), abs(Color.green(a) - Color.green(e)),
                abs(Color.blue(a) - Color.blue(e)), abs(Color.alpha(a) - Color.alpha(e)))
            if (delta > CHANNEL_TOLERANCE) { different++; diff[i] = Color.RED } else diff[i] = (e and 0x00FFFFFF) or 0x40000000
        }
        val share = different.toDouble() / actualPixels.size
        if (share > PIXEL_TOLERANCE) {
            val out = File("build/reports/golden-diff/$group/${rendered.name}")
            out.parentFile?.mkdirs()
            Bitmap.createBitmap(diff, w, h, Bitmap.Config.ARGB_8888).also { map ->
                out.outputStream().use { map.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fail("Вид изменился: ${golden.path} — отличается ${"%.2f".format(share * 100)} % пикселей (карта: ${out.path}). " +
                "Если изменение намеренное — обновите эталоны: -Pgolden.record")
        }
    }
}
