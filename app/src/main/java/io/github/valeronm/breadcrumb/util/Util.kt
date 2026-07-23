package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

/**
 * Delegate for a locale-derived value (typically a date formatter) that follows the *current*
 * default locale. A plain `val` captures the locale at class-load, and this process outlives the
 * UI by weeks (the recording service holds it) — a user switching language would otherwise keep
 * seeing dates in the old locale until the process finally dies. Cached per locale, so the value
 * is only rebuilt on an actual switch.
 */
class PerLocale<T>(private val make: (Locale) -> T) {
    @Volatile private var cached: Pair<Locale, T>? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val locale = Locale.getDefault()
        cached?.let { (cachedLocale, value) -> if (cachedLocale == locale) return value }
        return make(locale).also { cached = locale to it }
    }
}

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
