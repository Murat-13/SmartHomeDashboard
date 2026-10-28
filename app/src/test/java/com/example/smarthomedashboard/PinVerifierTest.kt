package com.example.smarthomedashboard

import org.junit.Assert.*
import org.junit.Test

class PinVerifierTest {
    private val verifier = PinVerifier()
    @Test fun correctAndIncorrectPinAreDistinguishedWithoutRetainingInput() {
        val input = "135790".toCharArray()
        val record = verifier.create(input, PinVerifier.Purpose.PIN)
        assertEquals(PinVerifier.Verdict.MATCH, verifier.verify(input, record.encoded, PinVerifier.Purpose.PIN))
        assertEquals(PinVerifier.Verdict.MISMATCH, verifier.verify("135791".toCharArray(), record.encoded, PinVerifier.Purpose.PIN))
        assertEquals("PinVerifierRecord", record.toString())
        assertFalse(record.encoded.contains("135790"))
    }
    @Test fun saltsDifferAndPurposeCannotBeChanged() {
        val a = verifier.create("1234".toCharArray(), PinVerifier.Purpose.PIN)
        val b = verifier.create("1234".toCharArray(), PinVerifier.Purpose.PIN)
        assertNotEquals(a.encoded, b.encoded)
        assertEquals(PinVerifier.Verdict.MATCH, verifier.verify("1234".toCharArray(), a.encoded, PinVerifier.Purpose.PIN))
        assertEquals(PinVerifier.Verdict.INVALID_RECORD, verifier.verify("1234".toCharArray(), a.encoded, PinVerifier.Purpose.RECOVERY))
    }
    @Test fun malformedAndUnboundedWorkFactorsFailClosed() {
        for(record in listOf("", "2:PIN:x:1:a:b", "1:PIN:PBKDF2WithHmacSHA256:2147483647:a:b", "x".repeat(513))) {
            assertEquals(PinVerifier.Verdict.INVALID_RECORD, verifier.verify("123456".toCharArray(), record, PinVerifier.Purpose.PIN))
        }
    }
    @Test fun newPinPolicyDoesNotAcceptOtherCharactersOrLengths() {
        assertTrue(PinVerifier.validNewPin("123456".toCharArray()))
        for(pin in listOf("", "1234", "1234567", "１２３４５６", "12 456", "abcdef")) assertFalse(PinVerifier.validNewPin(pin.toCharArray()))
    }
    @Test fun recoveryCodeHasIndependentRandomnessAndFixedFormat() {
        val a=verifier.newRecoveryCode();val b=verifier.newRecoveryCode()
        assertEquals(47,a.size);assertNotEquals(String(a),String(b))
        assertTrue(String(a).matches(Regex("[0-9A-F]{5}(-[0-9A-F]{5}){7}")))
        a.fill(' ');b.fill(' ')
    }
    @Test fun savedSha1AndSha256RecordsKeepTheirOriginalWorkFactors() {
        val input = "135790".toCharArray()
        val salt = ByteArray(32) { 7 }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            for ((algorithm, cost) in listOf("PBKDF2WithHmacSHA1" to 1_400_000, "PBKDF2WithHmacSHA256" to 600_000)) {
                val spec = javax.crypto.spec.PBEKeySpec(input, "PIN".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + salt, cost, 256)
                val digest = try { javax.crypto.SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded }
                    finally { spec.clearPassword() }
                val record = "1:PIN:$algorithm:$cost:${hex(salt)}:${hex(digest)}"
                digest.fill(0)
                assertEquals(PinVerifier.Verdict.MATCH, verifier.verify(input, record, PinVerifier.Purpose.PIN))
                assertEquals(PinVerifier.Verdict.MISMATCH, verifier.verify("135791".toCharArray(), record, PinVerifier.Purpose.PIN))
            }
        } finally {
            input.fill(' ')
            salt.fill(0)
        }
    }

}
