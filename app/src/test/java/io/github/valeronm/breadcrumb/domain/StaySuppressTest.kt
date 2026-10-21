package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaySuppressTest {

    private fun track(id: Long, activity: String, startedAt: Long) =
        TrackSummary(
            id, activity, startedAt, endedAt = startedAt + 1000, distanceMeters = 100.0,
            pointCount = 10, ignoredCount = 0, source = null, startPlaceId = null, endPlaceId = null,
        )

    private val walk = track(1, "WALKING", 0)
    private val drive = track(2, "IN_VEHICLE", 300_000)

    @Test fun `different activities yield a dissolve when merge is not offered`() {
        assertEquals(
            StaySuppress.Plan(afterTrackId = 1, laterTrackId = 2),
            StaySuppress.plan(walk, drive, intervalEnd = 60_000, mergeOffered = false),
        )
    }

    @Test fun `same activity yields a dissolve when merge is not offered`() {
        val secondWalk = track(2, "WALKING", 300_000)
        assertEquals(
            StaySuppress.Plan(afterTrackId = 1, laterTrackId = 2),
            StaySuppress.plan(walk, secondWalk, 60_000, mergeOffered = false),
        )
    }

    @Test fun `dissolve does not care how long the stay lasted when merge is not offered`() {
        val secondWalk = track(2, "WALKING", 3_600_000)
        val twentyMinutes = 20 * 60_000L
        assertEquals(
            StaySuppress.Plan(afterTrackId = 1, laterTrackId = 2),
            StaySuppress.plan(walk, secondWalk, twentyMinutes, mergeOffered = false),
        )
    }

    @Test fun `a merge offer blocks dissolve`() {
        assertNull(StaySuppress.plan(walk, drive, 60_000, mergeOffered = true))
    }

    @Test fun `dissolve is keyed by the track the stay follows`() {
        val stay = StayDeriver.Stay(start = 0, end = 60_000, afterTrackId = walk.id, clusterId = 0)
        val neighbors = mapOf(walk.id to TrackMerge.Neighbors(walk, drive))
        val plans = StaySuppress.plansByAnchor(listOf(stay), neighbors, mergePlans = emptyMap())
        assertEquals(StaySuppress.Plan(walk.id, drive.id), plans[walk.id])
    }

    @Test fun `hiding follows a pair across changed bounds and disappears after restore`() {
        val stay = StayDeriver.Stay(start = 1, end = 2 * 86_400_000, afterTrackId = 1, clusterId = 0)
        val hidden = setOf(StaySuppress.Plan(1, 2))
        assertEquals(emptyList<StayDeriver.Interval>(), StaySuppress.visibleIntervals(listOf(stay), mapOf(1L to 2L), hidden))
        assertEquals(listOf(stay), StaySuppress.visibleIntervals(listOf(stay), mapOf(1L to 3L), hidden))
        assertEquals(listOf(stay), StaySuppress.visibleIntervals(listOf(stay), mapOf(1L to 2L), emptySet()))
    }
}
