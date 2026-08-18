package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
import com.medmission.survey.data.network.UnauthorizedException
import com.medmission.survey.data.network.SurveyPayloadDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeSurveyDao : SurveyDao {
    val records = mutableMapOf<String, SurveyRecord>()
    override suspend fun upsert(record: SurveyRecord) { records[record.recordId] = record }
    override suspend fun getById(recordId: String): SurveyRecord? = records[recordId]
    override fun observeAll(): Flow<List<SurveyRecord>> = flowOf(records.values.toList())
    override suspend fun getByStatus(status: SyncStatus): List<SurveyRecord> =
        records.values.filter { it.status == status }
    override suspend fun countAll(): Int = records.size
}

private class FakeLaptopEndpointDao : LaptopEndpointDao {
    val endpoints = mutableMapOf<String, LaptopEndpoint>()
    override suspend fun upsert(endpoint: LaptopEndpoint) { endpoints[endpoint.id] = endpoint }
    override suspend fun getById(id: String): LaptopEndpoint? = endpoints[id]
    override fun observeAll(): Flow<List<LaptopEndpoint>> = flowOf(endpoints.values.toList())
}

private class FakeSurveyApiClient(private val result: Result<Unit>) : SurveyApiClient {
    var lastCallBaseUrl: String? = null
    var lastCallApiKey: String? = null
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> {
        lastCallBaseUrl = baseUrl
        lastCallApiKey = apiKey
        return result
    }
}

/** Fails the first call, succeeds afterwards, and records every payload it saw. */
private class FailsOnceThenSucceedsApiClient : SurveyApiClient {
    val payloads = mutableListOf<SurveyPayloadDto>()
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> {
        payloads += payload
        return if (payloads.size == 1) Result.failure(IOException("boom")) else Result.success(Unit)
    }
}

class SurveyRepositoryTest {
    private val laptop = LaptopEndpoint(id = "laptop-1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)

    @Test
    fun `sends the key stored on the endpoint`() = runTest {
        // Each bridge generates its own key, so the key belongs to the laptop, not
        // to the app: two laptops at one site do not share one.
        val surveyDao = FakeSurveyDao()
        val endpoint = laptop.copy(apiKey = "C79QS-CQ8RM-5QRWU-ABDEE")
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[endpoint.id] = endpoint }
        val record = SurveyRecord(firstName = "Ana")
        surveyDao.records[record.recordId] = record
        val api = FakeSurveyApiClient(Result.success(Unit))

        SurveyRepository(surveyDao, api, endpointDao, "built-in-key")
            .sendToLaptop(record.recordId, endpoint.id)

        assertEquals("C79QS-CQ8RM-5QRWU-ABDEE", api.lastCallApiKey)
    }

    @Test
    fun `falls back to the build-time key when the endpoint has none`() = runTest {
        // Endpoints saved before per-laptop keys existed, and sites that build the
        // APK with their own key, both land here.
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(firstName = "Ana")
        surveyDao.records[record.recordId] = record
        val api = FakeSurveyApiClient(Result.success(Unit))

        SurveyRepository(surveyDao, api, endpointDao, "built-in-key")
            .sendToLaptop(record.recordId, laptop.id)

        assertEquals("built-in-key", api.lastCallApiKey)
    }

    @Test
    fun `a rejected key fails the record at once instead of retrying for hours`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(firstName = "Ana")
        surveyDao.records[record.recordId] = record
        val api = FakeSurveyApiClient(Result.failure(UnauthorizedException("HTTP 401")))

        val result = SurveyRepository(surveyDao, api, endpointDao, "wrong-key")
            .sendToLaptop(record.recordId, laptop.id)

