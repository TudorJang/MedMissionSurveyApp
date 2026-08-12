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
