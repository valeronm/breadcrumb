package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** [raw] rounded to the nearest multiple of [step] and clamped into [range] — slider snapping. */
fun snapToStep(raw: Float, step: Int, range: ClosedFloatingPointRange<Float>): Float =
    ((raw / step).roundToInt() * step).toFloat().coerceIn(range.start, range.endInclusive)

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
