package com.medmission.survey.data.psgc

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PsgcRepositoryTest {
    private val repository = PsgcRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `lists all 17 regions from the bundled dataset`() {
        assertEquals(17, repository.regions().size)
    }

    @Test
    fun `Rizal is a province of the CALABARZON region`() {
        val calabarzon = repository.regions().first { it.contains("CALABARZON") }
        assertTrue(repository.provinces(calabarzon).contains("Rizal"))
    }

    @Test
    fun `NCR has no provinces`() {
        val ncr = repository.regions().first { it.contains("NATIONAL CAPITAL REGION") }
        assertTrue(repository.provinces(ncr).isEmpty())
    }

    @Test
    fun `Taytay resolves to ZIP 1920`() {
        assertEquals("1920", repository.zip(city = "Taytay", barangay = null))
    }
}
