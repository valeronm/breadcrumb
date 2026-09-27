package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, for the place screens' "my location" — asked of the platform directly, never
 * of the recorder, which may well have GPS off. [lastKnown] costs nothing and is what a map opens
 * showing; [current] is one fresh fix, taken only when the user asks to go there, and gives up
 * after a few seconds rather than keep the receiver on. Both answer null without a location grant.
 */
object DeviceLocation {

    private const val FRESH_TIMEOUT_MS = 10_000L

    fun granted(context: Context): Boolean =
        context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** The newest position any provider still holds, or null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Coordinate? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return providers()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?.let { Coordinate(it.latitude, it.longitude) }
    }

    /** One fresh fix, falling back to [lastKnown] when none arrives in time. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Coordinate? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return lastKnown(context)
        val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    cancel,
                    ContextCompat.getMainExecutor(context),
                ) { location -> if (continuation.isActive) continuation.resume(location) }
            }
        }
        return fresh?.let { Coordinate(it.latitude, it.longitude) } ?: lastKnown(context)
    }

    private fun providers(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }
}
