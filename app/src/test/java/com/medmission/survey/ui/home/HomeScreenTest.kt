package com.medmission.survey.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A record that failed to send is stranded otherwise: the retry worker gives up on a
 * rejected key by design, so the only way back is the operator asking for it again
 * after fixing the key on the laptop.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun record(status: SyncStatus) = SurveyRecord(
        recordId = "r-1",
        no = "TAB-A3F2-0001",
        firstName = "Juan",
        lastName = "Dela Cruz",
        status = status,
    )

    @Test
    fun `a failed record offers to send again`() {
        var resent: String? = null
        compose.setContent {
            HomeScreen(
                records = listOf(record(SyncStatus.FAILED)),
                onNewSurvey = {},
                onRecordClick = {},
                onResend = { resent = it },
            )
        }

        compose.onNodeWithText("Send again").assertIsDisplayed()
        compose.onNodeWithText("Send again").performClick()

        assertEquals("r-1", resent)
    }

    @Test
    fun `a sent record does not offer to send again`() {
        compose.setContent {
            HomeScreen(
                records = listOf(record(SyncStatus.SENT)),
                onNewSurvey = {},
                onRecordClick = {},
                onResend = {},
            )
        }

        compose.onNodeWithText("Send again").assertDoesNotExist()
    }
}
