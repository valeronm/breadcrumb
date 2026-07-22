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
 *
 * The params under test are the ones that ship — [EdgeStayDetector.BRIEF_STOP], and
 * [EdgeStayDetector.VEHICLE] where the activity floor is the point. A suite pinning the
 * constructor defaults would pass green through any change to the numbers the recorder runs.
 */
class EdgeStayDetectorTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private val MIN = 60_000L

    /** The params the recorder actually runs. Fixture cadence is 15 s — coarser than the 10 s
     *  bin, which the detector measures off the points, so one moving fix marks its bin. */
    private val params = EdgeStayDetector.BRIEF_STOP

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

    /** An out-and-back excursion of [amplitudeM] on a [periodSec] cycle, sampled every 15 s — a
     *  parked vehicle's settling drift, which over the 30 s lookback clears the moving bar while
     *  staying decades below any speed the vehicle itself travels at. */
    private fun drift(
        centerM: Double,
        amplitudeM: Double,
        periodSec: Int,
        startT: Long,
        minutes: Int,
        speedMps: Float = 1.0f,
    ): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            val phase = (i * 15.0 % periodSec) / periodSec
            pt(centerM + amplitudeM * (1 - Math.abs(2 * phase - 1)), startT + i * 15_000L, speedMps)
        }

    private fun detect(points: List<TrackPoint>, p: EdgeStayDetector.Params = params) =
        EdgeStayDetector.detect(points, p, flatDistance)

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
        // The field shape at an arrival: parked, but the platform keeps reporting
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
        // The shipped floor is half a minute. A 15 s stop can only reach it by pulling walk fixes
        // into the corral, and their net progress reads as transit rather than a stay.
        val brief = (0 until 2).map { i ->
            pt(850.0 + if (i == 0) 3.0 else -3.0, 10 * MIN + i * 15_000L, 0.1f)
        }
        assertTrue(detect(walk(0.0, 80.0, 0, 10) + brief).isEmpty())
    }

    @Test
    fun `a parked vehicle's own drift hides the arrival until the activity floor rules it out`() {
        // The regression that hid the arrival tail on 156 drives outright: parked, a car's
        // settling drift wanders far enough to clear the 0.7 m/s bar, so bins keep voting moving
        // to the end of the track, the refined stay collapses under the floor, and the stay is
        // dropped. Nothing about the position evidence changes — only that under 5 km/h a car is
        // not driving, which is what [VEHICLE] adds.
        val points = walk(0.0, 600.0, 0, 5) + drift(3000.0, 30.0, periodSec = 60, 5 * MIN, 15)

        assertTrue(detect(points, EdgeStayDetector.BRIEF_STOP).isEmpty())

        val stays = detect(points, EdgeStayDetector.VEHICLE)
        assertEquals(1, stays.size)
        val s = stays.single()
        assertEquals(EdgeStayDetector.Side.END, s.side)
        assertTrue(s.boundaryTs in (5 * MIN - 30_000L)..(5 * MIN + 30_000L))
    }

    @Test
    fun `a lone moving fix carries its bin only when no standstill could explain it`() {
        // Where a bin normally holds several fixes, one vote means the rest of the bin disagreed —
        // standstill drift. The exception is a fix moving too fast for drift to explain: settling
        // GPS covers tens of metres in half a minute, not the 80 m below. Same fixture, same lone
        // excursion, only its size differs — and that alone decides where the track ends.
        fun parkedWithExcursion(excursionM: Double): List<TrackPoint> {
            // 5 s cadence: a 10 s bin holds two fixes, so a single vote is short of the floor.
            val approach = (0 until 60).map { i -> pt(6.67 * i, i * 5_000L, 1.33f) }
            val parked = (0 until 120).map { i ->
                val t = 5 * MIN + i * 5_000L
                val off = if (i == 60) excursionM else if (i % 2 == 0) 2.0 else -2.0
                pt(400.0 + off, t, if (i == 60) (excursionM / 30.0).toFloat() else 0.1f)
            }
            return approach + parked
        }

        val drifted = detect(parkedWithExcursion(40.0)).single()
        assertTrue(drifted.boundaryTs in (5 * MIN - 15_000L)..(5 * MIN + 15_000L))

        // 80 m in half a minute is 2.66 m/s — over the solo bar, so the one fix carries its bin
        // and the arrival is placed there instead.
        val carried = detect(parkedWithExcursion(80.0)).single()
        assertTrue(carried.boundaryTs in (10 * MIN - 15_000L)..(10 * MIN + 15_000L))
    }

    @Test
    fun `a late first moving bin retracts the cut to the dwell rather than eating the journey`() {
        // Two imported drives proposed cutting hundreds of metres of ordinary driving off their
        // starts, because their bins only reached the moving threshold a minute into the drive.
        // Here the platform reports a flat zero for the first minute (an import's speed-less
        // shape), so those bins can't vote — and the span the bin edge would cut ranges far
        // beyond anything a stop could cover, which pulls the cut back to the dwell's own bound.
        val blindDrive = walk(120.0, 600.0, 3 * MIN, 8)
            .mapIndexed { i, p -> if (i < 4) p.copy(speed = 0f) else p }
        val stays = detect(linger(0.0, 5.0, 0, 3) + blindDrive)

        assertEquals(1, stays.size)
        val s = stays.single()
        assertEquals(EdgeStayDetector.Side.START, s.side)
        // The real departure, not the minute of driving the bins were blind to.
        assertTrue(s.boundaryTs <= 3 * MIN + 15_000L)
    }

    @Test
    fun `ignored multipath fixes cannot fake movement past the arrival`() {
        // The field shape at the end of a walk: indoors, quality-flagged fixes read 1.7–2.7 m/s.
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
