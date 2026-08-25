package com.medmission.survey.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneFormattingTest {
    private val formatter by lazy {
        PhoneFormatter(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a philippine number typed the local way becomes E164`() {
        assertEquals("+639171234567", formatter.toE164("0917 123 4567", "PH"))
        assertEquals("+639171234567", formatter.toE164("09171234567", "PH"))
        assertEquals("+639171234567", formatter.toE164("+63 917 123 4567", "PH"))
    }

    @Test
    fun `italy keeps its leading zero and we do not take it away`() {
        // The reason this library is here rather than a strip-the-zero rule of our own:
        // the Italian national number includes the zero, so dropping it would invent a
        // different number entirely.
        assertEquals("+390612345678", formatter.toE164("06 1234 5678", "IT"))
    }

    @Test
    fun `numbers from a few other countries survive the trip`() {
        assertEquals("+14155552671", formatter.toE164("(415) 555-2671", "US"))
        assertEquals("+84912345678", formatter.toE164("0912 345 678", "VN"))
        assertEquals("+821012345678", formatter.toE164("010-1234-5678", "KR"))
    }

    @Test
    fun `a number that country could not issue is refused rather than bent into shape`() {
        assertNull(formatter.toE164("123", "PH"))
        assertNull(formatter.toE164("not a number", "PH"))
        assertFalse(formatter.isValid("0917 123", "PH"))
        assertTrue(formatter.isValid("0917 123 4567", "PH"))
    }

    @Test
    fun `blank stays blank rather than becoming a country code`() {
        assertNull(formatter.toE164("", "PH"))
        assertEquals("", formatter.toDisplay("", "PH"))
    }

    @Test
    fun `display uses the grouping that country writes`() {
        assertEquals("+63 917 123 4567", formatter.toDisplay("09171234567", "PH"))
        assertEquals("+1 415-555-2671", formatter.toDisplay("4155552671", "US"))
    }

    @Test
    fun `the calling code comes from the country`() {
        assertEquals("+63", formatter.callingCodeFor("PH"))
        assertEquals("+84", formatter.callingCodeFor("VN"))
        assertNull(formatter.callingCodeFor("ZZ"))
    }
}
