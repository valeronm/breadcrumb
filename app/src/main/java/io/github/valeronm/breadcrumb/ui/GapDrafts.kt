package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import java.time.LocalDate

internal object GapDrafts {

    /** [at] is the recording's own fix at this end rather than the place's pin, which can sit a street from where the phone demonstrably was. */
    private fun endOf(place: PlaceResolver.ResolvedStay?, at: Coordinate?, atMs: Long?) =
        TripDraftEnd(at = at, placeName = place?.label, timeMs = atMs)

    fun wholeGap(
        gap: StayDeriver.Gap,
        day: LocalDate,
        fromPlace: PlaceResolver.ResolvedStay?,
        toPlace: PlaceResolver.ResolvedStay?,
        nextTrackId: Long,
    ) = draft(gap, day, endOf(fromPlace, gap.from?.at, gap.start), toPlace, nextTrackId)

    /** The departure is left untimed: when the stay before it ended is set later, by a road's duration or the user's pick. */
    fun missedStart(
        gap: StayDeriver.Gap,
        day: LocalDate,
        fromPlace: PlaceResolver.ResolvedStay?,
        toPlace: PlaceResolver.ResolvedStay?,
        nextTrackId: Long,
    ) = draft(gap, day, endOf(fromPlace, gap.from?.at, atMs = null), toPlace, nextTrackId)

    private fun draft(
        gap: StayDeriver.Gap,
        day: LocalDate,
        origin: TripDraftEnd,
        toPlace: PlaceResolver.ResolvedStay?,
        nextTrackId: Long,
    ) = TripDraft(
        day = day,
        origin = origin,
        destination = endOf(toPlace, gap.to?.at, gap.end),
        trackBefore = gap.afterTrackId,
        trackAfter = nextTrackId,
    )
}
