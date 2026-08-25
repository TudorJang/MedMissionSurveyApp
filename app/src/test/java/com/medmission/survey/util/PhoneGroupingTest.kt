package com.medmission.survey.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Cell Phone field looks like while it is being typed, per country.
 *
 * The Philippine form has always shown `0917-123-4567`, and the global form does not
 * impose that shape on everyone: it groups the way each country writes its own numbers,
 * which is what an operator is comparing against a piece of paper or an ID. The value
 * that leaves the tablet is E.164 either way, so the grouping is a reading aid and
 * nothing downstream depends on it.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneGroupingTest {
    private val formatter by lazy {
        PhoneFormatter(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `each country is grouped the way it writes its own numbers`() {
        // Spaces in Vietnam...
        assertEquals("0912 345 678", formatter.formatAsYouType("0912345678", "VN"))
        // ...dashes in Korea, which happens to look like the Philippine mask...
        assertEquals("010-1234-5678", formatter.formatAsYouType("01012345678", "KR"))
        // ...and parentheses in the United States.
        assertEquals("(415) 555-2671", formatter.formatAsYouType("4155552671", "US"))
    }

    @Test
    fun `the philippine grouping is the same shape the tablet has always shown`() {
        // 0917-123-4567 — four, three, four, which is what the Philippine form's own
        // mask produces, so switching a tablet to the global form and back does not
        // change how a Philippine number reads.
        assertEquals("0917 123 4567", formatter.formatAsYouType("09171234567", "PH"))
        assertEquals("0917-123-4567", formatCellPhoneInput("09171234567"))
    }

    @Test
    fun `however it is grouped on screen the wire value is the same`() {
        for (typed in listOf("0917 123 4567", "0917-123-4567", "09171234567")) {
            assertEquals("+639171234567", formatter.toE164(typed, "PH"))
        }
    }
}
