package com.medmission.survey.ui.form

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs on Robolectric, not an emulator. These tests exist because the field's mode
 * switching (picker vs free text) once shipped a regression no unit test could see:
 * "Not listed" set the value to "" and the field silently fell back to picker mode,
 * making free-text entry impossible. The cases below pin every mode transition.
 */
@RunWith(RobolectricTestRunner::class)
class GeoSelectFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = listOf("Alpha", "Beta", "Gamma")

    @Test
    fun `tapping the picker field asks to open the dialog`() {
        var opened = false
        compose.setContent {
            GeoSelectField(
                label = "Region",
                value = null,
                options = options,
                isDialogOpen = false,
                onOpenDialog = { opened = true },
                onDismissDialog = {},
                onSelect = {},
                onFreeText = {},
            )
        }

        compose.onNodeWithText("Region").performClick()

        assertTrue(opened)
    }

    @Test
    fun `a disabled picker field ignores taps`() {
        var opened = false
        compose.setContent {
            GeoSelectField(
                label = "Province",
                value = null,
                options = options,
                isDialogOpen = false,
                onOpenDialog = { opened = true },
                onDismissDialog = {},
                onSelect = {},
                onFreeText = {},
                enabled = false,
            )
        }

        compose.onNodeWithText("Province").performClick()

        assertEquals(false, opened)
    }

    @Test
    fun `the dialog lists options and reports the picked one`() {
        var selected: String? = null
        compose.setContent {
            GeoSelectField(
                label = "Region",
                value = null,
                options = options,
                isDialogOpen = true,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = { selected = it },
                onFreeText = {},
            )
        }

        compose.onNodeWithText("Beta").performClick()

        assertEquals("Beta", selected)
    }

    @Test
    fun `the dialog search box filters the options`() {
        compose.setContent {
            GeoSelectField(
                label = "Region",
                value = null,
                options = options,
                isDialogOpen = true,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = {},
                onFreeText = {},
            )
        }

        compose.onNodeWithText("Search").performTextInput("Al")

        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertDoesNotExist()
    }

    @Test
    fun `picking Not listed reports an empty free-text value`() {
        var freeText: String? = null
        compose.setContent {
            GeoSelectField(
                label = "Region",
                value = null,
                options = options,
                isDialogOpen = true,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = {},
                onFreeText = { freeText = it },
            )
        }

        compose.onNodeWithText("Not listed").performClick()

        assertEquals("", freeText)
    }

    @Test
    fun `a blank value renders the editable free-text field, not the picker`() {
        // Regression pin: "Not listed" starts free-text mode with value = "". An earlier
        // fix keyed free-text mode on non-blank, which sent "" back to picker mode and
        // made free-text entry impossible. The trailing icon only exists in free-text
        // mode, so its presence is the mode check.
        var typed: String? = null
        compose.setContent {
            GeoSelectField(
                label = "Barangay",
                value = "",
                options = options,
                isDialogOpen = false,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = {},
                onFreeText = { typed = it },
            )
        }

        compose.onNodeWithContentDescription("Choose from list").assertIsDisplayed()
        compose.onNodeWithText("Barangay").performTextInput("X")

        assertEquals("X", typed)
    }

    @Test
    fun `a value outside the options renders as free text with its content editable`() {
        var typed: String? = null
        compose.setContent {
            GeoSelectField(
                label = "Barangay",
                value = "Custom place",
                options = options,
                isDialogOpen = false,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = {},
                onFreeText = { typed = it },
            )
        }

        compose.onNodeWithText("Custom place").performTextInput("!")

        assertTrue(typed!!.contains("Custom place"))
    }

    @Test
    fun `the free-text field's trailing icon reopens the picker dialog`() {
        var opened = false
        compose.setContent {
            GeoSelectField(
                label = "Barangay",
                value = "Custom place",
                options = options,
                isDialogOpen = false,
                onOpenDialog = { opened = true },
                onDismissDialog = {},
                onSelect = {},
                onFreeText = {},
            )
        }

        compose.onNodeWithContentDescription("Choose from list").performClick()

        assertTrue(opened)
    }

    @Test
    fun `a value present in the options renders as the picker, not free text`() {
        compose.setContent {
            GeoSelectField(
                label = "Region",
                value = "Alpha",
                options = options,
                isDialogOpen = false,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = {},
                onFreeText = {},
            )
        }

        // Picker mode has no trailing icon; the value is still shown.
        compose.onNodeWithContentDescription("Choose from list").assertDoesNotExist()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `the dialog can open while in free-text mode and a real pick is reported`() {
        // The way back from free text: trailing icon opens the dialog, and choosing a
        // real option must come through onSelect so the caller can flip the value.
        var selected: String? = null
        compose.setContent {
            GeoSelectField(
                label = "Barangay",
                value = "Custom place",
                options = options,
                isDialogOpen = true,
                onOpenDialog = {},
                onDismissDialog = {},
                onSelect = { selected = it },
                onFreeText = {},
            )
        }

        compose.onNodeWithText("Gamma").performClick()

        assertEquals("Gamma", selected)
    }
}
