package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
import com.medmission.survey.data.network.SurveyPayloadMapper
import kotlinx.coroutines.flow.Flow
import java.io.IOException

class SurveyRepository(
    private val surveyDao: SurveyDao,
    private val apiClient: SurveyApiClient,
    private val laptopEndpointDao: LaptopEndpointDao,
    private val apiKey: String,
) {
    suspend fun saveDraft(record: SurveyRecord) {
        surveyDao.upsert(record)
    }

    fun observeAll(): Flow<List<SurveyRecord>> = surveyDao.observeAll()

    suspend fun getById(recordId: String): SurveyRecord? = surveyDao.getById(recordId)

    suspend fun getPendingRecords(): List<SurveyRecord> = surveyDao.getByStatus(SyncStatus.PENDING)

    suspend fun sendToLaptop(recordId: String, laptopId: String): Result<Unit> {
        // Not retryable and nothing in the DB to update — there is no row to count
        // attempts against. Since FormViewModel now persists every record at creation,
        // this should be unreachable in practice.
        val record = surveyDao.getById(recordId)
            ?: return Result.failure(IllegalStateException("Record not found: $recordId"))

        // A deleted endpoint or a stale targetLaptopId must still burn an attempt,
        // otherwise the record retries forever and never reaches FAILED.
        val endpoint = laptopEndpointDao.getById(laptopId)
            ?: return recordFailedAttempt(
                record,
                laptopId,
                IllegalStateException("Laptop endpoint not found: $laptopId"),
            )

        val payload = SurveyPayloadMapper.toDto(record)
        val baseUrl = "http://${endpoint.host}:${endpoint.port}"
        val result = apiClient.sendSurvey(baseUrl, apiKey, payload)

        return if (result.isSuccess) {
            surveyDao.upsert(
                record.copy(
                    status = SyncStatus.SENT,
                    sentAt = System.currentTimeMillis(),
                    targetLaptopId = laptopId,
                )
            )
            Result.success(Unit)
        } else {
            recordFailedAttempt(record, laptopId, result.exceptionOrNull() ?: IOException("Unknown send error"))
        }
    }

    private suspend fun recordFailedAttempt(
        record: SurveyRecord,
        laptopId: String,
        cause: Throwable,
    ): Result<Unit> {
        val attempts = record.sendAttempts + 1
        val newStatus = if (attempts >= MAX_SEND_ATTEMPTS) SyncStatus.FAILED else SyncStatus.PENDING
        surveyDao.upsert(
            record.copy(
                status = newStatus,
                sendAttempts = attempts,
                targetLaptopId = laptopId,
            )
        )
        return Result.failure(cause)
    }

    companion object {
        const val MAX_SEND_ATTEMPTS = 10
    }
}
