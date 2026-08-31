package io.github.valeronm.breadcrumb.domain

/**
 * A journey's share of the timeline: the rows overlapping its window, stays clamped to it so a
 * total over them credits only the journey's hours. The order of [TimelineItem]s in is the order
 * out.
 *
 * A bound the clamping moved clears its `holdsStart`/`holdsEnd` flag, exactly as
 * [TimelineRows.slicePerDay] stamps the bounds its own cuts put there — a clamped row states which
 * of its bounds are the stay's own, the same way every other sliced row does.
 *
 * A track is kept whole or not at all: its distance is one measurement over the whole path, and a
 * share of it proportional to time would be invented, not measured. The overcount that admits is
 * bounded by the one track straddling each edge — and a journey's window normally opens at the end
 * of a home stay, which is where the departing track begins.
 *
 * Gaps pass through untouched; they total nothing and place nothing.
 */
object JourneySlice {

    /** [items] must run newest first, as the timeline emits them — the walk below slices on it. */
    fun itemsWithin(
        items: List<TimelineItem>,
        windowStart: Long,
        windowEnd: Long,
        nowMs: Long,
    ): List<TimelineItem> {
        val within = ArrayList<TimelineItem>()
        // The rows tile time without overlapping (tracks and the intervals between them share only
        // their boundary instants) and arrive newest first, so both bounds walk monotonically:
        // everything after the window is a prefix to skip, everything before it a suffix to stop
        // at — a slice, not a walk of the whole history per reading.
        for (item in items) {
            if (item.startedAt >= windowEnd) continue
            val end = when (item) {
                is TimelineItem.TrackItem -> item.summary.endedAt ?: nowMs
                is TimelineItem.StayItem -> item.stay.end ?: nowMs
                is TimelineItem.GapItem -> item.gap.end
            }
            if (end <= windowStart) break
            // A zero-length row — a split's seam — overlaps nothing and would still count a visit.
            if (end <= item.startedAt) continue
            within += if (item is TimelineItem.StayItem) clamped(item, end, windowStart, windowEnd) else item
        }
        return within
    }

    /** [item] with its bounds held to the window — or itself, untouched, when both already are. */
    private fun clamped(
        item: TimelineItem.StayItem,
        end: Long,
        windowStart: Long,
        windowEnd: Long,
    ): TimelineItem.StayItem {
        val holdsStart = item.stay.start >= windowStart
        // An open stay's end is materialized here, which is as put-there as a clamp.
        val holdsEnd = item.stay.end != null && item.stay.end <= windowEnd
        if (holdsStart && holdsEnd) return item
        return item.copy(
            stay = item.stay.copy(
                start = maxOf(item.stay.start, windowStart),
                end = minOf(end, windowEnd),
            ),
            holdsStart = item.holdsStart && holdsStart,
            holdsEnd = item.holdsEnd && holdsEnd,
        )
    }
}
