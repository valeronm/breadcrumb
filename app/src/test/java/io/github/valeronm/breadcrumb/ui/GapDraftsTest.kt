package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.OnlineRouting
import io.github.valeronm.breadcrumb.domain.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class GapDraftsTest {
    private val detail = GapFixtures.detail(ZoneId.of("UTC"))

    @Test fun `the whole gap holds both instants and leaves the type unset`() {
        val d = GapDrafts.wholeGap(detail)
        assertEquals(detail.gap.start, d.origin!!.timeMs)
        assertEquals(detail.gap.end, d.destination!!.timeMs)
        assertEquals(detail.before.id to detail.after.id, d.tracksAround)
        assertNull(d.activity)
    }

    @Test fun `the missed start arrives at the next track as its type and leaves its departure untimed`() {
        val d = GapDrafts.missedStart(detail)
        assertEquals(detail.gap.from?.at, d.origin!!.at)
        assertNull(d.origin.timeMs)
        assertEquals(detail.gap.to?.at, d.destination!!.at)
        assertEquals(detail.gap.end, d.destination.timeMs)
        assertEquals(ActivityType.ofName(detail.after.activityType), d.activity)
    }

    private fun road(durationMs: Long) = OnlineRouting.Route(durationMs, emptyList())

    @Test fun `a road places the missed start's departure its duration before the arrival`() {
        val p = GapDrafts.withRoad(GapDrafts.missedStart(detail), road(20 * 60_000L), detail.gap.start)!!
        assertEquals(detail.gap.end - 20 * 60_000L, p.trip.origin.end.timestampMs)
        assertEquals(detail.gap.end, p.trip.destination.end.timestampMs)
        assertEquals(ActivityType.ofName(detail.after.activityType), p.trip.activityType)
    }

    @Test fun `the form opens a proposal with its departure coupled to the road`() {
        val p = GapDrafts.withRoad(GapDrafts.missedStart(detail), road(20 * 60_000L), detail.gap.start)!!
        assertEquals(p.trip.origin.end.timestampMs, p.draft.origin!!.coupledMs)
        assertNull(p.draft.origin.timeMs)
        assertEquals(detail.gap.end, p.draft.destination!!.timeMs)
        assertEquals(detail.before.id to detail.after.id, p.draft.tracksAround)
    }

    @Test fun `a road longer than the gap proposes nothing`() {
        val gapMs = detail.gap.end - detail.gap.start
        assertNull(GapDrafts.withRoad(GapDrafts.missedStart(detail), road(gapMs + 60_000L), detail.gap.start))
    }

    @Test fun `a road as long as the gap departs as the track before ends`() {
        val gapMs = detail.gap.end - detail.gap.start
        val p = GapDrafts.withRoad(GapDrafts.missedStart(detail), road(gapMs), detail.gap.start)!!
        assertEquals(detail.gap.start, p.trip.origin.end.timestampMs)
    }
}