        assertTrue(result.isFailure)
        // Nothing about the key changes on its own, so the ten retries would all fail
        // the same way; FAILED is what surfaces it to the person holding the tablet.
        assertEquals(SyncStatus.FAILED, surveyDao.records[record.recordId]!!.status)
    }

    @Test
    fun `sendToLaptop marks record SENT and records sentAt on success`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(firstName = "Ana")
        surveyDao.records[record.recordId] = record
        val repository = SurveyRepository(surveyDao, FakeSurveyApiClient(Result.success(Unit)), endpointDao, apiKey = "key")

        val result = repository.sendToLaptop(record.recordId, laptop.id)

        assertTrue(result.isSuccess)
        val stored = surveyDao.getById(record.recordId)!!
        assertEquals(SyncStatus.SENT, stored.status)
        assertTrue(stored.sentAt != null)
        assertEquals(laptop.id, stored.targetLaptopId)
    }

    @Test
    fun `sendToLaptop marks record PENDING and increments attempts on failure below threshold`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(sendAttempts = 3)
        surveyDao.records[record.recordId] = record
        val repository = SurveyRepository(
            surveyDao,
            FakeSurveyApiClient(Result.failure(IOException("boom"))),
            endpointDao,
            apiKey = "key",
        )

        val result = repository.sendToLaptop(record.recordId, laptop.id)

        assertTrue(result.isFailure)
        val stored = surveyDao.getById(record.recordId)!!
        assertEquals(SyncStatus.PENDING, stored.status)
        assertEquals(4, stored.sendAttempts)
    }

    @Test
    fun `sendToLaptop marks record FAILED once attempts reach the max threshold`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(sendAttempts = SurveyRepository.MAX_SEND_ATTEMPTS - 1)
        surveyDao.records[record.recordId] = record
        val repository = SurveyRepository(
            surveyDao,
            FakeSurveyApiClient(Result.failure(IOException("boom"))),
            endpointDao,
            apiKey = "key",
        )

        repository.sendToLaptop(record.recordId, laptop.id)

        val stored = surveyDao.getById(record.recordId)!!
        assertEquals(SyncStatus.FAILED, stored.status)
        assertEquals(SurveyRepository.MAX_SEND_ATTEMPTS, stored.sendAttempts)
    }

    @Test
    fun `a retry re-sends the same recordId so the bridge can upsert idempotently`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord(firstName = "Ana", lastName = "Reyes")
        surveyDao.records[record.recordId] = record
        val apiClient = FailsOnceThenSucceedsApiClient()
        val repository = SurveyRepository(surveyDao, apiClient, endpointDao, apiKey = "key")

        val first = repository.sendToLaptop(record.recordId, laptop.id)
        val second = repository.sendToLaptop(record.recordId, laptop.id)

        assertTrue(first.isFailure)
        assertTrue(second.isSuccess)
        assertEquals(2, apiClient.payloads.size)
        // The identity the bridge upserts on must be stable across the retry.
        assertEquals(record.recordId, apiClient.payloads[0].recordId)
        assertEquals(record.recordId, apiClient.payloads[1].recordId)
        assertEquals(apiClient.payloads[0].recordId, apiClient.payloads[1].recordId)
        // ...and so must the survey content itself.
        assertEquals(apiClient.payloads[0].patient, apiClient.payloads[1].patient)

        val stored = surveyDao.getById(record.recordId)!!
        assertEquals(SyncStatus.SENT, stored.status)
    }

    @Test
    fun `sendToLaptop counts an attempt when the laptop endpoint no longer exists`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao() // endpoint deliberately absent
        val record = SurveyRecord(sendAttempts = 3)
        surveyDao.records[record.recordId] = record
        val repository = SurveyRepository(surveyDao, FakeSurveyApiClient(Result.success(Unit)), endpointDao, apiKey = "key")

        val result = repository.sendToLaptop(record.recordId, "deleted-laptop")

        assertTrue(result.isFailure)
        val stored = surveyDao.getById(record.recordId)!!
        assertEquals(4, stored.sendAttempts)
        assertEquals(SyncStatus.PENDING, stored.status)
    }

    @Test
    fun `a record with a stale laptop id eventually reaches FAILED instead of retrying forever`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao()
        val record = SurveyRecord(sendAttempts = SurveyRepository.MAX_SEND_ATTEMPTS - 1)
        surveyDao.records[record.recordId] = record
        val repository = SurveyRepository(surveyDao, FakeSurveyApiClient(Result.success(Unit)), endpointDao, apiKey = "key")

        repository.sendToLaptop(record.recordId, "deleted-laptop")

        assertEquals(SyncStatus.FAILED, surveyDao.getById(record.recordId)!!.status)
    }

    @Test
    fun `sendToLaptop builds the base url from the endpoint host and port`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val record = SurveyRecord()
        surveyDao.records[record.recordId] = record
        val apiClient = FakeSurveyApiClient(Result.success(Unit))
        val repository = SurveyRepository(surveyDao, apiClient, endpointDao, apiKey = "key")

        repository.sendToLaptop(record.recordId, laptop.id)

        assertEquals("http://192.168.1.10:8080", apiClient.lastCallBaseUrl)
    }
}
