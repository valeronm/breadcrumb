package io.github.valeronm.breadcrumb

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
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
        // Separate from the ongoing tracking notification: this one is rare, actionable, and
        // must not be silent — it is the only way the user learns recording has stopped working.
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Recording problems",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shown when automatic recording stops responding"
        }
        // One transaction, not two: onCreate runs on every process start, including the cold
        // starts a transition broadcast or the watchdog alarm triggers.
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(channel, alerts))

        // Data housekeeping belongs to process start, not to any one screen — the background
        // service can keep the process alive for weeks without the UI ever being opened.
        // The handler keeps a failed pass from crashing the process: this block runs on every
        // start, so an uncaught throw here wouldn't just lose one sweep — it would crash every
        // launch until the data or rule changed.
        val logCrash = CoroutineExceptionHandler { _, e -> DebugLog.e("App", "housekeeping failed", e) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + logCrash).launch {
            val repository = TrackRepository(this@App)
            // Drop soft-deleted tracks past the retention window (kept only for tuning).
            repository.purgeOldDiscarded()
            // Crash-cleanup of dangling tracks happens in the service's arm path. One-time
            // data backfills also go here when needed — see "Backfills" in CLAUDE.md.
            // The ignored edge stays are verdicts of a rule that keeps moving, so they are
            // re-derived whenever the detector's version outruns the one they were computed
            // with — not once.
            if (Settings.edgeStayRuleVersion(this@App) < EdgeStayDetector.RULE_VERSION) {
                repository.sweepEdgeStays()
                Settings.setEdgeStayRuleVersion(this@App, EdgeStayDetector.RULE_VERSION)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val ALERT_CHANNEL_ID = "alerts"
    }
}
