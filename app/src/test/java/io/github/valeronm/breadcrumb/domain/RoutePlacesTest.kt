package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ends are resolved against the domain tests' flat-earth stub (0.001° ≈ 100 m), so fixtures lay the
 * path out and pin places by meters east of an origin and every assertion reasons in meters.
 */
class RoutePlacesTest {

    /** A good point [east] meters east of the origin. */
    private fun pt(east: Double) = TrackPoint(
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(east),
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = 0,
        ignored = false,
    )

    /** A named place pinned [east] meters east of the origin. */
    private fun place(
        id: Long,
        east: Double,
        radiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
    ) = Place(
        id = id,
        label = "Place $id",
        lat = ORIGIN_LAT,
        lon = lonAt(east),
        createdAt = 0,
        radiusM = radiusM,
    )

    /**
     * A path from [fromM] to [toM]. Two fixes and no cadence, deliberately: the rule reads
     * `first()` and `last()`, so no intermediate fix is observable in any assertion here and a
     * fixture that laid some out would imply a path shape the code under test cannot see.
     */
    private fun path(fromM: Double, toM: Double) = listOf(pt(fromM), pt(toM))

    private fun ends(points: List<TrackPoint>, vararg places: Place) =
        RoutePlaces.ends(points, places.toList(), flatDistance).map { it.id }

    @Test
    fun `the places holding each end come back, start first`() {
        assertEquals(
            listOf(7L, 3L),
            // Deliberately given end-first, to pin that the order is the path's and not the list's.
            ends(path(0.0, 5_000.0), place(3, east = 5_000.0), place(7, east = 0.0)),
        )
    }

    @Test
    fun `a place merely passed on the way is not an end`() {
        assertEquals(
            listOf(1L, 3L),
            ends(
                path(0.0, 5_000.0),
                place(1, east = 0.0),
                place(2, east = 2_500.0),
                place(3, east = 5_000.0),
            ),
        )
    }

    @Test
    fun `an end no place covers is left out`() {
        assertEquals(listOf(1L), ends(path(0.0, 5_000.0), place(1, east = 0.0)))
        assertEquals(listOf(3L), ends(path(0.0, 5_000.0), place(3, east = 5_000.0)))
        assertTrue(ends(path(0.0, 5_000.0), place(2, east = 2_500.0)).isEmpty())
    }

    @Test
    fun `a round trip is one place, not the same one twice`() {
        val there = path(0.0, 3_000.0)
        val back = path(3_000.0, 0.0)
        assertEquals(listOf(1L), ends(there + back, place(1, east = 0.0)))
    }

    /**
     * Either side of the radius, never on it: a fixture 150 m from a 150 m pin measures
     * 150.000000000006 under the stub, so an exactly-on-the-edge case would pin double rounding
     * rather than the rule. Inclusivity is [PlaceClusterer]'s to state, this being its predicate.
     */
    @Test
    fun `an end is claimed from inside the radius and not from outside`() {
        val start = path(150.0, 5_000.0)
        assertEquals(listOf(1L), ends(start, place(1, east = 0.0, radiusM = 160.0)))
        assertTrue(ends(start, place(1, east = 0.0, radiusM = 140.0)).isEmpty())
    }

    @Test
    fun `the nearest covering place wins, not the first`() {
        // Both cover the start; the second pin is nearer it.
        assertEquals(
            listOf(2L),
            ends(path(0.0, 5_000.0), place(1, east = -140.0), place(2, east = 60.0)),
        )
    }

    @Test
    fun `a wide place loses an end to a nearer narrow one`() {
        // The venue-scale pin covers the start from 300 m out; the doorway sits 20 m from it.
        assertEquals(
            listOf(2L),
            ends(path(0.0, 5_000.0), place(1, east = 300.0, radiusM = 500.0), place(2, east = 20.0, radiusM = 50.0)),
        )
    }

    @Test
    fun `a single fix is both ends`() {
        assertEquals(listOf(1L), ends(listOf(pt(0.0)), place(1, east = 0.0)))
    }

    @Test
    fun `nothing to walk and nothing to find are both empty`() {
        assertTrue(ends(emptyList(), place(1, east = 0.0)).isEmpty())
        assertTrue(ends(path(0.0, 500.0)).isEmpty())
    }
}
