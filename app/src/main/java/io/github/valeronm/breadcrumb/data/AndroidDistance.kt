package io.github.valeronm.breadcrumb.data

import android.location.Location
import io.github.valeronm.breadcrumb.domain.DistanceFn

/** Production [DistanceFn], backed by the Android framework's WGS84 ellipsoidal formula. */
val AndroidDistance = object : DistanceFn {
    // `distanceBetween` writes into a caller-owned array. One reusable array per thread, because
    // this singleton is called from the recorder's per-fix path, repository sweeps and the UI at
    // once — a shared array would race, a fresh one per call is churn on the hot path.
    private val out = ThreadLocal.withInitial { FloatArray(1) }

    override fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        // `get()` is nullable to Kotlin, but `withInitial` never hands back null.
        val result = checkNotNull(out.get())
        Location.distanceBetween(aLat, aLon, bLat, bLon, result)
        return result[0].toDouble()
    }
}
