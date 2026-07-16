package io.github.valeronm.breadcrumb

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification shown while recording GPS tracks"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Data housekeeping belongs to process start, not to any one screen — the background
        // service can keep the process alive for weeks without the UI ever being opened.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val repository = TrackRepository(this@App)
            // Drop soft-deleted tracks past the retention window (kept only for tuning).
            repository.purgeOldDiscarded()
            // Crash-cleanup of dangling tracks happens in the service's arm path. One-time
            // data backfills also go here when needed — see "Backfills" in CLAUDE.md.
            if (!Settings.isPointStarvedPurgeDone(this@App)) {
                repository.purgePointStarvedTracks()
                Settings.setPointStarvedPurgeDone(this@App)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tracking"
    }
}
