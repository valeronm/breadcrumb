package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyLineTest {

    @Test fun `a line's corners bound every stretch's fixes`() {
        val line = line(doubleArrayOf(0.002, 0.001, -0.003, 0.004), doubleArrayOf(0.005, -0.002))

        assertEquals(Coordinate(-0.002, -0.003) to Coordinate(0.004, 0.005), line.corners)
    }

    @Test fun `a line's stretches hold its fixes in order, one list per watched stretch`() {
        val line = line(doubleArrayOf(0.002, 0.001, -0.003, 0.004), doubleArrayOf(0.005, -0.002))

        assertEquals(
            listOf(listOf(Coordinate(0.001, 0.002), Coordinate(0.004, -0.003)), listOf(Coordinate(-0.002, 0.005))),
            line.stretches(),
        )
    }

    @Test fun `a line with no fix has no corners`() {
        assertNull(line().corners)
    }

    private fun line(vararg stretches: DoubleArray) =
        JourneyLine(1, "WALKING", greatCircle = false, segments = stretches.toList(), unmeasured = false, startedAt = 0)
}
