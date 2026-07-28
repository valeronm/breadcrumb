package io.github.valeronm.breadcrumb.domain

/**
 * Great-circle distance between two coordinates, in meters — a seam so distance-dependent
 * fix-quality logic unit-tests on the host JVM with a caller-supplied distance instead of the
 * framework's `Location.distanceBetween`, which throws under plain JUnit. Production passes
 * `AndroidDistance` (in `data`), backed by that framework formula.
 */
fun interface DistanceFn {
    fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double
}
