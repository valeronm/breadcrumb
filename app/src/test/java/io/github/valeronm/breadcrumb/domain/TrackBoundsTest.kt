package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock a track keeps. Every case here is a shape the recorder produces: a transition that
 * opened the row before GPS had a fix, one that closed it long after the last fix arrived, fixes
 * rejected by the quality gate at either edge, and the overrun rule's own flags — which this must
 * agree with rather than re-decide.
 */
class TrackBoundsTest {

    private fun pt(t: Long, ignored: Boolean = false, reason: String? = null) = TrackPoint(
        id = 0,
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(0.0),
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
        ignored = ignored,
        ignoreReason = reason,
    )

    @Test
    fun `the clock is the first and last usable fix`() {
        val points = listOf(pt(10_000L), pt(20_000L), pt(30_000L))

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 90_000L)

        assertEquals(10_000L, bounds.startedAt)
        assertEquals(30_000L, bounds.endedAt)
    }

    @Test
    fun `the minutes after GPS went silent are not the track's`() {
        // The shape this rule exists for: the phone stops getting fixes at an arrival — a garage,
        // indoors — and Activity Recognition reports the stop minutes later, closing the row there.
        // Held, that span claims a whereabouts nothing measured, and holds it against the stay next
        // door. The overrun rule cannot reach it: with no fixes past the last one there is no dwell
        // to find and no speed collapse to place a cut in.
        val points = (0..5).map { pt(it * 10_000L) }

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 6 * 60_000L)

        assertEquals(50_000L, bounds.endedAt)
    }

    @Test
    fun `a track opened before its first fix starts at that fix`() {
        // The same failure mirrored: the row opens on a transition and GPS takes minutes to acquire.
        val points = (0..5).map { pt(5 * 60_000L + it * 10_000L) }

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 6 * 60_000L)

        assertEquals(5 * 60_000L, bounds.startedAt)
    }

    @Test
    fun `fixes the quality gate rejected do not hold either bound`() {
        // They are stored, and they are not the path — the same status the overrun's fixes carry,
        // which is what lets one rule answer for both.
        val points = listOf(
            pt(0L, ignored = true, reason = IgnoreReason.NO_GNSS.code),
            pt(10_000L),
            pt(20_000L),
            pt(30_000L, ignored = true, reason = IgnoreReason.NO_GNSS.code),
        )

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 30_000L)

        assertEquals(10_000L, bounds.startedAt)
        assertEquals(20_000L, bounds.endedAt)
    }

    @Test
    fun `the overrun's flagged tail leaves the clock on the boundary fix`() {
        val points = listOf(pt(0L), pt(10_000L)) +
            (2..5).map { pt(it * 10_000L, ignored = true, reason = IgnoreReason.EDGE_STAY.code) }

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 50_000L)

        assertEquals(10_000L, bounds.endedAt)
    }

    @Test
    fun `a track with nothing usable keeps the bounds it was given`() {
        // Nothing to trim to, and the span is all such a row has left to say. A recorded one is on
        // its way to being discarded; an imported or restored one may be all the file held.
        val allBad = (0..3).map { pt(it * 10_000L, ignored = true, reason = IgnoreReason.ACCURACY.code) }

        assertEquals(
            TrackBounds.Bounds(1_000L, 90_000L),
            TrackBounds.of(allBad, startedAt = 1_000L, endedAt = 90_000L),
        )
        assertEquals(
            TrackBounds.Bounds(1_000L, 90_000L),
            TrackBounds.of(emptyList(), startedAt = 1_000L, endedAt = 90_000L),
        )
    }

    @Test
    fun `a lone usable fix collapses the track onto it`() {
        val points = listOf(pt(0L, ignored = true, reason = IgnoreReason.ACCURACY.code), pt(25_000L))

        val bounds = TrackBounds.of(points, startedAt = 0L, endedAt = 60_000L)

        assertEquals(TrackBounds.Bounds(25_000L, 25_000L), bounds)
    }
}
