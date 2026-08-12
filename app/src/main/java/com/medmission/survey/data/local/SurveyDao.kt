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
