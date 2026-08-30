package io.github.valeronm.breadcrumb.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/**
 * A journey's date range is the one date the app renders as an *interval*, and the shape of an
 * interval — which end carries the month, whether the month is said once or twice — is the locale's
 * to decide. These pin that the decision is the locale's and not this code's: the same range reads
 * month-first in one English and day-first in another. What is asserted is the structure, never
 * ICU's exact punctuation, which moves between CLDR releases.
 *
 * Robolectric because the formatter is the platform's ICU; there is no plain-JVM route to it.
 */
@RunWith(RobolectricTestRunner::class)
class JourneyDatesTest {

    private lateinit var original: Locale

    @Before fun keepLocale() {
        original = Locale.getDefault()
    }

    @After fun restoreLocale() {
        Locale.setDefault(original)
    }

    private val from = LocalDate.of(2024, 5, 12)
    private val to = LocalDate.of(2024, 5, 17)
    private val today = LocalDate.of(2024, 9, 1)

    @Test fun `a same-month range says the month once, where the locale puts it`() {
        Locale.setDefault(Locale.US)
        val us = dateRange(from, to, today)
        assertTrue(us, us.startsWith("May"))
        assertEquals(us, us.indexOf("May"), us.lastIndexOf("May"))
        assertTrue(us, "12" in us && "17" in us)

        Locale.setDefault(Locale.UK)
        val uk = dateRange(from, to, today)
        assertTrue(uk, uk.endsWith("May"))
        assertEquals(uk, uk.indexOf("May"), uk.lastIndexOf("May"))
        assertTrue(uk, "12" in uk && "17" in uk)
    }

    @Test fun `a range across months names both`() {
        Locale.setDefault(Locale.UK)
        val range = dateRange(LocalDate.of(2024, 4, 28), LocalDate.of(2024, 5, 3), today)
        assertTrue(range, "April" in range && "May" in range)
        assertFalse(range, "2024" in range)
    }

    @Test fun `the year is dropped only while both ends are in the current one`() {
        Locale.setDefault(Locale.UK)
        assertFalse("2024" in dateRange(from, to, today))
        assertTrue("2024" in dateRange(from, to, LocalDate.of(2025, 1, 1)))
    }

    @Test fun `a range across years states both years`() {
        Locale.setDefault(Locale.UK)
        val range = dateRange(LocalDate.of(2019, 12, 28), LocalDate.of(2020, 1, 3), today)
        assertTrue(range, "2019" in range && "2020" in range)
    }
}
