package io.github.valeronm.breadcrumb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Fires on the armed-session watchdog alarm (see [LocationRecordingService.onWatchdog]). The GMS
 * transition registration can die silently — observed in the field as the arm-time replay arriving
 * and then no live transitions ever again — so while armed the service re-registers every
 * interval; registration replays the current activity, so any missed transition is recovered
 * within one tick. Also self-heals the "armed flag set but service dead" state: the alarm's
 * temporary power-allowlist window permits the foreground-service start from the background.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = LocationRecordingService.instance
        when {
            service != null -> {
                // Hold the broadcast open until the re-register reaches GMS: the chained call
                // posts to the main looper, and without the wakelock Doze could freeze it there —
                // the very failure this alarm exists to fix.
                val pending = goAsync()
                service.onWatchdog { pending.finish() }
            }
            Settings.isAutoRecord(context) -> {
                DebugLog.w(TAG, "watchdog: armed but service dead — restarting")
                runCatching { LocationRecordingService.start(context) }
                    .onFailure { DebugLog.e(TAG, "watchdog restart FAILED: ${it.message}") }
            }
            else -> DebugLog.w(TAG, "watchdog fired while disarmed — ignoring")
        }
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}
