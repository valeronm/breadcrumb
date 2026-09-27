package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
 * Where the phone is, asked of the platform rather than of the recorder, which may well have GPS
 * off. A request gives up after a few seconds rather than keep the receiver on.
 */
object DeviceLocation {

    private const val FRESH_TIMEOUT_MS = 10_000L

    fun granted(context: Context): Boolean =
        context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** One fresh fix, or null without a location grant or when none arrives in time. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Coordinate? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        // GPS rarely gets a fix indoors.
        val provider = freshProviders()
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        cancel,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> if (continuation.isActive) continuation.resume(location) }
                } catch (_: SecurityException) {
                    // A grant revoked since the check above.
                    continuation.resume(null)
                }
            }
        }
        return fresh?.let { Coordinate(it.latitude, it.longitude) }
    }

    private fun freshProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }
}
