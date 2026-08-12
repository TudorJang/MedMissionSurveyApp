package com.medmission.survey.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRecordTest {
    @Test
    fun `a new record defaults to DRAFT status with all survey fields null or empty`() {
        val record = SurveyRecord()

        assertNotNull(record.recordId)
        assertEquals(SyncStatus.DRAFT, record.status)
        assertEquals(0, record.sendAttempts)
        assertEquals(null, record.firstName)
        assertEquals(null, record.no)
        assertTrue(record.medicalHistory.isEmpty())
        assertTrue(record.symptoms.isEmpty())
    }

    @Test
    fun `two freshly constructed records have different recordIds`() {
        val a = SurveyRecord()
        val b = SurveyRecord()
        assertTrue(a.recordId != b.recordId)
    }
}
