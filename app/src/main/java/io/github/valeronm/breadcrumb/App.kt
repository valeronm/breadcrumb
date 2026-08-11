package io.github.valeronm.breadcrumb

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.valeronm.breadcrumb.data.DerivationStore
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // First, so no line of this process's life predates the file — the log is the only record
        // of a field session that survives the process, and the earliest lines (a boot re-arm, a
        // transition that cold-started us) are usually the ones an investigation wants.
        DebugLog.attach(File(filesDir, "logs"))
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_tracking_description)
            setShowBadge(false)
        }
        // Separate from the ongoing tracking notification: this one is rare, actionable, and
        // must not be silent — it is the only way the user learns recording has stopped working.
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_alerts_description)
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
            // Drop soft-deleted tracks whose Recently-deleted review window has lapsed.
            repository.purgeOldDiscarded()
            // Crash-cleanup of dangling tracks happens in the service's arm path. One-time
            // data backfills also go here when needed — see "Backfills" in CLAUDE.md.
            // The ignored edge stays are verdicts of a rule that keeps moving, so they are
            // re-derived whenever the detector's version outruns the one they were computed
            // with — not once.
            // Whether either sweep below moved a track's bounds or its first/last good coordinates —
            // the derivation's whole input, and so what the rebuild at the end hangs on as much as
            // on its own rule version.
            var derivedInputMoved = false
            if (Settings.edgeStayRuleVersion(this@App) < EdgeStayDetector.RULE_VERSION) {
                if (repository.sweepEdgeStays()) derivedInputMoved = true
                Settings.setEdgeStayRuleVersion(this@App, EdgeStayDetector.RULE_VERSION)
            }
            // The aggregates on a track row are the output of a walk that keeps moving too, and
            // they are re-derived the same way. It runs second: the edge-stay sweep decides which
            // fixes are on the path, and this one totals whatever that leaves.
            if (Settings.statsRuleVersion(this@App) < TrackStats.RULE_VERSION) {
                if (repository.sweepStats()) derivedInputMoved = true
                Settings.setStatsRuleVersion(this@App, TrackStats.RULE_VERSION)
            }
            // Last, and the order is load-bearing: both sweeps above rewrite a track's bounds and
            // its first and last good coordinates, which are the whole of what the derivation reads.
            // Deriving ahead of them would store a reading of values about to move, with nothing to
            // say it was stale — which is also why a sweep that *did* move any of them re-derives
            // here whether or not this build's rules changed. A bound moves without a coordinate
            // moving whenever a track's clock was out past its fixes, so the flag cannot be read off
            // the coordinates alone. A seed moved while the app was closed (none can be today, but a
            // future importer could) is the reconcile's own business.
            val ruleMoved = Settings.derivedLogicVersion(this@App) < DerivationStore.LOGIC_VERSION
            DerivationStore(this@App).reconcile(stale = derivedInputMoved || ruleMoved)
            if (ruleMoved) Settings.setDerivedLogicVersion(this@App, DerivationStore.LOGIC_VERSION)
        }
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val ALERT_CHANNEL_ID = "alerts"
    }
}
