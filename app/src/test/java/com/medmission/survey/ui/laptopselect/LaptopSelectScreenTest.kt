package com.medmission.survey.ui.laptopselect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.medmission.survey.data.model.LaptopEndpoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every bridge generates its own key on first run, so a blank field means the send will
 * be refused unless this APK happens to carry that site's key. The screen used to say
 * only that the built-in key would be used, which reads like it will work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class LaptopSelectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(apiKey: String) {
        compose.setContent {
            LaptopSelectScreen(
                savedEndpoints = listOf(
                    LaptopEndpoint(id = "l-1", name = "DESKTOP", host = "192.168.8.54",
                        port = 18080, apiKey = apiKey),
                ),
                discoveredLaptops = emptyList(),
                onSelect = {},
                onAddManual = { _, _, _, _ -> },
                onApiKeyChange = { _, _ -> },
            )
        }
    }

    @Test
    fun `a blank key says the laptop will refuse the survey`() {
        show("")

        compose.onNodeWithText("Blank — the laptop refuses this unless this app was built with its key")
            .assertIsDisplayed()
    }

    @Test
    fun `a key that was entered is described as case-sensitive`() {
        show("C79QS-CQ8RM-5QRWU-ABDEE")

        compose.onNodeWithText("Case-sensitive, exactly as shown on the laptop").assertIsDisplayed()
    }
}
