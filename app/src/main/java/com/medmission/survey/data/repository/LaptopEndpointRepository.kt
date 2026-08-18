package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.Flow

class LaptopEndpointRepository(private val dao: LaptopEndpointDao) {
    suspend fun save(endpoint: LaptopEndpoint) = dao.upsert(endpoint)

    /**
     * Saves a laptop by its address rather than by a fresh id, so adding the same
     * discovered laptop twice updates the one row instead of creating a second card
     * the operator has to tell apart.
     */
    suspend fun addOrUpdate(name: String, host: String, port: Int, apiKey: String = "") {
        val key = apiKey.trim()
        val existing = dao.getByAddress(host, port)
        if (existing == null) {
            dao.upsert(LaptopEndpoint(name = name, host = host, port = port, apiKey = key))
            return
        }
        dao.upsert(
            existing.copy(
                name = name.ifBlank { existing.name },
                // Re-adding from discovery carries no key; wiping the one the operator
                // typed would break sends for no reason.
                apiKey = key.ifBlank { existing.apiKey },
            )
        )
    }

    fun observeAll(): Flow<List<LaptopEndpoint>> = dao.observeAll()

    suspend fun getById(id: String): LaptopEndpoint? = dao.getById(id)

    /** Trimmed because the key is typed off a laptop screen and a trailing space is invisible. */
    suspend fun updateApiKey(id: String, apiKey: String) {
        val endpoint = dao.getById(id) ?: return
        dao.upsert(endpoint.copy(apiKey = apiKey.trim()))
    }

    suspend fun markSendSuccess(id: String) {
        val endpoint = dao.getById(id) ?: return
        dao.upsert(endpoint.copy(lastSuccessAt = System.currentTimeMillis()))
    }
}
