package com.medmission.survey.data.psgc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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
    val districtByBarangay = mutableMapOf<Pair<String, String>, String>()

    fun addCity(region: String, province: String?, cityName: String, cityNode: JsonObject) {
        val path = PsgcPath(region = region, province = province, city = cityName)
        val barangays = locationChildren(cityNode)
        barangaysByCity[path] = barangays.map { it.first }
        for ((barangayName, barangayNode) in barangays) {
            val district = barangayNode["subMunicipality"]?.jsonPrimitive?.contentOrNull
            if (!district.isNullOrBlank()) {
                districtByBarangay[cityName to barangayName] = district
            }
        }
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

    return PsgcHierarchy(regions, provincesByRegion, citiesByParent, barangaysByCity,
        districtByBarangay)
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

/**
 * Manila's postal districts, for the barangays the name lookup cannot reach.
 *
 * Manila numbers its barangays ("Barangay 310"), so nothing in the ZIP dataset matches
 * them, and its PSGC city name does not match that dataset's Manila entries either —
 * every one of its 897 barangays came back without a ZIP. What PSA does carry for them
 * is the postal district ("Ermita", "Malate", …), and the ZIP dataset already ships one
 * entry per district, so the two can be joined by hand once instead of guessed at per
 * lookup. The values below are the dataset's own, quoted beside each.
 *
 * Districts whose dataset entry is split by direction are deliberately absent —
 * Sampaloc East/West (1008/1015), Sta. Cruz North/South (1014/1003), Tondo North/South
 * (1013/1012) and Port Area (South) (1018). PSA gives us one district where the post
 * office uses two, so any value we picked would be right about half the time and wrong
 * silently the rest. Those barangays keep coming back blank, which the operator can see
 * and fill; a wrong postcode in a medical record is not visible to anyone.
 */
private val MANILA_DISTRICT_ZIPS = mapOf(
    "Ermita" to "1000",       // "Manila CPO - Ermita"
    "Quiapo" to "1001",       // "Quiapo"
    "Intramuros" to "1002",   // "Intramuros"
    "Malate" to "1004",       // "Malate"
    "San Miguel" to "1005",   // "San Miguel"
    "Binondo" to "1006",      // "Binondo"
    "Paco" to "1007",         // "Paco"
    "Santa Ana" to "1009",    // "Sta. Ana"
    "San Nicolas" to "1010",  // "San Nicolas"
    "Pandacan" to "1011",     // "Pandacan"
)

fun findZip(
    zipByName: Map<String, String>,
    city: String,
    barangay: String?,
    districtOf: (String, String) -> String? = { _, _ -> null },
): String? =
    zipByName[city]
        ?: barangay?.let { zipByName[it] }
        ?: barangay?.let { districtOf(city, it) }?.let { MANILA_DISTRICT_ZIPS[it] }
