package io.github.valeronm.breadcrumb.domain.tracks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class EndTimeTest {

    private val utc = ZoneOffset.UTC
    private val ahead = ZoneOffset.ofHours(3)
    private val day = LocalDate.of(2020, 1, 2)
    private val bound = day.atTime(16, 16, 42, 123_000_000).toInstant(utc).toEpochMilli()

    @Test fun `a held bound is the same instant on any clock, and a typed one is read on the clock given`() {
        assertEquals(bound, EndTime.Held(bound).epochIn(utc))
        assertEquals(bound, EndTime.Held(bound).epochIn(ahead))
        val typed = EndTime.Typed(day, LocalTime.of(9, 0))
        assertEquals(typed.epochIn(utc) - 3 * 3_600_000L, typed.epochIn(ahead))
    }

    @Test fun `an end holding no time, or a date alone, names no instant`() {
        assertEquals(null, EndTime.None.epochIn(utc))
        assertEquals(null, EndTime.DateOnly(day).epochIn(utc))
        assertEquals(day, EndTime.DateOnly(day).dateIn(ahead))
    }

    @Test fun `a date picked keeps the time shown, to the millisecond, on that end's clock`() {
        val next = day.plusDays(1)
        val moved = EndTime.withDate(bound, next, utc)
        assertEquals(EndTime.Typed(next, LocalTime.of(16, 16, 42, 123_000_000)), moved)
        assertEquals(bound + 86_400_000L, moved.epochIn(utc))
    }

    @Test fun `a date picked for an end showing no time is held alone`() {
        assertEquals(EndTime.DateOnly(day), EndTime.withDate(null, day, utc))
    }
}
