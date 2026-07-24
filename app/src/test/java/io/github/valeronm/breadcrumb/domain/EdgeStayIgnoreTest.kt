package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make automatic, repeatable application safe: a plan derived from the raw
 * recording (so re-running it converges instead of eating the track), flags that come back when
 * the rule withdraws, and edges that are the only part of the track this rule may touch.
 *
 * Same fixture shape as [EdgeStayDetectorTest] — flat-earth distances (0.001° ≈ 100 m), a fix
 * every 15 s carrying its Doppler speed.
 */
class EdgeStayIgnoreTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private val params = EdgeStayDetector.Params()

    private var nextId = 1L

    private fun pt(meters: Double, t: Long, speed: Float?) = TrackPoint(
        id = nextId++,
        trackId = 1,
        latitude = 1.0,
        longitude = 1.0 + meters / 100_000.0,
        altitude = null,
        accuracy = null,
        speed = speed,
        bearing = null,
        timestamp = t,
    )

    private fun walk(fromM: Double, startT: Long, minutes: Int): List<TrackPoint> =
        (0 until minutes * 4).map { i -> pt(fromM + 80.0 * i / 4.0, startT + i * 15_000L, 1.33f) }

    private fun linger(centerM: Double, startT: Long, minutes: Int): List<TrackPoint> =
        (0 until minutes * 4).map { i ->
            pt(centerM + if (i % 2 == 0) 8.0 else -8.0, startT + i * 15_000L, 0.1f)
        }

    /** 10 min of walking, then 5 min stationary where it ended — the arrival-lag shape. */
    private fun walkThenLinger(): List<TrackPoint> =
        walk(0.0, 0L, 10).let { it + linger(it.last().longitudeM(), 10 * 60_000L, 5) }

    private fun TrackPoint.longitudeM() = (longitude - 1.0) * 100_000.0

    /** A fix carrying the flag an earlier run of the rule left on it. */
    private fun TrackPoint.asEdgeStay() =
        copy(ignored = true, ignoreReason = IgnoreReason.EDGE_STAY.code)

    private fun plan(points: List<TrackPoint>, startedAt: Long, endedAt: Long) =
        EdgeStayIgnore.plan(points, startedAt, endedAt, params, flatDistance)

    @Test
    fun `the arrival tail is flagged and the clock stops at the boundary`() {
        val points = walkThenLinger()
        val endedAt = points.last().timestamp + 5_000L

        val plan = plan(points, startedAt = 0L, endedAt = endedAt)

        assertTrue("the tail comes off the path", plan.ignore.size >= 15)
        assertTrue(plan.restore.isEmpty())
        val kept = EdgeStayIgnore.applied(points, plan).filter { !it.ignored }
        assertEquals(plan.endedAt, kept.last().timestamp)
        assertTrue("nothing before the boundary is touched", plan.endedAt < endedAt)
        assertEquals(0L, plan.startedAt)
    }

    @Test
    fun `re-planning an applied track changes nothing`() {
        // The invariant automatic application rests on: the plan is derived from the recording,
        // not from what the last plan left behind. Detection fed its own output would keep
        // finding a fresh stay in the remainder and walk the track backwards, sweep by sweep.
        val points = walkThenLinger()
        val first = plan(points, startedAt = 0L, endedAt = points.last().timestamp + 5_000L)
        val applied = EdgeStayIgnore.applied(points, first)

        val again = plan(applied, startedAt = first.startedAt, endedAt = first.endedAt)

        assertTrue(again.ignore.isEmpty())
        assertTrue(again.restore.isEmpty())
        assertEquals(first.startedAt, again.startedAt)
        assertEquals(first.endedAt, again.endedAt)
    }

    @Test
    fun `a rule that finds nothing hands the fixes back and reopens the clock`() {
        // Standing in for a moved rule: a track that walks end to end, carrying flags no current
        // rule would set. The raw end time is gone with the old cut, so the clock goes back to
        // the last fix — the only reading of it that survives.
        val walk = walk(0.0, 0L, 10)
        val flagged = walk.mapIndexed { i, p ->
            if (i >= walk.size - 8) p.asEdgeStay() else p
        }
        val cutAt = walk[walk.size - 9].timestamp

        val plan = plan(flagged, startedAt = 0L, endedAt = cutAt)

        assertEquals(8, plan.restore.size)
        assertTrue(plan.ignore.isEmpty())
        assertEquals(walk.last().timestamp, plan.endedAt)
        assertTrue(EdgeStayIgnore.applied(flagged, plan).none { it.ignored })
    }

    @Test
    fun `flags in the middle of a track are left alone`() {
        // What a merge leaves behind: the earlier track's overrun, now a stop the merged track
        // paused at. An edge rule will never re-derive it, so it must not be up for withdrawal.
        val points = walk(0.0, 0L, 10) + walk(800.0, 11 * 60_000L, 10)
        val middle = points.subList(38, 42).map { it.id }.toSet()
        val flagged = points.map {
            if (it.id in middle) it.asEdgeStay() else it
        }

        val plan = plan(flagged, startedAt = 0L, endedAt = points.last().timestamp)

        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.ignore.isEmpty())
    }

    @Test
    fun `points that carry no id are planned by position`() {
        // What a backup restore hands in: the format stores no point ids, so every point parses
        // with id 0. A plan keyed by id would match all of them at once and flag the whole track.
        val points = walkThenLinger().map { it.copy(id = 0) }

        val plan = plan(points, startedAt = 0L, endedAt = points.last().timestamp)
        val applied = EdgeStayIgnore.applied(points, plan)

        val kept = applied.filter { !it.ignored }
        assertTrue("the walk must survive", kept.size > points.size / 2)
        assertEquals(points.size, kept.size + plan.ignore.size)
        // Only the tail, and contiguously so.
        assertEquals(applied.indices.toList().takeLast(plan.ignore.size).toSet(), plan.ignore)
    }

    @Test
    fun `the stored overrun reads back with its side, length and joining fix`() {
        val points = walkThenLinger()
        val applied = EdgeStayIgnore.applied(
            points,
            plan(points, startedAt = 0L, endedAt = points.last().timestamp),
        )
        val good = applied.filter { !it.ignored }
        val stay = applied.filter { EdgeStayIgnore.isEdgeStay(it) }

        val overrun = EdgeStayIgnore.overruns(good, stay).single()

        assertEquals(EdgeStayDetector.Side.END, overrun.side)
        assertEquals(points.last().timestamp - good.last().timestamp, overrun.stayMs)
        // The good fix the track ends on leads the grayed line, so it meets what was drawn.
        assertEquals(good.last().id, overrun.points.first().id)
        assertEquals(stay.size + 1, overrun.points.size)
    }
}
