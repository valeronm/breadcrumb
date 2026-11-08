package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenStaysTest {

    private val minute = 60_000L

    private fun trip(id: Long, startMin: Long, hidesBefore: Boolean = false, hidesAfter: Boolean = false) =
        trackSummary(id, "WALKING", startedAt = startMin * minute, endedAt = (startMin + 10) * minute)
            .copy(hidesStayBefore = hidesBefore, hidesStayAfter = hidesAfter)

    private fun marks(summaries: List<TrackSummary>) =
        TimelineRows.interleave(summaries, emptyList())
            .filterIsInstance<TimelineItem.TrackItem>()
            .map { it.trip.main.id to it.hiddenStayAfter }

    @Test fun `a stay hidden at both facing ends marks the trip before it`() {
        val summaries = listOf(
            trip(4, 300, hidesBefore = true),
            trip(3, 200, hidesAfter = true),
            trip(2, 100, hidesBefore = true),
            trip(1, 0, hidesAfter = true),
        )

        assertEquals(
            listOf(4L to null, 3L to HiddenStay(3, 4), 2L to null, 1L to HiddenStay(1, 2)),
            marks(summaries),
        )
    }

    @Test fun `a stay hidden at one end only marks nothing`() {
        assertEquals(listOf(2L to null, 1L to null), marks(listOf(trip(2, 100), trip(1, 0, hidesAfter = true))))
    }

    @Test fun `ends facing across another trip mark nothing`() {
        val summaries = listOf(trip(3, 200, hidesBefore = true), trip(2, 100), trip(1, 0, hidesAfter = true))

        assertEquals(listOf(3L to null, 2L to null, 1L to null), marks(summaries))
    }

    @Test fun `a trip of several parts is marked by its last part`() {
        val first = trip(1, 0)
        val joined = trackSummary(2, "WALKING", startedAt = 10 * minute, endedAt = 20 * minute).copy(hidesStayAfter = true)
        val after = trip(3, 30, hidesBefore = true)

        assertEquals(listOf(3L to null, 1L to HiddenStay(2, 3)), marks(listOf(after, joined, first)))
    }

    private fun stayAfter(trackId: Long, clusterId: Int, end: Long? = trackId * minute + 1) =
        StayDeriver.Stay(start = trackId * minute, end = end, afterTrackId = trackId, clusterId = clusterId)

    private val kept = TrackMerge.neighborsByAnchor((5L downTo 1L).map { trackSummary(it, "WALKING", it * minute, it * minute) })

    @Test fun `every finished stay at a cluster is offered oldest first, and no other interval`() {
        val intervals = listOf(
            stayAfter(1, clusterId = 7),
            stayAfter(2, clusterId = 8),
            StayDeriver.Gap(3 * minute, 3 * minute + 1, afterTrackId = 3, from = null, to = null),
            stayAfter(4, clusterId = 7),
        )

        assertEquals(listOf(HiddenStay(1, 2), HiddenStay(4, 5)), HiddenStays.hideableAt(7, intervals, kept))
    }

    @Test fun `a stay with no finished track after it is not offered`() {
        val beforeRecording = listOf(stayAfter(1, clusterId = 7), stayAfter(5, clusterId = 7))
        val goingOnNow = listOf(stayAfter(1, clusterId = 7), stayAfter(5, clusterId = 7, end = null))

        assertEquals(listOf(HiddenStay(1, 2)), HiddenStays.hideableAt(7, beforeRecording, kept))
        assertEquals(listOf(HiddenStay(1, 2)), HiddenStays.hideableAt(7, goingOnNow, kept))
    }
}
