package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class NearbyPlacesTest {
    private val flat = DistanceFn { aLat, aLon, bLat, bLon -> hypot(bLat - aLat, bLon - aLon) }
    private fun place(id: Long, lat: Double, lon: Double = 0.0, radiusM: Double = 150.0, label: String? = "P$id") =
        Place(id = id, label = label, lat = lat, lon = lon, createdAt = 0L, radiusM = radiusM)
    private val arrival = Coordinate(0.0, 0.0)
    private val departure = Coordinate(400.0, 0.0)

    @Test fun `a place within reach of either end is offered, nearest first`() {
        val near = place(1, 50.0)
        val byDeparture = place(2, 650.0)
        val far = place(3, 1000.0)
        val got = NearbyPlaces.around(listOf(arrival, departure), listOf(far, byDeparture, near), null, flat)
        assertEquals(listOf(1L, 2L), got.map { it.place.id })
        assertEquals(listOf(50.0, 250.0), got.map { it.distanceM })
    }

    @Test fun `the reach includes its boundary`() {
        assertEquals(1, NearbyPlaces.around(listOf(arrival), listOf(place(1, 300.0)), null, flat).size)
        assertEquals(0, NearbyPlaces.around(listOf(arrival), listOf(place(1, 300.5)), null, flat).size)
    }

    @Test fun `a place pinned beyond reach whose circle covers an end is offered`() {
        val got = NearbyPlaces.around(listOf(arrival, departure), listOf(place(1, 1000.0, radiusM = 700.0)), null, flat)
        assertEquals(listOf(1L), got.map { it.place.id })
        assertEquals(listOf(600.0), got.map { it.distanceM })
    }

    @Test fun `a place pinned beyond reach whose circle misses both ends is not offered`() {
        val got = NearbyPlaces.around(listOf(arrival, departure), listOf(place(1, 1000.0, radiusM = 500.0)), null, flat)
        assertTrue(got.isEmpty())
    }

    @Test fun `the circle includes its edge`() {
        assertEquals(1, NearbyPlaces.around(listOf(arrival), listOf(place(1, 500.0, radiusM = 500.0)), null, flat).size)
        assertEquals(0, NearbyPlaces.around(listOf(arrival), listOf(place(1, 500.5, radiusM = 500.0)), null, flat).size)
    }

    @Test fun `unnamed rows are offered too`() {
        val got = NearbyPlaces.around(listOf(arrival), listOf(place(1, 10.0, label = null)), null, flat)
        assertEquals(listOf(1L), got.map { it.place.id })
    }

    @Test fun `the place the stay resolves to is offered however far it lies`() {
        val got = NearbyPlaces.around(listOf(arrival), listOf(place(1, 5000.0), place(2, 6000.0)), currentId = 2, flat)
        assertEquals(listOf(2L), got.map { it.place.id })
        assertEquals(listOf(true), got.map { it.current })
    }

    @Test fun `the place the stay resolves to is marked current`() {
        val got = NearbyPlaces.around(listOf(arrival), listOf(place(1, 10.0), place(2, 20.0)), currentId = 2, flat)
        assertEquals(listOf(false, true), got.map { it.current })
    }

    @Test fun `a stay going on now is measured from its arrival alone`() {
        val got = NearbyPlaces.around(listOf(arrival), listOf(place(1, 650.0)), null, flat)
        assertTrue(got.isEmpty())
    }
}
