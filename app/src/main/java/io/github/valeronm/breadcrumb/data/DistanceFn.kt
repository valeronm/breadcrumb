package io.github.valeronm.breadcrumb.data

import android.location.Location

/**
 * Great-circle distance between two coordinates, in meters. A seam so the fix-quality logic that
 * depends on distance can be unit-tested on the host JVM with a caller-supplied distance, instead of
 * pulling in the Android framework's [Location.distanceBetween] (which throws under plain JUnit).
 */
fun interface DistanceFn {
    fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double
}

/** Production distance, backed by the Android framework's WGS84 ellipsoidal formula. */
val AndroidDistance = DistanceFn { aLat, aLon, bLat, bLon ->
    FloatArray(1).also { Location.distanceBetween(aLat, aLon, bLat, bLon, it) }[0].toDouble()
}
