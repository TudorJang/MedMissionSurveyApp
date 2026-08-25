package com.medmission.survey.ui.form

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.isUntouched
import com.medmission.survey.data.psgc.PsgcRepository
import com.medmission.survey.util.todayLocalDateString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormEntryRulesTest {
    @get:Rule
    val compose = createComposeRule()

    private val psgc by lazy { PsgcRepository(ApplicationProvider.getApplicationContext()) }

    private fun show(
        record: SurveyRecord,
        onDone: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            FormScreen(
                record = record,
                onFieldChange = {},
                onToggleMedicalHistory = {},
                onToggleSymptom = {},
                onDone = onDone,
                onCancel = onCancel,
                psgcRepository = psgc,
            )
        }
    }

    @Test
    fun `a survey with no name at all cannot be taken to the laptop screen`() {
        // A worklist entry with no name gives the console operator nothing to call the
        // patient by and nothing to search on.
        var doneCalled = false
        show(SurveyRecord(recordId = "r"), onDone = { doneCalled = true })
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertFalse(doneCalled)
        compose.onNodeWithText("Enter a first name or a last name before continuing.")
            .assertIsDisplayed()
    }

    @Test
    fun `either name on its own is enough`() {
        var doneCalled = false
        show(SurveyRecord(recordId = "r", lastName = "Cruz"), onDone = { doneCalled = true })
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertTrue(doneCalled)
    }

    @Test
    fun `there is a way back out of the form`() {
        var cancelled = false
        show(SurveyRecord(recordId = "r"), onCancel = { cancelled = true })
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `a birth date left on its default does not count as filling the form in`() {
        // It is assigned at creation, not typed, so a record showing today's date and
        // nothing else is still empty.
        val fresh = SurveyRecord(
            recordId = "r", no = "TAB-ZL1D-0001", date = todayLocalDateString(),
            birthDate = todayLocalDateString(), gender = Gender.MALE, country = "PH",
        )
        assertTrue(fresh.isUntouched())
        // ...but a real one means somebody answered the question.
        assertFalse(fresh.copy(birthDate = "1980-01-01").isUntouched())
    }

    @Test
    fun `a record nobody typed into is recognised as untouched`() {
        val fresh = SurveyRecord(
            recordId = "r", no = "TAB-ZL1D-0001", date = todayLocalDateString(),
            birthDate = todayLocalDateString(), gender = Gender.MALE, country = "PH",
        )
        assertTrue(fresh.isUntouched())
        // The other gender is somebody's answer, so the record stays.
        assertFalse(fresh.copy(gender = Gender.FEMALE).isUntouched())
        // One keystroke anywhere is enough to keep it: a half-filled survey is work.
        assertFalse(fresh.copy(lastName = "C").isUntouched())
        assertFalse(fresh.copy(zip = "1003").isUntouched())
        assertFalse(fresh.copy(birthDate = "1980-01-01").isUntouched())
    }
}
