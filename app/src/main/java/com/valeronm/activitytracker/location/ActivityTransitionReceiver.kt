package com.valeronm.activitytracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.valeronm.activitytracker.data.ActivityType

/**
 * Receives Activity Transition broadcasts and forwards the latest detected activity to the
 * running [LocationRecordingService]. We hand off to the live service instance directly (same
 * process) rather than re-starting it, which avoids background-start restrictions.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRANSITION) return
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        // Events are ordered oldest-first; the last ENTER event is the current state.
        val latest = result.transitionEvents.lastOrNull() ?: return
        val activity = ActivityType.fromDetectedActivity(latest.activityType)

        LocationRecordingService.instance?.onActivityChanged(activity)
    }

    companion object {
        const val ACTION_TRANSITION = "com.valeronm.activitytracker.ACTION_TRANSITION"
    }
}
