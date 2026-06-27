package io.github.valeronm.breadcrumb.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * Registers/unregisters Activity Transition updates with Google Play Services. Transitions are
 * delivered to [ActivityTransitionReceiver] via a broadcast [PendingIntent].
 */
class ActivityRecognitionManager(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)

    private fun broadcastPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).apply {
            this.action = action
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system mutates the intent to attach activity results.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun transitionPendingIntent() =
        broadcastPendingIntent(ActivityTransitionReceiver.ACTION_TRANSITION, REQUEST_TRANSITION)

    private fun snapshotPendingIntent() =
        broadcastPendingIntent(ActivityTransitionReceiver.ACTION_SNAPSHOT, REQUEST_SNAPSHOT)

    private fun buildRequest(): ActivityTransitionRequest {
        val transitions = ArrayList<ActivityTransition>()
        for (activity in ActivityType.TRACKED_DETECTED_ACTIVITIES) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
        }
        return ActivityTransitionRequest(transitions)
    }

    /** Caller must hold ACTIVITY_RECOGNITION; the service checks before invoking. */
    @SuppressLint("MissingPermission")
    fun start() {
        client.requestActivityTransitionUpdates(buildRequest(), transitionPendingIntent())
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        client.removeActivityTransitionUpdates(transitionPendingIntent())
    }

    /**
     * Requests a one-shot snapshot of the current activity so recording can start immediately if
     * the user is already moving when they arm. The receiver removes it after the first result.
     */
    @SuppressLint("MissingPermission")
    fun requestSnapshot() {
        client.requestActivityUpdates(0L, snapshotPendingIntent())
    }

    @SuppressLint("MissingPermission")
    fun removeSnapshot() {
        client.removeActivityUpdates(snapshotPendingIntent())
    }

    private companion object {
        const val REQUEST_TRANSITION = 4711
        const val REQUEST_SNAPSHOT = 4712
    }
}
