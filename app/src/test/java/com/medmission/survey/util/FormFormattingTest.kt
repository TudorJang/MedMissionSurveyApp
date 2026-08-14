package com.medmission.survey.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormFormattingTest {

    // ---- devicePrefixFrom ----

    @Test
    fun `devicePrefixFrom takes the last 4 characters uppercased`() {
        assertEquals("A3F2", devicePrefixFrom("9f8e7d6c5b4aa3f2"))
    }

    @Test
    fun `devicePrefixFrom pads a short id with leading zeros`() {
        assertEquals("00AB", devicePrefixFrom("ab"))
    }

    @Test
    fun `devicePrefixFrom falls back to zeros for a null id`() {
        assertEquals("0000", devicePrefixFrom(null))
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
