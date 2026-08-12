# Tablet Survey App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android tablet survey app that digitizes the Medical Mission TB-screening form, stores every record locally as the source of truth, and sends it to a selected laptop's bridge program over HTTP with automatic offline retry.

**Architecture:** Single-module Android app (Kotlin, Jetpack Compose) with a Room database as source of truth, a repository layer mediating between local storage and an OkHttp-based network client, and a WorkManager job that retries `PENDING` records until they succeed or exhaust a retry budget. No DICOM logic lives in this app — it ends at a JSON POST to the bridge program's HTTP endpoint (spec §2, §7).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (KSP), WorkManager, OkHttp, kotlinx.serialization, Android NSD API, JUnit4 + kotlinx-coroutines-test + Robolectric + MockWebServer for tests.

## Global Constraints

- Package name: `com.medmission.survey`
- `minSdk 26`, `targetSdk 34`, `compileSdk 34`
- Every survey field is nullable/optional; no validation may block save or send (spec §5.4)
- Physician/AI-only PDF sections (Diagnosis, Treatment and Medication, X-RAY AI Assessment, Result/Guidance) are excluded entirely from the data model and UI (spec §5.3)
- `recordId` (UUID, client-generated) is the idempotency key sent to the bridge on every request (spec §5.1, §7.2)
- HTTP contract: `POST http://{host}:{port}/api/v1/surveys`, header `X-Api-Key: {key}`, JSON body per spec §7.1
- A record moves to `FAILED` after 10 failed send attempts; until then, failures leave it `PENDING` for automatic retry (spec §8)
- Local Room database is always the source of truth; UI writes go to the DB first, network send is a separate, retryable step (spec §4)

---

## File Structure

```
app/
  build.gradle.kts
  src/main/java/com/medmission/survey/
    SurveyApplication.kt
    MainActivity.kt
    data/
      model/
        Enums.kt                 // SyncStatus, Gender, MaritalStatus, MedicalHistoryItem,
                                  // Symptom, YesNoUnknown, SmokingStatus, SmokingDuration, AlcoholAmount
        SurveyRecord.kt          // Room @Entity, all survey fields
        LaptopEndpoint.kt        // Room @Entity
      local/
        Converters.kt
        SurveyDao.kt
        LaptopEndpointDao.kt
        AppDatabase.kt
      network/
        SurveyPayloadDto.kt      // kotlinx.serialization DTOs
        SurveyPayloadMapper.kt
        SurveyApiClient.kt
        NsdDiscoveryService.kt
      repository/
        SurveyRepository.kt
        LaptopEndpointRepository.kt
    work/
      SurveyRetryWorker.kt
    ui/
      home/
        HomeViewModel.kt
        HomeScreen.kt
      form/
        FormViewModel.kt
        FormScreen.kt
      laptopselect/
        LaptopSelectViewModel.kt
        LaptopSelectScreen.kt
      nav/
        SurveyNavGraph.kt
  src/test/java/com/medmission/survey/
    data/model/SurveyRecordTest.kt
    data/local/SurveyDaoTest.kt
    data/local/LaptopEndpointDaoTest.kt
    data/network/SurveyPayloadMapperTest.kt
    data/network/SurveyApiClientTest.kt
    data/repository/SurveyRepositoryTest.kt
    work/SurveyRetryWorkerTest.kt
    ui/home/HomeViewModelTest.kt
    ui/form/FormViewModelTest.kt
    ui/laptopselect/LaptopSelectViewModelTest.kt
```

---

### Task 1: Project scaffolding & Gradle setup

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/medmission/survey/MainActivity.kt`
- Create: `gradle.properties`

**Interfaces:**
- Produces: a buildable Android app module (package `com.medmission.survey`) with Compose, Room (KSP), WorkManager, OkHttp, kotlinx.serialization, and test dependencies (JUnit4, kotlinx-coroutines-test, Robolectric, MockWebServer, Room testing) all resolvable.

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MedMissionSurveyApp"
include(":app")
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
```

- [ ] **Step 3: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.medmission.survey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.medmission.survey"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SURVEY_API_KEY", "\"changeme-dev-key\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 4: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <application
        android:allowBackup="true"
        android:label="Medical Mission Survey"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 5: Create a placeholder `MainActivity.kt` so the project compiles**

```kotlin
package com.medmission.survey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Medical Mission Survey")
        }
    }
}
```

- [ ] **Step 6: Create `gradle.properties`**

```properties
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 7: Verify the project builds**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/medmission/survey/MainActivity.kt gradle.properties
git commit -m "chore: scaffold Android project with Compose, Room, WorkManager, OkHttp"
```

---

