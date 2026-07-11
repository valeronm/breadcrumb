package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Formats a distance in metres as km — the app's one rendering of track length. One decimal up
 * to 100 km (dropped when it's zero: "4 km", not "4,0 km"); beyond that the tenth is noise, so
 * whole (locale-grouped) kilometres.
 */
fun formatKm(meters: Double): String {
    val km = meters / 1000.0
    if (km >= 100) return "%,.0f km".format(km)
    // The decimal separator is locale-dependent — strip either form of a zero tenth.
    val value = "%.1f".format(km).removeSuffix(",0").removeSuffix(".0")
    return "$value km"
}

/** Formats a speed as a whole-number "km/h" string — the app's one rendering of speed. */
fun formatKmh(kmh: Double): String = "%.0f km/h".format(kmh)

/** Average speed in km/h over [distanceMeters] and [durationS]; 0 when there's no duration. */
fun avgSpeedKmh(distanceMeters: Double, durationS: Double): Double =
    if (durationS > 0) (distanceMeters / durationS) * 3.6 else 0.0

/** Whether [permission] is currently granted to this context. */
fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Whether either location permission is granted. Required before starting the location foreground
 * service: on Android 14+ starting an FGS of type `location` without it throws SecurityException.
 */
fun Context.hasLocationPermission(): Boolean =
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
