package io.github.valeronm.breadcrumb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Receives the departure fence's exit and hands it to the running [LocationRecordingService]. Like
 * [ActivityTransitionReceiver] it forwards to the live instance rather than starting one — the
 * service is already foreground for as long as the recorder is armed — and holds the broadcast open
 * ([goAsync]) until the departure has been applied, because returning releases the broadcast's
 * wakelock and in Doze the apply coroutine would then freeze until something else woke the process.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            DebugLog.w(TAG, "geofence event error: ${event?.errorCode}")
            return
        }
        val service = LocationRecordingService.instance
        if (service == null) {
            // Worth a line of its own: the fence outlives the process, so this is the case a
            // restart-from-broadcast would have to cover, and how often it happens is field data.
            DebugLog.w(TAG, "departure fence EXIT DROPPED — service instance is null")
            return
        }
        DebugLog.i(TAG, "departure fence EXIT")
        val pending = goAsync()
        service.onDeparture { pending.finish() }
    }

    companion object {
        const val ACTION_DEPARTED = "io.github.valeronm.breadcrumb.ACTION_DEPARTED"
        private const val TAG = "Breadcrumb"
    }
}
