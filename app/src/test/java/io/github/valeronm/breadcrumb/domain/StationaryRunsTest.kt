package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryRunsTest {

    private fun pt(meters: Double, t: Long, ignored: Boolean = false, segmentStart: Boolean = false) = TrackPoint(
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(meters),
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
        ignored = ignored,
        ignoreReason = if (ignored) IgnoreReason.ACCURACY.code else null,
        segmentStart = segmentStart,
    )

    /** A fix a second, [seconds] long from [fromS], each [step] metres on from [fromM]. */
    private fun drive(fromM: Double, fromS: Int, seconds: Int, step: Double = 10.0) =
        (0 until seconds).map { pt(fromM + it * step, (fromS + it) * 1_000L) }

    /** A fix a second at [atM], jittering two metres either way. */
    private fun stop(atM: Double, fromS: Int, seconds: Int) =
        (0 until seconds).map { pt(atM + if (it % 2 == 0) 2.0 else -2.0, (fromS + it) * 1_000L) }

    private fun atAltitude(points: List<TrackPoint>, verticalAccuracy: Float?, altitudeAt: (index: Int) -> Double) =
        points.mapIndexed { i, p -> p.copy(altitude = altitudeAt(i), verticalAccuracy = verticalAccuracy) }

    private fun inner(points: List<TrackPoint>) = StationaryRuns.inner(points, flatDistance)

    @Test
    fun `a red light keeps the fixes that bound it and drops the ones inside`() {
        val points = drive(0.0, 0, 20) + stop(195.0, 20, 60) + drive(205.0, 80, 20)
        assertEquals((20..78).toSet(), inner(points))
    }

    @Test
    fun `a stop shorter than the minimum is left whole`() {
        val points = drive(0.0, 0, 20) + stop(195.0, 20, 20) + drive(205.0, 40, 20)
        assertEquals(emptySet<Int>(), inner(points))
    }

    @Test
    fun `a crawl is movement, not a stop`() {
        assertEquals(emptySet<Int>(), inner(drive(0.0, 0, 120, step = 1.0)))
    }

    @Test
    fun `a sparse track thins a stop between its own fixes`() {
        val points = (0..4).map { pt(if (it % 2 == 0) 1.0 else -1.0, it * 30_000L) }
        assertEquals(setOf(1, 2, 3), inner(points))
    }

    @Test
    fun `a segment break ends a stop, even on an ignored fix`() {
        val before = stop(0.0, 0, 40)
        val after = stop(0.0, 41, 40)
        val points = before + pt(0.0, 40_500L, ignored = true, segmentStart = true) + after
        val thinned = inner(points)
        assertTrue(39 !in thinned)
        assertTrue(41 !in thinned)
        assertEquals((1..38).toSet() + (42..79).toSet(), thinned)
    }

    @Test
    fun `an ignored fix inside a stop neither ends it nor joins it`() {
        val points = stop(0.0, 0, 30) + pt(500.0, 30_500L, ignored = true) + stop(0.0, 31, 30)
        assertEquals((1..29).toSet() + (31..59).toSet(), inner(points))
    }

    @Test
    fun `a track too short to hold a stop thins nothing`() {
        assertEquals(emptySet<Int>(), inner(emptyList()))
        assertEquals(emptySet<Int>(), inner(stop(0.0, 0, 2)))
    }

    @Test
    fun `a stair climb inside the radius is movement, not a stop`() {
        val climb = atAltitude(stop(0.0, 0, 40), verticalAccuracy = 3f) { it * 0.25 }
        assertEquals(emptySet<Int>(), inner(climb))
    }

    @Test
    fun `a standstill's altitude is judged against the looser fix's vertical accuracy`() {
        val wobble = atAltitude(stop(0.0, 0, 60), verticalAccuracy = 3f) { if (it % 2 == 0) 0.0 else 5.5 }
        val points = listOf(wobble.first().copy(verticalAccuracy = 2f)) + wobble.drop(1)
        assertEquals((1..58).toSet(), inner(points))
    }

    @Test
    fun `altitude wandering under a tight vertical accuracy stays a stop`() {
        val wobble = atAltitude(stop(0.0, 0, 60), verticalAccuracy = 1.5f) { if (it % 2 == 0) 0.0 else 4.5 }
        assertEquals((1..58).toSet(), inner(wobble))
    }

    @Test
    fun `a climb whose fixes carry no vertical accuracy is left to the horizontal rule`() {
        val climb = atAltitude(stop(0.0, 0, 40), verticalAccuracy = null) { it * 0.25 }
        assertEquals((1..38).toSet(), inner(climb))
    }
}
