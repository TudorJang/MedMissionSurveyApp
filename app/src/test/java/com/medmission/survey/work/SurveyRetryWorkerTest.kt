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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto) = Result.success(Unit)
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
