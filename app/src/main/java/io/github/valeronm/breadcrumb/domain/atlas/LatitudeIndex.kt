package io.github.valeronm.breadcrumb.domain.atlas

import io.github.valeronm.breadcrumb.domain.DistanceFn
import java.util.function.IntPredicate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rows ordered by latitude, searched outward from a query's: a binary search finds its latitude, and a
 * walk steps away from it only while a row's latitude alone could still be within reach.
 *
 * Longitude cannot bound the *walk*, the ordering carrying none of it, but it rules out most of what
 * the walk visits before a distance is measured. A band of a quarter-degree circles the earth, so
 * nearly every row in it is on another continent, and the check is a read and a compare against a
 * distance call.
 */
internal class LatitudeIndex(private val rows: Rows) {

    /** Coordinates in microdegrees. */
    interface Rows {
        val count: Int

        fun latE6(row: Int): Int

        fun lonE6(row: Int): Int
    }

    class Found(val row: Int, val distanceM: Double)

    init {
        for (row in 1 until rows.count) {
            require(rows.latE6(row - 1) <= rows.latE6(row)) { "rows are not ordered by latitude at row $row" }
        }
    }

    /** The nearest row [accept] takes, or null where it takes none. */
    fun nearest(lat: Double, lon: Double, distance: DistanceFn, accept: IntPredicate): Found? {
        val count = rows.count
        val targetE6 = (lat * 1e6).roundToInt()
        val lonE6 = (lon * 1e6).roundToInt()
        val mPerDegreeLat = latMetersFloor(distance)
        var best = -1
        var bestM = Double.MAX_VALUE
        var mPerDegreeLon = 0.0
        var up = lowerBound(targetE6)
        var down = up - 1

        fun consider(row: Int) {
            if (!accept.test(row)) return
            if (lonDegreesOf(row, lonE6) * mPerDegreeLon > bestM) return
            val d = distance.meters(lat, lon, rows.latE6(row) / 1e6, rows.lonE6(row) / 1e6)
            if (d < bestM) {
                bestM = d
                best = row
                mPerDegreeLon = lonMetersFloor(lat, bestM, mPerDegreeLat, distance)
            }
        }

        // Interleaved rather than one side then the other: a coordinate far from any row (at sea, in
        // a desert) would otherwise walk one direction to the bound before the first distance exists
        // to prune the other with.
        while (up < count || down >= 0) {
            if (up < count && latBoundOf(up, targetE6, mPerDegreeLat) <= bestM) consider(up++) else up = count
            if (down >= 0 && latBoundOf(down, targetE6, mPerDegreeLat) <= bestM) consider(down--) else down = -1
        }
        return if (best < 0) null else Found(best, bestM)
    }

    /**
     * Hands [visit] every row within [reachM] of [lat]/[lon], along with some beyond it, which [visit]
     * tells apart by measuring.
     */
    inline fun forEachNear(lat: Double, lon: Double, reachM: Double, distance: DistanceFn, visit: (Int) -> Unit) {
        val count = rows.count
        val targetE6 = (lat * 1e6).roundToInt()
        val lonE6 = (lon * 1e6).roundToInt()
        val mPerDegreeLat = latMetersFloor(distance)
        val mPerDegreeLon = lonMetersFloor(lat, reachM, mPerDegreeLat, distance)
        var up = lowerBound(targetE6)
        var down = up - 1
        while (up < count && latBoundOf(up, targetE6, mPerDegreeLat) <= reachM) {
            if (lonDegreesOf(up, lonE6) * mPerDegreeLon <= reachM) visit(up)
            up++
        }
        while (down >= 0 && latBoundOf(down, targetE6, mPerDegreeLat) <= reachM) {
            if (lonDegreesOf(down, lonE6) * mPerDegreeLon <= reachM) visit(down)
            down--
        }
    }

    private fun lowerBound(targetE6: Int): Int {
        var lo = 0
        var hi = rows.count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rows.latE6(mid) < targetE6) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun latBoundOf(row: Int, targetE6: Int, mPerDegreeLat: Double): Double =
        abs(rows.latE6(row).toLong() - targetE6) / 1e6 * mPerDegreeLat

    private fun lonDegreesOf(row: Int, lonE6: Int): Double {
        val delta = abs(rows.lonE6(row).toLong() - lonE6) / 1e6
        return if (delta > HALF_TURN_DEGREES) 2 * HALF_TURN_DEGREES - delta else delta
    }

    /**
     * A path within [reachM] of [lat] never leaves the latitude band that reach spans, so no degree of
     * longitude on it is worth less than one at the band's poleward edge, measured by [distance] point
     * to point, which runs shorter than the parallel.
     */
    private fun lonMetersFloor(lat: Double, reachM: Double, mPerDegreeLat: Double, distance: DistanceFn): Double {
        val poleward = minOf(QUARTER_TURN_DEGREES, abs(lat) + reachM / mPerDegreeLat)
        return distance.meters(poleward, 0.0, poleward, 1.0)
    }

    /**
     * A degree of latitude is shortest across the equator, and [MIN_M_PER_DEGREE_LAT] keeps the earth's
     * understated.
     */
    private fun latMetersFloor(distance: DistanceFn): Double =
        minOf(MIN_M_PER_DEGREE_LAT, distance.meters(-HALF_DEGREE, 0.0, HALF_DEGREE, 0.0))

    private companion object {
        /** A longitude difference past this is shorter the other way round. */
        const val HALF_TURN_DEGREES = 180.0

        const val QUARTER_TURN_DEGREES = 90.0

        const val HALF_DEGREE = 0.5

        /** Meters per degree of latitude, understated (the true minimum is ~110,574 at the equator). */
        const val MIN_M_PER_DEGREE_LAT = 110_000.0
    }
}
