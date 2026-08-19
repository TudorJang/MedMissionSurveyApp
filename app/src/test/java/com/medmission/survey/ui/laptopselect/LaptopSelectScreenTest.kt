package com.medmission.survey.ui.laptopselect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun endpoint(id: String, name: String) =
        LaptopEndpoint(id = id, name = name, host = "192.168.8.$id", port = 18080, apiKey = "k")

    @Test
    fun `sending an already-sent survey to a second laptop asks first`() {
        var selected: String? = null
        compose.setContent {
            LaptopSelectScreen(
                savedEndpoints = listOf(endpoint("1", "Lane A"), endpoint("2", "Lane B")),
                discoveredLaptops = emptyList(),
                onSelect = { selected = it },
                onAddManual = { _, _, _, _ -> },
                onApiKeyChange = { _, _ -> },
                priorSend = PriorSend(laptopId = "1", laptopName = "Lane A"),
            )
        }

        compose.onAllNodesWithText("Send")[1].performClick()

        org.junit.Assert.assertNull(selected)
        compose.onNodeWithText("Send anyway").assertIsDisplayed()
        compose.onNodeWithText("Send anyway").performClick()
        org.junit.Assert.assertEquals("2", selected)
    }

    @Test
    fun `re-sending to the same laptop does not ask`() {
        var selected: String? = null
        compose.setContent {
            LaptopSelectScreen(
                savedEndpoints = listOf(endpoint("1", "Lane A")),
                discoveredLaptops = emptyList(),
                onSelect = { selected = it },
                onAddManual = { _, _, _, _ -> },
                onApiKeyChange = { _, _ -> },
                priorSend = PriorSend(laptopId = "1", laptopName = "Lane A"),
            )
        }

        compose.onAllNodesWithText("Send")[0].performClick()

        org.junit.Assert.assertEquals("1", selected)
        compose.onNodeWithText("Send anyway").assertDoesNotExist()
    }
}
