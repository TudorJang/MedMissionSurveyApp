package com.medmission.survey.data.psgc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val SAMPLE_ZIPS = """
{
  "1920": "Taytay",
  "1000": "Manila CPO - Ermita",
  "1100": ["Central", "Piñahan", "Project 6"]
}
""".trimIndent()

class ZipParserTest {
    private fun parse() = parseZipByName(Json.parseToJsonElement(SAMPLE_ZIPS).jsonObject)

    @Test
    fun `a plain string entry maps its name to its zip`() {
        assertEquals("1920", parse()["Taytay"])
    }

    @Test
    fun `an array entry maps every name in it to the same zip`() {
        val zipByName = parse()
        assertEquals("1100", zipByName["Central"])
        assertEquals("1100", zipByName["Piñahan"])
        assertEquals("1100", zipByName["Project 6"])
    }

    @Test
    fun `findZip prefers a city match over a barangay match`() {
        val zipByName = mapOf("Ermita" to "1000", "Manila" to "1099")
        assertEquals("1099", findZip(zipByName, city = "Manila", barangay = "Ermita"))
    }

    @Test
    fun `findZip falls back to the barangay name when the city has no entry`() {
        val zipByName = parse()
        assertEquals("1920", findZip(zipByName, city = "Unknown City", barangay = "Taytay"))
    }

    @Test
    fun `findZip returns null when neither barangay nor city is found`() {
        val zipByName = parse()
        assertNull(findZip(zipByName, city = "Unknown City", barangay = "Unknown Barangay"))
    }

    @Test
    fun `findZip prefers the city's own zip over a colliding barangay from another city`() {
        // "San Isidro" is a common barangay name that recurs across many unrelated cities.
        // Here it collides with a barangay entry that belongs to a different city's ZIP area
        // ("8888", some other town's "San Isidro") while "Taytay" itself maps to "1920".
        // City-first precedence must return Taytay's own zip, not the colliding barangay's.
        val zipByName = mapOf("Taytay" to "1920", "San Isidro" to "8888")
        assertEquals("1920", findZip(zipByName, city = "Taytay", barangay = "San Isidro"))
    }
}
