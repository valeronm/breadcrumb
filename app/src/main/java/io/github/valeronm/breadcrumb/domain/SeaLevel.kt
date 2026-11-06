package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/** The height of the geoid (mean sea level) above the WGS84 ellipsoid at a position, null when unknown. */
fun interface GeoidHeight {
    fun at(latitude: Double, longitude: Double): Double?
}

/**
 * The altitudes a reader is shown, which are heights above sea level. Stored altitudes stay as their
 * writer reported them, since the geoid height is a model's answer rather than a measurement.
 */
object SeaLevel {

    /**
     * [point]'s altitude, null where it has none. A writer reporting heights above the ellipsoid
     * ([TrackOrigin.altitudeAboveEllipsoid]) has [geoid]'s height at the fix subtracted, and has no
     * altitude where that height is unknown; every other writer's, an unknown one's included, is
     * returned as stored.
     */
    fun altitude(point: TrackPoint, origin: TrackOrigin?, geoid: GeoidHeight): Double? {
        val altitude = point.altitude ?: return null
        if (origin?.altitudeAboveEllipsoid != true) return altitude
        return geoid.at(point.latitude, point.longitude)?.let { altitude - it }
    }

    /** [points]' altitudes in order, each as [altitude] gives it. */
    fun altitudes(points: List<TrackPoint>, origin: TrackOrigin?, geoid: GeoidHeight): List<Double?> =
        points.map { altitude(it, origin, geoid) }
}
