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
}
