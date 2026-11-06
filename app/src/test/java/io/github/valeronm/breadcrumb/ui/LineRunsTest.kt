package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.PathSmoother
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawn line is cut for two different reasons, and the difference is the whole point: a color
 * change hands its boundary fix to both sides so the line stays continuous, while a segment break
 * keeps it for the resuming side alone, leaving the unwatched leg undrawn.
 */
class LineRunsTest {

    private fun points(count: Int, breakAt: Set<Int> = emptySet()) = (0 until count).map { i ->
        TrackPoint(
            trackId = 1,
            latitude = 1.0 + i * 0.0001,
            longitude = 0.0,
            altitude = null,
            accuracy = null,
            speed = null,
            bearing = null,
            timestamp = i * 1000L,
            segmentStart = i in breakAt,
        )
    }

    private fun recordedLine(fixes: List<TrackPoint>, colors: IntArray, unmeasured: Boolean = false) =
        CasedLine(fixes, PathSmoother.lonLat(fixes, null, unmeasured, smoothed = false), colors, unmeasured)

    /** One color throughout; index 0 is never read, so its value only has to exist. */
    private fun oneColor(count: Int) = IntArray(count) { RED }

    @Test fun `an unbroken track of one color is a single run`() {
        assertEquals(listOf(0..4), recordedLine(points(5), oneColor(5)).runs)
    }

    @Test fun `a color change shares its boundary fix, so the two runs meet on it`() {
        // The leg arriving at 3 is the first blue one, so fix 2 ends the red run and starts the blue.
        val colors = intArrayOf(RED, RED, RED, BLUE, BLUE)

        assertEquals(listOf(0..2, 2..4), recordedLine(points(5), colors).runs)
    }

    @Test fun `a segment break keeps its fix for the resuming run, leaving the leg undrawn`() {
        // Fix 3 resumed recording: 2..3 is ground nobody watched, and no run spans it.
        assertEquals(listOf(0..2, 3..4), recordedLine(points(5, breakAt = setOf(3)), oneColor(5)).runs)
    }

    @Test fun `a break at the first fix after the start leaves nothing to draw before it`() {
        assertEquals(listOf(1..3), recordedLine(points(4, breakAt = setOf(1)), oneColor(4)).runs)
    }

    @Test fun `back-to-back breaks leave the fix between them undrawn`() {
        // It is the whole of its own segment, and one fix is no line.
        assertEquals(listOf(0..1, 3..4), recordedLine(points(5, breakAt = setOf(2, 3)), oneColor(5)).runs)
    }

    @Test fun `a break lands on a color change without either cut swallowing the other`() {
        // The break wins the boundary fix: were the color cut to share it, the line would be drawn
        // across the gap in the new color.
        val colors = intArrayOf(RED, RED, RED, BLUE, BLUE)

        assertEquals(listOf(0..2, 3..4), recordedLine(points(5, breakAt = setOf(3)), colors).runs)
    }

    @Test fun `a break on the last fix draws nothing extra`() {
        assertEquals(listOf(0..2), recordedLine(points(4, breakAt = setOf(3)), oneColor(4)).runs)
    }

    @Test fun `a break on the very first fix is not a cut - there is nothing before it`() {
        assertEquals(listOf(0..3), recordedLine(points(4, breakAt = setOf(0)), oneColor(4)).runs)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
