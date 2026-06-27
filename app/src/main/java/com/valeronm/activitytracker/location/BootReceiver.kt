package com.valeronm.activitytracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valeronm.activitytracker.data.Settings

/** Re-arms automatic recording after a reboot if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.isAutoRecord(context)) return
        runCatching { LocationRecordingService.start(context) }
    }
}
