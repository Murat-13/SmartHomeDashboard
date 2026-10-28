package com.example.smarthomedashboard

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Versioned one-way records. No PIN or recovery code is retained by this component. */
internal class PinVerifier(private val random: SecureRandom = SecureRandom()) {
    enum class Purpose {
        PIN,
        RECOVERY,
    }

    enum class Verdict {
        MATCH,
        MISMATCH,
        INVALID_RECORD,
    }

    class Record internal constructor(internal val encoded: String) {
        override fun toString() = "PinVerifierRecord"
    }

    fun create(secret: CharArray, purpose: Purpose, legacyApi24: Boolean = false): Record {
        require(secret.isNotEmpty() && secret.size <= 256)
        val salt = ByteArray(32).also(random::nextBytes)
        val algorithm = if (legacyApi24) "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA512"
        val cost = if (legacyApi24) 1_400_000 else 220_000
        val result = derive(secret, salt, algorithm, cost, purpose)
        return try {
            Record(
                listOf("1", purpose.name, algorithm, cost.toString(), hex(salt), hex(result))
                    .joinToString(":"),
            )
        } finally {
            result.fill(0)
            salt.fill(0)
        }
    }

    fun verify(secret: CharArray, encoded: String, purpose: Purpose): Verdict {
        if (secret.isEmpty() || secret.size > 256 || encoded.length > 512)
            return Verdict.INVALID_RECORD
        val fields = encoded.split(':')
        if (fields.size != 6 || fields[0] != "1" || fields[1] != purpose.name)
            return Verdict.INVALID_RECORD
        val expectedCost =
            when (fields[2]) {
                "PBKDF2WithHmacSHA512" -> 220_000
                "PBKDF2WithHmacSHA256" -> 600_000
                "PBKDF2WithHmacSHA1" -> 1_400_000
                else -> return Verdict.INVALID_RECORD
            }
        if (fields[3] != expectedCost.toString()) return Verdict.INVALID_RECORD
        val salt = unhex(fields[4], 32) ?: return Verdict.INVALID_RECORD
        val expected = unhex(fields[5], 32) ?: return Verdict.INVALID_RECORD
        val actual = derive(secret, salt, fields[2], expectedCost, purpose)
        return try {
            if (MessageDigest.isEqual(expected, actual)) Verdict.MATCH else Verdict.MISMATCH
        } finally {
            salt.fill(0)
            expected.fill(0)
            actual.fill(0)
        }
    }

    fun newRecoveryCode(): CharArray {
        val bytes = ByteArray(20).also(random::nextBytes)
        return try {
            hex(bytes).uppercase(java.util.Locale.ROOT).chunked(5).joinToString("-").toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    private fun derive(
        secret: CharArray,
        salt: ByteArray,
        algorithm: String,
        cost: Int,
        purpose: Purpose,
    ): ByteArray {
        // Domain separation prevents a recovery record being accepted as a PIN record.
        val scopedSalt = purpose.name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + salt
        val spec = PBEKeySpec(secret, scopedSalt, cost, 256)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            scopedSalt.fill(0)
        }
    }

    companion object {
        fun validNewPin(pin: CharArray) = pin.size == 6 && pin.all { it in '0'..'9' }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

        private fun unhex(text: String, count: Int): ByteArray? {
            if (text.length != count * 2 || text.any { it !in '0'..'9' && it !in 'a'..'f' })
                return null
            return ByteArray(count) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
