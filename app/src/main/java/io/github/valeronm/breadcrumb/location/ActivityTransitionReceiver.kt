package io.github.valeronm.breadcrumb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.github.valeronm.breadcrumb.domain.ActivityInterpreter
import io.github.valeronm.breadcrumb.util.DebugLog
import java.util.Locale

/**
 * Receives activity updates from Google Play Services and forwards them to the running
 * [LocationRecordingService]. Handles two kinds:
 *  - Transition results (ENTER events) — the ongoing activity-change stream.
 *  - A one-shot recognition snapshot requested at start, so recording can begin immediately if the
 *    user is already moving when they arm.
 * We hand off to the live service instance directly (same process) rather than re-starting it,
 * which avoids background-start restrictions.
 *
 * Forwarded readings hold the broadcast open ([goAsync]) until the service has applied them.
 * Returning from onReceive releases the broadcast's wakelock, and in Doze the apply coroutine then
 * freezes: the reading is logged on time but applied minutes later, which puts a walking tail on a
 * drive track and stitches through a real stop.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = LocationRecordingService.instance
        when {
            ActivityTransitionResult.hasResult(intent) -> {
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                // Log every event in the batch (oldest-first), with how long ago it fired, so we can
                // see whether the right transition arrived and how laggy it was.
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                result.transitionEvents.forEach { e ->
                    val agoS = (nowNanos - e.elapsedRealTimeNanos) / 1_000_000_000.0
                    DebugLog.i(
                        TAG,
                        "transition ${transitionName(e.transitionType)} ${detectedName(e.activityType)} " +
                            "(${"%.1f".format(Locale.US, agoS)}s ago)",
                    )
                }
                // The last event is the current state; interpret it (see [ActivityInterpreter]).
                val latest = result.transitionEvents.lastOrNull() ?: return
                val detected = activityTypeOfDetected(latest.activityType)
                val isExit = latest.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
                when (val decision = ActivityInterpreter.interpretTransition(detected, isExit)) {
                    ActivityInterpreter.TransitionDecision.Ignore -> Unit
                    is ActivityInterpreter.TransitionDecision.Forward -> {
                        if (decision.exitMapped) DebugLog.i(TAG, "  EXIT $detected -> treating as STILL")
                        if (service == null) {
                            DebugLog.w(TAG, "transition ${decision.activity} DROPPED — service instance is null")
                        } else {
                            // The event's own wall-clock time (its stamp is elapsed-realtime).
                            val eventTimeMs =
                                System.currentTimeMillis() - (nowNanos - latest.elapsedRealTimeNanos) / 1_000_000
                            val pending = goAsync()
                            service.onActivityChanged(decision.activity, eventTimeMs) { pending.finish() }
                        }
                    }
                }
            }

            ActivityRecognitionResult.hasResult(intent) -> {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                // One-shot snapshot: stop further sampling immediately.
                ActivityRecognitionManager(context).removeSnapshot()

                val probable = result.mostProbableActivity
                val activity = activityTypeOfDetected(probable.type)
                val transitionApplied = service?.transitionSinceArm ?: false
                val forward = ActivityInterpreter.interpretSnapshot(
                    activity,
                    probable.confidence,
                    CONFIDENCE_THRESHOLD,
                    transitionApplied,
                )
                // Always logged: a snapshot fires once per arm, and a suppressed one once hid a
                // momentary STILL pausing a just-started drive.
                val ranked = result.probableActivities.joinToString {
                    "${detectedName(it.type)}:${it.confidence}"
                }
                DebugLog.i(
                    TAG,
                    "snapshot mostProbable=${detectedName(probable.type)}(${probable.confidence}) " +
                        "-> $activity acts=${forward != null} transitionApplied=$transitionApplied " +
                        "serviceAlive=${service != null} [$ranked]",
                )
                if (forward != null && service != null) {
                    val pending = goAsync()
                    // result.time is already wall-clock (unlike transition events' elapsed stamps).
                    service.onSnapshot(forward, result.time) { pending.finish() }
                }
            }

            else -> DebugLog.w(TAG, "onReceive: no transition or recognition result in ${intent.action}")
        }
    }

    private fun transitionName(type: Int): String = when (type) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
        else -> "T$type"
    }

    private fun detectedName(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.UNKNOWN -> "UNKNOWN"
        else -> "type$type"
    }

    companion object {
        const val ACTION_TRANSITION = "io.github.valeronm.breadcrumb.ACTION_TRANSITION"
        const val ACTION_SNAPSHOT = "io.github.valeronm.breadcrumb.ACTION_SNAPSHOT"
        private const val CONFIDENCE_THRESHOLD = 50
        private const val TAG = "Breadcrumb"
    }
}
