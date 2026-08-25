package com.medmission.survey.ui.form

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.psgc.PsgcRepository
import com.medmission.survey.data.settings.FormMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalFormModeTest {
    @get:Rule
    val compose = createComposeRule()

    private val psgc by lazy { PsgcRepository(ApplicationProvider.getApplicationContext()) }

    private fun show(mode: FormMode, record: SurveyRecord = SurveyRecord(recordId = "r")) {
        compose.setContent {
            FormScreen(
                record = record,
                onFieldChange = {},
                onToggleMedicalHistory = {},
                onToggleSymptom = {},
                onDone = {},
                psgcRepository = psgc,
                formMode = mode,
            )
        }
    }

    @Test
    fun `the philippine form asks for region and barangay`() {
        show(FormMode.PHILIPPINES)
        compose.onNodeWithText("Region").assertExists()
        compose.onNodeWithText("Barangay").assertExists()
    }

    @Test
    fun `the global form asks the four questions the original form asks`() {
        show(FormMode.GLOBAL)
        compose.onNodeWithText("Address").assertExists()
        compose.onNodeWithText("City").assertExists()
        compose.onNodeWithText("State / Province").assertExists()
        compose.onNodeWithText("ZIP / Postal Code").assertExists()
    }

    @Test
    fun `the global form drops the pickers that have no data behind them`() {
        show(FormMode.GLOBAL)
        // Region and Barangay are Philippine units; outside the country the cascade has
        // nothing to offer and an empty picker is worse than a field you can type in.
        compose.onNodeWithText("Region").assertDoesNotExist()
        compose.onNodeWithText("Barangay").assertDoesNotExist()
    }

    @Test
    fun `an address collected either way still lands in the same fields`() {
        // The wire shape does not fork: whichever form asked the questions, the record
        // carries address, city, province and zip, and the bridge reads those.
        val record = SurveyRecord(
            recordId = "r", address = "88 Main St", city = "Hanoi",
            province = "Ha Noi", zip = "100000", country = "VN",
        )
        show(FormMode.GLOBAL, record)
        compose.onNodeWithText("88 Main St").assertExists()
        compose.onNodeWithText("Hanoi").assertExists()
        compose.onNodeWithText("Ha Noi").assertExists()
        compose.onNodeWithText("100000").assertExists()
    }
}
