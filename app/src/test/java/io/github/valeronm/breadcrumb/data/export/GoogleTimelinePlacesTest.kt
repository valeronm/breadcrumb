package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.domain.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTimelinePlacesTest {

    private fun visits(placeId: String, semanticType: String?, count: Int, startMs: Long = 0, lat: Double = 1.0) =
        List(count) { GoogleTimelineVisit(startMs + it, placeId, semanticType, lat, -2.0) }

    private fun vote(vararg groups: List<GoogleTimelineVisit>) = GoogleTimelinePlaces.vote(groups.flatMap { it })

    @Test fun `a place mostly labelled home becomes a home`() {
        val result = vote(visits("a", "HOME", 3), visits("a", "UNKNOWN", 10))
        assertEquals(PlaceCategory.HOME, result.single().category)
    }

    @Test fun `inferred labels vote like stated ones`() {
        assertEquals(PlaceCategory.WORK, vote(visits("a", "INFERRED_WORK", 2)).single().category)
        assertEquals(PlaceCategory.HOME, vote(visits("b", "INFERRED_HOME", 2)).single().category)
    }

    @Test fun `searched and aliased labels vote against`() {
        assertTrue(
            vote(visits("a", "WORK", 8), visits("a", "SEARCHED_ADDRESS", 5), visits("a", "ALIASED_LOCATION", 4))
                .isEmpty(),
        )
    }

    @Test fun `a tie between home and work creates nothing`() {
        assertTrue(vote(visits("a", "HOME", 2), visits("a", "WORK", 2)).isEmpty())
    }

    @Test fun `a place never labelled creates nothing`() {
        assertTrue(vote(visits("a", "UNKNOWN", 5), visits("b", null, 5)).isEmpty())
    }

    @Test fun `the pin is the latest visit's, not the first's`() {
        val result = vote(
            visits("a", "HOME", 1, startMs = 100, lat = 1.001),
            visits("a", "HOME", 1, startMs = 50, lat = 1.002),
        )
        assertEquals(1.001, result.single().lat, 0.0)
    }

    @Test fun `several homes are several places`() {
        val result = vote(visits("a", "HOME", 2), visits("b", "HOME", 2, lat = 1.05), visits("c", "WORK", 2, lat = 1.1))
        assertEquals(listOf(PlaceCategory.HOME, PlaceCategory.HOME, PlaceCategory.WORK), result.map { it.category })
    }
}
