package io.github.valeronm.breadcrumb.data

import android.location.Location
import io.github.valeronm.breadcrumb.domain.DistanceFn

/** Production [DistanceFn], backed by the Android framework's WGS84 ellipsoidal formula. */
val AndroidDistance = DistanceFn { aLat, aLon, bLat, bLon ->
    FloatArray(1).also { Location.distanceBetween(aLat, aLon, bLat, bLon, it) }[0].toDouble()
}
