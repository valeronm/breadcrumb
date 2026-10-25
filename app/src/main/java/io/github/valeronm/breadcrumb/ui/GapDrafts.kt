package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.OnlineRouting
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.PlaceResolver

internal object GapDrafts {

    /** [at] is the recording's own fix at this end rather than the place's pin, which can sit a street from where the phone demonstrably was. */
    private fun endOf(place: PlaceResolver.ResolvedStay?, at: Coordinate?, atMs: Long?) =
        TripDraftEnd(at = at, placeName = place?.label, timeMs = atMs)

    fun wholeGap(detail: GapDetail) =
        draft(detail, endOf(detail.fromPlace, detail.gap.from?.at, detail.gap.start))

    /**
     * The departure is left untimed: when the stay before it ended is set later, by a road's duration
     * or the user's pick. The leg is the start of the next trip, so it is that trip's type.
     */
    fun missedStart(detail: GapDetail) = draft(
        detail,
        endOf(detail.fromPlace, detail.gap.from?.at, atMs = null),
        activity = ActivityType.ofName(detail.after.activityType),
    )

    /**
     * [missedStart] whole: departing exactly [route]'s duration before its recorded arrival, which
     * no picker typed to the minute. Null where that departure falls before [notBeforeMs], the end
     * of the track before the gap, which the trip would overlap, or the road takes no time.
     */
    fun withRoad(draft: TripDraft, route: OnlineRouting.Route, notBeforeMs: Long): RoadProposal? {
        val activity = draft.activity ?: return null
        val origin = draft.origin ?: return null
        val destination = draft.destination ?: return null
        val from = origin.at ?: return null
        val to = destination.at ?: return null
        val arriveMs = destination.timeMs ?: return null
        val departMs = ManualPath.coupledEnd(arriveMs, route.durationMs, forward = false, roundToMinute = false)
        if (departMs < notBeforeMs || departMs >= arriveMs) return null
        val form = TripDraft(
            day = draft.day,
            origin = origin,
            destination = destination,
            tracksAround = draft.tracksAround,
            activity = activity,
            roadDeparture = RoadDeparture(route, departMs),
        )
        val trip = TrackListViewModel.ManualTrip(
            activity,
            TrackListViewModel.ManualTripEnd(TrackRepository.ManualEnd(from, departMs), origin.placeName),
            TrackListViewModel.ManualTripEnd(TrackRepository.ManualEnd(to, arriveMs), destination.placeName),
            route.shape,
        )
        return RoadProposal(form, trip)
    }

    private fun draft(detail: GapDetail, origin: TripDraftEnd, activity: ActivityType? = null) = TripDraft(
        day = detail.gap.start.toLocalDate(detail.departureZone),
        origin = origin,
        destination = endOf(detail.toPlace, detail.gap.to?.at, detail.gap.end),
        tracksAround = detail.gap.afterTrackId to detail.after.id,
        activity = activity,
    )
}

/** A missed start made whole by a road: [trip] as it is saved, [draft] the same trip as the add-trip form holds it. */
internal class RoadProposal(
    val draft: TripDraft,
    val trip: TrackListViewModel.ManualTrip,
) {
    val lengthM: Double by lazy { storedLineM(trip.origin.end.at, trip.destination.end.at, trip.path) }
}
