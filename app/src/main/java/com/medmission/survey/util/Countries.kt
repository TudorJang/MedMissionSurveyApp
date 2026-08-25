package com.medmission.survey.util

import java.util.Locale

/**
 * ISO 3166-1 countries, taken from the platform rather than bundled.
 *
 * Android already ships the list and the display names for it, so a data file would be
 * one more thing to keep current for no gain — the Philippine address data is bundled
 * because nothing else has barangays, but every device already knows the countries.
 */
object Countries {
    /** Alpha-2 code paired with the name to show, sorted by name. */
    val all: List<Pair<String, String>> by lazy {
        Locale.getISOCountries()
            .map { code -> code to Locale("", code).getDisplayCountry(Locale.ENGLISH) }
            .filter { (code, name) -> name.isNotBlank() && name != code }
            .sortedBy { it.second }
    }

    private val nameByCode by lazy { all.toMap() }
    private val codeByName by lazy { all.associate { (c, n) -> n to c } }

    /** "Philippines" for "PH". The code itself when it is not a country we know. */
    fun nameOf(code: String?): String? =
        code?.takeIf { it.isNotBlank() }?.let { nameByCode[it.uppercase(Locale.ROOT)] ?: it }

    /** "PH" for "Philippines". Null when the text is not one of the names above. */
    fun codeOf(name: String?): String? =
        name?.takeIf { it.isNotBlank() }?.let { codeByName[it.trim()] }

    const val DEFAULT_CODE = "PH"
}
