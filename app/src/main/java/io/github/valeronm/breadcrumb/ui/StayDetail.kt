package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.NearbyPlaces
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.ResolvedStayInterval
import io.github.valeronm.breadcrumb.domain.TrackMerge

sealed interface StayState {
    /** No stay follows the track. */
    data object Gone : StayState
}

data class StayDetail(
    val resolved: ResolvedStayInterval,
    val nearby: List<NearbyPlaces.Nearby>,
    val placeDistanceM: Double?,
    val claim: StayClaim,
) : StayState {
    val unnamedSpot: PlaceResolver.ResolvedStay? get() = resolved.place?.takeUnless { it.isNamed }

    /** What the page offers for the stay, in the order it shows them. */
    val actions: List<StayAction>
        get() {
            val merge = resolved.merge?.let(StayAction::Merge)
            val hide = resolved.hide?.let(StayAction::Hide)
            if (unnamedSpot == null) return listOfNotNull(StayAction.ChangePlace, merge, hide)
            val hideAll = resolved.hideableHere.takeIf { it.size > 1 }?.let(StayAction::HideAll)
            return listOfNotNull(merge, StayAction.ChangePlace, hide, hideAll)
        }
}

sealed interface StayAction {
    data class Merge(val plan: TrackMerge.Plan) : StayAction

    data object ChangePlace : StayAction

    data class Hide(val stay: HiddenStay) : StayAction

    data class HideAll(val stays: List<HiddenStay>) : StayAction
}

/** The interval between the end of [beforeId] and the start of [afterId], or the one after [beforeId] where [afterId] is null. */
data class StayClaim(val beforeId: Long, val afterId: Long?) {
    data class Recording(val id: Long, val startedAt: Long)

    companion object {
        /**
         * The claim on [stay], whose departure is [recording]'s start where no finished track
         * follows and [stay] ends where [recording] started.
         */
        fun of(stay: ResolvedStayInterval, recording: Recording?): StayClaim {
            val beforeId = stay.stay.afterTrackId
            // A stitch reopens the arriving track itself, whose start lies before the stay.
            val departing = recording?.takeIf { it.id != beforeId && it.startedAt == stay.stay.end }
            return StayClaim(beforeId, stay.afterId ?: departing?.id)
        }
    }
}
