package com.medmission.survey

import android.app.Application
import android.provider.Settings
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
import com.medmission.survey.data.psgc.PsgcRepository
import com.medmission.survey.data.settings.AppSettings
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import com.medmission.survey.util.PhoneFormatter
import com.medmission.survey.util.devicePrefixFrom
import com.medmission.survey.work.SurveyRetryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SurveyApplication : Application(), Configuration.Provider {

    private val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "medmission-survey.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4)
            .build()
    }

    private val apiClient by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        OkHttpSurveyApiClient(httpClient, Json { ignoreUnknownKeys = true })
    }

    val surveyRepository by lazy {
        SurveyRepository(
            database.surveyDao(), apiClient, database.laptopEndpointDao(),
            BuildConfig.SURVEY_API_KEY,
            // The country a record was collected in decides how its number reads; when
            // the record does not say, this tablet's setting does. An unparseable
            // number is sent as typed rather than dropped — a wrong-looking number an
            // operator can still read beats no number at all.
            normalisePhone = { typed, country ->
                phoneFormatter.toE164(typed, country ?: appSettings.effectiveCountryCode) ?: typed
            },
        )
    }

    val laptopEndpointRepository by lazy {
        LaptopEndpointRepository(database.laptopEndpointDao())
    }

    val nsdDiscoveryService: NsdDiscoveryService by lazy {
        AndroidNsdDiscoveryService(this)
    }

    val psgcRepository: PsgcRepository by lazy {
        PsgcRepository(this)
    }

    val appSettings by lazy { AppSettings(this) }

    val phoneFormatter by lazy { PhoneFormatter(this) }

    // Distinguishes this tablet's records from every other tablet's in the "No." field —
    // ANDROID_ID is stable for the life of the install (survives app updates, resets only
    // on factory reset), so records numbered on this device keep a consistent prefix.
    val devicePrefix: String by lazy {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        devicePrefixFrom(androidId)
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

    // No existing app-wide CoroutineScope to reuse (SurveyNavGraph uses a Compose
    // rememberCoroutineScope local to the nav host); this one exists solely to warm the PSGC
    // cache below and is intentionally never cancelled — it lives as long as the process.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SurveyRetryWorker.enqueuePeriodic(this)
        // Fire-and-forget: parses the ~1.5MB/~45,000-node PSGC dataset once, early, off the
        // main thread, so FormScreen's later access just reads the already-populated `by
        // lazy` value instead of freezing the UI on the form's first open. If the user opens
        // the form before this finishes, FormScreen's own synchronous access is still a
        // correct (if slower) fallback.
        applicationScope.launch { psgcRepository.warmUp() }
    }
}
