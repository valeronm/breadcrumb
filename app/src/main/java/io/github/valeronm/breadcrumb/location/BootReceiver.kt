package io.github.valeronm.breadcrumb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Re-arms automatic recording if the user had it enabled, after the two events that kill the
 * armed service outside our control: a reboot and an app update (which stops the foreground
 * service without restarting it).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Settings.isAutoRecord(context)) return
        DebugLog.i(TAG, "boot receiver: re-arming (${intent.action})")
        runCatching { LocationRecordingService.start(context) }
            .onFailure { DebugLog.e(TAG, "boot receiver re-arm FAILED: ${it.message}") }
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}
