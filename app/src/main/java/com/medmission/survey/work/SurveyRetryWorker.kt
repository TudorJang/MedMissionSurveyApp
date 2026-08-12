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
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SurveyRetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
