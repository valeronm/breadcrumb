package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a duration reads at every scale it reaches. The cases are the rungs of the ladder and the
 * joins between them: a place's cumulative total runs to years, a stay to minutes, and the same
 * formatter serves both.
 */
class DurationFormatTest {

    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = minutes(n * 60)
    private fun days(n: Long) = hours(n * 24)

    @Test fun `under an hour reads in minutes`() {
        assertEquals("0m", formatDurationMs(0))
        assertEquals("45m", formatDurationMs(minutes(45)))
        assertEquals("59m", formatDurationMs(minutes(59)))
    }

    /** Seconds round to the nearest minute — no unit below a minute exists here. */
    @Test fun `seconds round into the minute`() {
        assertEquals("1m", formatDurationMs(89_000))
        assertEquals("2m", formatDurationMs(91_000))
    }

    @Test fun `hours carry minutes, and drop them when there are none`() {
        assertEquals("1h", formatDurationMs(hours(1)))
        assertEquals("5h 30m", formatDurationMs(hours(5) + minutes(30)))
        // Zero-padded so a column of these lines up.
        assertEquals("5h 05m", formatDurationMs(hours(5) + minutes(5)))
    }

    @Test fun `days carry hours, and drop them when there are none`() {
        assertEquals("1d", formatDurationMs(days(1)))
        assertEquals("3d 11h", formatDurationMs(days(3) + hours(11)))
        assertEquals("29d 23h", formatDurationMs(days(29) + hours(23)))
    }

    /** The minutes are gone by a day: at that size they are under a tenth of a percent. */
    @Test fun `a day or more is quoted to the hour`() {
        assertEquals("3d 11h", formatDurationMs(days(3) + hours(11) + minutes(29)))
        assertEquals("3d 12h", formatDurationMs(days(3) + hours(11) + minutes(31)))
    }

    @Test fun `a month or more sheds the hours for days`() {
        assertEquals("1mo", formatDurationMs(days(30)))
        assertEquals("1mo 1d", formatDurationMs(days(31) + hours(11)))
        assertEquals("1mo 15d", formatDurationMs(days(45) + hours(3)))
        assertEquals("2mo 2d", formatDurationMs(days(63) + hours(11)))
    }

    @Test fun `a year or more sheds the days for months`() {
        assertEquals("2y 2mo", formatDurationMs(days(800) + hours(3)))
        assertEquals("3y 5mo", formatDurationMs(days(1254) + hours(11)))
    }

    /**
     * The joins are where a naive split misreports: a remainder that has rounded up to a whole unit
     * *is* that unit, so a year's worth of days must not surface as eleven months and thirty days —
     * two figures that together say "a year" while looking like less. Just below the join the split
     * is honest and stays.
     */
    @Test fun `a remainder rounding up to a whole unit carries`() {
        assertEquals("11mo 29d", formatDurationMs(days(364)))
        assertEquals("1y", formatDurationMs(days(365)))
        assertEquals("1y", formatDurationMs(days(366)))
        assertEquals("1y 1mo", formatDurationMs(days(395)))
    }
}
