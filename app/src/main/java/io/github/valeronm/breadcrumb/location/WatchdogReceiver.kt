package io.github.valeronm.breadcrumb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Fires on the armed-session watchdog alarm (see [LocationRecordingService.onWatchdog]): the GMS
 * transition registration can die with no error surfacing, so while armed the service re-registers
 * every interval, and the replay of the current activity recovers a missed transition within one tick.
 * Also self-heals "armed flag set but service dead" — the alarm's temporary power-allowlist window
 * permits the foreground-service start from the background.
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
                LocationRecordingService.startSafely(context, "watchdog restart")
            }
            else -> DebugLog.w(TAG, "watchdog fired while disarmed — ignoring")
        }
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}
