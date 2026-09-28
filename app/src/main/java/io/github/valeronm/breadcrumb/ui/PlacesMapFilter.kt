package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.PlaceResolver

internal class PlacesMapFilter(
    val visible: List<PlaceResolver.PlaceSummary>,
    /** Null while anything is visible. */
    val emptiedBy: Emptied?,
) {
    enum class Emptied { RARE_STOPS, PAST_YEAR }
}

/**
 * [visitedSinceMs] is the past-year chip, null while it is off; a place with no visit never passes
 * it. When no place was visited since then, the past-year chip is what emptied the map, whatever the
 * rare-stops chip says, since turning that one on would bring nothing back. With rare stops shown and
 * no cutoff, the list is [places] itself.
 */
internal fun filterPlacesMap(
    places: List<PlaceResolver.PlaceSummary>,
    showRareStops: Boolean,
    visitedSinceMs: Long?,
): PlacesMapFilter {
    val recent = if (visitedSinceMs == null) {
        places
    } else {
        places.filter { (it.lastSeenMs ?: Long.MIN_VALUE) >= visitedSinceMs }
    }
    val visible = if (showRareStops) recent else recent.filterNot { it.isRareStop() }
    val emptiedBy = when {
        visible.isNotEmpty() -> null
        visitedSinceMs != null && recent.isEmpty() -> PlacesMapFilter.Emptied.PAST_YEAR
        else -> PlacesMapFilter.Emptied.RARE_STOPS
    }
    return PlacesMapFilter(visible, emptiedBy)
}

// Clusters below the notable-visit floor are "rare stops": hidden on the map unless its chip is
// on. A label doesn't exempt one — a place named on the strength of a single visit is exactly the
// clutter the chip is asked to clear, and a named cluster with no visits at all (a dropped pin, or
// one whose stays were deleted) is rarer still.
private fun PlaceResolver.PlaceSummary.isRareStop() =
    visitCount < PlaceResolver.NOTABLE_VISIT_MIN
