package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a day header adds up. The cases are the decisions: which stays count, how an open one is
 * measured, the two rules that deliberately differ from a stay row's own duration, and the floor a
 * category's summed time has to clear to be reported at all.
 *
 * Durations are in minutes (`MIN`) throughout, because the floor is: a suite written in seconds
 * would sit entirely underneath it and assert nothing.
 */
class DayCategoryTotalsTest {

    private val now = 600 * MIN

    private fun place(category: PlaceCategory?) = PlaceResolver.ResolvedStay(
        place = Place(
            id = 1, label = "Somewhere", lat = 1.0, lon = -2.0, createdAt = 0L, radiusM = 150.0,
            category = category?.code,
        ),
        visitCount = 1,
        centroid = StayDeriver.Endpoint(1.0, -2.0),
    )

    private var nextTrackId = 0L
    private fun stay(
        category: PlaceCategory?,
        start: Long,
        end: Long?,
        resolved: PlaceResolver.ResolvedStay? = place(category),
    ) = TimelineItem.StayItem(
        stay = StayDeriver.Stay(
            start = start, end = end, location = StayDeriver.Endpoint(1.0, -2.0),
            provenance = StayDeriver.Provenance.OBSERVED, afterTrackId = ++nextTrackId, clusterId = 0,
        ),
        place = resolved,
    )

    private fun totals(vararg items: TimelineItem) = dayCategoryTotals(items.toList(), now)
        .associate { it.category to it.durationMs }

    @Test fun `stays of one category sum together`() {
        val totals = totals(
            stay(PlaceCategory.GROCERIES, 10 * MIN, 14 * MIN),
            stay(PlaceCategory.GROCERIES, 20 * MIN, 23 * MIN),
        )
        assertEquals(mapOf(PlaceCategory.GROCERIES to 7 * MIN), totals)
    }

    @Test fun `longest category leads`() {
        val ordered = dayCategoryTotals(
            listOf(
                stay(PlaceCategory.GROCERIES, 10 * MIN, 16 * MIN),
                stay(PlaceCategory.OUTDOORS, 30 * MIN, 50 * MIN),
            ),
            now,
        )
        assertEquals(listOf(PlaceCategory.OUTDOORS, PlaceCategory.GROCERIES), ordered.map { it.category })
    }

    /** The exclusion is the categories' own; the totals must consult it rather than restate it. */
    @Test fun `categories out of time totals contribute nothing`() {
        PlaceCategory.entries.filterNot { it.inTimeTotals }.forEach { excluded ->
            assertEquals(
                "$excluded should not be totalled",
                emptyMap<PlaceCategory, Long>(),
                totals(stay(excluded, 10 * MIN, 90 * MIN)),
            )
        }
    }

    @Test fun `an untagged or unresolved stay contributes nothing`() {
        assertEquals(emptyMap<PlaceCategory, Long>(), totals(stay(null, 10 * MIN, 90 * MIN)))
        assertEquals(
            emptyMap<PlaceCategory, Long>(),
            totals(stay(null, 10 * MIN, 90 * MIN, resolved = null)),
        )
    }

    /** An open stay is still running, so it counts up to the instant the totals are taken. */
    @Test fun `an open stay runs to now`() {
        assertEquals(
            mapOf(PlaceCategory.FOOD to 20 * MIN),
            totals(stay(PlaceCategory.FOOD, now - 20 * MIN, null)),
        )
    }

    /**
     * A stop shorter than a stay row would quote (`REPORTABLE_DURATION_MS`) still counts here: it is
     * time spent, and the floor below is on the category's total, not on any one stop.
     */
    @Test fun `stops too short to quote on their rows still sum into a reported total`() {
        val brief = StayDeriver.REPORTABLE_DURATION_MS - 5_000L
        val stops = (1..6).map { stay(PlaceCategory.SHOPPING, it * 10 * MIN, it * 10 * MIN + brief) }
        assertEquals(mapOf(PlaceCategory.SHOPPING to brief * 6), totals(*stops.toTypedArray()))
    }

    /** A day's passing brush with a category says nothing about the day's shape. */
    @Test fun `a category under the floor is dropped while the rest of the day stands`() {
        assertEquals(
            mapOf(PlaceCategory.OUTDOORS to 90 * MIN),
            totals(
                stay(PlaceCategory.OUTDOORS, 10 * MIN, 100 * MIN),
                stay(PlaceCategory.FOOD, 120 * MIN, 124 * MIN),
            ),
        )
    }

    /** The floor is inclusive — five minutes is reported, and only under it disappears. */
    @Test fun `a total of exactly the floor is kept`() {
        assertEquals(
            mapOf(PlaceCategory.FOOD to 5 * MIN),
            totals(stay(PlaceCategory.FOOD, 10 * MIN, 15 * MIN)),
        )
        assertEquals(
            emptyMap<PlaceCategory, Long>(),
            totals(stay(PlaceCategory.FOOD, 10 * MIN, 15 * MIN - 1)),
        )
    }

    /** Tracks and gaps share the day's item list and are not stays; only stays are totalled. */
    @Test fun `non-stay items are ignored`() {
        val gap = TimelineItem.GapItem(
            StayDeriver.Gap(
                start = 1 * MIN, end = 2 * MIN, reason = StayDeriver.GapReason.MOVED_UNRECORDED,
                afterTrackId = 99, fromClusterId = null, toClusterId = null,
            ),
        )
        assertEquals(
            mapOf(PlaceCategory.GROCERIES to 10 * MIN),
            totals(gap, stay(PlaceCategory.GROCERIES, 30 * MIN, 40 * MIN)),
        )
    }
}
