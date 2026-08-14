package com.medmission.survey.data.psgc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

private val SAMPLE_HIERARCHY = """
{
  "REGION I (ILOCOS REGION)": {
    "population": 100,
    "Ilocos Norte": {
      "population": 50,
      "Batac": {
        "class": "City",
        "population": 20,
        "Ablan": {"population": 5},
        "Bagnos": {"population": 3}
      }
    }
  },
  "NATIONAL CAPITAL REGION (NCR)": {
    "population": 200,
    "City Of Manila": {
      "class": "City",
      "cityClass": "Highly Urbanized City",
      "population": 100,
      "Ermita": {"population": 10},
      "Malate": {"population": 15}
    }
  }
}
""".trimIndent()

class PsgcParserTest {
    private fun parse() = parsePsgcHierarchy(Json.parseToJsonElement(SAMPLE_HIERARCHY).jsonObject)

    @Test
    fun `lists every region`() {
        assertEquals(
            setOf("REGION I (ILOCOS REGION)", "NATIONAL CAPITAL REGION (NCR)"),
            parse().regions.toSet(),
        )
    }

    @Test
    fun `a region with provinces lists them`() {
        assertEquals(listOf("Ilocos Norte"), parse().provincesByRegion["REGION I (ILOCOS REGION)"])
    }

    @Test
    fun `NCR has no provinces`() {
        assertEquals(emptyList<String>(), parse().provincesByRegion["NATIONAL CAPITAL REGION (NCR)"])
    }

    @Test
    fun `cities are listed under their province`() {
        val path = PsgcPath(region = "REGION I (ILOCOS REGION)", province = "Ilocos Norte")
        assertEquals(listOf("Batac"), parse().citiesByParent[path])
    }

    @Test
    fun `NCR cities are listed directly under the region, with a null province`() {
        val path = PsgcPath(region = "NATIONAL CAPITAL REGION (NCR)", province = null)
        assertEquals(listOf("City Of Manila"), parse().citiesByParent[path])
    }

    @Test
    fun `barangays are listed under their city`() {
        val path = PsgcPath(region = "REGION I (ILOCOS REGION)", province = "Ilocos Norte", city = "Batac")
        assertEquals(setOf("Ablan", "Bagnos"), parse().barangaysByCity[path]?.toSet())
    }

    @Test
    fun `NCR barangays are listed under the city with a null province`() {
        val path = PsgcPath(region = "NATIONAL CAPITAL REGION (NCR)", province = null, city = "City Of Manila")
        assertEquals(setOf("Ermita", "Malate"), parse().barangaysByCity[path]?.toSet())
    }
}
