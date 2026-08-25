package com.medmission.survey.util

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * Turns what an operator typed into the one form every later system agrees on, E.164
 * (`+639171234567`), and back into something readable.
 *
 * The conversion is per-country and not a rule we could write down: most countries drop
 * the trunk zero once the country code is on, Italy keeps it, and some have no trunk
 * prefix at all. Getting it wrong means a positive patient cannot be called back, so
 * the country's own metadata decides rather than a guess of ours.
 *
 * The Philippine form keeps its own `0917-123-4567` mask — this is for the global form,
 * where the country is whatever the operator picked.
 */
class PhoneFormatter(context: Context) {
    private val util: PhoneNumberUtil = PhoneNumberUtil.createInstance(context.applicationContext)

    /** The `+63` to show beside the field once a country is chosen. Null if unknown. */
    fun callingCodeFor(regionCode: String): String? {
        val code = util.getCountryCodeForRegion(regionCode)
        return if (code == 0) null else "+$code"
    }

    /**
     * E.164 for storage and the wire, or null when the number cannot be one. A number
     * that does not belong to the chosen country is rejected rather than stored bent
     * into shape.
     */
    fun toE164(input: String, regionCode: String): String? {
        if (input.isBlank()) return null
        return try {
            val parsed = util.parse(input, regionCode)
            if (!util.isValidNumber(parsed)) null
            else util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: NumberParseException) {
            null
        }
    }

    /** `+63 917 123 4567` — the international display grouping for that country. */
    fun toDisplay(input: String, regionCode: String): String {
        if (input.isBlank()) return input
        return try {
            val parsed = util.parse(input, regionCode)
            if (!util.isValidNumber(parsed)) input
            else util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } catch (e: NumberParseException) {
            input
        }
    }

    /**
     * Groups digits the way that country writes them while the operator is still typing,
     * so the field reads like a phone number rather than a run of digits.
     */
    fun formatAsYouType(input: String, regionCode: String): String {
        val formatter = util.getAsYouTypeFormatter(regionCode)
        var out = ""
        for (ch in input) {
            if (ch.isDigit() || ch == '+') out = formatter.inputDigit(ch)
        }
        return out.ifBlank { input }
    }

    /** Whether the number is one this country could actually issue. */
    fun isValid(input: String, regionCode: String): Boolean = toE164(input, regionCode) != null
}
