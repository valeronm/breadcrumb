package com.valeronm.activitytracker.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.valeronm.activitytracker.data.ActivityType

/**
 * Registers/unregisters Activity Transition updates with Google Play Services. Transitions are
 * delivered to [ActivityTransitionReceiver] via a broadcast [PendingIntent].
 */
class ActivityRecognitionManager(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).apply {
            action = ActivityTransitionReceiver.ACTION_TRANSITION
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system mutates the intent to attach transition results.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

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
        client.requestActivityTransitionUpdates(buildRequest(), pendingIntent())
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        client.removeActivityTransitionUpdates(pendingIntent())
    }

    private companion object {
        const val REQUEST_CODE = 4711
    }
}
