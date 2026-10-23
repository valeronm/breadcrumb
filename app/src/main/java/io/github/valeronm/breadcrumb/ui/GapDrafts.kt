package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
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

    private fun draft(detail: GapDetail, origin: TripDraftEnd, activity: ActivityType? = null) = TripDraft(
        day = detail.gap.start.toLocalDate(detail.departureZone),
        origin = origin,
        destination = endOf(detail.toPlace, detail.gap.to?.at, detail.gap.end),
        tracksAround = detail.gap.afterTrackId to detail.after.id,
        activity = activity,
    )
}
