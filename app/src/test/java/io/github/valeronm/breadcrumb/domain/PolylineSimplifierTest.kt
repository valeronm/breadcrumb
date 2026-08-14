package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinates are flat lon/lat pairs at the neutral origin; the tolerance in these cases is
 * 0.001° ≈ 100 m under the suite's own scale, chosen large so fixtures stay legible.
 */
class PolylineSimplifierTest {

    private val tol = 0.001

    private fun line(vararg lonLat: Double) = doubleArrayOf(*lonLat)

    @Test fun `two points or fewer come back verbatim`() {
        val two = line(1.0, 1.0, 1.001, 1.0)
        assertArrayEquals(two, PolylineSimplifier.simplify(two, tol), 0.0)
        val one = line(1.0, 1.0)
        assertArrayEquals(one, PolylineSimplifier.simplify(one, tol), 0.0)
    }

    @Test fun `a straight run collapses to its endpoints`() {
        val straight = DoubleArray(20) { i -> if (i % 2 == 0) 1.0 + (i / 2) * 0.01 else 1.0 }

        val out = PolylineSimplifier.simplify(straight, tol)

        assertArrayEquals(line(1.0, 1.0, 1.09, 1.0), out, 0.0)
    }

    @Test fun `a spike above tolerance survives`() {
        val spiked = line(1.0, 1.0, 1.01, 1.005, 1.02, 1.0)

        val out = PolylineSimplifier.simplify(spiked, tol)

        assertArrayEquals(spiked, out, 0.0)
    }

    @Test fun `jitter below tolerance is dropped`() {
        val jittery = line(1.0, 1.0, 1.01, 1.0002, 1.02, 0.9998, 1.03, 1.0)

        val out = PolylineSimplifier.simplify(jittery, tol)

        assertArrayEquals(line(1.0, 1.0, 1.03, 1.0), out, 0.0)
    }

    @Test fun `the true endpoint is kept even when the radial pass lands short of it`() {
        // The last point sits within tolerance of the one before it, so the radial pass alone
        // would end the line early; the endpoint must survive regardless.
        val line = line(1.0, 1.0, 1.01, 1.01, 1.0201, 1.0)

        val out = PolylineSimplifier.simplify(line, 0.02)

        assertEquals(1.0201, out[out.size - 2], 0.0)
        assertEquals(1.0, out[out.size - 1], 0.0)
    }

    @Test fun `every surviving point is one of the originals`() {
        val zigzag = DoubleArray(40) { i ->
            if (i % 2 == 0) 1.0 + (i / 2) * 0.002 else 1.0 + if ((i / 2) % 2 == 0) 0.0 else 0.0015
        }

        val out = PolylineSimplifier.simplify(zigzag, tol)

        assertTrue(out.size < zigzag.size)
        val originals = zigzag.toList().chunked(2).toSet()
        for (pair in out.toList().chunked(2)) {
            assertTrue("$pair not in the input", pair in originals)
        }
    }
}
