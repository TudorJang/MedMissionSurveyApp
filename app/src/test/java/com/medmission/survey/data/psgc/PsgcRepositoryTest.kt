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

    @Test
    fun `a barangay name that collides with another city's ZIP entry does not override its own city's ZIP`() {
        // "San Isidro" exists as a barangay in many cities and matches an unrelated
        // Quezon City entry (1113) in the ZIP dataset. City-first precedence must keep
        // Taytay's own ZIP. This pins the real-dataset behavior end-to-end; the same
        // precedence is unit-tested with fixtures in ZipParserTest.
        assertEquals("1920", repository.zip(city = "Taytay", barangay = "San Isidro"))
    }
}
