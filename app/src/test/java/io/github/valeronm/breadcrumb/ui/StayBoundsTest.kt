package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every case of [stayBounds], on a plain JVM. The ones the day boundary produces are the point: they
 * need a bound landing on midnight *in the row's own zone*, which is a fixture a rendering test can
 * only reach by agreeing with the row about that zone — and which passes on the ordinary branch,
 * quietly, when it doesn't.
 *
 * Times are built from a zone and a wall clock rather than from epoch numbers, so each case says
 * which clock it means. [ROW] and [ELSEWHERE] are chosen for one property only: their midnights are
 * hours apart, and neither is the epoch's — so the same two instants are a sliced stay on one clock
 * and an ordinary one on the other, and neither can be reproduced by dividing by a day.
 */
class StayBoundsTest {

    @Test fun `a stay between two clock times states both`() {
        assertEquals(
            StayBounds.Between(at(9, 30), at(11, 45)),
            stayBounds(at(9, 30), at(11, 45), ROW),
        )
    }

    @Test fun `an ongoing stay is stated from its start`() {
        assertEquals(StayBounds.Since(at(9, 30)), stayBounds(at(9, 30), null, ROW))
    }

    @Test fun `a stay sliced at the day's start states only its end`() {
        assertEquals(StayBounds.Until(at(7, 15)), stayBounds(midnight, at(7, 15), ROW))
    }

    @Test fun `a stay sliced at the day's end states only its start`() {
        assertEquals(StayBounds.From(at(21, 40)), stayBounds(at(21, 40), nextMidnight, ROW))
    }

    @Test fun `a stay sliced at both ends states no clock time`() {
        assertEquals(StayBounds.AllDay, stayBounds(midnight, nextMidnight, ROW))
    }

    @Test fun `an ongoing stay running since midnight states no clock time`() {
        assertEquals(StayBounds.AllDay, stayBounds(midnight, null, ROW))
    }

    @Test fun `bounds landing on one clock minute are stated once`() {
        val start = at(9, 11)
        assertEquals(StayBounds.At(start), stayBounds(start, start + 20_000, ROW))
    }

    @Test fun `bounds a few seconds apart across a minute are stated as two`() {
        val start = at(9, 11) + 55_000
        assertEquals(
            StayBounds.Between(start, start + 20_000),
            stayBounds(start, start + 20_000, ROW),
        )
    }

    /**
     * The same two instants, read on a clock whose midnight they miss. The ROW half is the case
     * above; what is new here is that nothing about the pair decides it — only the zone does. Read
     * on the wrong one the row would state a clock time where the timeline drew a seam, and print a
     * duration for a stay that runs on past it.
     */
    @Test fun `midnight is decided on the zone the row is read in`() {
        assertEquals(
            StayBounds.Between(midnight, at(7, 15)),
            stayBounds(midnight, at(7, 15), ELSEWHERE),
        )
    }

    /**
     * A duration beside a sliced bound would restate the clock time and understate the stay. Asked
     * of the cases themselves rather than of inputs that produce them — which the tests above
     * already pin — so the list is one row per case and a case added later has nowhere to hide.
     */
    @Test fun `only bounds the day did not slice carry a duration`() {
        val cases = listOf<Pair<StayBounds, Boolean>>(
            StayBounds.AllDay to false,
            StayBounds.Until(at(7, 15)) to false,
            StayBounds.From(at(21, 40)) to false,
            StayBounds.Since(at(9, 30)) to true,
            StayBounds.At(at(9, 30)) to true,
            StayBounds.Between(at(9, 30), at(11, 45)) to true,
        )
        assertEquals(cases.map { it.second }, cases.map { it.first.withDuration })
    }

    /**
     * The fixture's own precondition. A row zone whose midnight happened to coincide with UTC's
     * would let every case above pass through an implementation that ignored the zone and bucketed
     * epoch millis by the day — which is the mistake most worth catching, and the one a fixture
     * chosen for the wrong reason hides. Asserted rather than left to the choice of [ROW].
     */
    @Test fun `the row's midnight is not the epoch's`() {
        assertNotEquals("$ROW's midnight is UTC's, so the sliced cases prove nothing", 0L, midnight % DAY_MS)
    }

    private companion object {
        /** The zone a row is read in; +9 from UTC on [DAY], so its midnight is nobody else's. */
        val ROW: ZoneId = ZoneId.of("Asia/Tokyo")

        /** A second clock, nine hours from [ROW]'s, for the one case that asks which of them decides. */
        val ELSEWHERE: ZoneId = ZoneId.of("Europe/Lisbon")

        /** A day neither zone changes its clocks on, so a wall time means exactly one instant. */
        val DAY: LocalDate = LocalDate.of(2021, 3, 4)
        const val DAY_MS = 86_400_000L

        fun at(hour: Int, minute: Int): Long =
            DAY.atTime(LocalTime.of(hour, minute)).atZone(ROW).toInstant().toEpochMilli()

        val midnight: Long = DAY.atStartOfDay(ROW).toInstant().toEpochMilli()

        val nextMidnight: Long = DAY.plusDays(1).atStartOfDay(ROW).toInstant().toEpochMilli()
    }
}
