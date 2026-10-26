package ch.m4rm0tt.mynetworks.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun internationalFormsAreRecognised() {
        assertEquals(PhoneParse.International("41791234567"), PhoneNumbers.toInternational("+41 79 123 45 67", null))
        assertEquals(PhoneParse.International("41791234567"), PhoneNumbers.toInternational("0041 79 123 45 67", null))
        assertEquals(PhoneParse.International("33612345678"), PhoneNumbers.toInternational("+33 (0)6-12.34.56.78".replace("(0)", ""), null))
    }

    @Test
    fun localNumberIsAmbiguousWithoutDefaultCountry() {
        assertEquals(PhoneParse.Ambiguous, PhoneNumbers.toInternational("079 123 45 67", null))
        assertEquals(PhoneParse.Ambiguous, PhoneNumbers.toInternational("079 123 45 67", ""))
    }

    @Test
    fun localNumberUsesExplicitDefaultCountry() {
        assertEquals(PhoneParse.International("41791234567"), PhoneNumbers.toInternational("079 123 45 67", "+41"))
    }

    @Test
    fun invalidInputs() {
        assertEquals(PhoneParse.Invalid, PhoneNumbers.toInternational("", null))
        assertEquals(PhoneParse.Invalid, PhoneNumbers.toInternational("+41 abc", null))
        assertEquals(PhoneParse.Invalid, PhoneNumbers.toInternational("+12", null))
    }
}
