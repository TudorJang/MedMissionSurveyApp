package com.medmission.survey.ui.home

import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen asks the laptop what became of each sent survey. It is the screen the
 * tablet sits on between patients, so whatever it does, it does all day.
 *
 * Asking about every sent record on every cycle does not scale to a screening day: a site
 * does around 150 studies, each question is a separate request, they run one after
 * another, and a laptop that is asleep or off the network costs the full connect timeout
 * for every one. A cycle then takes longer than the interval and the tablet transmits
 * continuously — on battery, sharing one access point with the survey uploads that
 * actually matter.
 *
 * Two things make it bounded: a study that has finished will not change again, and only
 * so many can be in flight at once.
 */
class XrayPollingTest {

    private fun sent(id: String) = SurveyRecord(recordId = id, status = SyncStatus.SENT)

    @Test
    fun `only sent records are asked about`() {
        val records = listOf(
            sent("a"),
            SurveyRecord(recordId = "b", status = SyncStatus.DRAFT),
            SurveyRecord(recordId = "c", status = SyncStatus.PENDING),
            SurveyRecord(recordId = "d", status = SyncStatus.FAILED),
        )

        assertEquals(listOf("a"), recordsToPoll(records, known = emptyMap()).map { it.recordId })
    }

    @Test
    fun `a study the console has finished is never asked about again`() {
        val records = listOf(sent("a"), sent("b"), sent("c"))
        val known = mapOf("a" to "Completed", "b" to "Cancelled")

        assertEquals(listOf("c"), recordsToPoll(records, known).map { it.recordId })
    }

    @Test
    fun `a study still moving is asked about again`() {
        val records = listOf(sent("a"), sent("b"))
        val known = mapOf("a" to "Received", "b" to "InProgress")

        assertEquals(listOf("a", "b"), recordsToPoll(records, known).map { it.recordId })
    }

    /** A whole screening day's worth, none of them answered yet. */
    @Test
    fun `a cycle stays bounded however many surveys the day has produced`() {
        val records = (1..150).map { sent("r$it") }

        val toPoll = recordsToPoll(records, known = emptyMap())

        assertTrue("polled ${toPoll.size} in one cycle", toPoll.size <= MAX_PER_CYCLE)
    }

    /** The newest are the ones an operator is standing there waiting on. */
    @Test
    fun `the cap keeps the most recent`() {
        val records = (1..150).map { SurveyRecord(recordId = "r$it", status = SyncStatus.SENT, createdAt = it.toLong()) }

        val toPoll = recordsToPoll(records, known = emptyMap())

        assertEquals("r150", toPoll.first().recordId)
    }

    @Test
    fun `an unknown status is treated as still moving, not as finished`() {
        val records = listOf(sent("a"))

        assertEquals(listOf("a"), recordsToPoll(records, mapOf("a" to "Something New")).map { it.recordId })
    }
}
