package com.medmission.survey.data.psgc

/**
 * A fully-qualified path through the PSGC hierarchy. [province] is null when the region has
 * no province level (currently only NCR) — never a placeholder string like "NCR".
 */
data class PsgcPath(
    val region: String,
    val province: String?,
    val city: String? = null,
    val barangay: String? = null,
)

data class PsgcHierarchy(
    val regions: List<String>,
    /** Empty list means the region has no province level (e.g. NCR) — cities sit directly under it. */
    val provincesByRegion: Map<String, List<String>>,
    /** Keyed by a path with city and barangay null. */
    val citiesByParent: Map<PsgcPath, List<String>>,
    /** Keyed by a path with barangay null. */
    val barangaysByCity: Map<PsgcPath, List<String>>,
    /**
     * The postal district a barangay belongs to, keyed by city and barangay name. PSA
     * publishes it only where a city has sub-municipalities — Manila, in practice — and
     * it is what lets a numbered barangay find a ZIP the name lookup cannot reach.
     */
    val districtByBarangay: Map<Pair<String, String>, String> = emptyMap(),
)
