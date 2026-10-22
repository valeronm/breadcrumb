package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smoother is fed the series the graph plots, so the fixtures are timestamps and values: the
 * coordinates never matter to it and sit at the domain tests' neutral origin.
 */
class MetricSmootherTest {

    private fun pt(t: Long) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
    )

    private fun smooth(points: List<TrackPoint>, values: List<Float?>, breaks: Set<Int> = emptySet()) =
        MetricSmoother.timeAveraged(
            points,
            values,
            BooleanArray(points.size) { it in breaks },
        )

    /** Points every [everyMs] from 0, one per value. */
    private fun series(values: List<Float?>, everyMs: Long = 1_000L): List<TrackPoint> =
        values.indices.map { pt(it * everyMs) }

    @Test
    fun `a steady pace comes back unchanged`() {
        val values = List(120) { 40f }
        smooth(series(values), values).forEach { assertEquals(40f, it!!, 0.001f) }
    }

    @Test
    fun `a spike is spread over its neighbors`() {
        val values = List(121) { if (it == 60) 90f else 50f }
        val out = smooth(series(values), values)
        assertTrue("${out[60]}", out[60]!! in 50.5f..55f)
    }

    @Test
    fun `the weight falls off with distance in time, symmetrically`() {
        val values = List(181) { if (it == 90) 100f else 0f }
        val out = smooth(series(values), values)
        assertEquals(out[80]!!, out[100]!!, 0.0001f)
        assertTrue(out[90]!! > out[80]!!)
        assertTrue(out[80]!! > out[70]!!)
    }

    @Test
    fun `the weighting is by real time, so the same fixes smooth differently at different cadences`() {
        val values = List(11) { if (it == 5) 90f else 50f }
        val dense = smooth(series(values, everyMs = 1_000L), values)[5]!!
        val sparse = smooth(series(values, everyMs = 120_000L), values)[5]!!
        assertTrue("a fix a second apart is averaged with its neighbors: $dense", dense < 60f)
        assertEquals("fixes two minutes apart are out of reach, so the spike stands", 90f, sparse, 0.01f)
    }

    @Test
    fun `a break is not averaged across`() {
        val values = List(30) { 10f } + List(30) { 90f }
        val out = smooth(series(values), values, breaks = setOf(30))
        assertEquals(10f, out[29]!!, 0.01f)
        assertEquals(90f, out[30]!!, 0.01f)
    }

    @Test
    fun `the last fix is averaged over the fixes before it`() {
        val values = List(60) { 50f } + 5f
        val out = smooth(series(values), values)
        assertTrue("the slow last fix is pulled toward the pace before it: ${out[60]}", out[60]!! > 40f)
    }

    @Test
    fun `a fix without the metric stays without it, and is not averaged in`() {
        val values = listOf(10f, null, 30f)
        val out = smooth(series(values), values)
        assertNull(out[1])
        assertEquals("a null neighbour is absent, not a zero", 10f, out[0]!!, 0.01f)
        assertEquals(30f, out[2]!!, 0.01f)
    }

    @Test
    fun `an empty series is answered in kind`() {
        assertEquals(emptyList<Float?>(), smooth(emptyList(), emptyList()))
    }
}
