package com.medmission.survey

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import android.content.Context
import androidx.work.ListenableWorker
import com.medmission.survey.data.local.AppDatabase
import com.medmission.survey.data.network.AndroidNsdDiscoveryService
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.network.OkHttpSurveyApiClient
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import com.medmission.survey.work.SurveyRetryWorker
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class SurveyApplication : Application(), Configuration.Provider {

    private val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "medmission-survey.db").build()
    }

    private val apiClient by lazy {
        OkHttpSurveyApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    val surveyRepository by lazy {
        SurveyRepository(database.surveyDao(), apiClient, database.laptopEndpointDao(), BuildConfig.SURVEY_API_KEY)
    }

    val laptopEndpointRepository by lazy {
        LaptopEndpointRepository(database.laptopEndpointDao())
    }

    val nsdDiscoveryService: NsdDiscoveryService by lazy {
        AndroidNsdDiscoveryService(this)
    }

    private val appWorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = when (workerClassName) {
            SurveyRetryWorker::class.java.name -> SurveyRetryWorker(appContext, workerParameters, surveyRepository)
            else -> null
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(appWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SurveyRetryWorker.enqueuePeriodic(this)
    }
}
