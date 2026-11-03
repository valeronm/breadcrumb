package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class CasedJourneyLinesTest {

    private fun line(id: Long, startedAt: Long, type: String, vararg segments: DoubleArray) =
        JourneyLine(id, type, greatCircle = false, segments = segments.toList(), unmeasured = false, startedAt = startedAt)

    @Test fun `tracks are drawn oldest first, whatever order they arrive in`() {
        val later = line(2, startedAt = 2_000, "WALKING", doubleArrayOf(1.0, 0.0, 1.0, 1.0))
        val earlier = line(1, startedAt = 1_000, "WALKING", doubleArrayOf(0.0, 0.0, 0.0, 1.0))

        val drawn = casedJourneyLines(listOf(later, earlier), { RED }, HashMap())

        assertEquals(listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0)), drawn.map { it.positions.first() })
    }

    @Test fun `a track's watched stretches are drawn apart, no leg across the gap`() {
        val track = line(1, startedAt = 0, "WALKING", doubleArrayOf(0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 5.0, 0.0, 6.0, 0.0, 7.0))

        assertEquals(listOf(0..1, 2..4), casedJourneyLines(listOf(track), { RED }, HashMap()).single().runs)
    }

    @Test fun `each track is drawn in its activity's color`() {
        val walk = line(1, startedAt = 0, "WALKING", doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val drive = line(2, startedAt = 1, "DRIVING", doubleArrayOf(0.0, 2.0, 0.0, 3.0))

        val strokes = casedStrokes(casedJourneyLines(listOf(walk, drive), mapOf("WALKING" to RED, "DRIVING" to BLUE)::getValue, HashMap()), zoom = 16)

        assertEquals(setOf(0 to RED, 1 to BLUE), strokes.filter { it.pen == Pen.COLOR }.map { it.line to it.argb }.toSet())
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
