package io.github.valeronm.breadcrumb.domain

/**
 * What swiping a stay row does: merge the tracks either side where [TrackMerge] plans it, since a
 * merge already takes the stay away, and otherwise hide the stay.
 */
sealed interface StaySwipe {
    data class Merge(val plan: TrackMerge.Plan) : StaySwipe

    data class Hide(val stay: HiddenStay) : StaySwipe

    companion object {
        /**
         * Each stay's swipe, keyed by the track it follows, as [TrackMerge.plansByAnchor] keys its
         * plans and for the same reason: [intervals] are the derivation's whole intervals, never the
         * per-day slices a row renders.
         */
        fun byAnchor(intervals: List<StayDeriver.Interval>, neighbors: Map<Long, TrackMerge.Neighbors>): Map<Long, StaySwipe> =
            buildMap {
                for (stay in intervals.filterIsInstance<StayDeriver.Stay>()) {
                    val (before, after) = neighbors[stay.afterTrackId] ?: continue
                    val plan = TrackMerge.plan(before, after, stay.start, stay.end)
                    put(before.id, plan?.let(::Merge) ?: Hide(HiddenStay(before.id, after.id)))
                }
            }
    }
}
