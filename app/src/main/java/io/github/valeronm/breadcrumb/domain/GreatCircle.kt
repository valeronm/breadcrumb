package io.github.valeronm.breadcrumb.domain

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The shorter great-circle arc between two coordinates, as the points a map should draw it
 * through. A straight segment in projected space is not the path anything travelled: on a Mercator
 * map the real route between two far-apart airports bows toward the pole, and a manual track drawn
 * straight claims a journey that never happened. Spherical rather than ellipsoidal on purpose —
 * this shapes a line, it measures nothing, and the flattening the ellipsoid adds is invisible at
 * line width.
 *
 * Longitudes come out **unwrapped** — continuous past ±180° — so a renderer draws one line across
 * the antimeridian instead of a full-width zigzag; MapLibre takes such coordinates as they are.
 */
object GreatCircle {

    /** Arc samples per radian of central angle — one point per ~1.2° keeps a hemisphere-scale arc
     *  smooth at a few dozen points. */
    private const val SAMPLES_PER_RADIAN = 48.0

    private const val MAX_SAMPLES = 96

    /** Under this central angle (~11 km) a straight segment and the arc are the same line. */
    private const val MIN_ANGLE_RAD = 0.001

    /**
     * The arc from [from] to [to] inclusive, ends exact. Degenerate spans hand back just the two
     * ends: a span too short for the bow to be visible, and an antipodal pair — where every
     * direction is a shortest path and drawing one of them would be an invention.
     */
    fun arc(from: Coordinate, to: Coordinate): List<Coordinate> {
        val a = unitVector(from.lat, from.lon)
        val b = unitVector(to.lat, to.lon)
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        val angle = acos(dot)
        if (angle < MIN_ANGLE_RAD || angle > Math.PI - MIN_ANGLE_RAD) return listOf(from, to)
        val steps = (angle * SAMPLES_PER_RADIAN).roundToInt().coerceIn(2, MAX_SAMPLES)
        val sinAngle = sin(angle)
        val out = ArrayList<Coordinate>(steps + 1)
        var prevLon = from.lon
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val wa = sin((1 - t) * angle) / sinAngle
            val wb = sin(t * angle) / sinAngle
            val x = wa * a[0] + wb * b[0]
            val y = wa * a[1] + wb * b[1]
            val z = wa * a[2] + wb * b[2]
            val lat = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
            var lon = Math.toDegrees(atan2(y, x))
            // Unwrap against the previous sample; steps are a few degrees apart, so a jump near
            // 360 is the atan2 seam, never the path.
            while (lon - prevLon > 180.0) lon -= 360.0
            while (prevLon - lon > 180.0) lon += 360.0
            prevLon = lon
            out += Coordinate(lat, lon)
        }
        // The ends are the caller's own values, not the slerp's rounding of them — the last one
        // shifted onto the unwrapped turn the walk ended on.
        out[0] = from
        val turns = Math.round((out.last().lon - to.lon) / 360.0)
        out[out.size - 1] = Coordinate(to.lat, to.lon + 360.0 * turns)
        return out
    }

    private fun unitVector(latDeg: Double, lonDeg: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        return doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    }
}
