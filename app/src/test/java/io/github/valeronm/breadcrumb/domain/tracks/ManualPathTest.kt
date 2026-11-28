package io.github.valeronm.breadcrumb.domain.tracks

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.at
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.pin
import io.github.valeronm.breadcrumb.domain.places.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.timeline.StayDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPathTest {

    private val from = at(0.0)
    private val to = at(1_000.0)

    private fun fixes(shape: List<Coordinate>) =
        ManualPath.fixes(ManualPath.Fix(from, 1_000L), ManualPath.Fix(to, 5_000L), shape, flatDistance)

    @Test fun `an empty shape is the two ends at their times`() {
        val fixes = fixes(emptyList())
        assertEquals(listOf(from, to), fixes.map { it.at })
        assertEquals(listOf(1_000L, 5_000L), fixes.map { it.timestampMs })
    }

    @Test fun `the ends keep the typed coordinates and times around the shape`() {
        val shape = listOf(at(10.0), at(500.0), at(990.0))
        val fixes = fixes(shape)
        assertEquals(listOf(from) + shape + to, fixes.map { it.at })
        assertEquals(1_000L, fixes.first().timestampMs)
        assertEquals(5_000L, fixes.last().timestampMs)
    }

    @Test fun `a fix is timed by its distance along the line at one speed`() {
        // 1000 m over 4000 ms: 4 ms per meter.
        val fixes = fixes(listOf(at(250.0), at(750.0)))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L), fixes.map { it.timestampMs })
    }

    @Test fun `times only ever increase, a point repeating its predecessor dropped`() {
        val shape = listOf(from, at(400.0), at(400.0), to)
        val fixes = fixes(shape)
        assertEquals(listOf(from, at(400.0), to), fixes.map { it.at })
        assertTrue(fixes.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
    }

    @Test fun `a coupled end sits the duration after or before the edited one, on the minute`() {
        val tenPast = 10 * 60_000L
        assertEquals(tenPast + 25 * 60_000L, ManualPath.coupledEnd(tenPast, 25 * 60_000L, forward = true))
        assertEquals(tenPast - 25 * 60_000L, ManualPath.coupledEnd(tenPast, 25 * 60_000L, forward = false))
        // 24 min 40 s rounds up to 25, 24 min 20 s down to 24, the same either way round.
        assertEquals(tenPast + 25 * 60_000L, ManualPath.coupledEnd(tenPast, 24 * 60_000L + 40_000L, forward = true))
        assertEquals(tenPast - 25 * 60_000L, ManualPath.coupledEnd(tenPast, 24 * 60_000L + 40_000L, forward = false))
        assertEquals(tenPast + 24 * 60_000L, ManualPath.coupledEnd(tenPast, 24 * 60_000L + 20_000L, forward = true))
    }

    @Test fun `a line's length is its legs' lengths added up`() {
        assertEquals(1_000.0, ManualPath.lengthM(ManualPath.line(from, to, listOf(at(250.0), at(750.0))), flatDistance), 1e-6)
        assertEquals(0.0, ManualPath.lengthM(listOf(from), flatDistance), 0.0)
    }

    @Test fun `the shape comes back out of the line it was stored along`() {
        val shape = listOf(at(10.0), at(500.0), at(990.0))
        assertEquals(shape, ManualPath.shapeOf(ManualPath.line(from, to, shape)))
        assertEquals(emptyList<Coordinate>(), ManualPath.shapeOf(ManualPath.line(from, to, emptyList())))
    }

    @Test fun `the one timed end anchors, else the unnamed one, else neither`() {
        val o = ManualPath.End.ORIGIN
        val d = ManualPath.End.DESTINATION
        // (originTimed, destinationTimed, originNamed, destinationNamed) -> anchor
        val table = mapOf(
            listOf(true, false, false, false) to o,
            listOf(true, false, true, true) to o,
            listOf(false, true, false, false) to d,
            listOf(false, true, true, true) to d,
            listOf(true, true, true, false) to d,
            listOf(true, true, false, true) to o,
            listOf(true, true, true, true) to null,
            listOf(true, true, false, false) to null,
            listOf(false, false, true, false) to null,
            listOf(false, false, false, false) to null,
        )
        table.forEach { (case, expected) ->
            assertEquals(case.toString(), expected, ManualPath.anchor(case[0], case[1], case[2], case[3]))
        }
    }

    @Test fun `an end's open days run between its neighbours' bounds, on that end's clock`() {
        val utc = java.time.ZoneOffset.UTC
        val ahead = java.time.ZoneOffset.ofHours(10)
        val evening = java.time.LocalDate.of(2020, 1, 1).atTime(20, 0).toInstant(utc).toEpochMilli()
        val morning = java.time.LocalDate.of(2020, 1, 3).atTime(8, 0).toInstant(utc).toEpochMilli()
        val day = { d: Int -> java.time.LocalDate.of(2020, 1, d) }

        assertEquals(day(1)..day(3), ManualPath.openDays(evening, morning, utc))
        assertEquals(day(2)..day(3), ManualPath.openDays(evening, morning, ahead))
        assertEquals(day(1)..java.time.LocalDate.MAX, ManualPath.openDays(evening, null, utc))
        assertEquals(java.time.LocalDate.MIN..day(3), ManualPath.openDays(null, morning, utc))
        assertEquals(null, ManualPath.openDays(null, null, utc))
        assertEquals(day(1)..day(1), ManualPath.openDays(evening - 3_600_000L, evening, utc))
    }

    @Test fun `an end continues a track only at that track's own bound`() {
        val o = ManualPath.End.ORIGIN
        val d = ManualPath.End.DESTINATION
        val minute = 60_000L
        assertEquals(setOf(o, d), ManualPath.continuing(1_000L, 9_000L, 1_000L, 9_000L))
        assertEquals(setOf(o), ManualPath.continuing(1_000L, 9_000L, 1_000L, 9_000L + minute))
        assertEquals(setOf(d), ManualPath.continuing(1_000L + minute, 9_000L, 1_000L, 9_000L))
        assertEquals(emptySet<ManualPath.End>(), ManualPath.continuing(1_000L, 9_000L, null, null))
        assertEquals(emptySet<ManualPath.End>(), ManualPath.continuing(null, null, 1_000L, 9_000L))
    }

    @Test fun `a stay is previewed where the timeline will draw one, long enough to print`() {
        val hour = 3_600_000L
        val agreement = StayDeriver.Agreement(StayDeriver.Params(), flatDistance, emptyList())
        fun side(ms: Long, east: Double, placeId: Long? = null) = ManualPath.StaySide(ms, at(east), placeId)
        assertEquals(2 * hour, ManualPath.stayMs(side(hour, 0.0), side(3 * hour, 50.0), agreement))
        assertEquals(null, ManualPath.stayMs(side(hour, 0.0), side(3 * hour, 500.0), agreement))
        assertEquals(null, ManualPath.stayMs(side(hour, 0.0), side(hour + 30_000L, 0.0), agreement))
        assertEquals(null, ManualPath.stayMs(side(3 * hour, 0.0), side(hour, 0.0), agreement))
        assertEquals(null, ManualPath.stayMs(side(hour, 0.0, placeId = 1), side(3 * hour, 0.0, placeId = 2), agreement))
        val pin = StayDeriver.Agreement(StayDeriver.Params(), flatDistance, listOf(PlaceClusterer.Seed(at(250.0), 300.0)))
        assertEquals(2 * hour, ManualPath.stayMs(side(hour, 0.0), side(3 * hour, 500.0), pin))
    }
}
