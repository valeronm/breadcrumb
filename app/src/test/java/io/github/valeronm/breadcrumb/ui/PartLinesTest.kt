package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartLinesTest {

    @Test fun `each part is one line, its fixes in order`() {
        val parts = listOf(part(0.001 to 0.002, 0.003 to 0.004), part(0.005 to 0.006, 0.007 to 0.008))

        val drawn = partLines(parts, RED, ActivityType.WALKING)

        assertEquals(listOf(listOf(0..1), listOf(0..1)), drawn.map { it.runs })
        assertEquals(listOf(Coordinate(0.001, 0.002), Coordinate(0.003, 0.004)), drawn.first().positions)
    }

    @Test fun `a part of one fix draws nothing`() {
        assertEquals(emptyList<IntRange>(), partLines(listOf(part(0.001 to 0.002)), RED, ActivityType.WALKING).single().runs)
    }

    @Test fun `every part is drawn in the color given`() {
        val parts = listOf(part(0.001 to 0.002, 0.003 to 0.004), part(0.005 to 0.006, 0.007 to 0.008))

        assertEquals(listOf(RED, RED), partLines(parts, RED, ActivityType.WALKING).map { it.color(1) })
    }

    @Test fun `a part's line keeps whether its motion was measured, and a flight's runs along great circles`() {
        val parts = listOf(part(0.001 to 0.002, 0.003 to 0.004), part(10.0 to 0.0, 50.0 to 40.0, unmeasured = true))

        val (walk, flight) = partLines(parts, RED, ActivityType.FLIGHT)

        assertFalse(walk.unmeasured)
        assertTrue(flight.unmeasured && flight.greatCircleLegs)
    }

    private fun part(vararg fixes: Pair<Double, Double>, unmeasured: Boolean = false) =
        TripPart(fixes.mapIndexed { i, (lat, lon) -> fix(i, lat, lon) }, unmeasured)

    private fun fix(i: Int, lat: Double, lon: Double) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = i * 1000L,
    )

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
