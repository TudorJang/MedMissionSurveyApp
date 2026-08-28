package com.medmission.survey.data.psgc

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PsgcRepositoryTest {
    private val repository = PsgcRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `lists all 18 regions from the bundled dataset`() {
        assertEquals(18, repository.regions().size)
    }

    @Test
    fun `the Negros Island Region re-established in 2024 is present`() {
        val nir = repository.regions().first { it.contains("Negros Island Region") }
        assertTrue(repository.provinces(nir).contains("Negros Occidental"))
    }

    @Test
    fun `a highly urbanised city is listed under the province it sits in`() {
        // PSA codes Bacolod, Baguio, Cebu and the rest at the province slot because
        // they answer to no province. Someone picking an address still looks for them
        // under the province they are in, so the dataset nests them there.
        val nir = repository.regions().first { it.contains("Negros Island Region") }
        assertTrue(repository.cities(nir, "Negros Occidental").contains("City of Bacolod"))
    }

    @Test
    fun `Rizal is a province of the CALABARZON region`() {
        val calabarzon = repository.regions().first { it.contains("CALABARZON") }
        assertTrue(repository.provinces(calabarzon).contains("Rizal"))
    }

    @Test
    fun `NCR has no provinces`() {
        val ncr = repository.regions().first { it.contains("National Capital Region") }
        assertTrue(repository.provinces(ncr).isEmpty())
    }

    @Test
    fun `a city whose name means one place resolves to its ZIP`() {
        assertEquals("1900", repository.zip(city = "Cainta", barangay = null))
    }

    /**
     * Taytay is a municipality in Rizal (1920) and another in Palawan (5312), and the
     * postcode dataset is keyed by name alone — no province, no region. 133 of its 1720
     * names are shared this way, "San Isidro" across eleven postcodes. Picking one looked
     * authoritative on screen, so nobody corrected it, and it rode into the patient's
     * DICOM address.
     */
    @Test
    fun `a city name shared by two provinces leaves the ZIP for the operator to type`() {
        assertNull(repository.zip(city = "Taytay", barangay = null))
    }

    @Test
    fun `a barangay name that collides with another city's ZIP entry does not override its own city's ZIP`() {
        // "San Isidro" exists as a barangay in many cities and is ambiguous in the ZIP
        // dataset. City-first precedence must keep the city's own ZIP; the same
        // precedence is unit-tested with fixtures in ZipParserTest.
        assertEquals("1900", repository.zip(city = "Cainta", barangay = "San Isidro"))
    }
}
