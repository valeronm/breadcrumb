package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlacesMapFilterTest {

    @Test fun `with both chips off the places pass through as the same list`() {
        val places = listOf(place(visits = 1, lastSeen = OLD), place(visits = 5, lastSeen = RECENT))
        val filter = filterPlacesMap(places, showRareStops = true, visitedSinceMs = null)
        assertSame(places, filter.visible)
        assertNull(filter.emptiedBy)
    }

    @Test fun `the rare-stops chip off hides places below the notable floor`() {
        val rare = place(visits = PlaceResolver.NOTABLE_VISIT_MIN - 1, lastSeen = RECENT)
        val notable = place(visits = PlaceResolver.NOTABLE_VISIT_MIN, lastSeen = RECENT)
        val filter = filterPlacesMap(listOf(rare, notable), showRareStops = false, visitedSinceMs = null)
        assertEquals(listOf(notable), filter.visible)
    }

    @Test fun `the past-year chip keeps a place last seen at or after the cutoff`() {
        val atCutoff = place(visits = 5, lastSeen = SINCE)
        val before = place(visits = 5, lastSeen = SINCE - 1)
        val filter = filterPlacesMap(listOf(atCutoff, before), showRareStops = true, visitedSinceMs = SINCE)
        assertEquals(listOf(atCutoff), filter.visible)
    }

    @Test fun `the past-year chip hides a place with no visit`() {
        val unvisited = place(visits = 0, lastSeen = null)
        val filter = filterPlacesMap(listOf(unvisited), showRareStops = true, visitedSinceMs = SINCE)
        assertEquals(emptyList<PlaceResolver.PlaceSummary>(), filter.visible)
    }

    @Test fun `a place must pass both chips`() {
        val recentRare = place(visits = 1, lastSeen = RECENT)
        val oldNotable = place(visits = 5, lastSeen = OLD)
        val recentNotable = place(visits = 5, lastSeen = RECENT)
        val filter = filterPlacesMap(
            listOf(recentRare, oldNotable, recentNotable),
            showRareStops = false,
            visitedSinceMs = SINCE,
        )
        assertEquals(listOf(recentNotable), filter.visible)
    }

    @Test fun `nothing visited in the past year is emptied by that chip even with rare stops hidden`() {
        val places = listOf(place(visits = 1, lastSeen = OLD), place(visits = 5, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, visitedSinceMs = SINCE)
        assertEquals(PlacesMapFilter.Emptied.PAST_YEAR, filter.emptiedBy)
    }

    @Test fun `recent places that are all rare are emptied by the rare-stops chip`() {
        val places = listOf(place(visits = 1, lastSeen = RECENT), place(visits = 5, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, visitedSinceMs = SINCE)
        assertEquals(PlacesMapFilter.Emptied.RARE_STOPS, filter.emptiedBy)
    }

    @Test fun `every place rare with the past-year chip off is emptied by the rare-stops chip`() {
        val places = listOf(place(visits = 1, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, visitedSinceMs = null)
        assertEquals(PlacesMapFilter.Emptied.RARE_STOPS, filter.emptiedBy)
    }

    private companion object {
        const val SINCE = 1_000_000L
        const val RECENT = SINCE + 1_000L
        const val OLD = SINCE - 1_000L

        fun place(visits: Int, lastSeen: Long?) = PlaceResolver.PlaceSummary(
            place = null,
            visitCount = visits,
            lastSeenMs = lastSeen,
            totalMs = 0,
            anchor = Coordinate(lat = 1.0, lon = -2.0),
            radiusM = 100.0,
            endpoints = emptyList(),
            endpointCentroid = null,
        )
    }
}
