package com.medmission.survey.data.psgc

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Manila's barangays are numbered, so the ZIP dataset's name matching never reached any
 * of the 897 of them. The postal district PSA publishes alongside each one does, for the
 * districts the post office does not split in two.
 */
@RunWith(RobolectricTestRunner::class)
class ManilaZipTest {
    private val psgc by lazy { PsgcRepository(ApplicationProvider.getApplicationContext()) }
    private val manila = "City of Manila"

    private fun barangayIn(district: String): String =
        psgc.barangays("National Capital Region (NCR)", null, manila)
            .first { psgc.districtOfForTest(manila, it) == district }

    @Test
    fun `a numbered barangay now resolves through its district`() {
        assertEquals("1004", psgc.zip(manila, barangayIn("Malate")))
        assertEquals("1009", psgc.zip(manila, barangayIn("Santa Ana")))
        assertEquals("1007", psgc.zip(manila, barangayIn("Paco")))
        assertEquals("1011", psgc.zip(manila, barangayIn("Pandacan")))
        assertEquals("1002", psgc.zip(manila, barangayIn("Intramuros")))
    }

    @Test
    fun `districts the post office splits in two stay blank rather than guess`() {
        // Sampaloc is 1008 east and 1015 west, Santa Cruz 1014 north and 1003 south,
        // Tondo 1013 north and 1012 south. PSA gives one district where the post office
        // uses two, and a postcode that is wrong half the time is invisible in a record.
        assertNull(psgc.zip(manila, barangayIn("Sampaloc")))
        assertNull(psgc.zip(manila, barangayIn("Santa Cruz")))
        assertNull(psgc.zip(manila, barangayIn("Tondo I/II")))
    }

    @Test
    fun `the rest of the country is unaffected`() {
        // The district table only ever applies after both name lookups miss.
        assertEquals("4335", psgc.zip("City of Calamba", "Real"))
    }
}
