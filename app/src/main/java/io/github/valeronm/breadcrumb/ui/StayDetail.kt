package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.NearbyPlaces
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.ResolvedStayInterval
import io.github.valeronm.breadcrumb.domain.TrackMerge

sealed interface StayState {
    /** No stay follows the track. */
    data object Gone : StayState
}

/** Built by [of], which chooses the kind from [resolved]'s place. */
sealed interface StayDetail : StayState {
    val resolved: ResolvedStayInterval
    val nearby: List<NearbyPlaces.Nearby>
    val claim: StayClaim

    /** The places the stay can be set to. */
    val choices: List<NearbyPlaces.Nearby>

    /** What the page offers for the stay, in the order it shows them. */
    val actions: List<StayAction>

    /** A stay at [spot], which becomes a place by being named. */
    data class Unnamed(
        override val resolved: ResolvedStayInterval,
        val spot: PlaceResolver.ResolvedStay,
        override val nearby: List<NearbyPlaces.Nearby>,
        override val claim: StayClaim,
    ) : StayDetail {
        /** The spot's own row is left for naming. */
        override val choices: List<NearbyPlaces.Nearby> get() = nearby.filterNot { it.current }

        val hideAll: List<HiddenStay>? get() = resolved.hideableHere.takeIf { it.size > 1 }

        override val actions: List<StayAction>
            get() = listOfNotNull(
                resolved.merge?.let(StayAction::Merge),
                StayAction.SetPlace.takeIf { choices.isNotEmpty() },
                resolved.hide?.let(StayAction::Hide),
                hideAll?.let(StayAction::HideAll),
            )

        val showsOtherOptions: Boolean get() = actions.isNotEmpty()
    }

    /** A stay at a named place, or at no place. */
    data class Placed(
        override val resolved: ResolvedStayInterval,
        override val nearby: List<NearbyPlaces.Nearby>,
        val placeDistanceM: Double?,
        override val claim: StayClaim,
    ) : StayDetail {
        override val choices: List<NearbyPlaces.Nearby> get() = nearby

        override val actions: List<StayAction>
            get() = listOfNotNull(
                if (resolved.place == null) StayAction.SetPlace else StayAction.ChangePlace,
                resolved.merge?.let(StayAction::Merge),
                resolved.hide?.let(StayAction::Hide),
            )
    }

    companion object {
        fun of(resolved: ResolvedStayInterval, nearby: List<NearbyPlaces.Nearby>, claim: StayClaim, distance: DistanceFn): StayDetail {
            val spot = resolved.place?.takeUnless { it.isNamed }
            return if (spot != null) {
                Unnamed(resolved, spot, nearby, claim)
            } else {
                Placed(resolved, nearby, resolved.placeDistanceM(distance), claim)
            }
        }
    }
}

sealed interface StayAction {
    data class Merge(val plan: TrackMerge.Plan) : StayAction

    data object ChangePlace : StayAction

    data object SetPlace : StayAction

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
