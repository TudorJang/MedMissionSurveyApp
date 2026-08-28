package com.medmission.survey.data.psgc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Philippine place names repeat across provinces — San Nicolas, San Isidro, Santa Cruz,
 * Poblacion — and the postcode file is keyed by name alone, with no province or region to
 * scope it. Whichever entry the parser met first used to win for every one of them, so a
 * patient in Ilocos Norte could have a Manila postcode filled in for them. It looks
 * authoritative, so nobody corrects it, and it travels into the DICOM address the console
 * reads.
 *
 * The file already argues the right answer for the same problem in Manila: a name that
 * cannot be resolved is left blank, because a blank the operator can see and fill beats a
 * wrong postcode in a medical record, which nobody can see at all.
 */
class AmbiguousPlaceNameTest {

    private val zips = """
    {
      "1010": ["San Nicolas", "Binondo"],
      "2900": ["San Nicolas", "Laoag"],
      "1920": "Taytay",
      "1100": ["Central", "Project 6"]
    }
    """.trimIndent()

    private fun parse() = parseZipByName(Json.parseToJsonElement(zips).jsonObject)

    @Test
    fun `a name that belongs to more than one postcode resolves to none of them`() {
        assertNull(parse()["San Nicolas"])
    }

    @Test
    fun `the unambiguous names in the same entries are unaffected`() {
        val zipByName = parse()
        assertEquals("1010", zipByName["Binondo"])
        assertEquals("2900", zipByName["Laoag"])
        assertEquals("1920", zipByName["Taytay"])
        assertEquals("1100", zipByName["Project 6"])
    }

    @Test
    fun `an ambiguous city leaves the zip blank rather than guessing`() {
        assertNull(findZip(parse(), city = "San Nicolas", barangay = null))
    }

    @Test
    fun `an ambiguous barangay does not override an unambiguous city`() {
        assertEquals("1920", findZip(parse(), city = "Taytay", barangay = "San Nicolas"))
    }

    /** A name listed twice under the same postcode is not ambiguous. */
    @Test
    fun `a name repeated under one postcode still resolves`() {
        val repeated = """{ "1200": ["Poblacion", "Poblacion"] }"""
        val zipByName = parseZipByName(Json.parseToJsonElement(repeated).jsonObject)

        assertEquals("1200", zipByName["Poblacion"])
    }
}
