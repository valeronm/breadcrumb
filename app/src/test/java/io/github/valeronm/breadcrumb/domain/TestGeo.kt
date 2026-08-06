package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint

/**
 * The shared geometry and time convention for the domain tests, in one place so the suites can't drift
 * apart on it: distances are flat-earth with 0.001° ≈ 100 m, so a fixture places points by *meters east
 * of an origin* and every assertion reasons in meters and minutes. The origin is deliberately neutral
 * (latitude 1.0) and must stay so — a real coordinate in a fixture leaks a region even when no trip is
 * named. Each suite still builds its own `TrackPoint`s — the shapes genuinely differ (Doppler speed,
 * pre-set ignore flags, row ids), only this convention is common — and builders should go through
 * [lonAt] rather than repeating the scale factor.
 */

/** Flat-earth distance stub: the larger degree delta scaled so 0.001° ≈ 100 m. */
internal val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
    maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
}

internal const val ORIGIN_LAT = 1.0
internal const val ORIGIN_LON = 1.0

/** The longitude standing for [meters] east of the origin under [flatDistance]. */
internal fun lonAt(meters: Double) = ORIGIN_LON + meters / 100_000.0

/** An endpoint [meters] east of the origin. */
internal fun at(meters: Double) = Endpoint(ORIGIN_LAT, lonAt(meters))

/** One minute in ms — fixtures lay points out in minutes. */
internal const val MIN = 60_000L

/**
 * A finished track row, with everything a case isn't about defaulted away. Shared because the row
 * has seven columns and only ever two or three of them are the subject: a suite spelling all seven
 * is a suite that has to be edited when an eighth arrives.
 */
internal fun trackSummary(
    id: Long,
    activityType: String,
    startedAt: Long,
    endedAt: Long?,
    meters: Double = 0.0,
    source: String = TrackOrigin.RECORDED.code,
) = io.github.valeronm.breadcrumb.data.db.TrackSummary(
    id = id,
    activityType = activityType,
    startedAt = startedAt,
    endedAt = endedAt,
    distanceMeters = meters,
    pointCount = 2,
    ignoredCount = 0,
    source = source,
)
