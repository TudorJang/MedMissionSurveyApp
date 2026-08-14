package com.medmission.survey.data.psgc

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PsgcRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private val hierarchy: PsgcHierarchy by lazy {
        parsePsgcHierarchy(json.parseToJsonElement(readAsset("psgc/hierarchy.json")).jsonObject)
    }

    private val zipByName: Map<String, String> by lazy {
        parseZipByName(json.parseToJsonElement(readAsset("psgc/zipcodes.json")).jsonObject)
    }

    fun regions(): List<String> = hierarchy.regions

    fun provinces(region: String): List<String> = hierarchy.provincesByRegion[region].orEmpty()

    fun cities(region: String, province: String?): List<String> =
        hierarchy.citiesByParent[PsgcPath(region, province)].orEmpty()

    fun barangays(region: String, province: String?, city: String): List<String> =
        hierarchy.barangaysByCity[PsgcPath(region, province, city)].orEmpty()

    fun zip(city: String, barangay: String?): String? = findZip(zipByName, city, barangay)
}
