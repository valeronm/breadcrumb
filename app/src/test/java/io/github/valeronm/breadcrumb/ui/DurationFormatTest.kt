package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a duration reads at every scale it reaches. The cases are the rungs of the ladder and the
 * joins between them: a place's cumulative total runs to years, a stay to minutes, and the same
 * formatter serves both.
 *
 * The rungs are what is pinned, not the letters — those come from resources and are a translator's.
 * [AsciiDurations] supplies the English ones so the expectations below read as the app does.
 */
class DurationFormatTest {

    private fun ladder(durationMs: Long) = formatDurationMs(durationMs, AsciiDurations)

    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = minutes(n * 60)
    private fun days(n: Long) = hours(n * 24)

    @Test fun `under an hour reads in minutes`() {
        assertEquals("0m", ladder(0))
        assertEquals("45m", ladder(minutes(45)))
        assertEquals("59m", ladder(minutes(59)))
    }

    /** Seconds round to the nearest minute — no unit below a minute exists here. */
    @Test fun `seconds round into the minute`() {
        assertEquals("1m", ladder(89_000))
        assertEquals("2m", ladder(91_000))
    }

    @Test fun `hours carry minutes, and drop them when there are none`() {
        assertEquals("1h", ladder(hours(1)))
        assertEquals("5h 30m", ladder(hours(5) + minutes(30)))
        // Zero-padded so a column of these lines up.
        assertEquals("5h 05m", ladder(hours(5) + minutes(5)))
    }

    @Test fun `days carry hours, and drop them when there are none`() {
        assertEquals("1d", ladder(days(1)))
        assertEquals("3d 11h", ladder(days(3) + hours(11)))
        assertEquals("29d 23h", ladder(days(29) + hours(23)))
    }

    /** The minutes are gone by a day: at that size they are under a tenth of a percent. */
    @Test fun `a day or more is quoted to the hour`() {
        assertEquals("3d 11h", ladder(days(3) + hours(11) + minutes(29)))
        assertEquals("3d 12h", ladder(days(3) + hours(11) + minutes(31)))
    }

    @Test fun `a month or more sheds the hours for days`() {
        assertEquals("1mo", ladder(days(30)))
        assertEquals("1mo 1d", ladder(days(31) + hours(11)))
        assertEquals("1mo 15d", ladder(days(45) + hours(3)))
        assertEquals("2mo 2d", ladder(days(63) + hours(11)))
    }

    @Test fun `a year or more sheds the days for months`() {
        assertEquals("2y 2mo", ladder(days(800) + hours(3)))
        assertEquals("3y 5mo", ladder(days(1254) + hours(11)))
    }

    /**
     * The joins are where a naive split misreports: a remainder that has rounded up to a whole unit
     * *is* that unit, so a year's worth of days must not surface as eleven months and thirty days —
     * two figures that together say "a year" while looking like less. Just below the join the split
     * is honest and stays.
     */
    @Test fun `a remainder rounding up to a whole unit carries`() {
        assertEquals("11mo 29d", ladder(days(364)))
        assertEquals("1y", ladder(days(365)))
        assertEquals("1y", ladder(days(366)))
        assertEquals("1y 1mo", ladder(days(395)))
    }
}
