package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TrackMergeTest {

    private fun track(id: Long, activity: String, startedAt: Long) =
        TrackSummary(id, activity, startedAt, endedAt = startedAt + 1000, distanceMeters = 100.0, pointCount = 10, ignoredCount = 0)

    private val before = track(1, "WALKING", 0)
    private val after = track(2, "WALKING", 300_000)

    @Test fun `short same-activity interval yields a merge into the earlier track`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = 60_000), // 1 min
        )
    }

    @Test fun `a 5 minute interval is still eligible (boundary)`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = TrackMerge.MAX_INTERVAL_MS),
        )
    }

    @Test fun `an interval longer than 5 minutes is not mergeable`() {
        assertNull(
            TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = TrackMerge.MAX_INTERVAL_MS + 1),
        )
    }

    @Test fun `different activities are not mergeable`() {
        val running = track(2, "RUNNING", 300_000)
        assertNull(TrackMerge.plan(before, running, intervalStart = 0, intervalEnd = 60_000))
    }

    @Test fun `an ongoing interval is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = null))
    }

    // --- plansByAnchor: what the timeline actually asks -------------------------------

    private val byId = mapOf(before.id to before)
    private val nextTrack = mapOf(before.id to after)

    private fun stay(start: Long, end: Long) = StayDeriver.Stay(
        start = start, end = end, location = StayDeriver.Endpoint(1.0, 1.0),
        provenance = StayDeriver.Provenance.OBSERVED, afterTrackId = before.id, clusterId = 0,
    )

    @Test fun `a short interval's plan is keyed by the track it follows`() {
        val plans = TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), byId, nextTrack)
        assertEquals(TrackMerge.Plan(earlierId = 1, laterId = 2), plans[before.id])
    }

    @Test fun `a stay across midnight is judged whole, not by the short slice it renders as`() {
        // 23:58 to 00:30 — half an hour at one place, drawn as a two-minute row and a
        // thirty-minute one. Merging on the short row would fuse the tracks across the whole stop.
        val start = DAY - 2 * MINUTE
        val stay = stay(start, start + 32 * MINUTE)
        val slices = StayDeriver.slicePerDay(listOf(stay), ZoneId.of("UTC"), nowMs = 2 * DAY)
        assertEquals(2, slices.size)
        assertTrue(slices.any { it.end!! - it.start <= TrackMerge.MAX_INTERVAL_MS })

        val plans = TrackMerge.plansByAnchor(listOf(stay), byId, nextTrack)
        // Every slice looks its offer up by the same anchor, so the short one gets the same
        // verdict as the stop it belongs to: none.
        assertNull(plans[before.id])
        assertTrue(slices.all { plans[it.afterTrackId] == null })
    }

    @Test fun `an interval whose later side is missing yields no plan`() {
        // The tail interval, running into a track that is still recording.
        assertNull(TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), byId, emptyMap())[before.id])
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
