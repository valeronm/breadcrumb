package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.valeronm.breadcrumb.util.isGranted

/**
 * The two cheap ways to hear that conditions may have changed while GPS is off after a failed
 * probe — neither of which costs a GPS engine. This reports only *which* fired; what a signal is
 * worth is recorder policy and lives with the guard that acts on it, so it can be exercised off the
 * device rather than asserted here.
 *
 * The recorder decides *when* these should be listening — [ArmResumeSignals][Effect.ArmResumeSignals]
 * and [ArmSignificantMotion][Effect.ArmSignificantMotion] are its two requests, and this owns only
 * the registration behind them. Everything here runs on the main thread, where the listeners are
 * registered and delivered.
 */
class ResumeSignals(
    private val context: Context,
    private val onSignal: (Signal) -> Unit,
) {

    /** Which cheap signal fired. */
    enum class Signal { MOTION, PASSIVE_FIX }

    private val sensors by lazy { context.getSystemService(SensorManager::class.java) }
    private val motionSensor by lazy { sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val locations by lazy { context.getSystemService(LocationManager::class.java) }

    private var motionListener: TriggerEventListener? = null
    private var passiveListener: LocationListenerCompat? = null

    /** Both signals: GPS has just been handed off and anything might revive it. */
    fun armAll() {
        armMotionOnly()
        armPassive()
    }

    /** One-shot hardware trigger that fires on walking/driving-scale motion, then disarms itself. */
    fun armMotionOnly() {
        if (motionListener != null) return
        val sm = sensors ?: return
        val sensor = motionSensor ?: return
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                motionListener = null // one-shot: already disarmed by the sensor framework
                onSignal(Signal.MOTION)
            }
        }
        if (sm.requestTriggerSensor(listener, sensor)) motionListener = listener
    }

    // MissingPermission suppressed for removeUpdates and cancelTriggerSensor: neither performs a
    // permission check (the compat annotation is over-broad), and gating teardown on isGranted
    // would leak a listener exactly when the permission was just revoked.
    @SuppressLint("MissingPermission")
    fun disarm() {
        motionListener?.let { listener ->
            motionListener = null
            motionSensor?.let { sensor -> sensors?.cancelTriggerSensor(listener, sensor) }
        }
        passiveListener?.let { listener ->
            locations?.let { LocationManagerCompat.removeUpdates(it, listener) }
        }
        passiveListener = null
    }

    /** Free ride on other apps' fixes: the platform delivering one to anyone means the sky is up. */
    @SuppressLint("MissingPermission")
    private fun armPassive() {
        if (passiveListener != null) return
        if (!context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = locations ?: return
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER) onSignal(Signal.PASSIVE_FIX)
            }
        }
        passiveListener = listener
        LocationManagerCompat.requestLocationUpdates(
            lm,
            LocationManager.PASSIVE_PROVIDER,
            LocationRequestCompat.Builder(PASSIVE_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_LOW_POWER)
                .build(),
            ContextCompat.getMainExecutor(context),
            listener,
        )
    }

    private companion object {
        const val PASSIVE_INTERVAL_MS = 30_000L
    }
}
