package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * A WGS84 coordinate, latitude first — the one pair type decisions pass around, so a latitude
 * can't land in a longitude slot. GeoJSON and MapLibre order the axes the other way (longitude
 * first), so building a map feature is a deliberate swap made at that seam, never here.
 *
 * Deliberately absent from the point walk: `TrackPoint` rows, [DistanceFn]'s four raw doubles and
 * the per-fix arithmetic behind them stay primitive — a pair object per distance call on a
 * million-row walk is allocation for nothing, the same fence that keeps [Speed] off bulk data.
 * Compared by value, and the derivation relies on that: stay clustering keys map lookups by
 * coordinate, not by object identity.
 */
data class Coordinate(val lat: Double, val lon: Double)

/** Where the user pinned this place — the row's coordinate. */
val Place.pin: Coordinate get() = Coordinate(lat, lon)
