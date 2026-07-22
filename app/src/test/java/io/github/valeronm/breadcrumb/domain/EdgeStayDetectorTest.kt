package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge stays are asserted with the flat-earth distance stub (0.001° ≈ 100 m) and fixtures every
 * 15 s carrying an explicit Doppler speed, so tests reason in metres/minutes and can steer the
 * two stages independently: position decides *whether* (the corral sweep), speed decides *where*
 * (the moving-bin boundary).
 */
class EdgeStayDetectorTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private val MIN = 60_000L

    /** Fixture cadence is 15 s, which the detector measures off the points: one moving fix
     *  marks its bin. */
    private val params = EdgeStayDetector.Params()

    private fun pt(meters: Double, t: Long, speed: Float?, ignored: Boolean = false) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 1.0 + meters / 100_000.0,
        altitude = null,
        accuracy = null,
        speed = speed,
        bearing = null,
        timestamp = t,
        ignored = ignored,
    )

    /** Points every 15 s walking at [paceMPerMin] from [fromM], Doppler speed to match. */
    private fun walk(fromM: Double, paceMPerMin: Double, startT: Long, minutes: Int): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            pt(fromM + paceMPerMin * i / 4.0, startT + i * 15_000L, (paceMPerMin / 60.0).toFloat())
        }

    /** Points every 15 s wandering ±[jitterM] around [centerM] at standstill Doppler speed —
     *  unless [speedMps] fakes movement (the pacing / approach-tail shape). */
    private fun linger(
        centerM: Double,
        jitterM: Double,
        startT: Long,
        minutes: Int,
        speedMps: Float? = 0.1f,
    ): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            pt(centerM + if (i % 2 == 0) jitterM else -jitterM, startT + i * 15_000L, speedMps)
        }

    private fun detect(points: List<TrackPoint>) =
        EdgeStayDetector.detect(points, params, flatDistance)

    @Test
    fun `a track sampled at bin scale is still detectable`() {
        // One fix per bin is normal when the recorder samples slowly (the sampling setting goes
        // to 30 s) or on an imported file. A vote floor that demanded corroboration regardless
        // would leave no bin able to be moving, and the detector would abstain on every such
        // track — the floor scales with the track's own cadence instead.
        val sparse = (0 until 40).map { i ->
            pt(80.0 * i, i * 30_000L, 2.66f) // 30 s apart, walking pace, one fix per bin
        } + (0 until 30).map { i ->
            pt(3200.0 + if (i % 2 == 0) 15.0 else -15.0, 20 * MIN + i * 30_000L, 0.1f)
        }
        val stays = detect(sparse)
        assertEquals(1, stays.size)
        assertEquals(EdgeStayDetector.Side.END, stays.single().side)
    }

    @Test
    fun `a steady walk has no edge stays`() {
        assertTrue(detect(walk(0.0, 80.0, 0, 30)).isEmpty())
    }

    @Test
    fun `arriving and lingering puts a stay at the end, cut where speed collapsed`() {
        val stays = detect(
            walk(0.0, 80.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 15),
        )
        assertEquals(1, stays.size)
        val s = stays.single()
        assertEquals(EdgeStayDetector.Side.END, s.side)
        // Boundary at the walk-to-linger transition, bin-quantized.
        assertTrue(s.boundaryTs in (10 * MIN - 30_000L)..(10 * MIN + 30_000L))
        assertTrue(s.stayMs >= 14 * MIN)
    }

    @Test
    fun `phantom Doppler at a standstill cannot move the boundary`() {
        // The field shape (2026-07-04 16:57 arrival): parked, but the platform keeps reporting
        // metres per second — three such fixes at the very end used to put the last moving bin
        // past the real arrival and collapse the stay to nothing. Displacement holds the veto, so
        // a fixture that never leaves its jitter box is stopped however fast its Doppler reads.
        val stays = detect(
            walk(0.0, 80.0, 0, 10) +
                linger(850.0, 20.0, 10 * MIN, 15, speedMps = 3.5f),
        )
        assertEquals(1, stays.size)
        val s = stays.single()
        assertTrue(s.boundaryTs in (10 * MIN - 30_000L)..(10 * MIN + 30_000L))
        assertTrue(s.stayMs >= 14 * MIN)
    }

    @Test
    fun `a burst of fixes after a quiet stretch cannot vote on jitter`() {
        // Once parked, min-distance sampling goes quiet for minutes, so the nearest earlier fix
        // can be far outside the lookback and the window shrinks to an adjacent-fix delta — where
        // ±20 m of jitter reads as tens of m/s. Those fixes must abstain, not vote the tail away.
        val burst = (0 until 3).map { i ->
            pt(850.0 + if (i % 2 == 0) 20.0 else -20.0, 16 * MIN + i * 1_000L, 3.5f)
        }
        val stays = detect(walk(0.0, 80.0, 0, 10) + linger(850.0, 20.0, 10 * MIN, 4) + burst)
        assertEquals(1, stays.size)
        assertTrue(stays.single().boundaryTs in (10 * MIN - 30_000L)..(10 * MIN + 30_000L))
    }

    @Test
    fun `the boundary is the fix the trimmed track ends at`() {
        // One value serves the split and the display: a real fix, the last one the surviving
        // track keeps. The speed-bin edge it derives from falls between fixes, and marking the
        // first *removed* fix would leave the track ending a leg short of the line shown.
        for (points in listOf(
            walk(0.0, 80.0, 0, 10) + linger(850.0, 20.0, 10 * MIN, 15),
            linger(0.0, 20.0, 0, 15) + walk(50.0, 80.0, 15 * MIN, 20),
        )) {
            val stay = detect(points).single()
            val isEnd = stay.side == EdgeStayDetector.Side.END
            assertTrue(points.any { it.timestamp == stay.boundaryTs })
            // The repository's partition: everything strictly beyond the boundary moves.
            val kept = points.filter {
                if (isEnd) it.timestamp <= stay.boundaryTs else it.timestamp >= stay.boundaryTs
            }
            assertEquals(stay.boundaryTs, if (isEnd) kept.last().timestamp else kept.first().timestamp)
            val edge = if (isEnd) points.last().timestamp else points.first().timestamp
            assertEquals(Math.abs(edge - stay.boundaryTs), stay.stayMs)
        }
    }

    @Test
    fun `lingering before departure puts a stay at the start`() {
        val stays = detect(
            linger(0.0, 20.0, 0, 15) +
                walk(50.0, 80.0, 15 * MIN, 20),
        )
        assertEquals(1, stays.size)
        val s = stays.single()
        assertEquals(EdgeStayDetector.Side.START, s.side)
        assertTrue(s.boundaryTs in (15 * MIN - 30_000L)..(15 * MIN + 30_000L))
        assertTrue(s.stayMs >= 14 * MIN)
    }

    @Test
    fun `a mid-track dwell is not an edge stay`() {
        assertTrue(
            detect(
                walk(0.0, 80.0, 0, 10) +
                    linger(850.0, 20.0, 10 * MIN, 12) +
                    walk(900.0, 80.0, 22 * MIN, 10),
            ).isEmpty(),
        )
    }

    @Test
    fun `a stop shorter than the floor leaves no edge stay`() {
        assertTrue(
            detect(
                walk(0.0, 80.0, 0, 10) +
                    linger(850.0, 20.0, 10 * MIN, 2),
            ).isEmpty(),
        )
    }

    @Test
    fun `an edge dwell whose refined stay is under the floor is dropped`() {
        // The corral holds from 8 min, but the walk goes on until 12 — real ground covered, so
        // speed keeps voting — leaving only a 1-minute stop, too short to be a stay.
        assertTrue(
            detect(
                walk(0.0, 80.0, 0, 12) +
                    linger(970.0, 20.0, 12 * MIN, 1),
            ).isEmpty(),
        )
    }

    @Test
    fun `ignored multipath fixes cannot fake movement past the arrival`() {
        // The field shape (Jun 29 19:21 walk): indoors, quality-flagged fixes read 1.7–2.7 m/s.
        val phantom = (0 until 8).map { i ->
            pt(850.0 + if (i % 2 == 0) 30.0 else -30.0, 10 * MIN + i * 15_000L, 2.0f, ignored = true)
        }
        val stays = detect(
            walk(0.0, 80.0, 0, 10) +
                (linger(850.0, 20.0, 10 * MIN, 15) + phantom).sortedBy { it.timestamp },
        )
        assertEquals(1, stays.size)
        assertTrue(stays.single().boundaryTs in (10 * MIN - 30_000L)..(10 * MIN + 30_000L))
    }

    @Test
    fun `without Doppler the cut comes from position-derived speed over the lookback`() {
        // Imported tracks carry no Doppler. Adjacent-fix deltas are jitter (the ±20 m linger
        // alternation reads as 2.7 m/s fix-to-fix!) — but over the 30 s lookback the linger
        // nets ~zero displacement while the walk still reads ~1.3 m/s.
        val noSpeed = (walk(0.0, 80.0, 0, 10) + linger(850.0, 20.0, 10 * MIN, 15))
            .map { it.copy(speed = null) }
        val stays = detect(noSpeed)
        assertEquals(1, stays.size)
        val s = stays.single()
        assertEquals(EdgeStayDetector.Side.END, s.side)
        assertTrue(s.boundaryTs in (10 * MIN - 30_000L)..(10 * MIN + 60_000L))
    }

    @Test
    fun `a speed-less loop that holds the corral is not trimmed`() {
        // The imported-drive failure shape: a car circling for parking — excursions to ~90 m
        // that return before the sustained-exit timer, net drift ~zero, real speed high. The
        // derived speed must keep those bins "moving" so no stay survives the floor.
        val loop = (0 until 6 * 4).map { i ->
            val phase = i % 4
            val offM = if (phase == 0 || phase == 3) 0.0 else 90.0
            pt(850.0 + offM, 10 * MIN + i * 15_000L, speed = null)
        }
        val stays = detect(
            walk(0.0, 80.0, 0, 10).map { it.copy(speed = null) } + loop,
        )
        assertTrue(stays.isEmpty())
    }

    @Test
    fun `stays at both edges are both reported`() {
        val stays = detect(
            linger(0.0, 20.0, 0, 10) +
                walk(50.0, 80.0, 10 * MIN, 15) +
                linger(1300.0, 20.0, 25 * MIN, 10),
        )
        assertEquals(2, stays.size)
        assertEquals(EdgeStayDetector.Side.START, stays[0].side)
        assertEquals(EdgeStayDetector.Side.END, stays[1].side)
    }
}
