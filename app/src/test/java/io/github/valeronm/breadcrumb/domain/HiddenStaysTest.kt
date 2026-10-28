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
}
