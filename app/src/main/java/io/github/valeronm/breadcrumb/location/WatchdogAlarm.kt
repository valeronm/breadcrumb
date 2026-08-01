package io.github.valeronm.breadcrumb.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The armed session's recurring wake, delivered to [WatchdogReceiver]. An alarm rather than a
 * coroutine timer because Doze freezes those — which is exactly when transitions go missing and the
 * watchdog is the only thing still running. One-shot and re-armed each time it fires, since
 * `setAndAllowWhileIdle` has no repeating form.
 */
class WatchdogAlarm(private val context: Context) {

    private val alarms by lazy { context.getSystemService(AlarmManager::class.java) }

    private val intent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Arm the next tick. `setAndAllowWhileIdle` needs no exact-alarm grant; in deep Doze while-idle
     * alarms are throttled to roughly one per app per 15 minutes, which is the interval anyway.
     */
    fun schedule() {
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            intent,
        )
    }

    fun cancel() {
        alarms.cancel(intent)
    }

    private companion object {
        const val INTERVAL_MS = 15 * 60_000L
    }
}
