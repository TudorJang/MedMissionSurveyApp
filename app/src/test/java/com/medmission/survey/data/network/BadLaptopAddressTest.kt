package com.medmission.survey.data.network

import com.medmission.survey.data.model.SurveyRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * When discovery finds nothing — the documented flaky-network case, where the send
 * screen itself calls manual entry the only way forward — the operator types the address
 * off the laptop's own screen. What they type is `http://192.168.1.10`, or the address
 * with the port already on it, or one with a trailing space. OkHttp's `url()` answers a
 * malformed URL with IllegalArgumentException, which is not an IOException and used to
 * travel straight out of the client and kill the app with the survey still unsent.
 */
class BadLaptopAddressTest {
    private val client = OkHttpSurveyApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
    private val payload = SurveyPayloadMapper.toDto(SurveyRecord(firstName = "Ana"))

    private val typedByHand = listOf(
        "http://http://192.168.1.10:18080",  // scheme typed into the host field
        "http://192.168.1.10:18080 ",        // trailing space off the laptop screen
        "http://192.168.1.10:99999",         // port out of range
        "http://:18080",                     // host left out
        "not a url at all",
    )

    @Test
    fun `a mistyped laptop address fails the send instead of throwing out of the client`() = runTest {
        for (baseUrl in typedByHand) {
            val result = runCatching { client.sendSurvey(baseUrl, "k", payload) }

            assertTrue("$baseUrl threw out of sendSurvey", result.isSuccess)
            val send = result.getOrThrow()
            assertTrue("$baseUrl was not reported as a failure", send.isFailure)
            assertTrue(
                "$baseUrl failed with ${send.exceptionOrNull()}, which the retry path does not handle",
                send.exceptionOrNull() is IOException,
            )
        }
    }

    @Test
    fun `a mistyped laptop address fails the status lookup the same way`() = runTest {
        for (baseUrl in typedByHand) {
            val result = runCatching { client.getSurveyStatus(baseUrl, "k", "rec-1") }

            assertTrue("$baseUrl threw out of getSurveyStatus", result.isSuccess)
            assertTrue("$baseUrl was not reported as a failure", result.getOrThrow().isFailure)
        }
    }
}
