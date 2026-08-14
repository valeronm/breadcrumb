package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneySliceTest {

    private val hour = 3_600_000L

    private fun stayItem(start: Long, end: Long?, clusterId: Int = 0, category: PlaceCategory? = null) =
        TimelineItem.StayItem(
            stay = StayDeriver.Stay(start = start, end = end, afterTrackId = start, clusterId = clusterId),
            place = category?.let {
                PlaceResolver.ResolvedStay(
                    place = Place(
                        id = 1L, label = "Somewhere", lat = ORIGIN_LAT, lon = ORIGIN_LON,
                        createdAt = 0L, radiusM = 400.0, category = it.code,
                    ),
                    visitCount = 1,
                    centroid = Coordinate(ORIGIN_LAT, ORIGIN_LON),
                )
            },
        )

    private fun trackItem(id: Long, start: Long, end: Long) =
        TimelineItem.TrackItem(trackSummary(id, "walking", start, end, meters = 1000.0))

    @Test fun `a stay straddling the window edge keeps only its hours inside`() {
        val sliced = JourneySlice.itemsWithin(
            listOf(stayItem(start = 0L, end = 10 * hour)),
            windowStart = 4 * hour,
            windowEnd = 24 * hour,
            nowMs = 24 * hour,
        )

        val item = sliced.single() as TimelineItem.StayItem
        assertEquals(4 * hour, item.stay.start)
        assertEquals(10 * hour, item.stay.end)
        // The clamping moved the start, so the row no longer holds it — as slicePerDay stamps cuts.
        assertEquals(false, item.holdsStart)
        assertEquals(true, item.holdsEnd)
    }

    @Test fun `rows wholly outside the window are not the journey's`() {
        // Newest first, as the timeline runs — the slicing leans on it.
        val sliced = JourneySlice.itemsWithin(
            listOf(
                trackItem(2L, start = 5 * hour, end = 6 * hour),
                stayItem(start = 2 * hour, end = 3 * hour),
                trackItem(1L, start = 0L, end = 2 * hour),
            ),
            windowStart = 4 * hour,
            windowEnd = 24 * hour,
            nowMs = 24 * hour,
        )

        assertEquals(listOf(2L), sliced.filterIsInstance<TimelineItem.TrackItem>().map { it.summary.id })
        assertTrue(sliced.filterIsInstance<TimelineItem.StayItem>().isEmpty())
    }

    @Test fun `a track straddling the edge is kept whole`() {
        val sliced = JourneySlice.itemsWithin(
            listOf(trackItem(1L, start = 2 * hour, end = 6 * hour)),
            windowStart = 4 * hour,
            windowEnd = 24 * hour,
            nowMs = 24 * hour,
        )

        val track = (sliced.single() as TimelineItem.TrackItem).summary
        assertEquals(2 * hour, track.startedAt)
        assertEquals(6 * hour, track.endedAt)
    }

    @Test fun `an ongoing stay is closed at the window's end`() {
        val sliced = JourneySlice.itemsWithin(
            listOf(stayItem(start = 20 * hour, end = null)),
            windowStart = 4 * hour,
            windowEnd = 24 * hour,
            nowMs = 30 * hour,
        )

        val item = sliced.single() as TimelineItem.StayItem
        assertEquals(24 * hour, item.stay.end)
        // The end was materialized by the clamp, not observed — the row must not claim it.
        assertEquals(false, item.holdsEnd)
    }

    @Test fun `totals over the slice credit only the journey's share`() {
        val items = listOf(
            trackItem(1L, start = 5 * hour, end = 6 * hour),
            stayItem(start = 0L, end = 10 * hour, category = PlaceCategory.TRAVEL),
        )

        val sliced = JourneySlice.itemsWithin(items, windowStart = 4 * hour, windowEnd = 24 * hour, nowMs = 24 * hour)
        val totals = TimelineTotalsBuilder().apply { addAll(sliced, nowMs = 24 * hour) }.build()

        assertEquals(hour, totals.activities.getValue("walking").durationMs)
        assertEquals(6 * hour, totals.categories.getValue(PlaceCategory.TRAVEL).durationMs)
    }
}
