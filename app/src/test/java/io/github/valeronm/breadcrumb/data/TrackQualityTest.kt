package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertArrayEquals
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

    // --- boundingExtentMeters (the min-extent keep signal) --------------

    @Test fun `extent measures the diagonal of the lat-lon bounding box`() {
        // Capture what corners the distance fn is handed; that corner selection is the logic here.
        var corners: List<Double>? = null
        val d = DistanceFn { aLat, aLon, bLat, bLon -> corners = listOf(aLat, aLon, bLat, bLon); 250.0 }
        val pts = listOf(
            point(0, lat = 1.0, lon = 2.0),
            point(1, lat = 5.0, lon = -3.0),
            point(2, lat = 3.0, lon = 4.0),
        )
        assertEquals(250.0, TrackQuality.boundingExtentMeters(pts, d), 0.0)
        assertEquals(listOf(1.0, -3.0, 5.0, 4.0), corners) // (minLat,minLon) -> (maxLat,maxLon)
    }

    @Test fun `extent of fewer than two points is zero`() {
        assertEquals(0.0, TrackQuality.boundingExtentMeters(emptyList(), gap(999.0)), 0.0)
        assertEquals(0.0, TrackQuality.boundingExtentMeters(listOf(point(0)), gap(999.0)), 0.0)
    }

    // --- pointSpeedsKmh (map speed colouring) ---------------------------
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

    @Test fun `a non-positive time gap yields zero for the derived point`() {
        val pts = listOf(point(1_000), point(1_000))
        assertArrayEquals(floatArrayOf(0f, 0f), TrackQuality.pointSpeedsKmh(pts, gap(50.0)), 1e-3f)
    }

    @Test fun `the first point with no reported speed is zero`() {
        assertArrayEquals(floatArrayOf(0f), TrackQuality.pointSpeedsKmh(listOf(point(0)), gap(50.0)), 1e-3f)
    }

    @Test fun `an empty track yields an empty array`() {
        assertArrayEquals(floatArrayOf(), TrackQuality.pointSpeedsKmh(emptyList(), gap(1.0)), 1e-3f)
    }
}
