package com.medmission.survey.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The bridge rejected the key. Unlike a timeout or a sleeping laptop this never fixes
 * itself, so callers must stop retrying and get a person to correct the key.
 */
class UnauthorizedException(message: String) : IOException(message)

interface SurveyApiClient {
    suspend fun sendSurvey(baseUrl: String, apiKey: String, payload: SurveyPayloadDto): Result<Unit>

    /** What became of a survey on the laptop: Received, InProgress, Completed, Cancelled. */
    suspend fun getSurveyStatus(baseUrl: String, apiKey: String, recordId: String): Result<String>
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
                    when {
                        response.isSuccessful -> Result.success(Unit)
                        response.code == 401 -> Result.failure(UnauthorizedException("HTTP 401"))
                        else -> Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                // An address the operator typed by hand off the laptop's screen: a
                // scheme in the host field, a trailing space, a port out of range.
                // OkHttp rejects those when the URL is built, and an unhandled throw
                // here used to take the app down with the survey still unsent. It is a
                // failure to reach the laptop like any other, so it is reported as one.
                Result.failure(IOException("This laptop address cannot be used: $baseUrl", e))
            }
        }

    override suspend fun getSurveyStatus(baseUrl: String, apiKey: String, recordId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/surveys/$recordId/status")
                    .header("X-Api-Key", apiKey)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            val status = json.parseToJsonElement(body)
                                .let { it as? kotlinx.serialization.json.JsonObject }
                                ?.get("status")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                            if (status != null) Result.success(status)
                            else Result.failure(IOException("No status in response"))
                        }
                        response.code == 401 -> Result.failure(UnauthorizedException("HTTP 401"))
                        else -> Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: kotlinx.serialization.SerializationException) {
                Result.failure(IOException("Unparseable status response", e))
            } catch (e: IllegalArgumentException) {
                // Same hand-typed address as in sendSurvey. This one runs on a 30-second
                // loop behind the home screen, so an unhandled throw here takes the app
                // down while the operator is looking at their list of surveys.
                Result.failure(IOException("This laptop address cannot be used: $baseUrl", e))
            }
        }
}
