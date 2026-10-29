package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlacesMapFilterTest {

    @Test fun `with both chips on the places pass through as the same list`() {
        val places = listOf(place(visits = 1, lastSeen = OLD), place(visits = 5, lastSeen = RECENT))
        val filter = filterPlacesMap(places, showRareStops = true, showLongAgo = true, longAgoBeforeMs = SINCE)
        assertSame(places, filter.visible)
        assertNull(filter.emptiedBy)
    }

    @Test fun `the rare-stops chip off hides places below the notable floor`() {
        val rare = place(visits = PlaceResolver.NOTABLE_VISIT_MIN - 1, lastSeen = RECENT)
        val notable = place(visits = PlaceResolver.NOTABLE_VISIT_MIN, lastSeen = RECENT)
        val filter = filterPlacesMap(listOf(rare, notable), showRareStops = false, showLongAgo = true, longAgoBeforeMs = SINCE)
        assertEquals(listOf(notable), filter.visible)
    }

    @Test fun `the long-ago chip off keeps a place last seen at or after the cutoff`() {
        val atCutoff = place(visits = 5, lastSeen = SINCE)
        val before = place(visits = 5, lastSeen = SINCE - 1)
        val filter = filterPlacesMap(listOf(atCutoff, before), showRareStops = true, showLongAgo = false, longAgoBeforeMs = SINCE)
        assertEquals(listOf(atCutoff), filter.visible)
    }

    @Test fun `the long-ago chip off hides a place with no visit`() {
        val unvisited = place(visits = 0, lastSeen = null)
        val filter = filterPlacesMap(listOf(unvisited), showRareStops = true, showLongAgo = false, longAgoBeforeMs = SINCE)
        assertEquals(emptyList<PlaceResolver.PlaceSummary>(), filter.visible)
    }

    @Test fun `a place must pass both chips`() {
        val recentRare = place(visits = 1, lastSeen = RECENT)
        val oldNotable = place(visits = 5, lastSeen = OLD)
        val recentNotable = place(visits = 5, lastSeen = RECENT)
        val filter = filterPlacesMap(
            listOf(recentRare, oldNotable, recentNotable),
            showRareStops = false,
            showLongAgo = false, longAgoBeforeMs = SINCE,
        )
        assertEquals(listOf(recentNotable), filter.visible)
    }

    @Test fun `every place long ago is emptied by the long-ago chip even with rare stops hidden`() {
        val places = listOf(place(visits = 1, lastSeen = OLD), place(visits = 5, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, showLongAgo = false, longAgoBeforeMs = SINCE)
        assertEquals(PlacesMapFilter.Emptied.LONG_AGO, filter.emptiedBy)
    }

    @Test fun `places since the cutoff that are all rare are emptied by the rare-stops chip`() {
        val places = listOf(place(visits = 1, lastSeen = RECENT), place(visits = 5, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, showLongAgo = false, longAgoBeforeMs = SINCE)
        assertEquals(PlacesMapFilter.Emptied.RARE_STOPS, filter.emptiedBy)
    }

    @Test fun `every place rare with the long-ago chip on is emptied by the rare-stops chip`() {
        val places = listOf(place(visits = 1, lastSeen = OLD))
        val filter = filterPlacesMap(places, showRareStops = false, showLongAgo = true, longAgoBeforeMs = SINCE)
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
