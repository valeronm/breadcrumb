package io.github.valeronm.breadcrumb.ui.timeline

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.places.PlaceResolver
import io.github.valeronm.breadcrumb.domain.timeline.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.timeline.StayDeriver
import java.time.ZoneId

sealed interface GapState {
    /** No gap follows the track. */
    data object Gone : GapState
}

data class GapDetail(
    val gap: StayDeriver.Gap,
    val before: TrackSummary,
    val after: TrackSummary,
    val fromPlace: PlaceResolver.ResolvedStay?,
    val toPlace: PlaceResolver.ResolvedStay?,
    val departureZone: ZoneId,
    val arrivalZone: ZoneId,
    val verdict: GapDiagnosis.Verdict,
) : GapState
