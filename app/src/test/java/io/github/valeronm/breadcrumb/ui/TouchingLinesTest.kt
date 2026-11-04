package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchingLinesTest {

    @Test fun `each watched stretch of a touching track is a run of its line`() {
        val track = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004), doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        val drawn = touchingLines(listOf(track), dark = false).single()

        assertEquals(listOf(0..1, 2..3), drawn.runs)
        assertEquals(listOf(Coordinate(0.002, 0.001), Coordinate(0.004, 0.003), Coordinate(0.006, 0.005), Coordinate(0.008, 0.007)), drawn.positions)
    }

    @Test fun `a stretch of one fix is no run`() {
        val track = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002), doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        assertEquals(listOf(1..2), touchingLines(listOf(track), dark = false).single().runs)
    }

    @Test fun `each track is drawn in its activity's color`() {
        val walk = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004)))
        val drive = touching(ActivityType.DRIVING, line(doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        val colors = touchingLines(listOf(walk, drive), dark = true).map { it.color(1) }

        assertEquals(listOf(ActivityType.WALKING, ActivityType.DRIVING).map { mapActivityArgb(it, dark = true) }, colors)
    }

    @Test fun `a track's line keeps whether its motion was measured and whether it runs along great circles`() {
        val measured = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004)))
        val entered = touching(ActivityType.FLIGHT, line(doubleArrayOf(0.0, 10.0, 40.0, 50.0), unmeasured = true, greatCircle = true))

        val (walk, flight) = touchingLines(listOf(measured, entered), dark = false)

        assertFalse(walk.unmeasured || walk.greatCircleLegs)
        assertTrue(flight.unmeasured && flight.greatCircleLegs)
    }

    private fun line(vararg stretches: DoubleArray, unmeasured: Boolean = false, greatCircle: Boolean = false) =
        JourneyLine(1, "", greatCircle, segments = stretches.toList(), unmeasured = unmeasured, startedAt = 0)

    private fun touching(activity: ActivityType, vararg lines: JourneyLine) =
        TouchingTrack(lines.toList(), activity.name, Coordinate(0.0, 0.0), boundMs = 0, placeId = null)
}
