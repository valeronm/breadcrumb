package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwells are detected from a track's good points with the flat-earth distance stub used across
 * the domain tests: 0.001° ≈ 100 m, so tests place points by metres east of an origin and reason
 * in metres/minutes directly.
 */
class DwellDetectorTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private val MIN = 60_000L

    /** A good point [meters] east of the origin at [t] (ms). */
    private fun pt(meters: Double, t: Long, ignored: Boolean = false) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 1.0 + meters / 100_000.0,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
        ignored = ignored,
    )

    /** Points every 15 s walking at [paceMPerMin] from [fromM], for [minutes]. */
    private fun walk(fromM: Double, paceMPerMin: Double, startT: Long, minutes: Int): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            pt(fromM + paceMPerMin * i / 4.0, startT + i * 15_000L)
        }

    /** Points every 15 s wandering within ±[jitterM] of [centerM], for [minutes]. */
    private fun linger(centerM: Double, jitterM: Double, startT: Long, minutes: Int): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            val offset = if (i % 2 == 0) jitterM else -jitterM
            pt(centerM + offset, startT + i * 15_000L)
        }

    private fun detect(points: List<TrackPoint>, params: DwellDetector.Params = DwellDetector.Params()) =
        DwellDetector.detect(points, params, flatDistance)

    @Test
    fun `a steady walk has no dwells`() {
        // 80 m/min for 30 min — never lingers.
        assertTrue(detect(walk(0.0, 80.0, 0, 30)).isEmpty())
    }

    @Test
    fun `lingering fifteen minutes inside the corral is a dwell`() {
        val dwells = detect(
            walk(0.0, 80.0, 0, 10) +
                linger(850.0, 30.0, 10 * MIN, 15) +
                walk(900.0, 80.0, 25 * MIN, 10),
        )
        assertEquals(1, dwells.size)
        val d = dwells.single()
        // Entry may start a couple of samples early: the final approach fixes are already
        // within the corral of the linger spot. Never late, never more than ~2 min early.
        assertTrue(d.entryTs in (8 * MIN)..(10 * MIN))
        assertTrue(d.exitTs >= 24 * MIN)
        // Centroid lands within one corral radius of the lingering spot (approach fixes that
        // joined the window drag it a little toward the walked-in direction).
        assertEquals(850.0, (d.centroid.lon - 1.0) * 100_000.0, 55.0)
    }

    @Test
    fun `a five minute stop is below the venue bar`() {
        val dwells = detect(
            walk(0.0, 80.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 5) +
                walk(900.0, 80.0, 15 * MIN, 10),
        )
        assertTrue(dwells.isEmpty())
    }

    @Test
    fun `poking past the corral and returning within the confirm window keeps one dwell`() {
        // 12 min lingering, a 90 s excursion to +80 m (outside 55, inside 110), 12 more min.
        val excursion = (0 until 6).map { i -> pt(930.0, 22 * MIN + i * 15_000L) }
        val dwells = detect(
            walk(0.0, 85.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 12) +
                excursion +
                linger(850.0, 20.0, 24 * MIN, 12),
        )
        assertEquals(1, dwells.size)
        assertTrue(dwells.single().exitTs >= 35 * MIN)
    }

    @Test
    fun `a fix beyond the hard radius ends the dwell immediately`() {
        val dwells = detect(
            linger(0.0, 20.0, 0, 12) +
                walk(200.0, 80.0, 12 * MIN, 20),
        )
        assertEquals(1, dwells.size)
        // Exit at the last in-corral fix, before the 200 m jump-off.
        assertTrue(dwells.single().exitTs < 12 * MIN)
    }

    @Test
    fun `two nearby dwells with a short stroll between merge into one visit`() {
        // The field case shape: linger, 5-min stroll 80 m over, linger again.
        val dwells = detect(
            walk(0.0, 85.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 15) +
                walk(870.0, 16.0, 25 * MIN, 5) +
                linger(930.0, 20.0, 30 * MIN, 15) +
                walk(950.0, 85.0, 45 * MIN, 10),
        )
        assertEquals(1, dwells.size)
        val d = dwells.single()
        assertTrue(d.entryTs in (8 * MIN)..(10 * MIN))
        assertTrue(d.exitTs >= 44 * MIN)
    }

    @Test
    fun `two dwells far apart stay separate`() {
        val dwells = detect(
            linger(0.0, 20.0, 0, 15) +
                walk(50.0, 80.0, 15 * MIN, 10) +   // 800 m between venues
                linger(850.0, 20.0, 25 * MIN, 15),
        )
        assertEquals(2, dwells.size)
    }

    @Test
    fun `a pause gap resuming in the corral credits the gap as dwell`() {
        // 4 min of fixes, a 9-min recording pause, 4 more min at the same spot: only 8 min of
        // fixes, but 17 min wall-clock inside the corral.
        val dwells = detect(
            walk(0.0, 85.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 4) +
                linger(850.0, 20.0, 23 * MIN, 4) +
                walk(900.0, 85.0, 27 * MIN, 10),
        )
        assertEquals(1, dwells.size)
        assertTrue(dwells.single().entryTs in (8 * MIN)..(10 * MIN))
    }

    @Test
    fun `a pause gap resuming elsewhere ends the dwell at the last fix before the pause`() {
        val dwells = detect(
            linger(0.0, 20.0, 0, 12) +
                walk(600.0, 80.0, 40 * MIN, 20), // resumed 600 m away, 28 min later
        )
        assertEquals(1, dwells.size)
        assertTrue(dwells.single().exitTs < 12 * MIN)
    }

    @Test
    fun `a dwell running to the end of the track exits at the final fix`() {
        val track = walk(0.0, 85.0, 0, 10) + linger(850.0, 20.0, 10 * MIN, 20)
        val dwells = detect(track)
        assertEquals(1, dwells.size)
        assertEquals(track.last().timestamp, dwells.single().exitTs)
    }

    @Test
    fun `ignored points do not form or extend a dwell`() {
        val noisy = linger(850.0, 20.0, 10 * MIN, 15).map { it.copy(ignored = true) }
        assertTrue(detect(walk(0.0, 80.0, 0, 10) + noisy + walk(900.0, 80.0, 25 * MIN, 10)).isEmpty())
    }

    @Test
    fun `a slow arced walk passing through the corral is a transit, not a dwell`() {
        // The false-positive shape: a winding path swings ±40 m laterally while making
        // slow but steady net progress (~8 m/min) — it holds the corral past minDwellMs yet never
        // actually stops. The drift gate must reject it.
        val arc = (0 until 14 * 4).map { i ->
            val lat = 1.0 + (if (i % 4 < 2) 40.0 else -40.0) / 100_000.0
            TrackPoint(
                trackId = 1,
                latitude = lat,
                longitude = 1.0 + (8.0 * i / 4.0) / 100_000.0,
                altitude = null, accuracy = null, speed = null, bearing = null,
                timestamp = i * 15_000L,
            )
        }
        assertTrue(detect(arc).isEmpty())
    }

    @Test
    fun `a slow drift that keeps leaving the corral is not a dwell`() {
        // 25 m/min: covers 55 m in ~2.2 min, so the centroid can never hold a 10-min window.
        assertTrue(detect(walk(0.0, 25.0, 0, 30)).isEmpty())
    }

    @Test
    fun `dense one-second sampling decimates without losing the dwell boundaries`() {
        val dense = (0 until 10 * 60).map { i -> pt(0.0 + i * 85.0 / 60.0, i * 1_000L) } +
            (0 until 15 * 60).map { i -> pt(850.0 + if (i % 2 == 0) 20.0 else -20.0, 10 * MIN + i * 1_000L) } +
            (0 until 10 * 60).map { i -> pt(900.0 + i * 85.0 / 60.0, 25 * MIN + i * 1_000L) }
        val dwells = detect(dense)
        assertEquals(1, dwells.size)
        val d = dwells.single()
        // Entry within the early-approach tolerance, exit at the linger's tail.
        assertTrue(d.entryTs in (8 * MIN)..(10 * MIN))
        assertTrue(d.exitTs >= 24 * MIN)
    }
}
