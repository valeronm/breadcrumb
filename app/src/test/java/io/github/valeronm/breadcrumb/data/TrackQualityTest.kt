package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DistanceFn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bad-fix rule, tested purely on how it *handles* a distance — not on how that distance is
 * computed. Each test injects a [DistanceFn] that returns exactly the gap it wants to assert about,
 * so the cases read as a distance/time → verdict table with no coordinates or geodesy involved.
 */
class TrackQualityTest {

    private val WALKING = ActivityType.WALKING
    private val DRIVING = ActivityType.DRIVING

    /** A fixed gap in meters, regardless of the coordinates passed. */
    private fun gap(meters: Double) = DistanceFn { _, _, _, _ -> meters }

    private fun point(
        timestamp: Long,
        accuracy: Float? = 5f,
        lat: Double = 0.0,
        lon: Double = 0.0,
        speed: Float? = null,
    ) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = accuracy,
        speed = speed,
        bearing = null,
        timestamp = timestamp,
    )

    // --- Accuracy gate (independent of distance) -------------------------

    @Test fun `a fix at or past the accuracy radius is bad`() {
        val bad = point(timestamp = 1_000, accuracy = 50f)
        assertNotNull(TrackQuality.badFixReason(point(0), bad, WALKING, maxAccuracyM = 50f, distance = gap(1.0)))
    }

    @Test fun `a fix inside the accuracy radius is not gated on accuracy`() {
        val ok = point(timestamp = 1_000, accuracy = 49f)
        assertNull(TrackQuality.badFixReason(point(0), ok, WALKING, maxAccuracyM = 50f, distance = gap(1.0)))
    }

    @Test fun `a fix with unknown accuracy skips the accuracy gate`() {
        val ok = point(timestamp = 5_000, accuracy = null)
        assertNull(TrackQuality.badFixReason(point(0), ok, WALKING, maxAccuracyM = 50f, distance = gap(5.0)))
    }

    // --- First point of a track / segment -------------------------------

    @Test fun `the first point of a segment is never a bad fix`() {
        assertNull(TrackQuality.badFixReason(null, point(0), WALKING, maxAccuracyM = 50f, distance = gap(9_999.0)))
    }

    // --- Implausible-speed teleport (per activity) ----------------------

    @Test fun `a walking teleport beyond plausible speed is bad`() {
        // 100 m in 1 s = 360 km/h, well past walking's 12 km/h ceiling.
        assertNotNull(TrackQuality.badFixReason(point(0), point(1_000), WALKING, 50f, distance = gap(100.0)))
    }

    @Test fun `a normal walking step is kept`() {
        // 8 m in 5 s ≈ 5.8 km/h, under 12 km/h.
        assertNull(TrackQuality.badFixReason(point(0), point(5_000), WALKING, 50f, distance = gap(8.0)))
    }

    @Test fun `the speed ceiling is per activity`() {
        // 300 m in 5 s = 216 km/h: implausible on foot, fine in a vehicle (ceiling 220).
        val prev = point(0)
        val next = point(5_000)
        assertNotNull(TrackQuality.badFixReason(prev, next, WALKING, 50f, distance = gap(300.0)))
        assertNull(TrackQuality.badFixReason(prev, next, DRIVING, 50f, distance = gap(300.0)))
    }

    // --- Zero / negative time gap ---------------------------------------

    @Test fun `a large jump over a non-positive time gap is bad`() {
        // Same timestamp, > MIN_JUMP_M apart → treated as an infinite-speed teleport.
        assertNotNull(TrackQuality.badFixReason(point(1_000), point(1_000), DRIVING, 50f, distance = gap(11.0)))
    }

    @Test fun `a tiny jump over a non-positive time gap is not bad`() {
        // Same timestamp but within MIN_JUMP_M → jitter, not a teleport.
        assertNull(TrackQuality.badFixReason(point(1_000), point(1_000), WALKING, 50f, distance = gap(9.0)))
    }

    // --- distanceMeters just delegates to the DistanceFn ----------------

    @Test fun `distanceMeters returns the supplied function's value`() {
        assertEquals(42.0, TrackQuality.distanceMeters(point(0), point(1_000), gap(42.0)), 0.0)
    }

    // --- pointSpeedsKmh (map speed coloring) ---------------------------
    // Derived-speed cases inject a fixed gap() so the derivation runs on the host without the
    // Android Location distance (there's no unitTests.returnDefaultValues, so the default would throw).

    @Test fun `a reported speed is converted m per s to km per h`() {
        // 2 m/s -> 7.2 km/h. First point uses its reported speed; no distance involved.
        assertArrayEquals(floatArrayOf(7.2f), TrackQuality.pointSpeedsKmh(listOf(point(0, speed = 2f))), 1e-3f)
    }

    @Test fun `a reported speed wins over the derivable one`() {
        // Second point reports 3 m/s (10.8 km/h); the gap is never consulted for it.
        val pts = listOf(point(0, speed = 1f), point(5_000, speed = 3f))
        assertArrayEquals(floatArrayOf(3.6f, 10.8f), TrackQuality.pointSpeedsKmh(pts, gap(999.0)), 1e-3f)
    }

    @Test fun `a missing or negative reported speed falls back to the derived one`() {
        // 10 m over 5 s = 2 m/s = 7.2 km/h, from the injected gap.
        val absent = listOf(point(0), point(5_000))
        assertArrayEquals(floatArrayOf(0f, 7.2f), TrackQuality.pointSpeedsKmh(absent, gap(10.0)), 1e-3f)
        val negative = listOf(point(0), point(5_000, speed = -1f))
        assertArrayEquals(floatArrayOf(0f, 7.2f), TrackQuality.pointSpeedsKmh(negative, gap(10.0)), 1e-3f)
    }

    @Test fun `a non-positive time gap carries the previous speed instead of reporting a stop`() {
        // 50 m over 5 s = 36 km/h, then a fix at the same instant: unmeasurable, not stopped.
        val pts = listOf(point(0), point(5_000), point(5_000), point(10_000))
        assertArrayEquals(
            floatArrayOf(0f, 36f, 36f, 36f),
            TrackQuality.pointSpeedsKmh(pts, gap(50.0)),
            1e-3f,
        )
    }

    @Test fun `a zero gap on the second point has nothing to carry`() {
        val pts = listOf(point(1_000), point(1_000))
        assertArrayEquals(floatArrayOf(0f, 0f), TrackQuality.pointSpeedsKmh(pts, gap(50.0)), 1e-3f)
    }

    @Test fun `the first point with no reported speed is zero`() {
        assertArrayEquals(floatArrayOf(0f), TrackQuality.pointSpeedsKmh(listOf(point(0)), gap(50.0)), 1e-3f)
    }

    @Test fun `an empty track yields an empty array`() {
        assertArrayEquals(floatArrayOf(), TrackQuality.pointSpeedsKmh(emptyList(), gap(1.0)), 1e-3f)
    }

    // --- Stray leading point (drive-start cold-start artifact in imports) -----
    //
    // The rule is relative, not an absolute ceiling: the first seam is a stray when it's much
    // faster than the real pace that follows (a car pulling out does a few km/h, not 180 in the
    // opening second). Per-seam distance is keyed by the from-point's latitude, and every point is
    // one second apart unless a test says otherwise, so seam speed (km/h) = gap-meters × 3.6.

    /** Per-seam gaps keyed by the from-point's latitude: lat n -> gaps[n] meters to the next. */
    private fun seamGaps(vararg gaps: Double) = DistanceFn { la1, _, la2, _ ->
        gaps[minOf(la1, la2).toInt()]
    }

    /** Five points, lat 0..4, one second apart — four seams driven by [seamGaps]. */
    private fun fivePoints() = List(5) { i -> point(timestamp = i * 1_000L, lat = i.toDouble()) }

    @Test fun `a stray first point is detected when the rest of the track is a slow start`() {
        // First seam 180 km/h; the car then crawls out at ~14 km/h — an impossible launch.
        val pts = fivePoints()
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 4.0, 4.0, 4.0)))
    }

    @Test fun `a fast start that stays fast is not a stray`() {
        // Already on the motorway: every seam ~100 km/h, first seam no outlier.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(28.0, 28.0, 28.0, 28.0)))
    }

    @Test fun `a genuine acceleration from a stop is not a stray`() {
        // First seam slow (~4 km/h), speeding up after — the opposite shape from a stray.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(1.0, 17.0, 17.0, 17.0)))
    }

    @Test fun `a sub-ceiling stray is still caught`() {
        // 100 km/h first seam is under the driving jump ceiling, yet impossible one second after
        // setting off when the real pace is ~14 km/h. The absolute rule missed exactly these.
        val pts = fivePoints()
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(28.0, 4.0, 4.0, 4.0)))
    }

    @Test fun `a modest first seam below the floor is not a stray`() {
        // ~29 km/h first seam: faster than the follow pace, but a plausible launch — not flagged.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(8.0, 0.5, 0.5, 0.5)))
    }

    @Test fun `a first seam not far enough above the follow pace is not a stray`() {
        // First 180, follow 72 km/h — fast but only 2.5x, within a plausible ramp-up.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 20.0, 20.0, 20.0)))
    }

    @Test fun `a degenerate second seam is skipped when judging the follow pace`() {
        // Points 1 and 2 share a timestamp (the real data's shape); the follow pace comes from
        // the later valid seams, and the stray is still caught.
        val pts = listOf(
            point(timestamp = 0, lat = 0.0),
            point(timestamp = 1_000, lat = 1.0),
            point(timestamp = 1_000, lat = 2.0),
            point(timestamp = 2_000, lat = 3.0),
            point(timestamp = 3_000, lat = 4.0),
        )
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 5.0, 4.0, 4.0)))
    }

    @Test fun `tracks with fewer than three points are never flagged`() {
        assertFalse(TrackQuality.leadingPointIsJump(fivePoints().take(2), seamGaps(50.0)))
        assertFalse(TrackQuality.leadingPointIsJump(emptyList(), seamGaps()))
    }
}
