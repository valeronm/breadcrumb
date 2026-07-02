package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A fixed gap in metres, regardless of the coordinates passed. */
    private fun gap(meters: Double) = DistanceFn { _, _, _, _ -> meters }

    private fun point(
        timestamp: Long,
        accuracy: Float? = 5f,
        lat: Double = 0.0,
        lon: Double = 0.0,
    ) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = accuracy,
        speed = null,
        bearing = null,
        timestamp = timestamp,
    )

    // --- Accuracy gate (independent of distance) -------------------------

    @Test fun `a fix at or past the accuracy radius is bad`() {
        val bad = point(timestamp = 1_000, accuracy = 50f)
        assertTrue(TrackQuality.isBadFix(point(0), bad, WALKING, maxAccuracyM = 50f, distance = gap(1.0)))
    }

    @Test fun `a fix inside the accuracy radius is not gated on accuracy`() {
        val ok = point(timestamp = 1_000, accuracy = 49f)
        assertFalse(TrackQuality.isBadFix(point(0), ok, WALKING, maxAccuracyM = 50f, distance = gap(1.0)))
    }

    @Test fun `a fix with unknown accuracy skips the accuracy gate`() {
        val ok = point(timestamp = 5_000, accuracy = null)
        assertFalse(TrackQuality.isBadFix(point(0), ok, WALKING, maxAccuracyM = 50f, distance = gap(5.0)))
    }

    // --- First point of a track / segment -------------------------------

    @Test fun `the first point of a segment is never a bad fix`() {
        assertFalse(TrackQuality.isBadFix(null, point(0), WALKING, maxAccuracyM = 50f, distance = gap(9_999.0)))
    }

    // --- Implausible-speed teleport (per activity) ----------------------

    @Test fun `a walking teleport beyond plausible speed is bad`() {
        // 100 m in 1 s = 360 km/h, well past walking's 12 km/h ceiling.
        assertTrue(TrackQuality.isBadFix(point(0), point(1_000), WALKING, 50f, distance = gap(100.0)))
    }

    @Test fun `a normal walking step is kept`() {
        // 8 m in 5 s ≈ 5.8 km/h, under 12 km/h.
        assertFalse(TrackQuality.isBadFix(point(0), point(5_000), WALKING, 50f, distance = gap(8.0)))
    }

    @Test fun `the speed ceiling is per activity`() {
        // 300 m in 5 s = 216 km/h: implausible on foot, fine in a vehicle (ceiling 220).
        val prev = point(0)
        val next = point(5_000)
        assertTrue(TrackQuality.isBadFix(prev, next, WALKING, 50f, distance = gap(300.0)))
        assertFalse(TrackQuality.isBadFix(prev, next, DRIVING, 50f, distance = gap(300.0)))
    }

    // --- Zero / negative time gap ---------------------------------------

    @Test fun `a large jump over a non-positive time gap is bad`() {
        // Same timestamp, > MIN_JUMP_M apart → treated as an infinite-speed teleport.
        assertTrue(TrackQuality.isBadFix(point(1_000), point(1_000), DRIVING, 50f, distance = gap(11.0)))
    }

    @Test fun `a tiny jump over a non-positive time gap is not bad`() {
        // Same timestamp but within MIN_JUMP_M → jitter, not a teleport.
        assertFalse(TrackQuality.isBadFix(point(1_000), point(1_000), WALKING, 50f, distance = gap(9.0)))
    }

    // --- distanceMeters just delegates to the DistanceFn ----------------

    @Test fun `distanceMeters returns the supplied function's value`() {
        assertEquals(42.0, TrackQuality.distanceMeters(point(0), point(1_000), gap(42.0)), 0.0)
    }
}
