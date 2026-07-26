package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
