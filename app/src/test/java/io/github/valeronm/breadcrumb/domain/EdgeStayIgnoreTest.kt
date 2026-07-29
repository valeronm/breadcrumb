package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make automatic, repeatable application safe: a plan derived from the raw recording
 * (so re-running it converges instead of eating the track), flags that come back when the rule
 * withdraws, and edges as the only part of the track this rule may touch. Same fixture shape as
 * [EdgeStayDetectorTest] — flat-earth distances (0.001° ≈ 100 m), a fix every 15 s carrying its Doppler speed.
 */
class EdgeStayIgnoreTest {

    /** The params the recorder actually runs, as in [EdgeStayDetectorTest]: the detector's own
     *  defaults ship nowhere, so a plan derived through them is a plan of a rule the app doesn't
     *  apply — and whether a steady walk plans *nothing* is exactly a question of the numbers. */
    private val params = EdgeStayDetector.BRIEF_STOP

    private var nextId = 1L

    private fun pt(meters: Double, t: Long, speed: Float?) = TrackPoint(
        id = nextId++,
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(meters),
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
    fun `flags in the middle of a track are handed back`() {
        // What a merge leaves behind: the earlier track's overrun, now buried mid-track. No edge
        // rule reaches it there, so holding it would keep those fixes off the path forever on the
        // say-so of a rule that has stopped applying — they go back to the line.
        val points = walk(0.0, 0L, 10) + walk(800.0, 11 * 60_000L, 10)
        val middle = points.indices.filter { it in 38..41 }.toSet()
        val flagged = points.mapIndexed { i, p ->
            if (i in middle) p.asEdgeStay() else p
        }

        val plan = plan(flagged, startedAt = 0L, endedAt = points.last().timestamp)

        assertEquals(middle, plan.restore)
        assertTrue(plan.ignore.isEmpty())
        assertTrue(EdgeStayIgnore.applied(flagged, plan).none { it.ignored })
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

    // --- overruns ------------------------------------------------------------
    //
    // Read back off the stored flags, so these hand it the two lists directly rather than detecting
    // first: the screen's contract is "describe what the rows say", and a fixture that ran the
    // detector would test the detector's verdict instead of this rendering of it.

    @Test
    fun `a lead overrun hangs off the fix that opens the track`() {
        // The mirror of the arrival tail: recording armed while still parked, so the flagged fixes
        // sit *before* the first good one and the polyline ends on it rather than starting there.
        val lead = linger(0.0, 0L, 3)
        val good = walk(0.0, 3 * 60_000L, 10)

        val overrun = EdgeStayIgnore.overruns(good, lead).single()

        assertEquals(EdgeStayDetector.Side.START, overrun.side)
        assertEquals(good.first().timestamp - lead.first().timestamp, overrun.stayMs)
        assertEquals(lead.size + 1, overrun.points.size)
        assertEquals(good.first().id, overrun.points.last().id)
    }

    @Test
    fun `a track that overran at both ends reads back as two, start first`() {
        val lead = linger(0.0, 0L, 3)
        val good = walk(0.0, 3 * 60_000L, 10)
        val tail = linger(good.last().longitudeM(), 13 * 60_000L, 3)

        val overruns = EdgeStayIgnore.overruns(good, lead + tail)

        assertEquals(
            listOf(EdgeStayDetector.Side.START, EdgeStayDetector.Side.END),
            overruns.map { it.side },
        )
        // Each joins the end it hangs off, from opposite directions.
        assertEquals(good.first().id, overruns[0].points.last().id)
        assertEquals(good.last().id, overruns[1].points.first().id)
    }

    @Test
    fun `flags buried mid-track render no overrun`() {
        // What a merge leaves behind. `plan` hands these back to the path; until that sweep runs the
        // screen must draw no grayed edge for them — they are at neither end.
        val good = walk(0.0, 0L, 10)
        val buried = listOf(good[20], good[21])

        assertTrue(EdgeStayIgnore.overruns(good, buried).isEmpty())
    }

    @Test
    fun `nothing to hang off and nothing to hang are both empty`() {
        val walk = walk(0.0, 0L, 10)
        // A bad-points-only track: every fix rejected, so there is no good fix to join.
        assertTrue(EdgeStayIgnore.overruns(emptyList(), walk).isEmpty())
        assertTrue(EdgeStayIgnore.overruns(walk, emptyList()).isEmpty())
    }

    @Test
    fun `a plan that moves nothing hands back the very same list`() {
        // A steady walk with no stop at either edge and no flags to withdraw. Identity, not equality:
        // this runs on every track finish, merge, split, import and retype, and the fast path is
        // what keeps the common case from copying every point row to change none of them.
        val points = walk(0.0, 0L, 10)

        val plan = plan(points, startedAt = 0L, endedAt = points.last().timestamp)

        assertTrue("a steady walk plans nothing", !plan.movesPoints)
        assertTrue(EdgeStayIgnore.applied(points, plan) === points)
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
