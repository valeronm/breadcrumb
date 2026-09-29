package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/** The nearest named place among [opened] and [overlapping], else [opened]. */
fun initialKeeper(opened: Place, overlapping: List<Place>, distance: DistanceFn): Place =
    (listOf(opened) + overlapping)
        .filter { it.isNamed }
        .minByOrNull { distance.meters(opened.lat, opened.lon, it.lat, it.lon) }
        ?: opened
