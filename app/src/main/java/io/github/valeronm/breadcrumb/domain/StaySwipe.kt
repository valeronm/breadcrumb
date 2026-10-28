package io.github.valeronm.breadcrumb.domain

/**
 * What swiping a stay row does: merge the tracks either side where [TrackMerge] plans it, since a
 * merge already takes the stay away, and otherwise hide a stay no longer than [MAX_HIDDEN_MS].
 */
sealed interface StaySwipe {
    data class Merge(val plan: TrackMerge.Plan) : StaySwipe

    data class Hide(val stay: HiddenStay) : StaySwipe

    companion object {
        /** A longer stay is a visit, which a stray swipe must not take off the timeline. */
        const val MAX_HIDDEN_MS = 15 * 60_000L

        /**
         * Each stay's swipe, keyed by the track it follows, as [mergePlans] are keyed
         * ([TrackMerge.plansByAnchor]) and for the same reason: [intervals] are the derivation's
         * whole intervals, never the per-day slices a row renders.
         */
        fun byAnchor(
            intervals: List<StayDeriver.Interval>,
            neighbors: Map<Long, TrackMerge.Neighbors>,
            mergePlans: Map<Long, TrackMerge.Plan>,
        ): Map<Long, StaySwipe> = buildMap {
            for (stay in intervals.filterIsInstance<StayDeriver.Stay>()) {
                val anchor = stay.afterTrackId
                val n = neighbors[anchor] ?: continue
                val end = stay.end ?: continue
                val swipe = mergePlans[anchor]?.let(::Merge)
                    ?: Hide(HiddenStay(anchor, n.after.id)).takeIf { end - stay.start <= MAX_HIDDEN_MS }
                    ?: continue
                put(anchor, swipe)
            }
        }
    }
}
