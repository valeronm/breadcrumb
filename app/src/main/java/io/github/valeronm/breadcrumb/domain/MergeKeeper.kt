package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/** The nearest place among [opened] and [overlapping] that the user made or last edited, else [opened]. */
fun initialKeeper(opened: Place, overlapping: List<Place>, distance: DistanceFn): Place =
    (listOf(opened) + overlapping)
        .filter { it.placeOrigin == PlaceOrigin.MANUAL }
        .minByOrNull { distance.meters(opened.lat, opened.lon, it.lat, it.lon) }
        ?: opened
