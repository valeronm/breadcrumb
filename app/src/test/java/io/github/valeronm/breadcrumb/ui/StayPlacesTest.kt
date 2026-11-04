package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StayPlacesTest {

    @Test fun `only the places a shown stay landed at are drawn`() {
        val visited = summary(place = null, lat = 1.0)
        val elsewhere = summary(place = null, lat = 1.5)

        val drawn = stayPlacesOf(setOf(visited.key), listOf(visited, elsewhere), mergeableAnchors = emptySet())
        assertEquals(listOf(visited.key), drawn.map { it.key })
    }

    @Test fun `a named place draws its reach and an unnamed one none`() {
        val named = summary(place = Place(id = 1, label = "Somewhere", lat = 1.0, lon = -2.0, createdAt = 0L, radiusM = 180.0))
        val unnamed = summary(place = null, lat = 1.5)

        val drawn = stayPlacesOf(setOf(named.key, unnamed.key), listOf(named, unnamed), mergeableAnchors = emptySet())
            .associateBy { it.key }
        assertEquals(180.0, drawn.getValue(named.key).radiusM)
        assertNull(drawn.getValue(unnamed.key).radiusM)
    }

    @Test fun `an unnamed place whose only stay can be merged away draws as a brief stop`() {
        val brief = summary(place = null, lat = 1.0, stays = listOf(stay(afterTrackId = 7)))
        val kept = summary(place = null, lat = 1.5, stays = listOf(stay(afterTrackId = 8)))

        val drawn = stayPlacesOf(setOf(brief.key, kept.key), listOf(brief, kept), mergeableAnchors = setOf(7L))
            .associateBy { it.key }
        assertEquals(UnnamedDot.BRIEF, drawn.getValue(brief.key).dot)
        assertEquals(UnnamedDot.CLUSTER, drawn.getValue(kept.key).dot)
    }

    private fun stay(afterTrackId: Long) = StayDeriver.Stay(start = 0, end = 60_000, afterTrackId = afterTrackId, clusterId = 0)

    private fun summary(place: Place?, lat: Double = 1.0, stays: List<StayDeriver.Stay> = emptyList()) =
        PlaceResolver.PlaceSummary(
            place = place,
            visitCount = stays.size,
            lastSeenMs = null,
            totalMs = 0,
            anchor = place?.let { Coordinate(it.lat, it.lon) } ?: Coordinate(lat = lat, lon = -2.0),
            radiusM = place?.radiusM ?: 100.0,
            endpoints = emptyList(),
            endpointCentroid = null,
            stays = stays,
        )
}
