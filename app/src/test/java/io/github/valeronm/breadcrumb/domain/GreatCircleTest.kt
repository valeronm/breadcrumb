package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GreatCircleTest {

    private fun at(lat: Double, lon: Double) = StayDeriver.Endpoint(lat, lon)

    @Test fun `the arc keeps its ends exact and bows along the great circle`() {
        // A quarter of the equator: the arc stays on it, and its midpoint is the halfway meridian.
        val arc = GreatCircle.arc(at(0.0, 0.0), at(0.0, 90.0))

        assertEquals(0.0, arc.first().lat, 0.0)
        assertEquals(0.0, arc.first().lon, 0.0)
        assertEquals(0.0, arc.last().lat, 0.0)
        assertEquals(90.0, arc.last().lon, 0.0)
        assertTrue(arc.size > 10)
        arc.forEach { assertEquals(0.0, it.lat, 1e-9) }
        assertEquals(45.0, arc[arc.size / 2].lon, 2.0)
    }

    @Test fun `a long east-west arc bows toward the pole`() {
        // The reason this object exists: between two mid-latitude points a hemisphere apart, the
        // real path runs far poleward of the straight parallel a projected line would follow.
        val arc = GreatCircle.arc(at(45.0, -120.0), at(45.0, 30.0))

        val apex = arc.maxOf { it.lat }
        assertTrue("apex $apex should sit well north of the endpoints", apex > 60.0)
    }

    @Test fun `an antimeridian crossing unwraps rather than zigzagging`() {
        val arc = GreatCircle.arc(at(10.0, 170.0), at(10.0, -170.0))

        // Consecutive samples never jump the seam...
        arc.zipWithNext { a, b -> assertTrue(abs(b.lon - a.lon) < 180.0) }
        // ...so the eastbound walk ends past 180, on the unwrapped copy of the destination.
        assertEquals(190.0, arc.last().lon, 1e-9)
        assertEquals(10.0, arc.last().lat, 0.0)
    }

    @Test fun `a short hop is just its two ends`() {
        val arc = GreatCircle.arc(at(1.0, -2.0), at(1.001, -2.0))

        assertEquals(2, arc.size)
    }

    @Test fun `an antipodal pair draws no invented arc`() {
        // Every direction is a shortest path, so no single one may be claimed.
        val arc = GreatCircle.arc(at(10.0, 20.0), at(-10.0, -160.0))

        assertEquals(2, arc.size)
    }
}
