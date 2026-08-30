package io.github.valeronm.breadcrumb.location

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.ui.MainActivity
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Everything the recorder puts in the shade: the ongoing foreground notification whose wording
 * follows the recording state, and the transient alert raised when activity detection stalls. The
 * recorder decides *what* to say; this decides whether saying it again is worth an IPC, and holds
 * the two ids and the intents that never change. The channels themselves belong to [App], which
 * creates both at startup — a notification can be posted long before this exists.
 *
 * Takes the [Service] rather than a `Context` because entering and leaving the foreground is a
 * property of the service itself, not of the notification it does it with.
 */
class RecorderNotifications(private val service: Service) {

    // Last content posted, so repeat publishes with an unchanged state don't re-post. Written from
    // the main thread by the foreground start and stop and under the recorder's lock by updates,
    // unlike [deafPosted], which every writer reaches under the lock.
    @Volatile private var lastPosted: Pair<String, String>? = null

    // Whether the alert is currently up. Tracked here rather than read back off the recorder's
    // deafness bookkeeping: this is the only thing that posts or cancels it, so it cannot disagree.
    private var deafPosted = false

    private val manager by lazy {
        ContextCompat.getSystemService(service, NotificationManager::class.java)
    }

    // The PendingIntents never change, so build them once and reuse across rebuilds — each
    // getActivity/getService is a round-trip to the system.
    private val openIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            service,
            1,
            Intent(service, LocationRecordingService::class.java).setAction(LocationRecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Enter the foreground. The `location` type is what the platform requires of this service. */
    fun startForeground(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        lastPosted = title to text
        ServiceCompat.startForeground(service, ONGOING_ID, build(title, text), type)
    }

    /** Leave the foreground and take the notification with it. */
    fun stopForeground() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        lastPosted = null
    }

    /**
     * Re-post the ongoing notification, but only when the wording actually changed: the recorder
     * publishes its status on every fix, and a post per second would cost a wakelock and an IPC
     * each time. That the caller passes state-shaped text rather than live figures is what makes
     * this dedupe effective — see the `live = null` at its call site.
     */
    fun update(title: String, text: String) {
        if (lastPosted == title to text) return
        lastPosted = title to text
        manager?.notify(ONGOING_ID, build(title, text))
    }

    /** Escalate the deafness the Record card already shows, for when the app isn't open. */
    fun warnDeaf() {
        DebugLog.w(TAG, "activity detection not responding — notifying the user")
        val text = service.getString(R.string.notification_deaf_text)
        deafPosted = true
        manager?.notify(
            ALERT_ID,
            NotificationCompat.Builder(service, App.ALERT_CHANNEL_ID)
                .setContentTitle(service.getString(R.string.notification_deaf_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                // Deliberately not auto-cancelling: the condition outlives the tap, and dismissing
                // it must not be the only way to make the warning go away.
                .setContentIntent(openIntent)
                .build(),
        )
    }

    /** Withdraw the warning. Costs nothing when none was posted, which arming and disarming rely on. */
    fun clearDeafWarning() {
        if (!deafPosted) return
        deafPosted = false
        manager?.cancel(ALERT_ID)
    }

    private fun build(title: String, text: String): Notification = NotificationCompat.Builder(service, App.CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openIntent)
        .addAction(0, service.getString(R.string.notification_stop), stopIntent)
        .build()

    private companion object {
        const val ONGOING_ID = 1001

        // The alerts channel is the second one: transient and IMPORTANCE_DEFAULT, where the ongoing
        // notification's channel is the silent one a foreground service must carry.
        const val ALERT_ID = 1002
        const val TAG = "Breadcrumb"
    }
}
