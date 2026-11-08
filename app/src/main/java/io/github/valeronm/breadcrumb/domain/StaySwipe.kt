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
         * per-day slices a row renders. [plans] are [TrackMerge.plansByAnchor] over [intervals] and [neighbors].
         */
        fun byAnchor(
            intervals: List<StayDeriver.Interval>,
            neighbors: Map<Long, TrackMerge.Neighbors>,
            plans: Map<Long, TrackMerge.Plan>,
        ): Map<Long, StaySwipe> = buildMap {
            for (stay in intervals.filterIsInstance<StayDeriver.Stay>()) {
                val hide = HiddenStays.of(stay, neighbors) ?: continue
                put(hide.beforeId, plans[hide.beforeId]?.let(::Merge) ?: Hide(hide))
            }
        }
    }
}
