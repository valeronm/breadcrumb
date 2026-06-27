package com.valeronm.activitytracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransitionResult
import com.valeronm.activitytracker.data.ActivityType

/**
 * Receives activity updates from Google Play Services and forwards them to the running
 * [LocationRecordingService]. Handles two kinds:
 *  - Transition results (ENTER events) — the ongoing activity-change stream.
 *  - A one-shot recognition snapshot requested at start, so recording can begin immediately if the
 *    user is already moving when they arm.
 * We hand off to the live service instance directly (same process) rather than re-starting it,
 * which avoids background-start restrictions.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when {
            ActivityTransitionResult.hasResult(intent) -> {
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                // Events are ordered oldest-first; the last ENTER event is the current state.
                val latest = result.transitionEvents.lastOrNull() ?: return
                val activity = ActivityType.fromDetectedActivity(latest.activityType)
                LocationRecordingService.instance?.onActivityChanged(activity)
            }

            ActivityRecognitionResult.hasResult(intent) -> {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                // One-shot snapshot: stop further sampling immediately.
                ActivityRecognitionManager(context).removeSnapshot()

                val probable = result.mostProbableActivity
                val activity = ActivityType.fromDetectedActivity(probable.type)
                // Only kick off recording if we're confidently already in a moving activity.
                if (probable.confidence >= CONFIDENCE_THRESHOLD &&
                    activity.recording &&
                    activity != ActivityType.UNKNOWN
                ) {
                    LocationRecordingService.instance?.onActivityChanged(activity)
                }
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "com.valeronm.activitytracker.ACTION_TRANSITION"
        const val ACTION_SNAPSHOT = "com.valeronm.activitytracker.ACTION_SNAPSHOT"
        private const val CONFIDENCE_THRESHOLD = 50
    }
}
