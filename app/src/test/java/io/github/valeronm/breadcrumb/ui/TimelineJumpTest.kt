package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TimelineRows
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.trackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineJumpTest {

    private val utc = ZoneId.of("UTC")

    private fun at(day: Int, hour: Int) =
        LocalDate.of(2026, 7, day).atStartOfDay(utc).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun rowsOf(gap: StayDeriver.Gap): List<TimelineItem.GapItem> =
        TimelineRows.interleave(
            emptyList(),
            TimelineRows.slicePerDay(listOf(gap), { Clocks.both(utc) }, nowMs = at(30, 0)),
        ).filterIsInstance<TimelineItem.GapItem>()

    private fun gap(start: Long, end: Long) = StayDeriver.Gap(
        start = start,
        end = end,
        afterTrackId = 7,
    )

    @Test fun `each end of a gap crossing midnight lands on the half that holds it`() {
        val crossing = gap(at(16, 20), at(18, 10))
        val rows = rowsOf(crossing)
        assertEquals(2, rows.size)

        val departure = rows.single { TimelineJump.Gap(crossing, atDeparture = true).matches(it) }
        val arrival = rows.single { TimelineJump.Gap(crossing, atDeparture = false).matches(it) }

        assertTrue(departure.holdsDeparture)
        assertTrue(arrival.holdsArrival)
        for (row in rows) {
            assertTrue(TimelineJump.Gap(row.gap, atDeparture = row.holdsDeparture).matches(row))
        }
        assertEquals(crossing.start, departure.gap.start)
        assertEquals(crossing.end, arrival.gap.end)
    }

    @Test fun `a gap within one day is one row both ends land on`() {
        val hop = gap(at(16, 9), at(16, 11))
        val row = rowsOf(hop).single()

        assertTrue(TimelineJump.Gap(hop, atDeparture = true).matches(row))
        assertTrue(TimelineJump.Gap(hop, atDeparture = false).matches(row))
    }

    @Test fun `a trip lands on its row, anchored at its start`() {
        val recorded = trackSummary(1, ActivityType.DRIVING.name, at(16, 9), at(16, 10))
        val entered = trackSummary(2, ActivityType.DRIVING.name, at(16, 10), at(16, 11), source = TrackOrigin.MANUAL.code)
        val elsewhere = trackSummary(3, ActivityType.WALKING.name, at(16, 14), at(16, 15))
        val row = TimelineRows.interleave(listOf(elsewhere, entered, recorded), emptyList())
            .filterIsInstance<TimelineItem.TrackItem>()
            .single { it.trip.chained }
        val jump = TimelineJump.TripRow(row.trip)

        assertTrue(jump.matches(row))
        assertEquals(recorded.startedAt, jump.anchorMs)
        assertFalse(TimelineJump.TripRow(Trip(elsewhere)).matches(row))
    }
}
