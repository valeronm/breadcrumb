package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a day header adds up. The cases are the decisions: which stays count, how an open one is
 * measured, and the two rules that deliberately differ from a stay row's own duration.
 */
class DayCategoryTotalsTest {

    private val now = 100_000L

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
            stay(PlaceCategory.GROCERIES, 1_000, 2_000),
            stay(PlaceCategory.GROCERIES, 5_000, 5_500),
        )
        assertEquals(mapOf(PlaceCategory.GROCERIES to 1_500L), totals)
    }

    @Test fun `longest category leads`() {
        val ordered = dayCategoryTotals(
            listOf(
                stay(PlaceCategory.GROCERIES, 1_000, 2_000),
                stay(PlaceCategory.OUTDOORS, 3_000, 9_000),
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
                totals(stay(excluded, 1_000, 9_000)),
            )
        }
    }

    @Test fun `an untagged or unresolved stay contributes nothing`() {
        assertEquals(emptyMap<PlaceCategory, Long>(), totals(stay(null, 1_000, 9_000)))
        assertEquals(
            emptyMap<PlaceCategory, Long>(),
            totals(stay(null, 1_000, 9_000, resolved = null)),
        )
    }

    /** An open stay is still running, so it counts up to the instant the totals are taken. */
    @Test fun `an open stay runs to now`() {
        assertEquals(
            mapOf(PlaceCategory.FOOD to now - 40_000L),
            totals(stay(PlaceCategory.FOOD, 40_000, null)),
        )
    }

    /**
     * A stop shorter than a stay row would quote (`REPORTABLE_DURATION_MS`) still counts here: it is
     * time spent, and dropping it would leave the day's totals short of the day.
     */
    @Test fun `a stop too short to quote on its row still counts`() {
        val brief = 5_000L
        assertEquals(
            mapOf(PlaceCategory.GROCERIES to brief),
            totals(stay(PlaceCategory.GROCERIES, 1_000, 1_000 + brief)),
        )
    }

    /** Tracks and gaps share the day's item list and are not stays; only stays are totalled. */
    @Test fun `non-stay items are ignored`() {
        val gap = TimelineItem.GapItem(
            StayDeriver.Gap(
                start = 1_000, end = 2_000, reason = StayDeriver.GapReason.MOVED_UNRECORDED,
                afterTrackId = 99, fromClusterId = null, toClusterId = null,
            ),
        )
        assertEquals(
            mapOf(PlaceCategory.GROCERIES to 1_000L),
            totals(gap, stay(PlaceCategory.GROCERIES, 3_000, 4_000)),
        )
    }
}
