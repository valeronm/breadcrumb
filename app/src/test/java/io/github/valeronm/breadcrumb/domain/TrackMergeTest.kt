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

    @Test fun `short same-activity stay yields a merge into the earlier track`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, stayStart = 0, stayEnd = 60_000), // 1 min
        )
    }

    @Test fun `a 5 minute stay is still eligible (boundary)`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, stayStart = 0, stayEnd = TrackMerge.MAX_STAY_MS),
        )
    }

    @Test fun `a stay longer than 5 minutes is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, stayStart = 0, stayEnd = TrackMerge.MAX_STAY_MS + 1))
    }

    @Test fun `different activities are not mergeable`() {
        val running = track(2, "RUNNING", 300_000)
        assertNull(TrackMerge.plan(before, running, stayStart = 0, stayEnd = 60_000))
    }

    @Test fun `an ongoing stay is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, stayStart = 0, stayEnd = null))
    }

    @Test fun `a stay on a named place is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, stayStart = 0, stayEnd = 60_000, stayIsNamedPlace = true))
    }

    @Test fun `a sub-minute stay on a named place is an artifact, not a visit - mergeable`() {
        // The restored edge-stay tail: seconds from its track, and arrivals usually land on a
        // named place — the protection must not block merging the tail back.
        val plan = TrackMerge.plan(before, after, stayStart = 0, stayEnd = 15_000, stayIsNamedPlace = true)
        assertEquals(TrackMerge.Plan(earlierId = 1, laterId = 2), plan)
    }
}
