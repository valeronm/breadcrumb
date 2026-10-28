package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaySwipeTest {

    private fun summary(id: Long, activity: ActivityType) = trackSummary(id, activity.name, startedAt = 0, endedAt = 0)

    private val carThenWalk = TrackMerge.Neighbors(summary(1, ActivityType.DRIVING), summary(2, ActivityType.WALKING))
    private val walkThenWalk = TrackMerge.Neighbors(summary(1, ActivityType.WALKING), summary(2, ActivityType.WALKING))

    private fun stay(minutes: Long) = StayDeriver.Stay(start = 0, end = minutes * 60_000L, afterTrackId = 1, clusterId = 0)

    private fun swipes(stay: StayDeriver.Stay, neighbors: TrackMerge.Neighbors) = swipes(listOf(stay), mapOf(1L to neighbors))

    private fun swipes(intervals: List<StayDeriver.Interval>, neighbors: Map<Long, TrackMerge.Neighbors>) =
        StaySwipe.byAnchor(intervals, neighbors, TrackMerge.plansByAnchor(intervals, neighbors))

    @Test fun `a stop the tracks either side can merge across is merged`() {
        assertEquals(StaySwipe.Merge(TrackMerge.Plan(1, 2)), swipes(stay(2), walkThenWalk)[1])
    }

    @Test fun `a stop no merge can close is hidden`() {
        assertEquals(StaySwipe.Hide(HiddenStay(beforeId = 1, afterId = 2)), swipes(stay(2), carThenWalk)[1])
    }

    @Test fun `a stay too long to merge is hidden up to the cap`() {
        assertEquals(StaySwipe.Hide(HiddenStay(beforeId = 1, afterId = 2)), swipes(stay(15), walkThenWalk)[1])
    }

    @Test fun `a stay past the cap offers nothing`() {
        assertNull(swipes(stay(16), carThenWalk)[1])
    }

    @Test fun `a stay with no track after it offers nothing`() {
        assertNull(swipes(listOf(stay(2)), emptyMap())[1])
    }

    @Test fun `a gap offers no hide`() {
        val gap = StayDeriver.Gap(0, 2 * 60_000L, afterTrackId = 1, from = null, to = null)
        assertNull(swipes(listOf(gap), mapOf(1L to carThenWalk))[1])
    }
}
