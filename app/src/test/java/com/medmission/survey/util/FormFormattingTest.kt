package com.medmission.survey.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormFormattingTest {

    // ---- devicePrefixFrom ----

    // The prefix space is base36 (0-9A-Z), 36^4 = 1,679,616 — 26x the old hex tail.
    // The console team confirmed no code parses or validates the accession format, so
    // widening the alphabet is a tablet-only release. Values are pinned so the
    // derivation never drifts silently: a drifted prefix would strand a device's
    // numbering mid-campaign.

    @Test
    fun `devicePrefixFrom derives a pinned base36 prefix from the whole id`() {
        assertEquals("WZIC", devicePrefixFrom("9f8e7d6c5b4aa3f2"))
        assertEquals("6NTW", devicePrefixFrom("ab"))
    }

    @Test
    fun `devicePrefixFrom stays within 0-9A-Z and 4 characters`() {
        for (id in listOf("emulatorA", "emulatorB", "x", "9f8e7d6c5b4aa3f2")) {
            val prefix = devicePrefixFrom(id)
            assertEquals(4, prefix.length)
            assertEquals(true, prefix.all { it in '0'..'9' || it in 'A'..'Z' })
        }
    }

    @Test
    fun `devicePrefixFrom is deterministic and separates devices`() {
        assertEquals(devicePrefixFrom("emulatorA"), devicePrefixFrom("emulatorA"))
        assertEquals("C5HB", devicePrefixFrom("emulatorA"))
        assertEquals("J54W", devicePrefixFrom("emulatorB"))
    }

    @Test
    fun `devicePrefixFrom falls back to zeros for a null or blank id`() {
        assertEquals("0000", devicePrefixFrom(null))
        assertEquals("0000", devicePrefixFrom("  "))
    }

    // ---- formatRecordNo ----

    @Test
    fun `formatRecordNo pads the index to 4 digits`() {
        assertEquals("TAB-A3F2-0001", formatRecordNo("A3F2", 1))
        assertEquals("TAB-A3F2-0042", formatRecordNo("A3F2", 42))
    }

    @Test
    fun `formatRecordNo does not truncate an index past 4 digits`() {
        assertEquals("TAB-A3F2-12345", formatRecordNo("A3F2", 12345))
    }

    // ---- todayLocalDateString ----

    @Test
    fun `todayLocalDateString formats as ISO yyyy-MM-dd`() {
        assertEquals("2026-08-13", todayLocalDateString(LocalDate.of(2026, 8, 13)))
    }

    // ---- formatBirthDateInput / formatCellPhoneInput masks ----

    @Test
    fun `formatBirthDateInput inserts dashes after 4 and 6 digits`() {
        assertEquals("1990-01-01", formatBirthDateInput("19900101"))
        assertEquals("1990", formatBirthDateInput("1990"))
        assertEquals("1990-0", formatBirthDateInput("19900"))
    }

    @Test
    fun `formatBirthDateInput strips non-digits and re-derives dashes from any input`() {
        assertEquals("1990-01-01", formatBirthDateInput("1990-01-01"))
        assertEquals("1990-01-01", formatBirthDateInput("abc1990xy0101zz"))
    }

    @Test
    fun `formatBirthDateInput ignores digits past the 8th`() {
        assertEquals("1990-01-01", formatBirthDateInput("199001019999"))
    }

    @Test
    fun `formatCellPhoneInput inserts dashes after 4 and 7 digits`() {
        assertEquals("0917-123-4567", formatCellPhoneInput("09171234567"))
        assertEquals("0917-123", formatCellPhoneInput("0917123"))
    }

    // ---- formatZipInput ----

    @Test
    fun `formatZipInput keeps only the first 4 digits`() {
        assertEquals("1000", formatZipInput("1000"))
        assertEquals("1000", formatZipInput("10005"))
        assertEquals("1000", formatZipInput("1a0b0c0d5"))
    }

    // ---- formatYearInput ----

    @Test
    fun `formatYearInput keeps only the first 4 digits`() {
        assertEquals("2019", formatYearInput("2019"))
        assertEquals("2019", formatYearInput("20195"))
        assertEquals("2019", formatYearInput("2y0o1u9"))
    }

    // ---- filterVitalSignInput ----

    @Test
    fun `filterVitalSignInput keeps only digits and dots`() {
        assertEquals("36.5", filterVitalSignInput("36.5"))
        assertEquals("36.5", filterVitalSignInput("36.5abc"))
        assertEquals("36.5", filterVitalSignInput("3 6 . 5"))
    }

    // ---- calculateAge ----

    @Test
    fun `calculateAge computes full years between birth date and today`() {
        assertEquals(36, calculateAge("1990-01-01", LocalDate.of(2026, 8, 13)))
    }

    @Test
    fun `calculateAge returns null for an unparseable or incomplete date`() {
        assertNull(calculateAge("1990-01", LocalDate.of(2026, 8, 13)))
        assertNull(calculateAge("", LocalDate.of(2026, 8, 13)))
        assertNull(calculateAge("1990-13-40", LocalDate.of(2026, 8, 13)))
    }

    @Test
    fun `calculateAge returns null for a birth date in the future`() {
        assertNull(calculateAge("2030-01-01", LocalDate.of(2026, 8, 13)))
    }
}
