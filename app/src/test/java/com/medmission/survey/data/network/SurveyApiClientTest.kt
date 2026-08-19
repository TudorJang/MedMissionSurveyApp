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
    fun `a rejected api key comes back as UnauthorizedException, not a generic failure`() = runTest {
        // The tablet has to tell "wrong key, ask the operator" apart from "laptop is
        // asleep, try again later": retrying a rejected key just burns attempts until
        // the record lands in FAILED with nothing to show for it.
        server.enqueue(MockResponse().setResponseCode(401))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = client.sendSurvey(baseUrl, "wrong-key", samplePayload)

        assertTrue(result.exceptionOrNull() is UnauthorizedException)
    }

    @Test
    fun `returns failure for a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = client.sendSurvey(baseUrl, "test-key-123", samplePayload)

        assertTrue(result.isFailure)
    }

    @Test
    fun `asks the bridge what became of a survey and returns the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"Completed"}"""))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = client.getSurveyStatus(baseUrl, "test-key-123", "r-1")

        assertEquals("Completed", result.getOrNull())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/surveys/r-1/status", recorded.path)
        assertEquals("test-key-123", recorded.getHeader("X-Api-Key"))
    }

    @Test
    fun `a status the bridge does not know is a failure, not a crash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val baseUrl = server.url("/").toString().trimEnd('/')

        assertTrue(client.getSurveyStatus(baseUrl, "test-key-123", "r-1").isFailure)
    }
}
