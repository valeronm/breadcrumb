package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecorderNotificationsTest {

    class StubService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private val service = Robolectric.setupService(StubService::class.java)
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val notifications = RecorderNotifications(service)

    @Before fun grantNotifications() {
        shadowOf(service.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val postedTitles: List<String?>
        get() = shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE) }

    @Test fun `an update with unchanged wording posts nothing`() {
        notifications.startForeground("Ready", "Waiting for a trip")
        manager.cancelAll()

        notifications.update("Ready", "Waiting for a trip")

        assertEquals(emptyList<String?>(), postedTitles)
    }

    @Test fun `a post made while notifications were off comes back once they are on`() {
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.startForeground("Ready", "Waiting for a trip")
        manager.cancelAll()

        shadowOf(manager).setNotificationsEnabled(true)
        notifications.restoreDropped()

        assertEquals(listOf<String?>("Ready"), postedTitles)
    }

    @Test fun `a restore waits while notifications are still off`() {
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.startForeground("Ready", "Waiting for a trip")
        manager.cancelAll()

        notifications.restoreDropped()

        assertEquals(emptyList<String?>(), postedTitles)
    }

    @Test fun `a restore posts nothing when no post was made with notifications off`() {
        notifications.startForeground("Ready", "Waiting for a trip")
        manager.cancelAll()

        notifications.restoreDropped()

        assertEquals(emptyList<String?>(), postedTitles)
    }

    @Test fun `a warning dropped by a blocked channel comes back alone once the channel is unblocked`() {
        alertChannel(NotificationManager.IMPORTANCE_NONE)
        notifications.startForeground("Ready", "Waiting for a trip")
        notifications.warnDeaf()
        manager.cancelAll()

        alertChannel(NotificationManager.IMPORTANCE_DEFAULT)
        notifications.restoreDropped()

        assertEquals(listOf<String?>(service.getString(R.string.notification_deaf_title)), postedTitles)
    }

    private fun alertChannel(importance: Int) {
        if (manager.getNotificationChannel(App.ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(App.ALERT_CHANNEL_ID, "alerts", importance))
        }
        manager.getNotificationChannel(App.ALERT_CHANNEL_ID).importance = importance
    }

    @Test fun `a restore leaves down a warning the recorder has withdrawn`() {
        shadowOf(manager).setNotificationsEnabled(false)
        notifications.startForeground("Ready", "Waiting for a trip")
        notifications.warnDeaf()
        notifications.clearDeafWarning()
        manager.cancelAll()

        shadowOf(manager).setNotificationsEnabled(true)
        notifications.restoreDropped()

        assertEquals(listOf<String?>("Ready"), postedTitles)
    }
}
