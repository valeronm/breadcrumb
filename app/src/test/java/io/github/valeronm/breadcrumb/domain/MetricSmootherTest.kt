package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The smoother is fed the series the graph plots, so the fixtures are timestamps and values: the
 * coordinates never matter to it and sit at the domain tests' neutral origin. Runs at the shipping
 * [MetricSmoother.WINDOW_MS] except where the window's width is the point of the case.
 */
class MetricSmootherTest {

    private fun pt(t: Long, segmentStart: Boolean = false) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
        segmentStart = segmentStart,
    )

    /** Points every [everyMs] from 0, one per value. */
    private fun series(vararg values: Float?, everyMs: Long = 5_000L): Pair<List<TrackPoint>, List<Float?>> =
        values.indices.map { pt(it * everyMs) } to values.toList()

    @Test
    fun `a spike is pulled toward the pace around it`() {
        val (points, values) = series(50f, 50f, 90f, 50f, 50f)
        val out = MetricSmoother.timeAveraged(points, values)
        // The window reaches 7.5 s each way, so at 5 s spacing it holds one neighbour either
        // side: (50+90+50)/3.
        // Down from the 90 it was recorded at, and not flattened into the 50 around it.
        assertEquals(63.33f, out[2]!!, 0.01f)
    }

    @Test
    fun `a steady pace comes back unchanged`() {
        val (points, values) = series(40f, 40f, 40f, 40f, 40f)
        MetricSmoother.timeAveraged(points, values).forEach { assertEquals(40f, it!!, 0.001f) }
    }

    @Test
    fun `the window is real time, so the same fixes smooth differently at different cadences`() {
        val (densePoints, denseValues) = series(50f, 50f, 90f, 50f, 50f, everyMs = 1_000L)
        val (sparsePoints, sparseValues) = series(50f, 50f, 90f, 50f, 50f, everyMs = 60_000L)
        assertEquals(
            "a fix a second apart has every neighbour in the window",
            58f,
            MetricSmoother.timeAveraged(densePoints, denseValues)[2]!!,
            0.01f,
        )
        assertEquals(
            "a fix a minute apart has none, so the spike stands",
            90f,
            MetricSmoother.timeAveraged(sparsePoints, sparseValues)[2]!!,
            0.01f,
        )
    }

    @Test
    fun `a gap in the recording is not averaged across`() {
        val points = listOf(pt(0), pt(5_000), pt(10_000, segmentStart = true), pt(15_000))
        val values = listOf(10f, 10f, 90f, 90f)
        val out = MetricSmoother.timeAveraged(points, values)
        assertEquals("the pace before the gap can't be pulled up by what follows it", 10f, out[1]!!, 0.01f)
        assertEquals(90f, out[2]!!, 0.01f)
    }

    @Test
    fun `a fix without the metric stays without it, and is not averaged in`() {
        val (points, values) = series(10f, null, 30f)
        val out = MetricSmoother.timeAveraged(points, values)
        assertNull(out[1])
        assertEquals("a null neighbour is absent, not a zero", 10f, out[0]!!, 0.01f)
        assertEquals(30f, out[2]!!, 0.01f)
    }

    @Test
    fun `an empty series is answered in kind`() {
        assertEquals(emptyList<Float?>(), MetricSmoother.timeAveraged(emptyList(), emptyList()))
    }
}
