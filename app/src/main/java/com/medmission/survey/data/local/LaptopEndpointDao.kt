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
