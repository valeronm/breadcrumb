package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.TrackMerge

internal sealed interface StayedHereChoice : GapAction {
    data class Direct(val side: PlaceResolver.ResolvedStay) : StayedHereChoice

    data class Ask(
        val from: PlaceResolver.ResolvedStay,
        val to: PlaceResolver.ResolvedStay,
        val preselected: PlaceResolver.ResolvedStay,
    ) : StayedHereChoice
}

/** A named place with a saved row: one named moments ago has no id to link to until its insert answers. */
internal val PlaceResolver.ResolvedStay.holdsStays: Boolean get() = isNamed && place?.id != 0L

/** [sides] run departure first. */
internal fun stayedHereChoice(sides: List<PlaceResolver.ResolvedStay>): StayedHereChoice {
    val named = sides.filter { it.holdsStays }.distinctBy { it.key }
    require(named.isNotEmpty()) { "a stay offered without a named side" }
    return named.singleOrNull()?.let { StayedHereChoice.Direct(it) }
        ?: StayedHereChoice.Ask(named[0], named[1], preselected = named.maxBy { it.visitCount })
}

internal sealed interface GapAction {
    data class AddTrip(val draft: TripDraft) : GapAction

    /** Ask a road for the departure [draft] leaves untimed before offering it. */
    data class ProposeStart(val draft: TripDraft) : GapAction

    data class Merge(val plan: TrackMerge.Plan) : GapAction
}

internal fun gapAction(fix: GapDiagnosis.Fix, detail: GapDetail): GapAction = when (fix) {
    GapDiagnosis.Fix.MISSED_START -> GapAction.ProposeStart(GapDrafts.missedStart(detail))
    GapDiagnosis.Fix.ADD_TRIP -> GapAction.AddTrip(GapDrafts.wholeGap(detail))
    GapDiagnosis.Fix.MERGE ->
        GapAction.Merge(checkNotNull(detail.verdict.merge) { "merge offered without a plan" })
    GapDiagnosis.Fix.STAYED_HERE -> stayedHereChoice(listOfNotNull(detail.fromPlace, detail.toPlace))
}
