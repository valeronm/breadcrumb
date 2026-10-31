package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphBreaksTest {

    private fun pt(t: Long, segmentStart: Boolean = false, altitude: Double? = null) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = altitude,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
        verticalAccuracy = 3f,
        segmentStart = segmentStart,
    )

    /** A fix at each of [times], with [meters] travelled into each one after the first. */
    private fun breaks(
        times: List<Long>,
        meters: List<Double>,
        segmentStarts: Set<Int> = emptySet(),
        altitudes: List<Double>? = null,
    ): List<Int> {
        val points = times.mapIndexed { i, t -> pt(t, segmentStart = i in segmentStarts, altitude = altitudes?.get(i)) }
        val metersBefore = DoubleArray(times.size) { if (it == 0) 0.0 else meters[it - 1] }
        return GraphBreaks.of(points, metersBefore)
            .withIndex().filter { it.value }.map { it.index }
    }

    /** [count] fixes a second apart from [start], each [step] metres on. */
    private fun everySecond(start: Long, count: Int, step: Double = 10.0) =
        List(count) { start + it * 1_000L } to List(count) { step }

    @Test
    fun `a stop the receiver reported nothing through breaks the line`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(50_000, 20)
        assertEquals(listOf(20), breaks(before + after, beforeM.drop(1) + 2.3 + afterM))
    }

    @Test
    fun `a pause that climbs is movement, not a stop`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(50_000, 20)
        val altitudes = List(20) { 0.0 } + List(20) { 9.0 }
        assertEquals(emptyList<Int>(), breaks(before + after, beforeM.drop(1) + 2.3 + afterM, altitudes = altitudes))
    }

    @Test
    fun `the same pause covering ground is a gap in reception, not a stop`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(50_000, 20)
        assertEquals(emptyList<Int>(), breaks(before + after, beforeM.drop(1) + 400.0 + afterM))
    }

    @Test
    fun `a pause shorter than a thinned stop stays joined`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(19_000 + StationaryRuns.MIN_SPAN_MS - 1_000, 20)
        assertEquals(emptyList<Int>(), breaks(before + after, beforeM.drop(1) + 0.5 + afterM))
    }

    @Test
    fun `the shortest stop StationaryRuns thins is a break`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(19_000 + StationaryRuns.MIN_SPAN_MS, 20)
        assertEquals(listOf(20), breaks(before + after, beforeM.drop(1) + StationaryRuns.RADIUS_M + afterM))
    }

    @Test
    fun `a stop recorded fix by fix stays joined`() {
        val (times, meters) = everySecond(0, 120, step = 0.2)
        assertEquals(emptyList<Int>(), breaks(times, meters.drop(1)))
    }

    @Test
    fun `a sparse track's stop is its usual spacing, not a gap`() {
        val times = List(10) { it * 30_000L }
        assertEquals(emptyList<Int>(), breaks(times, List(9) { 1.0 }))
    }

    @Test
    fun `a sparse track breaks only past several of its own spacings`() {
        val times = List(10) { it * 30_000L } + List(10) { 400_000L + it * 30_000L }
        val meters = List(9) { 300.0 } + 2.0 + List(10) { 300.0 }
        assertEquals(listOf(10), breaks(times, meters))
    }

    @Test
    fun `a segment start breaks whatever the distance, and does not count as spacing`() {
        val (before, beforeM) = everySecond(0, 20)
        val (after, afterM) = everySecond(600_000, 20)
        assertEquals(
            listOf(20),
            breaks(before + after, beforeM.drop(1) + 5_000.0 + afterM, segmentStarts = setOf(20)),
        )
    }

    @Test
    fun `a track too short to have spacing breaks nowhere`() {
        assertEquals(emptyList<Int>(), breaks(emptyList(), emptyList()))
        assertEquals(emptyList<Int>(), breaks(listOf(0L), emptyList()))
    }
}
