package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one point walk: distance, counts, endpoints, extent. A flat-earth [DistanceFn] (1 degree =
 * 1 metre in each axis, summed) keeps the expected distances readable — the geodesy itself belongs
 * to [AndroidDistance], not here.
 */
class TrackStatsTest {

    private val flat = DistanceFn { aLat, aLon, bLat, bLon ->
        Math.abs(bLat - aLat) + Math.abs(bLon - aLon)
    }

    private fun point(
        lat: Double = 0.0,
        lon: Double = 0.0,
        ignored: Boolean = false,
        segmentStart: Boolean = false,
        timestamp: Long = 0,
    ) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        timestamp = timestamp,
        ignored = ignored,
        segmentStart = segmentStart,
    )

    @Test fun `an empty track has no endpoints and no distance`() {
        val stats = TrackStats.of(emptyList(), flat)

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0, stats.pointCount)
        assertEquals(0, stats.ignoredCount)
        assertNull(stats.startLat)
        assertNull(stats.endLat)
        assertEquals(0.0, stats.extentMeters, 0.0)
    }

    @Test fun `a single point is both endpoints and has no distance`() {
        val stats = TrackStats.of(listOf(point(lat = 3.0, lon = 4.0)), flat)

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(1, stats.pointCount)
        assertEquals(3.0, stats.startLat!!, 0.0)
        assertEquals(4.0, stats.startLon!!, 0.0)
        assertEquals(3.0, stats.endLat!!, 0.0)
        assertEquals(4.0, stats.endLon!!, 0.0)
        // Extent needs two points — a single fix has no spread.
        assertEquals(0.0, stats.extentMeters, 0.0)
    }

    @Test fun `distance sums consecutive good points`() {
        val stats = TrackStats.of(
            listOf(point(lat = 0.0), point(lat = 1.0), point(lat = 3.0)),
            flat,
        )

        assertEquals(3.0, stats.distanceMeters, 0.0)
        assertEquals(3, stats.pointCount)
    }

    @Test fun `ignored fixes are counted but contribute no distance and never become endpoints`() {
        val stats = TrackStats.of(
            listOf(
                point(lat = 100.0, ignored = true), // a leading stray
                point(lat = 0.0),
                point(lat = 1.0, ignored = true), // a bad fix mid-track
                point(lat = 2.0),
                point(lat = 100.0, ignored = true), // a trailing stray
            ),
            flat,
        )

        // 0 -> 2 directly: the ignored fix between them is skipped, not used as a waypoint.
        assertEquals(2.0, stats.distanceMeters, 0.0)
        assertEquals(2, stats.pointCount)
        assertEquals(3, stats.ignoredCount)
        assertEquals(0.0, stats.startLat!!, 0.0)
        assertEquals(2.0, stats.endLat!!, 0.0)
        // The strays are outside the good points' box, so they must not widen the extent either.
        assertEquals(2.0, stats.extentMeters, 0.0)
    }

    @Test fun `a segment start detaches from the previous point so the paused gap is not counted`() {
        val stats = TrackStats.of(
            listOf(
                point(lat = 0.0),
                point(lat = 1.0),
                // Recording resumed a kilometre away after an auto-pause.
                point(lat = 1000.0, segmentStart = true),
                point(lat = 1002.0),
            ),
            flat,
        )

        assertEquals(1.0 + 2.0, stats.distanceMeters, 0.0)
        assertEquals(4, stats.pointCount)
        // The gap is not travelled, but it *is* spanned — extent covers the whole track.
        assertEquals(1002.0, stats.extentMeters, 0.0)
    }

    @Test fun `bounds cover every good point, not just the endpoints`() {
        val stats = TrackStats.of(
            listOf(
                point(lat = 0.0, lon = 0.0),
                point(lat = 5.0, lon = -3.0), // the excursion the endpoints don't see
                point(lat = 1.0, lon = 1.0),
            ),
            flat,
        )

        // Extent spans the whole box, not just the endpoints: 5 of latitude, 4 of longitude.
        assertEquals(5.0 + 4.0, stats.extentMeters, 0.0)
    }

    /**
     * The property the whole change rests on: the recorder no longer writes distance per fix, so
     * what it showed live and what the repository recomputes when the track closes (or when
     * `finalizeDangling` recovers it after a crash) must be the same number. They share this
     * accumulator, so this pins that feeding it fix-by-fix — the recorder's usage — agrees with
     * folding the stored points through it.
     */
    @Test fun `accumulating fix by fix equals recomputing from the stored points`() {
        val points = listOf(
            point(lat = 0.0, lon = 0.0),
            point(lat = 0.5, lon = 0.5),
            point(lat = 90.0, lon = 0.0, ignored = true),
            point(lat = 1.0, lon = 0.5),
            point(lat = 40.0, lon = 40.0, segmentStart = true),
            point(lat = 41.0, lon = 40.5),
        )

        val live = TrackStats.Accumulator(flat)
        for (point in points) live.add(point) // as the fixes arrive

        assertEquals(TrackStats.of(points, flat), live.stats())
    }

    @Test fun `the accumulator's last good fix is the recorder's jump-check baseline`() {
        val accumulator = TrackStats.Accumulator(flat)
        val good = point(lat = 1.0)
        accumulator.add(good)
        accumulator.add(point(lat = 99.0, ignored = true))

        // A rejected fix must not become the baseline the next fix is measured against.
        assertEquals(good, accumulator.lastGood)
    }
}
