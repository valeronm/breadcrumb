package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceOverlapTest {

    /** A row [meters] east of the origin under [flatDistance]. */
    private fun place(id: Long, meters: Double, radiusM: Double = 150.0) =
        Place(id = id, label = null, lat = ORIGIN_LAT, lon = lonAt(meters), createdAt = 0, radiusM = radiusM)

    private fun candidates(of: Place, vararg others: Place) =
        PlaceOverlap.candidatesFor(of, listOf(of) + others, flatDistance).map { it.id }

    @Test fun `circles closer than their radii summed overlap, nearest first`() {
        assertEquals(listOf(3L, 2L), candidates(place(1, 0.0), place(2, 250.0), place(3, 40.0)))
    }

    @Test fun `circles that only touch do not overlap`() {
        val exact = DistanceFn { _, _, _, _ -> 300.0 }
        val a = place(1, 0.0, radiusM = 150.0)
        val b = place(2, 0.0, radiusM = 150.0)
        assertTrue(PlaceOverlap.candidatesFor(a, listOf(a, b), exact).isEmpty())
    }

    @Test fun `each circle brings its own radius`() {
        assertEquals(listOf(2L), candidates(place(1, 0.0, radiusM = 50.0), place(2, 240.0, radiusM = 200.0)))
        assertTrue(candidates(place(1, 0.0, radiusM = 50.0), place(2, 260.0, radiusM = 200.0)).isEmpty())
    }

    @Test fun `the place is never its own candidate`() {
        val self = place(1, 0.0)
        assertTrue(PlaceOverlap.candidatesFor(self, listOf(self, self.copy()), flatDistance).isEmpty())
    }
}
