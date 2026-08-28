package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.isUntouched
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
import com.medmission.survey.data.network.SurveyPayloadMapper
import com.medmission.survey.data.network.UnauthorizedException
import kotlinx.coroutines.flow.Flow
import java.io.IOException

class SurveyRepository(
    private val surveyDao: SurveyDao,
    private val apiClient: SurveyApiClient,
    private val laptopEndpointDao: LaptopEndpointDao,
    /** Fallback for endpoints saved without one; set at build time with -PsurveyApiKey. */
    private val apiKey: String,
    /** What the operator typed, in the one form every later system agrees on. Defaults
     *  to leaving it alone so a caller without phone metadata still works. */
    private val normalisePhone: (String, String?) -> String? = { typed, _ -> typed },
) {
    /**
     * Throws away a draft nobody filled in.
     *
     * Opening the form creates the row before a single field is typed, so that a
     * straight-to-Done record exists to send and a tablet that dies mid-form loses
     * nothing. The cost is that backing out — or an app restart that lands on the form —
     * leaves an empty record and burns a patient number. Only genuinely untouched drafts
     * go; anything with a keystroke in it is kept, because a half-filled survey is
     * somebody's work.
     */
    suspend fun discardIfUntouched(recordId: String): Boolean {
        val record = surveyDao.getById(recordId) ?: return false
        if (record.status != SyncStatus.DRAFT || !record.isUntouched()) return false
        surveyDao.deleteById(recordId)
        return true
    }

    suspend fun saveDraft(record: SurveyRecord) {
        surveyDao.upsert(record)
    }

    fun observeAll(): Flow<List<SurveyRecord>> = surveyDao.observeAll()

    suspend fun getById(recordId: String): SurveyRecord? = surveyDao.getById(recordId)

    suspend fun getPendingRecords(): List<SurveyRecord> = surveyDao.getByStatus(SyncStatus.PENDING)

    suspend fun countAll(): Int = surveyDao.countAll()

    /**
     * What the X-ray side did with a survey this tablet sent — or null when the laptop
     * cannot be asked right now. Display-only: nothing is stored, the next look asks again.
     */
    suspend fun fetchXrayStatus(record: SurveyRecord): String? {
        val laptopId = record.targetLaptopId ?: return null
        val endpoint = laptopEndpointDao.getById(laptopId) ?: return null
        val baseUrl = "http://${endpoint.host}:${endpoint.port}"
        return apiClient
            .getSurveyStatus(baseUrl, endpoint.apiKey.ifBlank { apiKey }, record.recordId)
            .getOrNull()
    }

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

        // The intent to send goes on disk before the wire is touched. This runs in the
        // send screen's scope, which dies when the operator presses Back, when the screen
        // times out, or when Android reaps the backgrounded app — all of them easy while
        // the tablet waits out a connect timeout on a sleeping laptop. A record still
        // DRAFT at that moment is never picked up again, because the retry worker only
        // looks at PENDING. Claimed first, an interruption costs a retry rather than the
        // patient.
        val claimed = record.copy(status = SyncStatus.PENDING, targetLaptopId = laptopId)
        if (record.status != claimed.status || record.targetLaptopId != laptopId) {
            surveyDao.upsert(claimed)
        }

        val payload = SurveyPayloadMapper.toDto(claimed, normalisePhone)
        val baseUrl = "http://${endpoint.host}:${endpoint.port}"
        // Each bridge generates its own key, so the endpoint's key wins; the
        // build-time one only covers endpoints saved before a key was entered.
        val result = apiClient.sendSurvey(baseUrl, endpoint.apiKey.ifBlank { apiKey }, payload)

        return if (result.isSuccess) {
            surveyDao.upsert(
                claimed.copy(
                    status = SyncStatus.SENT,
                    sentAt = System.currentTimeMillis(),
                )
            )
            Result.success(Unit)
        } else {
            val cause = result.exceptionOrNull() ?: IOException("Unknown send error")
            // A rejected key cannot come good on its own. Retrying it ten times only
            // delays the moment someone notices and fixes the key on this laptop.
            if (cause is UnauthorizedException) recordRejectedKey(claimed, laptopId, cause)
            else recordFailedAttempt(claimed, laptopId, cause)
        }
    }

    private suspend fun recordRejectedKey(
        record: SurveyRecord,
        laptopId: String,
        cause: Throwable,
    ): Result<Unit> {
        surveyDao.upsert(
            record.copy(
                status = SyncStatus.FAILED,
                sendAttempts = record.sendAttempts + 1,
                targetLaptopId = laptopId,
            )
        )
        return Result.failure(cause)
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
