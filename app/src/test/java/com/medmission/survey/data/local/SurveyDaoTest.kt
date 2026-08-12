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
