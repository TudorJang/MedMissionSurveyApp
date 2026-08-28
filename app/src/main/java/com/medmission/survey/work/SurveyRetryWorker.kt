package com.medmission.survey.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.medmission.survey.data.repository.SurveyRepository
import java.util.concurrent.TimeUnit

class SurveyRetryWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: SurveyRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = repository.getPendingRecords()
        var anyFailure = false
        for (record in pending) {
            val laptopId = record.targetLaptopId ?: continue
            val result = repository.sendToLaptop(record.recordId, laptopId)
            if (result.isFailure) anyFailure = true
        }
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "survey_retry_worker"

        fun enqueuePeriodic(context: Context) {
            // NetworkType.CONNECTED means a network Android has validated as reaching the
            // internet. A screening site's access point has no upstream — Android shows
            // "connected, no internet" — so the constraint is never satisfied and every
            // PENDING survey waits while the laptop sits two metres away answering. What
            // matters here is reaching that laptop, which has nothing to do with the
            // internet, and an unreachable one already costs only a connect timeout. So
            // the worker runs regardless and lets the send decide.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val request = PeriodicWorkRequestBuilder<SurveyRetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            // UPDATE rather than KEEP: a tablet upgraded from an earlier build already
            // has the old request enqueued, and KEEP would leave it on the constraint
            // above — the tablets that need this fix most would never get it.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
