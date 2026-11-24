package com.example.smarthomedashboard

import android.graphics.Bitmap
import android.graphics.Color

/**
 * QR-код для входа с телефона (docs/setup-tz.md): байтовый режим, уровень коррекции M, версии 1–10 (до 213 байт) —
 * ссылке на страницу планшета хватает версии 4. Своя реализация ISO/IEC 18004, чтобы не добавлять библиотеку.
 */
internal class QrCode private constructor(val version: Int, private val modules: Array<BooleanArray>) {
    val size get() = modules.size
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    /** Картинка: [scale] точек на модуль и белое поле 4 модуля. */
    fun bitmap(scale: Int): Bitmap {
        val side = (size + 2 * QUIET) * scale
        val pixels = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / scale - QUIET
            val my = y / scale - QUIET
            pixels[y * side + x] = if (mx in 0 until size && my in 0 until size && modules[my][mx]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.RGB_565)
    }

    /** Построение матрицы одной версии. */
    private class Builder(val version: Int) {
        val size = 17 + 4 * version
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; reserved[y][x] = true }

        fun functionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val centers = ALIGNMENT[version]
            for (a in centers) for (b in centers) {
                val corner = a == 6 && b == 6 || a == 6 && b == centers.last() || a == centers.last() && b == 6
                if (!corner) alignment(a, b)
            }
            format(0) // место занято; настоящие биты — после выбора маски
            if (version >= 7) versionInfo()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, ring != 2 && ring != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        /** Уровень M — биты 00; [mask] — номер маски. */
        fun format(mask: Int) {
            var rest = mask
            repeat(10) { rest = (rest shl 1) xor ((rest ushr 9) * 0x537) }
            val bits = ((mask shl 10) or rest) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun versionInfo() {
            var rest = version
            repeat(12) { rest = (rest shl 1) xor ((rest ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rest
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark); set(b, a, dark)
            }
        }

        /** Данные змейкой снизу вверх парами столбцов, столбец 6 пропускается. */
        fun data(codewords: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (step in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - step else step
                    if (!reserved[y][x] && i < codewords.size * 8) {
                        modules[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (reserved[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        fun penalty(): Int {
            var score = 0
            for (line in 0 until 2 * size) {
                val at: (Int) -> Boolean = if (line < size) { i -> modules[line][i] } else { i -> modules[i][line - size] }
                var run = 1
                for (i in 1..size) {
                    if (i < size && at(i) == at(i - 1)) run++
                    else { if (run >= 5) score += run - 2; run = 1 }
                }
                for (i in 0..size - 11) {
                    val forward = FINDER_LIKE.indices.all { at(i + it) == FINDER_LIKE[it] }
                    val backward = FINDER_LIKE.indices.all { at(i + it) == FINDER_LIKE[FINDER_LIKE.size - 1 - it] }
                    if (forward || backward) score += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            score += (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total * 10 - 10
            return score
        }
    }

    companion object {
        private const val QUIET = 4
        /** Темно-светлая последовательность, похожая на поисковый узор (штраф маски). */
        private val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        private val TOTAL = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
        private val ECC = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private val ALIGNMENT = arrayOf(intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50))
        private const val MAX_VERSION = 10
        private const val MASKS = 8

        private fun dataWords(version: Int) = TOTAL[version] - ECC[version] * BLOCKS[version]

        /** Слишком длинный текст — [IllegalArgumentException]. */
        fun encode(text: String): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { 4 + countBits(it) + bytes.size * 8 <= dataWords(it) * 8 }
                ?: throw IllegalArgumentException("QR: текст длиннее ${bytes.size} байт не помещается")
            val words = interleave(version, dataCodewords(version, bytes))
            val best = (0 until MASKS).map { mask ->
                Builder(version).apply { functionPatterns(); data(words); applyMask(mask); format(mask) }
            }.minBy { it.penalty() }
            return QrCode(version, best.modules)
        }

        private fun countBits(version: Int) = if (version < 10) 8 else 16

        private fun dataCodewords(version: Int, bytes: ByteArray): IntArray {
            val capacity = dataWords(version)
            val bits = ArrayList<Boolean>(capacity * 8)
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            put(0b0100, 4)
            put(bytes.size, countBits(version))
            bytes.forEach { put(it.toInt() and 0xff, 8) }
            put(0, minOf(4, capacity * 8 - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity * 8) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return IntArray(capacity) { w -> (0..7).fold(0) { acc, b -> (acc shl 1) or (if (bits[w * 8 + b]) 1 else 0) } }
        }

        private fun interleave(version: Int, data: IntArray): IntArray {
            val blocks = BLOCKS[version]
            val ecc = ECC[version]
            val short = data.size / blocks
            val longBlocks = data.size % blocks
            val generator = generator(ecc)
            var at = 0
            val parts = (0 until blocks).map { b ->
                val length = short + if (b >= blocks - longBlocks) 1 else 0
                data.copyOfRange(at, at + length).also { at += length }
            }
            val checks = parts.map { remainder(it, generator) }
            val out = ArrayList<Int>(TOTAL[version])
            for (i in 0..short) parts.forEach { if (i < it.size) out.add(it[i]) }
            for (i in 0 until ecc) checks.forEach { out.add(it[i]) }
            return out.toIntArray()
        }

        // Поле Галуа GF(256), многочлен 0x11D.
        private fun multiply(a: Int, b: Int): Int {
            var result = 0
            for (i in 7 downTo 0) {
                result = (result shl 1) xor ((result ushr 7) * 0x11D)
                result = result xor (((b ushr i) and 1) * a)
            }
            return result
        }

        private fun generator(degree: Int): IntArray {
            val poly = IntArray(degree).also { it[degree - 1] = 1 }
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    poly[j] = multiply(poly[j], root)
                    if (j + 1 < degree) poly[j] = poly[j] xor poly[j + 1]
                }
                root = multiply(root, 2)
            }
            return poly
        }

        private fun remainder(data: IntArray, generator: IntArray): IntArray {
            val result = IntArray(generator.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(generator[i], factor)
            }
            return result
        }
    }
}
