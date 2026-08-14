package com.medmission.survey.data.psgc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val METADATA_KEYS = setOf("population", "class", "cityClass", "subMunicipality")

/**
 * True when [node]'s children are city/municipality objects rather than province objects —
 * i.e. at least one child carries the PSGC `"class"` key, which every city/municipality node
 * has and no province node ever has. NCR is the only region where this is currently true,
 * but the check itself doesn't hardcode "NCR" — it generalizes to any future region PSA
 * structures the same way.
 */
private fun childrenAreCities(node: JsonObject): Boolean =
    locationChildren(node).any { (_, child) -> child.containsKey("class") }

private fun locationChildren(node: JsonObject): List<Pair<String, JsonObject>> =
    node.entries
        .filter { (key, value) -> key !in METADATA_KEYS && value is JsonObject }
        .map { (key, value) -> key to value.jsonObject }

fun parsePsgcHierarchy(root: JsonObject): PsgcHierarchy {
    val regions = mutableListOf<String>()
    val provincesByRegion = mutableMapOf<String, List<String>>()
    val citiesByParent = mutableMapOf<PsgcPath, List<String>>()
    val barangaysByCity = mutableMapOf<PsgcPath, List<String>>()

    fun addCity(region: String, province: String?, cityName: String, cityNode: JsonObject) {
        val path = PsgcPath(region = region, province = province, city = cityName)
        barangaysByCity[path] = locationChildren(cityNode).map { it.first }
    }

    for ((regionName, regionNode) in locationChildren(root)) {
        regions += regionName
        if (childrenAreCities(regionNode)) {
            provincesByRegion[regionName] = emptyList()
            val citiesPath = PsgcPath(region = regionName, province = null)
            val cities = locationChildren(regionNode)
            citiesByParent[citiesPath] = cities.map { it.first }
            for ((cityName, cityNode) in cities) {
                addCity(regionName, province = null, cityName, cityNode)
            }
        } else {
            val provinces = locationChildren(regionNode)
            provincesByRegion[regionName] = provinces.map { it.first }
            for ((provinceName, provinceNode) in provinces) {
                val citiesPath = PsgcPath(region = regionName, province = provinceName)
                val cities = locationChildren(provinceNode)
                citiesByParent[citiesPath] = cities.map { it.first }
                for ((cityName, cityNode) in cities) {
                    addCity(regionName, provinceName, cityName, cityNode)
                }
            }
        }
    }

    return PsgcHierarchy(regions, provincesByRegion, citiesByParent, barangaysByCity)
}

fun parseZipByName(root: JsonObject): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for ((zip, value) in root) {
        val names = when (value) {
            is JsonArray -> value.map { it.jsonPrimitive.content }
            else -> listOf(value.jsonPrimitive.content)
        }
        for (name in names) {
            result.putIfAbsent(name, zip)
        }
    }
    return result
}

fun findZip(zipByName: Map<String, String>, city: String, barangay: String?): String? =
    barangay?.let { zipByName[it] } ?: zipByName[city]