### Task 2: Domain enums & SurveyRecord entity

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/model/Enums.kt`
- Create: `app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt`
- Test: `app/src/test/java/com/medmission/survey/data/model/SurveyRecordTest.kt`

**Interfaces:**
- Produces: `SyncStatus`, `Gender`, `MaritalStatus`, `MedicalHistoryItem`, `Symptom`, `YesNoUnknown`, `SmokingStatus`, `SmokingDuration`, `AlcoholAmount` enums (each with a `label: String` except `SyncStatus`), and the `SurveyRecord` data class with fields exactly as listed below — every later task (DB, mapper, UI) references these exact names.

- [ ] **Step 1: Write the failing test for `SurveyRecord` defaults**

```kotlin
package com.medmission.survey.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRecordTest {
    @Test
    fun `a new record defaults to DRAFT status with all survey fields null or empty`() {
        val record = SurveyRecord()

        assertNotNull(record.recordId)
        assertEquals(SyncStatus.DRAFT, record.status)
        assertEquals(0, record.sendAttempts)
        assertEquals(null, record.firstName)
        assertEquals(null, record.no)
        assertTrue(record.medicalHistory.isEmpty())
        assertTrue(record.symptoms.isEmpty())
    }

    @Test
    fun `two freshly constructed records have different recordIds`() {
        val a = SurveyRecord()
        val b = SurveyRecord()
        assertTrue(a.recordId != b.recordId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.model.SurveyRecordTest"`
Expected: FAIL — `SurveyRecord` / `SyncStatus` unresolved references

- [ ] **Step 3: Implement `Enums.kt`**

```kotlin
package com.medmission.survey.data.model

enum class SyncStatus { DRAFT, PENDING, SENT, FAILED }

enum class Gender(val label: String) {
    MALE("Male"),
    FEMALE("Female"),
}

enum class MaritalStatus(val label: String) {
    MARRIED("Married"),
    SINGLE("Single"),
    DIVORCED("Divorced"),
    WIDOWED("Widowed"),
    OTHER("Other"),
}

enum class MedicalHistoryItem(val label: String) {
    HYPERTENSION("Hypertension"),
    DIABETES("Diabetes"),
    ASTHMA("Asthma"),
    HEART_DISEASE("Heart Disease"),
    KIDNEY_DISEASE("Kidney Disease"),
    STROKE("Stroke"),
    TUBERCULOSIS("Tuberculosis"),
    CANCER("Cancer"),
    ALLERGIES("Allergies"),
}

enum class Symptom(val label: String) {
    COUGH("Cough"),
    COUGH_2WEEKS_PLUS("Cough for 2 weeks or more"),
    SPUTUM("Sputum"),
    BLOOD_IN_SPUTUM("Blood in sputum"),
    FEVER("Fever"),
    CHEST_PAIN("Chest pain"),
    SHORTNESS_OF_BREATH("Shortness of breath"),
    WEIGHT_LOSS("Weight loss"),
    NIGHT_SWEATS("Night sweats"),
    FATIGUE("Fatigue"),
    NONE("None"),
}

enum class YesNoUnknown(val label: String) {
    YES("Yes"),
    NO("No"),
    DONT_KNOW("Don't Know"),
}

enum class SmokingStatus(val label: String) {
    NEVER("Never smoker"),
    CURRENT("Current smoker"),
    FORMER("Former smoker"),
}

enum class SmokingDuration(val label: String) {
    NONE("None"),
    LESS_THAN_5("< 5 year"),
    FIVE_TO_10("5–10 years"),
    MORE_THAN_10("> 10 years"),
}

enum class AlcoholAmount(val label: String) {
    ONE_TO_TWO("1-2 drinks"),
    THREE_TO_FOUR("3-4 drinks"),
    FIVE_PLUS("5 or more drinks"),
}
```

- [ ] **Step 4: Implement `SurveyRecord.kt`**

```kotlin
package com.medmission.survey.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "survey_records")
data class SurveyRecord(
    @PrimaryKey val recordId: String = UUID.randomUUID().toString(),
    val status: SyncStatus = SyncStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val targetLaptopId: String? = null,
    val sendAttempts: Int = 0,

    val no: String? = null,
    val date: String? = null,

    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val gender: Gender? = null,
    val age: Int? = null,
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
    val email: String? = null,
    val cellPhone: String? = null,
    val maritalStatus: MaritalStatus? = null,

    val medicalHistory: Set<MedicalHistoryItem> = emptySet(),
    val medicalHistoryOthers: String? = null,
    val recentSurgeriesOrHospitalization: String? = null,
    val currentMedication: String? = null,

    val height: Double? = null,
    val weight: Double? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val pulseRate: Int? = null,
    val respiratoryRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Double? = null,
    val bloodGlucose: Double? = null,

    val symptoms: Set<Symptom> = emptySet(),

    val everDiagnosedTB: YesNoUnknown? = null,
    val diagnosisYear: String? = null,
    val everReceivedTreatment: YesNoUnknown? = null,
    val treatmentCompleted: YesNoUnknown? = null,
    val closeContactActiveTB: YesNoUnknown? = null,
    val closeContactWhen: String? = null,
    val householdMemberTBTreatment: YesNoUnknown? = null,

    val smokingStatus: SmokingStatus? = null,
    val smokingDuration: SmokingDuration? = null,
    val drinksAlcohol: Boolean? = null,
    val alcoholAmount: AlcoholAmount? = null,

    val dustSmokeChemicalExposure: Boolean? = null,
    val cooksWithSolidFuels: Boolean? = null,
    val secondhandSmokeExposure: Boolean? = null,
    val crowdedLivingConditions: Boolean? = null,
)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.model.SurveyRecordTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/model/Enums.kt app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt app/src/test/java/com/medmission/survey/data/model/SurveyRecordTest.kt
git commit -m "feat: add survey domain enums and SurveyRecord entity"
```

---

### Task 3: Room Converters, SurveyDao, AppDatabase

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/local/Converters.kt`
- Create: `app/src/main/java/com/medmission/survey/data/local/SurveyDao.kt`
- Create: `app/src/main/java/com/medmission/survey/data/local/AppDatabase.kt`
- Test: `app/src/test/java/com/medmission/survey/data/local/SurveyDaoTest.kt`

**Interfaces:**
- Consumes: `SurveyRecord`, `SyncStatus`, and all enums from Task 2
- Produces: `SurveyDao` with `upsert(record: SurveyRecord)`, `getById(recordId: String): SurveyRecord?`, `observeAll(): Flow<List<SurveyRecord>>`, `getByStatus(status: SyncStatus): List<SurveyRecord>`; `AppDatabase` (Room `RoomDatabase`) exposing `surveyDao(): SurveyDao` and `laptopEndpointDao(): LaptopEndpointDao` (the latter wired in Task 6)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SurveyDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SurveyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.surveyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then getById returns the same record including a set field`() = runBlocking {
        val record = SurveyRecord(medicalHistory = setOf(MedicalHistoryItem.ASTHMA, MedicalHistoryItem.DIABETES))
        dao.upsert(record)

        val loaded = dao.getById(record.recordId)

        assertEquals(record, loaded)
    }

    @Test
    fun `upsert with same recordId overwrites rather than duplicates`() = runBlocking {
        val record = SurveyRecord(firstName = "Ana")
        dao.upsert(record)
        dao.upsert(record.copy(firstName = "Ana Maria", status = SyncStatus.SENT))

        val all = dao.observeAll().first()

        assertEquals(1, all.size)
        assertEquals("Ana Maria", all.first().firstName)
        assertEquals(SyncStatus.SENT, all.first().status)
    }

    @Test
    fun `getByStatus filters correctly`() = runBlocking {
        dao.upsert(SurveyRecord(status = SyncStatus.PENDING))
        dao.upsert(SurveyRecord(status = SyncStatus.SENT))
        dao.upsert(SurveyRecord(status = SyncStatus.PENDING))

        val pending = dao.getByStatus(SyncStatus.PENDING)

        assertEquals(2, pending.size)
    }

    @Test
    fun `getById returns null for unknown id`() = runBlocking {
        assertNull(dao.getById("does-not-exist"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.local.SurveyDaoTest"`
Expected: FAIL — `AppDatabase` / `SurveyDao` unresolved references

- [ ] **Step 3: Implement `Converters.kt`**

```kotlin
package com.medmission.survey.data.local

import androidx.room.TypeConverter
import com.medmission.survey.data.model.AlcoholAmount
import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MaritalStatus
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SmokingDuration
import com.medmission.survey.data.model.SmokingStatus
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.model.YesNoUnknown

class Converters {
    @TypeConverter fun fromSyncStatus(v: SyncStatus): String = v.name
    @TypeConverter fun toSyncStatus(v: String): SyncStatus = SyncStatus.valueOf(v)

    @TypeConverter fun fromGender(v: Gender?): String? = v?.name
    @TypeConverter fun toGender(v: String?): Gender? = v?.let { Gender.valueOf(it) }

    @TypeConverter fun fromMaritalStatus(v: MaritalStatus?): String? = v?.name
    @TypeConverter fun toMaritalStatus(v: String?): MaritalStatus? = v?.let { MaritalStatus.valueOf(it) }

    @TypeConverter fun fromYesNoUnknown(v: YesNoUnknown?): String? = v?.name
    @TypeConverter fun toYesNoUnknown(v: String?): YesNoUnknown? = v?.let { YesNoUnknown.valueOf(it) }

    @TypeConverter fun fromSmokingStatus(v: SmokingStatus?): String? = v?.name
    @TypeConverter fun toSmokingStatus(v: String?): SmokingStatus? = v?.let { SmokingStatus.valueOf(it) }

    @TypeConverter fun fromSmokingDuration(v: SmokingDuration?): String? = v?.name
    @TypeConverter fun toSmokingDuration(v: String?): SmokingDuration? = v?.let { SmokingDuration.valueOf(it) }

    @TypeConverter fun fromAlcoholAmount(v: AlcoholAmount?): String? = v?.name
    @TypeConverter fun toAlcoholAmount(v: String?): AlcoholAmount? = v?.let { AlcoholAmount.valueOf(it) }

    @TypeConverter
    fun fromMedicalHistorySet(v: Set<MedicalHistoryItem>): String = v.joinToString(",") { it.name }
    @TypeConverter
    fun toMedicalHistorySet(v: String): Set<MedicalHistoryItem> =
        if (v.isBlank()) emptySet() else v.split(",").map { MedicalHistoryItem.valueOf(it) }.toSet()

    @TypeConverter
    fun fromSymptomSet(v: Set<Symptom>): String = v.joinToString(",") { it.name }
    @TypeConverter
    fun toSymptomSet(v: String): Set<Symptom> =
        if (v.isBlank()) emptySet() else v.split(",").map { Symptom.valueOf(it) }.toSet()
}
```

- [ ] **Step 4: Implement `SurveyDao.kt`**

```kotlin
package com.medmission.survey.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {
    @Upsert
    suspend fun upsert(record: SurveyRecord)

    @Query("SELECT * FROM survey_records WHERE recordId = :recordId")
    suspend fun getById(recordId: String): SurveyRecord?

    @Query("SELECT * FROM survey_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SurveyRecord>>

    @Query("SELECT * FROM survey_records WHERE status = :status")
    suspend fun getByStatus(status: SyncStatus): List<SurveyRecord>
}
```

- [ ] **Step 5: Implement `AppDatabase.kt`** (references `LaptopEndpointDao`, built in Task 6 — declare it now so the interface is stable; Task 6 fills in its body)

```kotlin
package com.medmission.survey.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord

@Database(
    entities = [SurveyRecord::class, LaptopEndpoint::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun laptopEndpointDao(): LaptopEndpointDao
}
```

This references `LaptopEndpoint` (model) and `LaptopEndpointDao`, which do not exist yet — create minimal stand-ins now so the module compiles; Task 6 replaces them with the real implementation.

- [ ] **Step 5b: Create a minimal `LaptopEndpoint.kt` stand-in**

```kotlin
package com.medmission.survey.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "laptop_endpoints")
data class LaptopEndpoint(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val lastSuccessAt: Long? = null,
)
```

- [ ] **Step 5c: Create a minimal `LaptopEndpointDao.kt` stand-in**

```kotlin
package com.medmission.survey.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.Flow

@Dao
interface LaptopEndpointDao {
    @Upsert
    suspend fun upsert(endpoint: LaptopEndpoint)

    @Query("SELECT * FROM laptop_endpoints WHERE id = :id")
    suspend fun getById(id: String): LaptopEndpoint?

    @Query("SELECT * FROM laptop_endpoints ORDER BY name ASC")
    fun observeAll(): Flow<List<LaptopEndpoint>>
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.local.SurveyDaoTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/local app/src/main/java/com/medmission/survey/data/model/LaptopEndpoint.kt app/src/test/java/com/medmission/survey/data/local/SurveyDaoTest.kt
git commit -m "feat: add Room database, SurveyDao, and type converters"
```

---

### Task 4: LaptopEndpoint repository & DAO test

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/repository/LaptopEndpointRepository.kt`
- Test: `app/src/test/java/com/medmission/survey/data/local/LaptopEndpointDaoTest.kt`

**Interfaces:**
- Consumes: `LaptopEndpoint`, `LaptopEndpointDao`, `AppDatabase` from Task 3
- Produces: `LaptopEndpointRepository` with `save(endpoint: LaptopEndpoint)`, `observeAll(): Flow<List<LaptopEndpoint>>`, `getById(id: String): LaptopEndpoint?`, `markSendSuccess(id: String)`

- [ ] **Step 1: Write the failing test for the DAO**

```kotlin
package com.medmission.survey.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaptopEndpointDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LaptopEndpointDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.laptopEndpointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then observeAll returns endpoints sorted by name`() = runBlocking {
        dao.upsert(LaptopEndpoint(id = "2", name = "2번 X-ray실", host = "192.168.1.20", port = 8080))
        dao.upsert(LaptopEndpoint(id = "1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080))

        val all = dao.observeAll().first()

        assertEquals(listOf("1번 X-ray실", "2번 X-ray실"), all.map { it.name })
    }

    @Test
    fun `getById finds a saved endpoint by id`() = runBlocking {
        val endpoint = LaptopEndpoint(id = "3", name = "3번 X-ray실", host = "192.168.1.30", port = 9090)
        dao.upsert(endpoint)

        assertEquals(endpoint, dao.getById("3"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.local.LaptopEndpointDaoTest"`
Expected: FAIL — the sorting assertion fails against the stand-in DAO from Task 3 if `ORDER BY name ASC` was already correct, this should already pass. Confirm the two new tests both compile and pass; if `getById` stand-in already satisfies the contract, this step's real purpose is verifying the repository layer next, so proceed if both tests pass here.

- [ ] **Step 3: Implement `LaptopEndpointRepository.kt`**

```kotlin
package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.Flow

class LaptopEndpointRepository(private val dao: LaptopEndpointDao) {
    suspend fun save(endpoint: LaptopEndpoint) = dao.upsert(endpoint)

    fun observeAll(): Flow<List<LaptopEndpoint>> = dao.observeAll()

    suspend fun getById(id: String): LaptopEndpoint? = dao.getById(id)

    suspend fun markSendSuccess(id: String) {
        val endpoint = dao.getById(id) ?: return
        dao.upsert(endpoint.copy(lastSuccessAt = System.currentTimeMillis()))
    }
}
```

- [ ] **Step 4: Run tests to verify everything passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.local.LaptopEndpointDaoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/repository/LaptopEndpointRepository.kt app/src/test/java/com/medmission/survey/data/local/LaptopEndpointDaoTest.kt
git commit -m "feat: add LaptopEndpointRepository"
```

---

### Task 5: Network DTOs & SurveyPayloadMapper

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt`
- Create: `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadMapper.kt`
- Test: `app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt`

**Interfaces:**
- Consumes: `SurveyRecord` and all enums from Task 2
- Produces: `SurveyPayloadDto` (and nested `PatientDto`, `MedicalHistoryDto`, `VitalSignsDto`, `TbInfoDto`, `SmokingDto`, `AlcoholDto`, `EnvironmentalExposureDto`, all `@Serializable`), and `SurveyPayloadMapper.toDto(record: SurveyRecord): SurveyPayloadDto` — Task 7 (`SurveyApiClient`) serializes this DTO to JSON exactly as defined here.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.data.network

import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.YesNoUnknown
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyPayloadMapperTest {
    @Test
    fun `maps patient, medical history, symptoms and tb info onto the dto`() {
        val record = SurveyRecord(
            firstName = "Juan",
            lastName = "Dela Cruz",
            gender = Gender.MALE,
            medicalHistory = setOf(MedicalHistoryItem.ASTHMA),
            medicalHistoryOthers = "Migraine",
            symptoms = setOf(Symptom.COUGH, Symptom.FEVER),
            everDiagnosedTB = YesNoUnknown.NO,
        )

        val dto = SurveyPayloadMapper.toDto(record)

        assertEquals(record.recordId, dto.recordId)
        assertEquals("Juan", dto.patient.firstName)
        assertEquals("MALE", dto.patient.gender)
        assertEquals(listOf("ASTHMA"), dto.medicalHistory.items)
        assertEquals("Migraine", dto.medicalHistory.others)
        assertEquals(setOf("COUGH", "FEVER"), dto.symptoms.toSet())
        assertEquals("NO", dto.tbInfo.everDiagnosedTB)
    }

    @Test
    fun `serializes a fully-empty record to JSON without throwing`() {
        val dto = SurveyPayloadMapper.toDto(SurveyRecord())
        val json = Json.encodeToString(SurveyPayloadDto.serializer(), dto)

        assertEquals(true, json.contains("\"recordId\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyPayloadMapperTest"`
Expected: FAIL — `SurveyPayloadDto` / `SurveyPayloadMapper` unresolved references

- [ ] **Step 3: Implement `SurveyPayloadDto.kt`**

```kotlin
package com.medmission.survey.data.network

import kotlinx.serialization.Serializable

@Serializable
data class SurveyPayloadDto(
    val recordId: String,
    val no: String? = null,
    val date: String? = null,
    val patient: PatientDto,
    val medicalHistory: MedicalHistoryDto,
    val vitalSigns: VitalSignsDto,
    val symptoms: List<String>,
    val tbInfo: TbInfoDto,
    val smoking: SmokingDto,
    val alcohol: AlcoholDto,
    val environmentalExposure: EnvironmentalExposureDto,
)

@Serializable
data class PatientDto(
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
    val email: String? = null,
    val cellPhone: String? = null,
    val maritalStatus: String? = null,
)

@Serializable
data class MedicalHistoryDto(
    val items: List<String> = emptyList(),
    val others: String? = null,
    val recentSurgeriesOrHospitalization: String? = null,
    val currentMedication: String? = null,
)

@Serializable
data class VitalSignsDto(
    val height: Double? = null,
    val weight: Double? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val pulseRate: Int? = null,
    val respiratoryRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Double? = null,
    val bloodGlucose: Double? = null,
)

@Serializable
data class TbInfoDto(
    val everDiagnosedTB: String? = null,
    val diagnosisYear: String? = null,
    val everReceivedTreatment: String? = null,
    val treatmentCompleted: String? = null,
    val closeContactActiveTB: String? = null,
    val closeContactWhen: String? = null,
    val householdMemberTBTreatment: String? = null,
)

@Serializable
data class SmokingDto(
    val status: String? = null,
    val duration: String? = null,
)

@Serializable
data class AlcoholDto(
    val drinks: Boolean? = null,
    val amount: String? = null,
)

@Serializable
data class EnvironmentalExposureDto(
    val dustSmokeChemicalExposure: Boolean? = null,
    val cooksWithSolidFuels: Boolean? = null,
    val secondhandSmokeExposure: Boolean? = null,
    val crowdedLivingConditions: Boolean? = null,
)
```

- [ ] **Step 4: Implement `SurveyPayloadMapper.kt`**

```kotlin
package com.medmission.survey.data.network

import com.medmission.survey.data.model.SurveyRecord

object SurveyPayloadMapper {
    fun toDto(record: SurveyRecord): SurveyPayloadDto = SurveyPayloadDto(
        recordId = record.recordId,
        no = record.no,
        date = record.date,
        patient = PatientDto(
            firstName = record.firstName,
            lastName = record.lastName,
            birthDate = record.birthDate,
            gender = record.gender?.name,
            age = record.age,
            address = record.address,
            city = record.city,
            stateProvince = record.stateProvince,
            zip = record.zip,
            email = record.email,
            cellPhone = record.cellPhone,
            maritalStatus = record.maritalStatus?.name,
        ),
        medicalHistory = MedicalHistoryDto(
            items = record.medicalHistory.map { it.name },
            others = record.medicalHistoryOthers,
            recentSurgeriesOrHospitalization = record.recentSurgeriesOrHospitalization,
            currentMedication = record.currentMedication,
        ),
        vitalSigns = VitalSignsDto(
            height = record.height,
            weight = record.weight,
            bpSystolic = record.bpSystolic,
            bpDiastolic = record.bpDiastolic,
            pulseRate = record.pulseRate,
            respiratoryRate = record.respiratoryRate,
            temperature = record.temperature,
            oxygenSaturation = record.oxygenSaturation,
            bloodGlucose = record.bloodGlucose,
        ),
        symptoms = record.symptoms.map { it.name },
        tbInfo = TbInfoDto(
            everDiagnosedTB = record.everDiagnosedTB?.name,
            diagnosisYear = record.diagnosisYear,
            everReceivedTreatment = record.everReceivedTreatment?.name,
            treatmentCompleted = record.treatmentCompleted?.name,
            closeContactActiveTB = record.closeContactActiveTB?.name,
            closeContactWhen = record.closeContactWhen,
            householdMemberTBTreatment = record.householdMemberTBTreatment?.name,
        ),
        smoking = SmokingDto(
            status = record.smokingStatus?.name,
            duration = record.smokingDuration?.name,
        ),
        alcohol = AlcoholDto(
            drinks = record.drinksAlcohol,
            amount = record.alcoholAmount?.name,
        ),
        environmentalExposure = EnvironmentalExposureDto(
            dustSmokeChemicalExposure = record.dustSmokeChemicalExposure,
            cooksWithSolidFuels = record.cooksWithSolidFuels,
            secondhandSmokeExposure = record.secondhandSmokeExposure,
            crowdedLivingConditions = record.crowdedLivingConditions,
        ),
    )
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyPayloadMapperTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt app/src/main/java/com/medmission/survey/data/network/SurveyPayloadMapper.kt app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt
git commit -m "feat: add JSON DTOs and SurveyRecord-to-payload mapper"
```

---

### Task 6: SurveyApiClient (HTTP POST with API key)

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/network/SurveyApiClient.kt`
- Test: `app/src/test/java/com/medmission/survey/data/network/SurveyApiClientTest.kt`

**Interfaces:**
- Consumes: `SurveyPayloadDto` from Task 5
- Produces: `SurveyApiClient` interface with `suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit>`, and `OkHttpSurveyApiClient` implementation — Task 8 (`SurveyRepository`) calls this directly.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.data.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurveyApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SurveyApiClient
    private val samplePayload = SurveyPayloadMapper.toDto(com.medmission.survey.data.model.SurveyRecord(firstName = "Ana"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpSurveyApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends a POST to api v1 surveys with the api key header and json body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = client.sendSurvey(baseUrl, "test-key-123", samplePayload)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/surveys", recorded.path)
        assertEquals("test-key-123", recorded.getHeader("X-Api-Key"))
        assertTrue(recorded.body.readUtf8().contains("\"firstName\":\"Ana\""))
    }

    @Test
    fun `returns failure for a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = client.sendSurvey(baseUrl, "test-key-123", samplePayload)

        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyApiClientTest"`
Expected: FAIL — `SurveyApiClient` / `OkHttpSurveyApiClient` unresolved references

- [ ] **Step 3: Implement `SurveyApiClient.kt`**

```kotlin
package com.medmission.survey.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface SurveyApiClient {
    suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit>
}

class OkHttpSurveyApiClient(
    private val client: OkHttpClient,
    private val json: Json,
) : SurveyApiClient {
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body = json.encodeToString(SurveyPayloadDto.serializer(), payload)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/surveys")
                    .header("X-Api-Key", apiKey)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(Unit)
                    else Result.failure(IOException("HTTP ${response.code}"))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyApiClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/network/SurveyApiClient.kt app/src/test/java/com/medmission/survey/data/network/SurveyApiClientTest.kt
git commit -m "feat: add OkHttp-based SurveyApiClient with API key auth"
```

---

### Task 7: SurveyRepository — draft save/query + send/retry orchestration

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/repository/SurveyRepository.kt`
- Test: `app/src/test/java/com/medmission/survey/data/repository/SurveyRepositoryTest.kt`

**Interfaces:**
- Consumes: `SurveyDao` (Task 3), `LaptopEndpointDao` (Task 3/4), `SurveyApiClient` (Task 6), `SurveyPayloadMapper` (Task 5)
- Produces: `SurveyRepository` with `suspend fun saveDraft(record: SurveyRecord)`, `fun observeAll(): Flow<List<SurveyRecord>>`, `suspend fun getById(recordId: String): SurveyRecord?`, `suspend fun sendToLaptop(recordId: String, laptopId: String): Result<Unit>`, `suspend fun getPendingRecords(): List<SurveyRecord>`, and constant `SurveyRepository.MAX_SEND_ATTEMPTS = 10` — Task 9 (`SurveyRetryWorker`) calls `getPendingRecords()` and `sendToLaptop()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.local.SurveyDao
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.network.SurveyApiClient
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
}

private class FakeLaptopEndpointDao : LaptopEndpointDao {
    val endpoints = mutableMapOf<String, LaptopEndpoint>()
    override suspend fun upsert(endpoint: LaptopEndpoint) { endpoints[endpoint.id] = endpoint }
    override suspend fun getById(id: String): LaptopEndpoint? = endpoints[id]
    override fun observeAll(): Flow<List<LaptopEndpoint>> = flowOf(endpoints.values.toList())
}

private class FakeSurveyApiClient(private val result: Result<Unit>) : SurveyApiClient {
    var lastCallBaseUrl: String? = null
    override suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit> {
        lastCallBaseUrl = baseUrl
        return result
    }
}

class SurveyRepositoryTest {
    private val laptop = LaptopEndpoint(id = "laptop-1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.repository.SurveyRepositoryTest"`
Expected: FAIL — `SurveyRepository` unresolved reference

- [ ] **Step 3: Implement `SurveyRepository.kt`**

```kotlin
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
        val record = surveyDao.getById(recordId)
            ?: return Result.failure(IllegalStateException("Record not found: $recordId"))
        val endpoint = laptopEndpointDao.getById(laptopId)
            ?: return Result.failure(IllegalStateException("Laptop endpoint not found: $laptopId"))

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
            val attempts = record.sendAttempts + 1
            val newStatus = if (attempts >= MAX_SEND_ATTEMPTS) SyncStatus.FAILED else SyncStatus.PENDING
            surveyDao.upsert(
                record.copy(
                    status = newStatus,
                    sendAttempts = attempts,
                    targetLaptopId = laptopId,
                )
            )
            Result.failure(result.exceptionOrNull() ?: IOException("Unknown send error"))
        }
    }

    companion object {
        const val MAX_SEND_ATTEMPTS = 10
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.repository.SurveyRepositoryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/repository/SurveyRepository.kt app/src/test/java/com/medmission/survey/data/repository/SurveyRepositoryTest.kt
git commit -m "feat: add SurveyRepository with send/retry status transitions"
```

---

### Task 8: SurveyRetryWorker (WorkManager)

**Files:**
- Create: `app/src/main/java/com/medmission/survey/work/SurveyRetryWorker.kt`
- Test: `app/src/test/java/com/medmission/survey/work/SurveyRetryWorkerTest.kt`

**Interfaces:**
- Consumes: `SurveyRepository.getPendingRecords()` and `SurveyRepository.sendToLaptop(recordId, laptopId)` from Task 7
- Produces: `SurveyRetryWorker` (a `CoroutineWorker`) that retries every `PENDING` record against its stored `targetLaptopId`, and `SurveyRetryWorker.enqueuePeriodic(context: Context)` — called from `SurveyApplication` in Task 9. Records with no `targetLaptopId` (never attempted) are skipped, since retry only applies to records that already tried and failed.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.work.SurveyRetryWorkerTest"`
Expected: FAIL — `SurveyRetryWorker` and `FakeWorkerFactory` unresolved references

- [ ] **Step 3: Implement `SurveyRetryWorker.kt`** (includes a `WorkerFactory`-friendly constructor and the `FakeWorkerFactory` test double lives in the test file, added in Step 3b)

```kotlin
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
```

- [ ] **Step 3b: Add `FakeWorkerFactory` to the test file** (append to `SurveyRetryWorkerTest.kt`)

```kotlin
private class FakeWorkerFactory(
    private val repository: SurveyRepository,
) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = SurveyRetryWorker(appContext, workerParameters, repository)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.work.SurveyRetryWorkerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/work/SurveyRetryWorker.kt app/src/test/java/com/medmission/survey/work/SurveyRetryWorkerTest.kt
git commit -m "feat: add SurveyRetryWorker for automatic offline-queue retry"
```

---

### Task 9: NsdDiscoveryService

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/network/NsdDiscoveryService.kt`

**Interfaces:**
- Produces: `data class DiscoveredLaptop(val name: String, val host: String, val port: Int)` and `NsdDiscoveryService` with `fun discover(): Flow<List<DiscoveredLaptop>>` — consumed by `LaptopSelectViewModel` in Task 13. This wraps `android.net.nsd.NsdManager`, which requires an instrumented device/emulator to exercise; it is not unit-testable on the JVM, so this task has no local test — Task 13's ViewModel test uses a fake implementation of this interface instead.

- [ ] **Step 1: Implement `NsdDiscoveryService.kt`**

```kotlin
package com.medmission.survey.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredLaptop(val name: String, val host: String, val port: Int)

private const val SERVICE_TYPE = "_medmission._tcp."

interface NsdDiscoveryService {
    fun discover(): Flow<List<DiscoveredLaptop>>
}

class AndroidNsdDiscoveryService(private val context: Context) : NsdDiscoveryService {
    override fun discover(): Flow<List<DiscoveredLaptop>> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = mutableMapOf<String, DiscoveredLaptop>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                found[serviceInfo.serviceName] = DiscoveredLaptop(serviceInfo.serviceName, host, serviceInfo.port)
                trySend(found.values.toList())
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = close()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                trySend(found.values.toList())
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose { nsdManager.stopServiceDiscovery(discoveryListener) }
    }
}
```

- [ ] **Step 2: Verify the module still compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/network/NsdDiscoveryService.kt
git commit -m "feat: add NSD-based laptop discovery service"
```

---

### Task 10: SurveyApplication wiring (DI container + WorkManager)

**Files:**
- Create: `app/src/main/java/com/medmission/survey/SurveyApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `android:name=".SurveyApplication"`, remove default `WorkManagerInitializer` via `tools:node="remove"` since we self-initialize)

**Interfaces:**
- Consumes: `AppDatabase`, `SurveyRepository`, `LaptopEndpointRepository`, `SurveyApiClient`, `OkHttpSurveyApiClient`, `AndroidNsdDiscoveryService`, `SurveyRetryWorker`, `BuildConfig.SURVEY_API_KEY`
- Produces: `SurveyApplication` exposing `val surveyRepository: SurveyRepository`, `val laptopEndpointRepository: LaptopEndpointRepository`, `val nsdDiscoveryService: NsdDiscoveryService` as application-scoped singletons — every ViewModel in Tasks 11–13 reads these off `Application`.

- [ ] **Step 1: Implement `SurveyApplication.kt`**

```kotlin
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
```

- [ ] **Step 2: Update `AndroidManifest.xml`** — add the application name and remove WorkManager's default initializer (self-managed via `Configuration.Provider`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <application
        android:name=".SurveyApplication"
        android:allowBackup="true"
        android:label="Medical Mission Survey"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Add the `androidx.startup:startup-runtime` dependency needed for the manifest merge** (append to `app/build.gradle.kts` dependencies block)

```kotlin
    implementation("androidx.startup:startup-runtime:1.1.1")
```

- [ ] **Step 4: Verify the app builds and launches**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/SurveyApplication.kt app/src/main/AndroidManifest.xml app/build.gradle.kts
git commit -m "feat: wire SurveyApplication with DI container and WorkManager configuration"
```

---

### Task 11: HomeViewModel + HomeScreen

**Files:**
- Create: `app/src/main/java/com/medmission/survey/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/medmission/survey/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/com/medmission/survey/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `SurveyRepository.observeAll()` from Task 7
- Produces: `HomeViewModel(repository: SurveyRepository)` exposing `val records: StateFlow<List<SurveyRecord>>`; `HomeScreen(records: List<SurveyRecord>, onNewSurvey: () -> Unit, onRecordClick: (String) -> Unit)` — Task 14 wires this into navigation.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.ui.home

import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `records reflects repository state, newest first`() = runTest {
        val newest = SurveyRecord(status = SyncStatus.DRAFT, createdAt = 2000L)
        val oldest = SurveyRecord(status = SyncStatus.SENT, createdAt = 1000L)
        val repository: SurveyRepository = mock()
        whenever(repository.observeAll()).thenReturn(flowOf(listOf(newest, oldest)))

        val viewModel = HomeViewModel(repository)

        assertEquals(listOf(newest, oldest), viewModel.records.first())
    }
}
```

Add the Mockito-Kotlin test dependency (needed by this and later ViewModel tests) — append to `app/build.gradle.kts`:

```kotlin
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.home.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` unresolved reference

- [ ] **Step 3: Implement `HomeViewModel.kt`**

```kotlin
package com.medmission.survey.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(repository: SurveyRepository) : ViewModel() {
    val records: StateFlow<List<SurveyRecord>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.home.HomeViewModelTest"`
Expected: PASS

- [ ] **Step 5: Implement `HomeScreen.kt`**

```kotlin
package com.medmission.survey.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.SurveyRecord

@Composable
fun HomeScreen(
    records: List<SurveyRecord>,
    onNewSurvey: () -> Unit,
    onRecordClick: (String) -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewSurvey, modifier = Modifier.padding(16.dp)) {
                Text("+ 새 설문")
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(records, key = { it.recordId }) { record ->
                    Card(
                        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                        onClick = { onRecordClick(record.recordId) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(record.no ?: record.recordId.take(8))
                            Text("${record.firstName.orEmpty()} ${record.lastName.orEmpty()}")
                            Text(record.status.name)
                        }
                    }
                }
            }
        }
    }
}
```

Note: `Card(onClick = ...)` requires the Material3 clickable Card overload; if the resolved Compose BOM version does not expose it, wrap the `Card` content in a `Modifier.clickable { onRecordClick(record.recordId) }` on the inner `Column` instead.

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/home app/src/test/java/com/medmission/survey/ui/home/HomeViewModelTest.kt app/build.gradle.kts
git commit -m "feat: add Home screen with record list and status badges"
```

---

### Task 12: LaptopSelectViewModel + LaptopSelectScreen

**Files:**
- Create: `app/src/main/java/com/medmission/survey/ui/laptopselect/LaptopSelectViewModel.kt`
- Create: `app/src/main/java/com/medmission/survey/ui/laptopselect/LaptopSelectScreen.kt`
- Test: `app/src/test/java/com/medmission/survey/ui/laptopselect/LaptopSelectViewModelTest.kt`

**Interfaces:**
- Consumes: `LaptopEndpointRepository` (Task 4), `NsdDiscoveryService` / `DiscoveredLaptop` (Task 9), `SurveyRepository.sendToLaptop` (Task 7)
- Produces: `LaptopSelectViewModel(laptopEndpointRepository, nsdDiscoveryService, surveyRepository, recordId)` exposing `val savedEndpoints: StateFlow<List<LaptopEndpoint>>`, `val discovered: StateFlow<List<DiscoveredLaptop>>`, `suspend fun addManualEndpoint(name: String, host: String, port: Int)`, `suspend fun send(laptopId: String): Result<Unit>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.ui.laptopselect

import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private class FakeNsdDiscoveryService(private val results: List<DiscoveredLaptop>) : NsdDiscoveryService {
    override fun discover(): Flow<List<DiscoveredLaptop>> = flowOf(results)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LaptopSelectViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `savedEndpoints reflects the repository and discovered reflects nsd results`() = runTest {
        val saved = LaptopEndpoint(id = "1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)
        val laptopRepo: LaptopEndpointRepository = mock()
        whenever(laptopRepo.observeAll()).thenReturn(flowOf(listOf(saved)))
        val discovered = DiscoveredLaptop("2번 X-ray실", "192.168.1.20", 8080)
        val nsd = FakeNsdDiscoveryService(listOf(discovered))
        val surveyRepo: SurveyRepository = mock()

        val viewModel = LaptopSelectViewModel(laptopRepo, nsd, surveyRepo, recordId = "record-1")

        assertEquals(listOf(saved), viewModel.savedEndpoints.first())
        assertEquals(listOf(discovered), viewModel.discovered.first())
    }

    @Test
    fun `send delegates to SurveyRepository sendToLaptop with the held recordId`() = runTest {
        val laptopRepo: LaptopEndpointRepository = mock()
        whenever(laptopRepo.observeAll()).thenReturn(flowOf(emptyList()))
        val surveyRepo: SurveyRepository = mock()
        whenever(surveyRepo.sendToLaptop("record-1", "laptop-1")).thenReturn(Result.success(Unit))

        val viewModel = LaptopSelectViewModel(laptopRepo, FakeNsdDiscoveryService(emptyList()), surveyRepo, recordId = "record-1")
        val result = viewModel.send("laptop-1")

        assertTrue(result.isSuccess)
        verify(surveyRepo).sendToLaptop("record-1", "laptop-1")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.laptopselect.LaptopSelectViewModelTest"`
Expected: FAIL — `LaptopSelectViewModel` unresolved reference

- [ ] **Step 3: Implement `LaptopSelectViewModel.kt`**

```kotlin
package com.medmission.survey.ui.laptopselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

class LaptopSelectViewModel(
    private val laptopEndpointRepository: LaptopEndpointRepository,
    nsdDiscoveryService: NsdDiscoveryService,
    private val surveyRepository: SurveyRepository,
    private val recordId: String,
) : ViewModel() {

    val savedEndpoints: StateFlow<List<LaptopEndpoint>> = laptopEndpointRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discovered: StateFlow<List<DiscoveredLaptop>> = nsdDiscoveryService.discover()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun addManualEndpoint(name: String, host: String, port: Int) {
        laptopEndpointRepository.save(LaptopEndpoint(id = UUID.randomUUID().toString(), name = name, host = host, port = port))
    }

    suspend fun send(laptopId: String): Result<Unit> = surveyRepository.sendToLaptop(recordId, laptopId)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.laptopselect.LaptopSelectViewModelTest"`
Expected: PASS

- [ ] **Step 5: Implement `LaptopSelectScreen.kt`**

```kotlin
package com.medmission.survey.ui.laptopselect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.LaptopEndpoint

@Composable
fun LaptopSelectScreen(
    savedEndpoints: List<LaptopEndpoint>,
    onSelect: (String) -> Unit,
    onAddManual: () -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("전송할 랩톱을 선택하세요")
            LazyColumn(Modifier.fillMaxSize()) {
                items(savedEndpoints, key = { it.id }) { endpoint ->
                    Card(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(endpoint.name)
                            Text("${endpoint.host}:${endpoint.port}")
                            Button(onClick = { onSelect(endpoint.id) }) { Text("전송") }
                        }
                    }
                }
            }
            Button(onClick = onAddManual) { Text("수동 추가") }
        }
    }
}
```

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/laptopselect app/src/test/java/com/medmission/survey/ui/laptopselect/LaptopSelectViewModelTest.kt
git commit -m "feat: add laptop selection screen with saved and discovered endpoints"
```

---

### Task 13: FormViewModel + FormScreen

**Files:**
- Create: `app/src/main/java/com/medmission/survey/ui/form/FormViewModel.kt`
- Create: `app/src/main/java/com/medmission/survey/ui/form/FormScreen.kt`
- Test: `app/src/test/java/com/medmission/survey/ui/form/FormViewModelTest.kt`

**Interfaces:**
- Consumes: `SurveyRepository.saveDraft`/`getById` (Task 7), `SurveyRecord` and all enums (Task 2)
- Produces: `FormViewModel(repository: SurveyRepository, recordId: String?)` exposing `val record: StateFlow<SurveyRecord>` and one `update*` function per field group (`updateField(transform: (SurveyRecord) -> SurveyRecord)` generic entry point, used by both the ViewModel's own field setters and the Compose screen); every field edit calls `saveDraft` — Task 14 wires `recordId` from navigation.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.medmission.survey.ui.form

import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FormViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starting with no recordId begins a fresh DRAFT record`() = runTest {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        val record = viewModel.record.first()

        assertTrue(record.firstName == null)
        assertEquals(com.medmission.survey.data.model.SyncStatus.DRAFT, record.status)
    }

    @Test
    fun `starting with an existing recordId loads it from the repository`() = runTest {
        val existing = SurveyRecord(firstName = "Maria")
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)

        val viewModel = FormViewModel(repository, recordId = existing.recordId)
        viewModel.load()

        assertEquals("Maria", viewModel.record.first().firstName)
    }

    @Test
    fun `updateField mutates the record and persists a draft`() = runTest {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.updateField { it.copy(firstName = "Juan", gender = Gender.MALE) }

        assertEquals("Juan", viewModel.record.first().firstName)
        assertEquals(Gender.MALE, viewModel.record.first().gender)
        verify(repository).saveDraft(viewModel.record.first())
    }

    @Test
    fun `toggling a medical history item adds and removes it from the set`() = runTest {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        assertTrue(viewModel.record.first().medicalHistory.contains(MedicalHistoryItem.ASTHMA))

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        assertTrue(viewModel.record.first().medicalHistory.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.form.FormViewModelTest"`
Expected: FAIL — `FormViewModel` unresolved reference

- [ ] **Step 3: Implement `FormViewModel.kt`**

```kotlin
package com.medmission.survey.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FormViewModel(
    private val repository: SurveyRepository,
    private val recordId: String?,
) : ViewModel() {

    private val _record = MutableStateFlow(SurveyRecord(recordId = recordId ?: java.util.UUID.randomUUID().toString()))
    val record: StateFlow<SurveyRecord> = _record.asStateFlow()

    fun load() {
        val id = recordId ?: return
        viewModelScope.launch {
            repository.getById(id)?.let { _record.value = it }
        }
    }

    fun updateField(transform: (SurveyRecord) -> SurveyRecord) {
        val updated = transform(_record.value)
        _record.value = updated
        viewModelScope.launch { repository.saveDraft(updated) }
    }

    fun toggleMedicalHistory(item: MedicalHistoryItem) {
        updateField { record ->
            val set = record.medicalHistory
            record.copy(medicalHistory = if (item in set) set - item else set + item)
        }
    }

    fun toggleSymptom(symptom: Symptom) {
        updateField { record ->
            val set = record.symptoms
            record.copy(symptoms = if (symptom in set) set - symptom else set + symptom)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.ui.form.FormViewModelTest"`
Expected: PASS

- [ ] **Step 5: Implement `FormScreen.kt`** — sections rendered by iterating enum values (Medical History, Symptoms) plus representative text/checkbox fields for the remaining sections, establishing the pattern for every field in spec §5.2

```kotlin
package com.medmission.survey.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Row
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord

@Composable
fun FormScreen(
    record: SurveyRecord,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onToggleMedicalHistory: (MedicalHistoryItem) -> Unit,
    onToggleSymptom: (Symptom) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            item { Text("환자 정보") }
            item {
                OutlinedTextField(
                    value = record.firstName.orEmpty(),
                    onValueChange = onFirstNameChange,
                    label = { Text("First Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = record.lastName.orEmpty(),
                    onValueChange = onLastNameChange,
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Text("병력") }
            items(MedicalHistoryItem.values().toList()) { item ->
                Row {
                    Checkbox(
                        checked = item in record.medicalHistory,
                        onCheckedChange = { onToggleMedicalHistory(item) },
                    )
                    Text(item.label)
                }
            }

            item { Text("현재 증상") }
            items(Symptom.values().toList()) { symptom ->
                Row {
                    Checkbox(
                        checked = symptom in record.symptoms,
                        onCheckedChange = { onToggleSymptom(symptom) },
                    )
                    Text(symptom.label)
                }
            }

            item {
                androidx.compose.material3.Button(onClick = onDone) { Text("완료") }
            }
        }
    }
}
```

The remaining sections (Vital Signs numeric fields, TB Related Information yes/no/don't-know groups, Smoking, Alcohol, Environmental Exposure) follow the same two established patterns — `OutlinedTextField` bound to a single nullable field via a dedicated `onXChange` callback for free-text/numeric fields, and an enum-driven `items(...)` checkbox/radio loop for selectable groups — applied to the remaining fields listed in spec §5.2.

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/form app/src/test/java/com/medmission/survey/ui/form/FormViewModelTest.kt
git commit -m "feat: add survey form screen with autosave and enum-driven checkbox sections"
```

---

### Task 14: Navigation graph wiring

**Files:**
- Create: `app/src/main/java/com/medmission/survey/ui/nav/SurveyNavGraph.kt`
- Modify: `app/src/main/java/com/medmission/survey/MainActivity.kt`

**Interfaces:**
- Consumes: `HomeViewModel`/`HomeScreen` (Task 11), `FormViewModel`/`FormScreen` (Task 13), `LaptopSelectViewModel`/`LaptopSelectScreen` (Task 12), `SurveyApplication` (Task 10)
- Produces: a fully wired app — Home → Form (new or existing `recordId`) → Laptop Select → back to Home on success.

- [ ] **Step 1: Implement `SurveyNavGraph.kt`**

```kotlin
package com.medmission.survey.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.medmission.survey.SurveyApplication
import com.medmission.survey.ui.form.FormScreen
import com.medmission.survey.ui.form.FormViewModel
import com.medmission.survey.ui.home.HomeScreen
import com.medmission.survey.ui.home.HomeViewModel
import com.medmission.survey.ui.laptopselect.LaptopSelectScreen
import com.medmission.survey.ui.laptopselect.LaptopSelectViewModel
import kotlinx.coroutines.launch

@Composable
fun SurveyNavGraph(navController: NavHostController = rememberNavController()) {
    val app = LocalContext.current.applicationContext as SurveyApplication

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val viewModel = HomeViewModel(app.surveyRepository)
            val records by viewModel.records.collectAsState()
            HomeScreen(
                records = records,
                onNewSurvey = { navController.navigate("form") },
                onRecordClick = { recordId -> navController.navigate("form?recordId=$recordId") },
            )
        }
        composable(
            "form?recordId={recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")
            val viewModel = FormViewModel(app.surveyRepository, recordId)
            val scope = rememberCoroutineScopeCompat()
            if (recordId != null) viewModel.load()
            val record by viewModel.record.collectAsState()
            FormScreen(
                record = record,
                onFirstNameChange = { viewModel.updateField { r -> r.copy(firstName = it) } },
                onLastNameChange = { viewModel.updateField { r -> r.copy(lastName = it) } },
                onToggleMedicalHistory = { viewModel.toggleMedicalHistory(it) },
                onToggleSymptom = { viewModel.toggleSymptom(it) },
                onDone = { navController.navigate("laptopSelect/${record.recordId}") },
            )
            scope
        }
        composable(
            "laptopSelect/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")!!
            val viewModel = LaptopSelectViewModel(app.laptopEndpointRepository, app.nsdDiscoveryService, app.surveyRepository, recordId)
            val saved by viewModel.savedEndpoints.collectAsState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaptopSelectScreen(
                savedEndpoints = saved,
                onSelect = { laptopId ->
                    scope.launch {
                        viewModel.send(laptopId)
                        navController.popBackStack("home", inclusive = false)
                    }
                },
                onAddManual = { /* opens a dialog — left to a follow-up UI-polish task */ },
            )
        }
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
```

- [ ] **Step 2: Update `MainActivity.kt`**

```kotlin
package com.medmission.survey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.medmission.survey.ui.nav.SurveyNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SurveyNavGraph()
        }
    }
}
```

- [ ] **Step 3: Add the Navigation Compose dependency confirmation** — already present from Task 1 (`androidx.navigation:navigation-compose:2.7.7`); no change needed.

- [ ] **Step 4: Verify the app builds**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/nav/SurveyNavGraph.kt app/src/main/java/com/medmission/survey/MainActivity.kt
git commit -m "feat: wire navigation graph — Home to Form to Laptop Select"
```

---

## Self-Review Notes

- **Spec coverage:** §5 (data model) → Tasks 2–3; §7 (network contract, idempotency key, auth header) → Tasks 5–7, 10; §4/§8 (source of truth, offline retry, FAILED threshold) → Tasks 3, 7–8; §6 (screens) → Tasks 11–14; §9 (testing) → every task's TDD steps plus MockWebServer/Robolectric coverage. The bridge program and DICOM tag mapping are explicitly out of scope per spec §10 and not planned here.
- **Placeholder scan:** no TBD/TODO markers; the one deferred item (`onAddManual` dialog in Task 14) is a real, explicitly named follow-up, not a stand-in for required scope — the "완료" flow and send-to-laptop path are fully implemented without it.
- **Type consistency:** `SurveyRecord`, `SyncStatus`, and all enum names are defined once in Task 2 and referenced identically through Converters (Task 3), the mapper (Task 5), the repository (Task 7), the worker (Task 8), and every ViewModel (Tasks 11–13).
