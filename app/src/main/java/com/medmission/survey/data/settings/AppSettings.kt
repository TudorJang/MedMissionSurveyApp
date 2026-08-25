package com.medmission.survey.data.settings

import android.content.Context
import com.medmission.survey.util.Countries

/**
 * Which address form this tablet asks for.
 *
 * The Philippine form walks region → province → city → barangay from bundled PSGC data,
 * because that is the address a screening site in the Philippines needs and barangay is
 * the unit follow-up works in. Everywhere else that cascade has nothing behind it, so
 * the global form asks the four questions the original survey form asks — Address,
 * City, State/Province, ZIP — and lets the operator type them.
 *
 * Philippines is the default because that is where this runs today.
 */
enum class FormMode { PHILIPPINES, GLOBAL }

/**
 * The handful of choices that belong to the tablet rather than to a patient. Kept in
 * preferences: there are two of them, they are read on every form, and putting them in
 * the database would mean a migration every time one is added.
 */
class AppSettings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("medmission-settings", Context.MODE_PRIVATE)

    var formMode: FormMode
        get() = runCatching { FormMode.valueOf(prefs.getString(KEY_FORM_MODE, null) ?: "") }
            .getOrDefault(FormMode.PHILIPPINES)
        set(value) = prefs.edit().putString(KEY_FORM_MODE, value.name).apply()

    /**
     * The country the global form is collecting addresses in, as an ISO 3166-1 alpha-2
     * code. It belongs here rather than on each patient: a screening site sees three
     * hundred people from one country, and asking each of them costs the operator time
     * to learn nothing. It also tells the phone field which country's number rules to
     * apply.
     */
    var countryCode: String
        get() = prefs.getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() }
            ?: Countries.DEFAULT_CODE
        set(value) = prefs.edit().putString(KEY_COUNTRY, value).apply()

    /**
     * The country a record is actually being collected in.
     *
     * The stored [countryCode] belongs to the global form and survives a switch back to
     * the Philippine one, so reading it directly stamps the last global country onto
     * Philippine records — a tablet used in Vietnam and then brought home filed Manila
     * addresses as Vietnamese, and their phone numbers stopped normalising because they
     * were being parsed under the wrong country's rules. The Philippine form is only
     * ever collecting Philippine addresses, so it says so.
     */
    val effectiveCountryCode: String
        get() = if (formMode == FormMode.PHILIPPINES) Countries.DEFAULT_CODE else countryCode

    private companion object {
        const val KEY_FORM_MODE = "formMode"
        const val KEY_COUNTRY = "countryCode"
    }
}
