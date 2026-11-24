package com.example.smarthomedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QR-код для входа с телефона. Читаемость проверена распознавателем macOS (Vision) на всех версиях 1–10: тест с
 * QR_DUMP=<папка> пишет картинки, `tools/ha-stand/readqr.swift` их читает. Эталон — отпечаток проверенной матрицы.
 */
class QrCodeTest {
    private val link = "http://192.168.1.50:41234/AbCdEfGhIjKlMnOpQrStUv"
    private fun print(qr: QrCode) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(qr.size * qr.size) { i -> if (qr[i % qr.size, i / qr.size]) 1 else 0 })
        .joinToString("") { "%02x".format(it) }.take(16)

    @Test fun linkFitsSmallVersionAndHasFinderPatterns() {
        val qr = QrCode.encode(link)
        assertEquals(4, qr.version)
        assertEquals(33, qr.size)
        for ((ox, oy) in listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)) for (y in 0..6) for (x in 0..6) {
            val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
            assertEquals("узор $ox,$oy: $x,$y", ring != 2, qr[ox + x, oy + y])
        }
        assertTrue("тёмный модуль", qr[8, qr.size - 8])
        for (i in 8 until qr.size - 8) { assertEquals(i % 2 == 0, qr[i, 6]); assertEquals(i % 2 == 0, qr[6, i]) }
    }

    @Test fun matrixIsTheOneCheckedWithScanner() {
        assertEquals(GOLDEN, print(QrCode.encode(link)))
    }

    @Test fun everyVersionEncodesAndTooLongIsRefused() {
        val lengths = mapOf(1 to 14, 2 to 26, 3 to 42, 4 to 62, 5 to 84, 6 to 106, 7 to 122, 8 to 152, 9 to 180, 10 to 213)
        for ((version, length) in lengths) {
            assertEquals(version, QrCode.encode("a".repeat(length)).version)
            if (version < 10) assertEquals(version + 1, QrCode.encode("a".repeat(length + 1)).version)
        }
        assertThrows(IllegalArgumentException::class.java) { QrCode.encode("a".repeat(214)) }
    }

    /** Картинки для внешней проверки (PBM): QR_DUMP=<папка>. */
    @Test fun dumpForScanner() {
        val dir = System.getenv("QR_DUMP") ?: return
        val texts = listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213).map { n -> (0 until n).joinToString("") { ('a' + (it * 7 + n) % 26).toString() } } +
            link + "Панель — вход"
        texts.forEachIndexed { i, text ->
            val qr = QrCode.encode(text)
            val scale = 8
            val side = (qr.size + 8) * scale
            val rows = (0 until side).joinToString("\n") { y -> (0 until side).joinToString(" ") { x ->
                val mx = x / scale - 4; val my = y / scale - 4
                if (mx in 0 until qr.size && my in 0 until qr.size && qr[mx, my]) "0" else "255"
            } }
            File(dir, "qr$i.pgm").writeText("P2\n$side $side\n255\n$rows\n")
            File(dir, "qr$i.txt").writeText(text)
        }
        println("QR link " + print(QrCode.encode(link)))
    }

    private companion object { const val GOLDEN = "61daa998b5ce8695" }
}
