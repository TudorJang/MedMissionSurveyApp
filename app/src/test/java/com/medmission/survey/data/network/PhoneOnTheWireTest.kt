package com.medmission.survey.data.network

import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.util.PhoneFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneOnTheWireTest {
    private val formatter by lazy { PhoneFormatter(ApplicationProvider.getApplicationContext()) }
    private val normalise: (String, String?) -> String? = { typed, country ->
        formatter.toE164(typed, country ?: "PH") ?: typed
    }

    private fun wire(record: SurveyRecord) =
        SurveyPayloadMapper.toDto(record, normalise).patient?.cellPhone

    @Test
    fun `the field keeps the national grouping and the wire carries E164`() {
        // What the operator sees is what a Vietnamese patient would write down; what
        // leaves the tablet is the form every later system agrees on.
        val record = SurveyRecord(recordId = "r", cellPhone = "0912 345 678", country = "VN")
        assertEquals("+84912345678", wire(record))
    }

    @Test
    fun `a philippine record still goes out as E164`() {
        val record = SurveyRecord(recordId = "r", cellPhone = "0917-123-4567", country = null)
        assertEquals("+639171234567", wire(record))
    }

    @Test
    fun `a number already written internationally is left where it is`() {
        val record = SurveyRecord(recordId = "r", cellPhone = "+63 917 123 4567", country = "PH")
        assertEquals("+639171234567", wire(record))
    }

    @Test
    fun `a number that cannot be parsed is sent as typed rather than dropped`() {
        // A number an operator can still read beats no number at all: this is how a
        // patient with a positive film gets called back.
        val record = SurveyRecord(recordId = "r", cellPhone = "0917 12", country = "PH")
        assertEquals("0917 12", wire(record))
    }

    @Test
    fun `no number stays no number`() {
        assertEquals(null, wire(SurveyRecord(recordId = "r", cellPhone = null)))
    }
}
