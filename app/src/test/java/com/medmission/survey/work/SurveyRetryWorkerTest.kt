package com.medmission.survey.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
import com.medmission.survey.data.network.SurveyPayloadDto
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private class FakeSurveyDao : SurveyDao {
    val records = mutableMapOf<String, SurveyRecord>()
    override suspend fun upsert(record: SurveyRecord) { records[record.recordId] = record }
    override suspend fun getById(recordId: String): SurveyRecord? = records[recordId]
    override fun observeAll(): Flow<List<SurveyRecord>> = flowOf(records.values.toList())
    override suspend fun getByStatus(status: SyncStatus): List<SurveyRecord> =
        records.values.filter { it.status == status }
}

private class FakeLaptopEndpointDao : LaptopEndpointDao {
    val endpoints = mutableMapOf<String, LaptopEndpoint>()
    override suspend fun upsert(endpoint: LaptopEndpoint) { endpoints[endpoint.id] = endpoint }
    override suspend fun getById(id: String): LaptopEndpoint? = endpoints[id]
    override fun observeAll(): Flow<List<LaptopEndpoint>> = flowOf(endpoints.values.toList())
}

private class AlwaysSucceedsApiClient : SurveyApiClient {
    val payloads = mutableListOf<SurveyPayloadDto>()
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> {
        payloads += payload
        return Result.success(Unit)
    }
}

private class AlwaysFailsApiClient : SurveyApiClient {
    val payloads = mutableListOf<SurveyPayloadDto>()
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> {
        payloads += payload
        return Result.failure(IOException("network down"))
    }
}

@RunWith(RobolectricTestRunner::class)
class SurveyRetryWorkerTest {
    @Test
    fun `retries every PENDING record with a target laptop and reports success`() = runTest {
        val laptop = LaptopEndpoint(id = "laptop-1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val pendingRecord = SurveyRecord(status = SyncStatus.PENDING, targetLaptopId = laptop.id, sendAttempts = 2)
        surveyDao.records[pendingRecord.recordId] = pendingRecord
        val repository = SurveyRepository(surveyDao, AlwaysSucceedsApiClient(), endpointDao, apiKey = "key")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SurveyRetryWorker>(context)
            .setWorkerFactory(FakeWorkerFactory(repository))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(SyncStatus.SENT, surveyDao.getById(pendingRecord.recordId)!!.status)
    }

    @Test
    fun `asks WorkManager to retry when a send fails`() = runTest {
        val laptop = LaptopEndpoint(id = "laptop-1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao().apply { endpoints[laptop.id] = laptop }
        val pendingRecord = SurveyRecord(status = SyncStatus.PENDING, targetLaptopId = laptop.id, sendAttempts = 1)
        surveyDao.records[pendingRecord.recordId] = pendingRecord
        val repository = SurveyRepository(surveyDao, AlwaysFailsApiClient(), endpointDao, apiKey = "key")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SurveyRetryWorker>(context)
            .setWorkerFactory(FakeWorkerFactory(repository))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val stored = surveyDao.getById(pendingRecord.recordId)!!
        assertEquals(SyncStatus.PENDING, stored.status)
        assertEquals(2, stored.sendAttempts)
    }

    @Test
    fun `skips a PENDING record that has no target laptop without forcing a retry`() = runTest {
        val surveyDao = FakeSurveyDao()
        val endpointDao = FakeLaptopEndpointDao()
        val orphan = SurveyRecord(status = SyncStatus.PENDING, targetLaptopId = null, sendAttempts = 0)
        surveyDao.records[orphan.recordId] = orphan
        val apiClient = AlwaysSucceedsApiClient()
        val repository = SurveyRepository(surveyDao, apiClient, endpointDao, apiKey = "key")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SurveyRetryWorker>(context)
            .setWorkerFactory(FakeWorkerFactory(repository))
            .build()

        val result = worker.doWork()

        // Nothing was sent, and the untargeted record did not by itself make the
        // worker report failure — it just isn't the worker's to send.
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(apiClient.payloads.isEmpty())
        val stored = surveyDao.getById(orphan.recordId)!!
        assertEquals(SyncStatus.PENDING, stored.status)
        assertEquals(0, stored.sendAttempts)
    }
}

private class FakeWorkerFactory(
    private val repository: SurveyRepository,
) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = SurveyRetryWorker(appContext, workerParameters, repository)
}
