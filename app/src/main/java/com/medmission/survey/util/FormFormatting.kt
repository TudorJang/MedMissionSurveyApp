package com.medmission.survey.util

import java.time.LocalDate
import java.time.Period

/** Last 4 alphanumeric characters of a device id, uppercased. Falls back to "0000". */
fun devicePrefixFrom(deviceId: String?): String {
    val cleaned = deviceId?.filter { it.isLetterOrDigit() }.orEmpty()
    val tail = cleaned.takeLast(4).ifEmpty { "0000" }
    return tail.uppercase().padStart(4, '0')
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

fun filterVitalSignInput(input: String): String = input.filter { it.isDigit() || it == '.' }

/** Full years between an ISO "yyyy-MM-dd" birth date and [today]. Null if unparseable or future. */
fun calculateAge(birthDate: String, today: LocalDate = LocalDate.now()): Int? {
    val parsed = runCatching { LocalDate.parse(birthDate) }.getOrNull() ?: return null
    if (parsed.isAfter(today)) return null
    return Period.between(parsed, today).years
}
