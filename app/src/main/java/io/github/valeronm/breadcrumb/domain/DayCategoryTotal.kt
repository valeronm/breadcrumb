package io.github.valeronm.breadcrumb.domain

/** How long one day went to places of one category — the staying half of a day's totals. */
class DayCategoryTotal(val category: PlaceCategory, val durationMs: Long)

/**
 * A category the day gave less than this to doesn't earn a place in its totals: the line answers
 * what the day went to, and a chip is as wide whether it reports six hours or two minutes, so the
 * short ones cost the reading exactly what the long ones do while saying nothing about the day's
 * shape. The floor is on the **summed** total, not on any one stop — several brief errands still
 * add up to an errand-shaped afternoon, which is the case that would be wrong to hide.
 */
private const val REPORTED_TOTAL_FLOOR_MS = 5 * 60_000L

/**
 * Time per category over one day's stays, longest first — what a timeline day header reports under
 * the distances. The intervals arrive already sliced at midnight ([StayDeriver.slicePerDay]), so a
 * stay's bounds here are its share of *this* day and summing them is the day's total. Two rules
 * differ from a stay row's on purpose — the difference between describing one stop and a day: a
 * midnight-sliced bound doesn't suppress the duration (the row hides one because it would merely
 * restate its own clock times, while the total asks how much of the day went here — a night at
 * home is the day's hours from midnight), and a stop too short to quote on its own row still
 * counts toward its category (so no [StayDeriver.Stay.reportableDurationMs] floor on the
 * stays). What is dropped is a *category* under [REPORTED_TOTAL_FLOOR_MS], once summed — so these
 * totals deliberately don't add up to the day, and are a summary rather than a ledger. An open stay
 * runs to [nowMs]. Untagged and unnamed stays contribute nothing — there is no category to
 * attribute them to — nor do the categories [PlaceCategory.inTimeTotals] excludes.
 */
fun dayCategoryTotals(items: List<TimelineItem>, nowMs: Long): List<DayCategoryTotal> =
    items.filterIsInstance<TimelineItem.StayItem>()
        .mapNotNull { item -> item.place?.category?.takeIf { it.inTimeTotals }?.let { it to item.stay } }
        .groupBy({ (category, _) -> category }, { (_, stay) -> stay })
        .map { (category, stays) ->
            DayCategoryTotal(category, stays.sumOf { (it.end ?: nowMs) - it.start })
        }
        .filter { it.durationMs >= REPORTED_TOTAL_FLOOR_MS }
        .sortedByDescending { it.durationMs }
