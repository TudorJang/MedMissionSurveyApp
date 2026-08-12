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
