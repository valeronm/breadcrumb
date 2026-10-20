package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimelineNavigationTest {
    @Test
    fun `single day jumps land on the requested row including the first`() {
        assertEquals(0, timelineJumpScrollIndex(0, singleDayMode = true))
        assertEquals(3, timelineJumpScrollIndex(3, singleDayMode = true))
    }

    @Test
    fun `classic jumps retain space for the sticky day heading`() {
        assertEquals(0, timelineJumpScrollIndex(1, singleDayMode = false))
        assertEquals(3, timelineJumpScrollIndex(4, singleDayMode = false))
    }

    @Test
    fun `a visit lands on its own local day on either side of the date line`() {
        val start = Instant.parse("2026-10-20T01:00:00Z").toEpochMilli()
        val stay = StayDeriver.Stay(start = start, end = start + 60_000, afterTrackId = 1, clusterId = 0)
        val tokyo = TimelineItem.StayItem(stay, zone = ZoneId.of("Asia/Tokyo"))
        val honolulu = tokyo.copy(zone = ZoneId.of("Pacific/Honolulu"))

        assertEquals(LocalDate.of(2026, 10, 20), timelineJumpDay(TimelineJump.Visit(stay), listOf(tokyo)))
        assertEquals(LocalDate.of(2026, 10, 19), timelineJumpDay(TimelineJump.Visit(stay), listOf(honolulu)))
    }

    @Test
    fun `a multi day gap lands on the selected departure or arrival half`() {
        val start = Instant.parse("2026-10-18T01:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-20T01:00:00Z").toEpochMilli()
        val gap = StayDeriver.Gap(start, end, StayDeriver.GapReason.MOVED_UNRECORDED, afterTrackId = 1)
        val departure = TimelineItem.GapItem(
            gap = gap,
            departureZone = ZoneId.of("Asia/Tokyo"),
            holdsArrival = false,
        )
        val arrival = TimelineItem.GapItem(
            gap = gap,
            arrivalZone = ZoneId.of("Pacific/Honolulu"),
            holdsDeparture = false,
        )
        val items = listOf(arrival, departure)

        assertEquals(
            LocalDate.of(2026, 10, 18),
            timelineJumpDay(TimelineJump.Gap(PlaceResolver.PlaceGap(gap, fromHere = true, farKey = null)), items),
        )
        assertEquals(
            LocalDate.of(2026, 10, 19),
            timelineJumpDay(TimelineJump.Gap(PlaceResolver.PlaceGap(gap, fromHere = false, farKey = null)), items),
        )
    }

    @Test
    fun `a removed visit falls back to the surrounding row day`() {
        val start = Instant.parse("2026-10-20T01:00:00Z").toEpochMilli()
        val stay = StayDeriver.Stay(start = start, end = start + 60_000, afterTrackId = 1, clusterId = 0)
        val replacement = TimelineItem.StayItem(
            stay.copy(start = start - 60_000),
            zone = ZoneId.of("Pacific/Honolulu"),
        )

        assertEquals(LocalDate.of(2026, 10, 19), timelineJumpDay(TimelineJump.Visit(stay), listOf(replacement)))
    }
}
