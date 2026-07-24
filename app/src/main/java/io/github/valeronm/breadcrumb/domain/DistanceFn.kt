package io.github.valeronm.breadcrumb.domain

/**
 * Great-circle distance between two coordinates, in meters. A seam so the fix-quality logic that
 * depends on distance can be unit-tested on the host JVM with a caller-supplied distance, instead
 * of pulling in the Android framework's `Location.distanceBetween` (which throws under plain
 * JUnit). Production passes `AndroidDistance` (in `data`), backed by that framework formula.
 */
fun interface DistanceFn {
    fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double
}
