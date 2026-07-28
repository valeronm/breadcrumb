package io.github.valeronm.breadcrumb.domain

/** How long one day went to places of one category — the staying half of a day's totals. */
class DayCategoryTotal(val category: PlaceCategory, val durationMs: Long)

/**
 * Time per category over one day's stays, longest first — what a timeline day header reports under
 * the distances. The intervals arrive already sliced at midnight ([StayDeriver.slicePerDay]), so a
 * stay's bounds here are its share of *this* day and summing them is the day's total. Two rules
 * differ from a stay row's on purpose — the difference between describing one stop and a day: a
 * midnight-sliced bound doesn't suppress the duration (the row hides one because it would merely
 * restate its own clock times, while the total asks how much of the day went here — a night at
 * home is the day's hours from midnight), and a stop too short to quote on its own row still
 * counts (it is time spent either way, and rounding it away would leave the totals short of the
 * day they describe — so no [StayDeriver.Interval.reportableDurationMs] floor here). An open stay
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
        .sortedByDescending { it.durationMs }
