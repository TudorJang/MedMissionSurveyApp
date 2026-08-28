package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
import com.medmission.survey.data.network.SurveyPayloadDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A survey that was collected but never delivered is a patient who cannot be recalled.
 * The send starts on the laptop-select screen, in a scope that dies when the operator
 * presses Back, when the screen times out, or when Android reaps the backgrounded app —
 * all of which happen while the tablet is waiting out a 5s connect timeout on a sleeping
 * laptop. If the record is still DRAFT at that moment, nothing ever picks it up again:
 * the retry worker only looks at PENDING. So the intent to send has to be on disk before
 * the wire is touched, not after it answers.
 */
class InterruptedSendTest {

    private class Dao : SurveyDao {
        val records = mutableMapOf<String, SurveyRecord>()
        val statusHistory = mutableListOf<SyncStatus>()
        override suspend fun upsert(record: SurveyRecord) {
            records[record.recordId] = record
            statusHistory += record.status
        }
        override suspend fun getById(recordId: String): SurveyRecord? = records[recordId]
        override fun observeAll(): Flow<List<SurveyRecord>> = flowOf(records.values.toList())
        override suspend fun getByStatus(status: SyncStatus): List<SurveyRecord> =
            records.values.filter { it.status == status }
        override suspend fun countAll(): Int = records.size
        override suspend fun deleteById(recordId: String) { records.remove(recordId) }
    }

    private class Endpoints(private val endpoint: LaptopEndpoint) : LaptopEndpointDao {
        override suspend fun upsert(endpoint: LaptopEndpoint) = Unit
        override suspend fun getById(id: String): LaptopEndpoint? =
            endpoint.takeIf { it.id == id }
        override suspend fun getByAddress(host: String, port: Int): LaptopEndpoint? = null
        override fun observeAll(): Flow<List<LaptopEndpoint>> = flowOf(listOf(endpoint))
    }

    /** Stands in for the operator walking away mid-send. */
    private class InterruptedApiClient : SurveyApiClient {
        override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> =
            throw CancellationException("the screen went away")
        override suspend fun getSurveyStatus(baseUrl: String, apiKey: String, recordId: String): Result<String> =
            Result.failure(UnsupportedOperationException("not under test"))
    }

    private val laptop = LaptopEndpoint(id = "lap-1", name = "1번 X-ray실", host = "192.168.1.10", port = 18080)

    private fun repository(dao: Dao) = SurveyRepository(
        surveyDao = dao,
        laptopEndpointDao = Endpoints(laptop),
        apiClient = InterruptedApiClient(),
        apiKey = "k",
    )

    @Test
    fun `an interrupted first send leaves the record PENDING, where the retry worker will find it`() = runTest {
        val dao = Dao()
        val record = SurveyRecord(firstName = "Ana", status = SyncStatus.DRAFT)
        dao.upsert(record)
        val repository = repository(dao)

        runCatching { repository.sendToLaptop(record.recordId, laptop.id) }

        val stranded = dao.getById(record.recordId)!!
        assertEquals(SyncStatus.PENDING, stranded.status)
        assertEquals(laptop.id, stranded.targetLaptopId)
        assertEquals(listOf(stranded), dao.getByStatus(SyncStatus.PENDING))
    }

    @Test
    fun `the record is marked PENDING before the wire is touched, not after it answers`() = runTest {
        val dao = Dao()
        val record = SurveyRecord(firstName = "Ana", status = SyncStatus.DRAFT)
        dao.upsert(record)

        runCatching { repository(dao).sendToLaptop(record.recordId, laptop.id) }

        // DRAFT from the setup above, then PENDING written before the call that never returned.
        assertEquals(listOf(SyncStatus.DRAFT, SyncStatus.PENDING), dao.statusHistory)
    }
}
