package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * How rows fall into days once each carries the clock of the place it happened in.
 *
 * The rule under test is that a day is a **run**, not a value: a date can appear twice or be skipped
 * entirely, because that is what crossing zones does to a calendar. Every fixture here is built from
 * instants and zones alone — no coordinates, so nothing here can leak where anyone was.
 */
class TimelineDayGroupingTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val honolulu = ZoneId.of("Pacific/Honolulu")
    private val utc = ZoneId.of("UTC")

    /** A stay of no consequence at [at], read on [zone] — only its instant and clock matter here. */
    private fun row(at: Long, zone: ZoneId, id: Long = at) = TimelineItem.StayItem(
        stay = StayDeriver.Stay(
            start = at, end = at + 60_000,
            provenance = StayDeriver.Provenance.OBSERVED, afterTrackId = id, clusterId = 0,
        ),
        zone = zone,
    )

    private fun at(zone: ZoneId, date: LocalDate, hour: Int) =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test fun `rows sharing a date and a clock make one day`() {
        val day = LocalDate.of(2026, 7, 18)
        val groups = groupTimelineByDay(
            listOf(row(at(utc, day, 18), utc), row(at(utc, day, 9), utc)),
        )

        assertEquals(1, groups.size)
        assertEquals(day, groups.single().date)
        assertEquals(2, groups.single().items.size)
    }

    @Test fun `a date lived twice becomes two days, not one heading with a hole in it`() {
        // Leave Tokyo on the 18th, land in Honolulu on the 17th — nineteen hours behind, so the
        // later instant carries the earlier date. Newest-first, the dates run 17, 18: not
        // descending, and the two 18ths must not be welded together across the crossing.
        val tokyoMorning = at(tokyo, LocalDate.of(2026, 7, 18), 10)
        val items = listOf(
            row(tokyoMorning + 3 * 3_600_000, tokyo), // still the 18th in Tokyo
            row(tokyoMorning + 2 * 3_600_000, honolulu), // the 17th in Honolulu
            row(tokyoMorning, tokyo), // the 18th again, before the crossing
        )

        val groups = groupTimelineByDay(items)

        assertEquals(3, groups.size)
        assertEquals(
            listOf(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)),
            groups.map { it.date },
        )
        assertEquals(items.size, groups.sumOf { it.items.size })
    }

    @Test fun `every row survives its grouping, in the order it arrived`() {
        val items = listOf(
            row(at(utc, LocalDate.of(2026, 7, 18), 9), utc),
            row(at(tokyo, LocalDate.of(2026, 7, 18), 9), tokyo),
            row(at(utc, LocalDate.of(2026, 7, 17), 9), utc),
        )

        assertEquals(items, groupTimelineByDay(items).flatMap { it.items })
    }

    @Test fun `a row nothing could place still lands somewhere`() {
        // No zone attached — an import with no usable endpoint. It must not vanish from the list,
        // and it must not take the grouping down with it.
        val groups = groupTimelineByDay(listOf(TimelineItem.TrackItem(trackSummary(at(utc, LocalDate.of(2026, 7, 18), 9)))))

        assertEquals(1, groups.size)
        assertNull(groups.single().items.single().zone)
    }

    @Test fun `the grouping of nothing is nothing`() {
        assertEquals(emptyList<DayGroup>(), groupTimelineByDay(emptyList()))
    }

    /**
     * A piece of an absence: the clock each of its ends runs on, and which of them this piece speaks
     * for — an end it does not hold is a row of its own elsewhere. One that happened in a single
     * place holds both.
     */
    private fun gapSlice(
        from: Long,
        to: Long,
        departureZone: ZoneId = utc,
        arrivalZone: ZoneId = utc,
        holdsDeparture: Boolean = true,
        holdsArrival: Boolean = true,
    ) = TimelineItem.GapItem(
        gap = StayDeriver.Gap(
            start = from, end = to,
            reason = StayDeriver.GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        ),
        departureZone = departureZone,
        arrivalZone = arrivalZone,
        holdsDeparture = holdsDeparture,
        holdsArrival = holdsArrival,
    )

    @Test fun `an absence spanning days files its two halves under the days holding its ends`() {
        // The shape the slicer actually emits for a multi-day outage: a departure half running to
        // the midnight opening the day it ended, and the arrival half. The 17th is inside the first
        // half and gets no group of its own — nothing is known about it, so there is nothing to say.
        val day = { d: Int -> LocalDate.of(2026, 7, d).atStartOfDay(utc).toInstant().toEpochMilli() }
        val items = listOf(
            gapSlice(day(18), day(18) + 10 * 3_600_000, holdsDeparture = false), // resumed on the 18th
            gapSlice(day(16) + 20 * 3_600_000, day(18), holdsArrival = false), // stopped on the 16th
        )

        val groups = groupTimelineByDay(items)

        assertEquals(
            listOf(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 16)),
            groups.map { it.date },
        )
        assertEquals(listOf(1, 1), groups.map { it.items.size })
    }

    @Test fun `a crossing's arrival files under the day it landed, its departure under the day it left`() {
        // Eastward, where the arrival's day genuinely begins after the departure — going the other
        // way a flight lands on the day it left, and there is no second day to give a row to.
        val lisbon = ZoneId.of("Europe/Lisbon")
        val departedAt = LocalDate.of(2026, 7, 17).atStartOfDay(lisbon).plusHours(10)
            .toInstant().toEpochMilli()
        val landedAt = LocalDate.of(2026, 7, 18).atStartOfDay(tokyo).plusHours(12)
            .toInstant().toEpochMilli()
        val cut = LocalDate.of(2026, 7, 18).atStartOfDay(tokyo).toInstant().toEpochMilli()
        val items = listOf(
            gapSlice(cut, landedAt, lisbon, tokyo, holdsDeparture = false),
            gapSlice(departedAt, cut, lisbon, tokyo, holdsArrival = false),
        )

        val groups = groupTimelineByDay(items)

        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 7, 18), groups[0].date)
        assertEquals(LocalDate.of(2026, 7, 17), groups[1].date)
    }

    private fun trackSummary(startedAt: Long) = io.github.valeronm.breadcrumb.data.db.TrackSummary(
        id = 1, activityType = "walking", startedAt = startedAt, endedAt = startedAt + 60_000,
        distanceMeters = 0.0, pointCount = 2, ignoredCount = 0, source = "recorded",
    )
}
