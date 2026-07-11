package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Formats a distance in metres as a "%.1f km" string — the app's one rendering of track length. */
fun formatKm(meters: Double): String = "%.1f km".format(meters / 1000.0)

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
