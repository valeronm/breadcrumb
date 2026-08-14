package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentBreaksTest {

    private fun point(
        id: Long,
        ignored: Boolean = false,
        segmentStart: Boolean = false,
    ) = TrackPoint(
        id = id,
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        timestamp = id * 1_000L,
        ignored = ignored,
        segmentStart = segmentStart,
    )

    private fun breaksAt(points: List<TrackPoint>): List<Long> =
        points.filter { it.segmentStart }.map { it.id }

    @Test
    fun `a break stranded on an ignored fix moves to the fix that resumes`() {
        val points = listOf(
            point(1),
            point(2),
            point(3, ignored = true, segmentStart = true),
            point(4),
        )

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(1L, 2L, 4L), good.map { it.id })
        assertEquals(listOf(4L), breaksAt(good))
    }

    @Test
    fun `a break on a good fix is left where it is, and the rows are not rewritten`() {
        val points = listOf(point(1), point(2, segmentStart = true), point(3))

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(2L), breaksAt(good))
        // Nothing moved, so the caller gets the stored rows themselves.
        points.forEachIndexed { i, p -> assertSame(p, good[i]) }
    }

    @Test
    fun `splitting cuts at each break, the break's own fix opening the next stretch`() {
        val points = listOf(point(1), point(2), point(3, segmentStart = true), point(4))

        val stretches = SegmentBreaks.split(points)

        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)), stretches.map { s -> s.map { it.id } })
    }

    @Test
    fun `a break on the first fix marks nothing to cut`() {
        val points = listOf(point(1, segmentStart = true), point(2))

        assertEquals(1, SegmentBreaks.split(points).size)
    }

    @Test
    fun `a run of ignored fixes carrying a break resumes once`() {
        // A rejected resume fix followed by more rejects — the boundary is still one boundary.
        val points = listOf(
            point(1),
            point(2, ignored = true, segmentStart = true),
            point(3, ignored = true),
            point(4, ignored = true, segmentStart = true),
            point(5),
            point(6),
        )

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(1L, 5L, 6L), good.map { it.id })
        assertEquals(listOf(5L), breaksAt(good))
    }

    @Test
    fun `a break with no good fix after it is dropped`() {
        // Nothing resumes, so there is no leg for the break to detach.
        val points = listOf(point(1), point(2), point(3, ignored = true, segmentStart = true))

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(1L, 2L), good.map { it.id })
        assertTrue(breaksAt(good).isEmpty())
    }

    @Test
    fun `the carry does not disturb a fix that already opens a segment`() {
        val points = listOf(
            point(1),
            point(2, ignored = true, segmentStart = true),
            point(3, segmentStart = true),
        )

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(3L), breaksAt(good))
        assertSame("already flagged, so nothing to rewrite", points[2], good[1])
    }

    @Test
    fun `a track whose path is empty carries nothing`() {
        val points = listOf(point(1, ignored = true, segmentStart = true))

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertTrue(good.isEmpty())
    }

    @Test
    fun `an ignored fix without a break leaves the path connected`() {
        val points = listOf(point(1), point(2, ignored = true), point(3))

        val good = SegmentBreaks.goodWithCarriedBreaks(points)

        assertEquals(listOf(1L, 3L), good.map { it.id })
        assertFalse(good.any { it.segmentStart })
    }
}
