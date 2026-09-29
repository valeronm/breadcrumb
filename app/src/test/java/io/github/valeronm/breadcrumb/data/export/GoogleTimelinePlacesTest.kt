package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.flatDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTimelinePlacesTest {

    private fun visits(
        placeId: String,
        semanticType: String?,
        count: Int,
        startMs: Long = 0,
        lat: Double = 1.0,
        topLevel: Boolean = true,
    ) = List(count) {
        GoogleTimelineVisit(startMs + it, startMs + it, placeId, semanticType, Coordinate(lat, -2.0), topLevel)
    }

    private fun places(vararg groups: List<GoogleTimelineVisit>) = GoogleTimelinePlaces.places(groups.flatMap { it })

    private fun categoryOf(vararg groups: List<GoogleTimelineVisit>) = places(*groups).single().category

    @Test fun `a place mostly labelled home is a home`() {
        assertEquals(PlaceCategory.HOME, categoryOf(visits("a", "HOME", 3), visits("a", "UNKNOWN", 10)))
    }

    @Test fun `inferred labels vote like stated ones`() {
        assertEquals(PlaceCategory.WORK, categoryOf(visits("a", "INFERRED_WORK", 2)))
        assertEquals(PlaceCategory.HOME, categoryOf(visits("b", "INFERRED_HOME", 2)))
    }

    @Test fun `searched and aliased labels vote against`() {
        assertNull(
            categoryOf(visits("a", "WORK", 8), visits("a", "SEARCHED_ADDRESS", 5), visits("a", "ALIASED_LOCATION", 4)),
        )
    }

    @Test fun `a tie between home and work tags nothing`() {
        assertNull(categoryOf(visits("a", "HOME", 2), visits("a", "WORK", 2)))
    }

    @Test fun `every place with a top-level visit is a place, labelled or not`() {
        val result = places(visits("a", "UNKNOWN", 5), visits("b", null, 5), visits("c", "HOME", 1))
        assertEquals(listOf("a", "b", "c"), result.map { it.placeId })
        assertEquals(listOf(null, null, PlaceCategory.HOME), result.map { it.category })
    }

    @Test fun `a place seen only nested is a place only when it is a home or a work`() {
        assertTrue(places(visits("shop", "UNKNOWN", 3, topLevel = false)).isEmpty())
        assertEquals(PlaceCategory.HOME, categoryOf(visits("flat", "HOME", 3, topLevel = false)))
    }

    @Test fun `the pin is the latest visit's, not the first's`() {
        val result = places(
            visits("a", "HOME", 1, startMs = 100, lat = 1.001),
            visits("a", "HOME", 1, startMs = 50, lat = 1.002),
        )
        assertEquals(1.001, result.single().lat, 0.0)
    }

    @Test fun `several homes are several places`() {
        val result = places(visits("a", "HOME", 2), visits("b", "HOME", 2, lat = 1.05), visits("c", "WORK", 2, lat = 1.1))
        assertEquals(listOf(PlaceCategory.HOME, PlaceCategory.HOME, PlaceCategory.WORK), result.map { it.category })
    }

    private fun candidate(placeId: String, category: PlaceCategory?, lat: Double = 1.0) =
        GoogleTimelinePlaces.Candidate(placeId, category, lat, -2.0)

    private fun existing(id: Long, category: PlaceCategory?, lat: Double = 1.0, radiusM: Double = 150.0) =
        Place(id = id, label = "own", lat = lat, lon = -2.0, createdAt = 0, radiusM = radiusM, category = category?.code)

    private fun match(candidates: List<GoogleTimelinePlaces.Candidate>, places: List<Place>) =
        GoogleTimelinePlaces.matchExisting(candidates, places, flatDistance)

    @Test fun `a home inside an existing home's circle is that home`() {
        assertEquals(mapOf("g" to 7L), match(listOf(candidate("g", PlaceCategory.HOME, lat = 1.001)), listOf(existing(7, PlaceCategory.HOME))))
    }

    @Test fun `of two homes holding the pin, the nearer is the match`() {
        val homes = listOf(existing(1, PlaceCategory.HOME, lat = 1.0), existing(2, PlaceCategory.HOME, lat = 1.0015))
        assertEquals(mapOf("g" to 2L), match(listOf(candidate("g", PlaceCategory.HOME, lat = 1.001)), homes))
    }

    @Test fun `a pin outside the existing circle stands as its own place`() {
        val home = existing(1, PlaceCategory.HOME, radiusM = 50.0)
        assertTrue(match(listOf(candidate("g", PlaceCategory.HOME, lat = 1.001)), listOf(home)).isEmpty())
    }

    @Test fun `only the same category matches, and an untagged candidate never does`() {
        val places = listOf(existing(1, PlaceCategory.WORK), existing(2, null))
        assertTrue(match(listOf(candidate("h", PlaceCategory.HOME), candidate("u", null)), places).isEmpty())
    }
}
