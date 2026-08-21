package com.medmission.survey.util

import java.security.MessageDigest
import java.time.LocalDate
import java.time.Period

/**
 * Four base36 characters (0-9A-Z) derived from the whole device id: 36^4 = 1,679,616
 * prefixes, 26x the old hex-tail space, so two tablets colliding on a number becomes
 * that much rarer. The console team confirmed nothing parses the accession format, so
 * this is a tablet-only change. SHA-256 keeps it deterministic per device; falls back
 * to "0000" when there is no id at all.
 */
fun devicePrefixFrom(deviceId: String?): String {
    val id = deviceId?.takeIf { it.isNotBlank() } ?: return "0000"
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    var acc = 0L
    for (i in 0 until 8) acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
    var n = ((acc and Long.MAX_VALUE) % 1_679_616L).toInt()
    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val prefix = CharArray(4)
    for (i in 3 downTo 0) {
        prefix[i] = alphabet[n % 36]
        n /= 36
    }
    return String(prefix)
}

/** e.g. "TAB-A3F2-0001". The index is never truncated, only ever zero-padded to 4+ digits. */
fun formatRecordNo(devicePrefix: String, index: Int): String =
    "TAB-$devicePrefix-${index.toString().padStart(4, '0')}"

fun todayLocalDateString(today: LocalDate = LocalDate.now()): String = today.toString()

/** Strips everything but digits, keeps at most [groupSizes] worth, and re-inserts dashes. */
private fun maskDigitsWithDashes(input: String, groupSizes: List<Int>): String {
    val digits = input.filter { it.isDigit() }.take(groupSizes.sum())
    val result = StringBuilder()
    var consumed = 0
    for ((i, size) in groupSizes.withIndex()) {
        if (consumed >= digits.length) break
        if (i > 0) result.append('-')
        val end = minOf(consumed + size, digits.length)
        result.append(digits, consumed, end)
        consumed = end
    }
    return result.toString()
}

fun formatBirthDateInput(input: String): String = maskDigitsWithDashes(input, listOf(4, 2, 2))

fun formatCellPhoneInput(input: String): String = maskDigitsWithDashes(input, listOf(4, 3, 4))

fun formatZipInput(input: String): String = input.filter { it.isDigit() }.take(4)

fun formatYearInput(input: String): String = input.filter { it.isDigit() }.take(4)

fun filterVitalSignInput(input: String): String = input.filter { it.isDigit() || it == '.' }

/** Full years between an ISO "yyyy-MM-dd" birth date and [today]. Null if unparseable or future. */
fun calculateAge(birthDate: String, today: LocalDate = LocalDate.now()): Int? {
    val parsed = runCatching { LocalDate.parse(birthDate) }.getOrNull() ?: return null
    if (parsed.isAfter(today)) return null
    return Period.between(parsed, today).years
}
