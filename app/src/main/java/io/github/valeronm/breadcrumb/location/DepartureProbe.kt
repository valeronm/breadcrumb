package io.github.valeronm.breadcrumb.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.backgroundGranted

/**
 * Coarse positions, cheaply, while nothing is being recorded — so [io.github.valeronm.breadcrumb
 * .domain.DepartureWatch] can be asked whether the phone has left. The recorder decides *when* to
 * ask ([Effect.StartDepartureProbe] / [Effect.StopDepartureProbe]); this owns only the request.
 *
 * **Balanced power, deliberately, and not the recorder's own GPS.** The recorder records off raw
 * platform GNSS; this asks Play Services' fused engine for Wi-Fi and cell positioning, which costs a
 * fraction of a satellite engine and answers the only question being asked — a hundred meters is
 * plenty to tell a car park from a motorway. Handing these to [FixIngest] would be a category error:
 * they are a wake, never a data source.
 *
 * A [durationMs] window is what makes the motion-triggered trigger nearly free: the request tears
 * itself down when the window lapses, so a phone that was merely picked up costs a handful of
 * positions rather than a standing request. Zero means run until stopped, which is the continuous
 * trigger.
 *
 * Wraps final platform classes, so it is host-untestable by construction and deliberately holds no
 * decisions.
 */
class DepartureProbe(
    private val context: Context,
    // ageMs is how old the position already was, and it is here because **the fused engine is free
    // to answer from its cache**: deliveries have been seen arriving in under 100 ms, which is no
    // Wi-Fi scan, so nothing else separates a position it measured from one it remembered. No rule
    // reads it — whether staleness needs a ceiling is a field question, and this is what answers
    // it, exactly as DepartureFence.armFromLastKnown logs the age it arms on.
    private val onPosition: (
        latitude: Double,
        longitude: Double,
        accuracyM: Double,
        ageMs: Long,
    ) -> Unit,
) {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Held rather than posted against a token: the token overload of postDelayed is API 28, and this
    // app runs from 26.
    private val windowExpiry = Runnable { stop() }

    private var callback: LocationCallback? = null

    /** The interval the live request was built with, so a repeat ask can tell a change from a no-op. */
    private var runningIntervalMs = 0L

    /** How many positions this stretch of probing has cost — the number its battery case is made on. */
    private var positionCount = 0

    /** Whether a request is live, so a dispatcher can skip the main-thread hop a teardown would need. */
    val running: Boolean get() = callback != null

    /**
     * Ask for a position every [intervalMs], for [durationMs] (0 = until stopped). Re-asking with the
     * same interval only extends the window: rebuilding the request would restart the engine's
     * acquisition for nothing, and the motion sensor can fire repeatedly while one is already up.
     */
    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long, durationMs: Long) {
        // Background location is what makes this deliver with the screen off; without it the request
        // is accepted and then silently starves.
        if (!context.backgroundGranted()) return
        armWindow(durationMs)
        if (callback != null && runningIntervalMs == intervalMs) return
        // A different cadence is a different request: tear the old one down first, or the engine
        // delivers on both and the slower one outlives its window.
        if (callback != null) removeUpdates()
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            // The engine may batch and deliver early; nothing here minds a position sooner than
            // asked, and refusing one would only delay the answer.
            .setMinUpdateIntervalMillis(intervalMs / 2)
            // Deliveries stop when the screen is off unless this is set, which is the entire state
            // this trigger operates in.
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                positionCount++
                // Monotonic, not wall clock: a clock step between the fix and now would otherwise
                // report an age that never elapsed.
                val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
                onPosition(fix.latitude, fix.longitude, fix.accuracy.toDouble(), ageMs)
            }
        }
        callback = cb
        runningIntervalMs = intervalMs
        positionCount = 0
        // Ordered against every other Play Services registration in this package — see GmsCalls. A
        // pass that stops the probe and re-arms the fence issues both, and GMS gives no ordering
        // guarantee between them or against a later start.
        GmsCalls.chain { client.requestLocationUpdates(request, cb, Looper.getMainLooper()) }
        DebugLog.i(
            TAG,
            "departure probe started (every ${intervalMs / 1000}s" +
                if (durationMs > 0) ", for ${durationMs / 1000}s)" else ", until stopped)",
        )
    }

    fun stop() {
        handler.removeCallbacks(windowExpiry)
        if (callback == null) return
        val cost = positionCount
        removeUpdates()
        DebugLog.i(TAG, "departure probe stopped ($cost position(s))")
    }

    private fun removeUpdates() {
        callback?.let { cb -> GmsCalls.chain { client.removeLocationUpdates(cb) } }
        callback = null
        runningIntervalMs = 0L
    }

    /**
     * Replace whatever the window was with [durationMs]. A fresh motion trigger during a live window
     * extends it rather than letting the original expiry cut a probe that has just been given a new
     * reason to run.
     */
    private fun armWindow(durationMs: Long) {
        handler.removeCallbacks(windowExpiry)
        if (durationMs <= 0) return
        handler.postDelayed(windowExpiry, durationMs)
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}
