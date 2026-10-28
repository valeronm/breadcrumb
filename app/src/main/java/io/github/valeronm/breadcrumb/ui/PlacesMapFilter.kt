package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.PlaceResolver

internal class PlacesMapFilter(
    val visible: List<PlaceResolver.PlaceSummary>,
    /** Null while anything is visible. */
    val emptiedBy: Emptied?,
) {
    enum class Emptied { RARE_STOPS, LONG_AGO }
}

/**
 * A place is long ago when its last visit ended before [longAgoBeforeMs], or when it has no visit.
 * When every place is long ago and that chip is off, the long-ago chip is what emptied the map,
 * whatever the rare-stops chip says, since turning that one on would bring nothing back. With both
 * chips on, the list is [places] itself.
 */
internal fun filterPlacesMap(
    places: List<PlaceResolver.PlaceSummary>,
    showRareStops: Boolean,
    showLongAgo: Boolean,
    longAgoBeforeMs: Long,
): PlacesMapFilter {
    val sinceCutoff = if (showLongAgo) {
        places
    } else {
        places.filter { (it.lastSeenMs ?: Long.MIN_VALUE) >= longAgoBeforeMs }
    }
    val visible = if (showRareStops) sinceCutoff else sinceCutoff.filterNot { it.isRareStop() }
    val emptiedBy = when {
        visible.isNotEmpty() -> null
        !showLongAgo && sinceCutoff.isEmpty() -> PlacesMapFilter.Emptied.LONG_AGO
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

internal data class ChipsNeeded(val rareStops: Boolean, val longAgo: Boolean)

/** Which chips must be on for [filterPlacesMap] to draw every one of [places]. */
internal fun chipsShowingAll(places: List<PlaceResolver.PlaceSummary>, longAgoBeforeMs: Long): ChipsNeeded {
    fun hidesAny(showRareStops: Boolean, showLongAgo: Boolean) =
        filterPlacesMap(places, showRareStops, showLongAgo, longAgoBeforeMs).visible.size < places.size
    return ChipsNeeded(
        rareStops = hidesAny(showRareStops = false, showLongAgo = true),
        longAgo = hidesAny(showRareStops = true, showLongAgo = false),
    )
}
