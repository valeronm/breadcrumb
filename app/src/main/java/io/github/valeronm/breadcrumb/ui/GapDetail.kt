package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
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
