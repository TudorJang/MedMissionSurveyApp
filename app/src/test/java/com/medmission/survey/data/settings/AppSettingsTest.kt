package com.medmission.survey.data.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {
    private val settings by lazy { AppSettings(ApplicationProvider.getApplicationContext()) }

    @Test
    fun `a fresh tablet asks the philippine form`() {
        assertEquals(FormMode.PHILIPPINES, settings.formMode)
        assertEquals("PH", settings.effectiveCountryCode)
    }

    @Test
    fun `the global country does not leak back into the philippine form`() {
        // Found end to end: a tablet used in Vietnam and switched back filed Manila
        // addresses as Vietnamese, and their phone numbers stopped normalising because
        // they were parsed under Vietnam's rules.
        settings.formMode = FormMode.GLOBAL
        settings.countryCode = "VN"
        assertEquals("VN", settings.effectiveCountryCode)

        settings.formMode = FormMode.PHILIPPINES
        assertEquals("PH", settings.effectiveCountryCode)

        // and the global choice is still there when the tablet goes back to it
        settings.formMode = FormMode.GLOBAL
        assertEquals("VN", settings.effectiveCountryCode)
    }

    @Test
    fun `both choices survive being read back from a new instance`() {
        settings.formMode = FormMode.GLOBAL
        settings.countryCode = "KR"
        val reopened = AppSettings(ApplicationProvider.getApplicationContext())
        assertEquals(FormMode.GLOBAL, reopened.formMode)
        assertEquals("KR", reopened.countryCode)
    }
}
