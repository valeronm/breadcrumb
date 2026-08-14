package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyTotalsTest {

    private val hour = 3_600_000L

    private fun stayItem(start: Long, end: Long, category: PlaceCategory) = TimelineItem.StayItem(
        stay = StayDeriver.Stay(start = start, end = end, afterTrackId = start, clusterId = 0),
        place = PlaceResolver.ResolvedStay(
            place = Place(
                id = 1L, label = "Somewhere", lat = ORIGIN_LAT, lon = ORIGIN_LON,
                createdAt = 0L, radiusM = 400.0, category = category.code,
            ),
            visitCount = 1,
            centroid = Coordinate(ORIGIN_LAT, ORIGIN_LON),
        ),
    )

    @Test fun `activities rank by distance, categories by time`() {
        val items = listOf(
            TimelineItem.TrackItem(trackSummary(1, "walking", 0, hour, meters = 2_000.0)),
            TimelineItem.TrackItem(trackSummary(2, "driving", hour, 2 * hour, meters = 30_000.0)),
            stayItem(2 * hour, 3 * hour, PlaceCategory.TRAVEL),
            stayItem(3 * hour, 9 * hour, PlaceCategory.SIGHTSEEING),
        )

        val totals = journeyTotals(items, nowMs = 9 * hour)

        assertEquals(listOf("driving", "walking"), totals.activities.map { it.activityType })
        assertEquals(
            listOf(PlaceCategory.SIGHTSEEING, PlaceCategory.TRAVEL),
            totals.categories.map { it.category },
        )
    }

    @Test fun `a stop too brief for the day's floor still earns its journey row`() {
        val brief = listOf(stayItem(0L, 2 * 60_000L, PlaceCategory.TRAVEL))

        assertEquals(2 * 60_000L, journeyTotals(brief, nowMs = hour).categories.single().durationMs)
    }
}
