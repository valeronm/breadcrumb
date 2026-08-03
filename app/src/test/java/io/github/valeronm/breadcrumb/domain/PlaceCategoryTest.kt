package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The codes are the stored vocabulary — they reach the DB column and the backup format the web
 * viewer reads — so what is pinned here is their stability, not the labels beside them.
 */
class PlaceCategoryTest {

    private fun place(category: String?) =
        Place(id = 1, label = "Somewhere", lat = 1.0, lon = -2.0, createdAt = 0L, radiusM = 150.0, category = category)

    @Test fun `every code round-trips`() {
        PlaceCategory.entries.forEach { category ->
            assertEquals(category, PlaceCategory.fromCode(category.code))
        }
    }

    @Test fun `codes are unique`() {
        val codes = PlaceCategory.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    /**
     * A code this build doesn't know reads as untagged rather than throwing — that is what lets a
     * backup from a later version restore here. The column keeps the raw string either way, so the
     * value survives the round trip even while this build can't name it.
     */
    @Test fun `an unknown code reads as untagged`() {
        assertNull(PlaceCategory.fromCode("laundromat"))
        assertNull(place("laundromat").placeCategory)
        assertEquals("laundromat", place("laundromat").category)
    }

    @Test fun `no category at all is untagged`() {
        assertNull(PlaceCategory.fromCode(null))
        assertNull(place(null).placeCategory)
    }

    @Test fun `a stored code resolves through the place`() {
        assertEquals(PlaceCategory.GROCERIES, place("groceries").placeCategory)
    }

    /**
     * The colour grouping, pinned as a whole rather than per category: it is a statement about which
     * stops are *alike*, so moving one category between groups is a decision to make deliberately —
     * and every category must land in exactly one group, or a place would have no colour to wear.
     */
    @Test fun `the groups partition the categories`() {
        assertEquals(
            mapOf(
                PlaceCategoryGroup.HOME_PEOPLE to listOf(PlaceCategory.FRIENDS_FAMILY, PlaceCategory.HOME),
                PlaceCategoryGroup.ERRANDS to listOf(
                    PlaceCategory.GROCERIES, PlaceCategory.FOOD, PlaceCategory.SHOPPING,
                    PlaceCategory.SERVICES, PlaceCategory.HEALTH,
                ),
                PlaceCategoryGroup.ROUTINE to listOf(
                    PlaceCategory.KIDS_SCHOOL, PlaceCategory.SPORTS, PlaceCategory.WORK,
                ),
                PlaceCategoryGroup.AWAY to listOf(
                    PlaceCategory.OUTDOORS, PlaceCategory.SIGHTSEEING, PlaceCategory.TRAVEL,
                    PlaceCategory.ENTERTAINMENT,
                ),
                PlaceCategoryGroup.TRANSIENT to listOf(
                    PlaceCategory.PARKING, PlaceCategory.TRANSIT,
                    PlaceCategory.GAS_STATION, PlaceCategory.SERVICE_AREA,
                ),
            ),
            PlaceCategory.entries.groupBy { it.group },
        )
    }

    /**
     * Who stays out of the time totals, pinned: home, the baseline a day returns to, would dwarf the
     * line it shares; a car park and a fuel stop are waypoints to the thing, nothing of their own to
     * total. All three still tag places and label stays — a change here changes what the day header reads.
     */
    @Test fun `only home and the transient stops stay out of time totals`() {
        assertEquals(
            listOf(
                PlaceCategory.HOME, PlaceCategory.PARKING, PlaceCategory.TRANSIT,
                PlaceCategory.GAS_STATION, PlaceCategory.SERVICE_AREA,
            ),
            PlaceCategory.entries.filterNot { it.inTimeTotals },
        )
    }

    /**
     * Which stops are the road rather than a place on it, pinned: fuel, a service area and a
     * transit stop name no journey and count as no city visited. **A car park is not one of
     * them** — a history recorded by car has the car park standing for the city it sits in, and
     * dropping it would cost that city its name.
     */
    @Test fun `only the roadside stops go unvisited`() {
        assertEquals(
            listOf(PlaceCategory.TRANSIT, PlaceCategory.GAS_STATION, PlaceCategory.SERVICE_AREA),
            PlaceCategory.entries.filterNot { it.visited },
        )
    }
}
