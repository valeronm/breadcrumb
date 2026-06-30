package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import io.github.valeronm.breadcrumb.util.DebugLog
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.ui.MainActivity
import io.github.valeronm.breadcrumb.util.formatKm
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service that records GPS while the app is in the background. It listens for activity
 * changes (delivered via [ActivityTransitionReceiver]) and adjusts the GPS sampling profile, or
 * pauses recording entirely while the user is stationary.
 */
class LocationRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private lateinit var repository: TrackRepository
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var activityManager: ActivityRecognitionManager

    @Volatile private var currentActivity = ActivityType.STILL
    @Volatile private var currentTrackId: Long? = null
    @Volatile private var distanceMeters = 0.0
    @Volatile private var pointCount = 0
    // Last point accepted as a good fix — the baseline for distance and the bad-fix jump check.
    private var lastGoodPoint: TrackPoint? = null
    private var locationCallback: LocationCallback? = null

    // --- Auto-pause / stitch state (all touched only under [mutex]) ---
    // While paused, [currentTrackId] stays open (GPS off) so a brief stop can be stitched back into
    // the same track when the same activity resumes within the configured window.
    @Volatile private var pausedSince: Long? = null
    private var pausedActivity: ActivityType? = null
    private var pauseAnchor: TrackPoint? = null      // last good point at pause, for the resume-distance check
    private var pauseToken = 0                        // generation guard for the delayed finalize
    private var verifyResumeDistance = false          // check the first fix after a resume isn't too far
    private var pendingSegmentStart = false           // mark the first good fix after a resume as a new segment
    private var pollJob: Job? = null                  // periodic activity re-read while armed

    // Start debounce: a new track only opens after the same moving activity is seen this many times
    // in a row, so a lone blip that reverts to STILL next poll doesn't spin up GPS for nothing.
    private var pendingStartActivity: ActivityType? = null
    private var pendingStartCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = TrackRepository(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        activityManager = ActivityRecognitionManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            // Null intent = the system restarted us (START_STICKY) after process death.
            // Resume only if auto-recording is still armed; otherwise shut down cleanly.
            else -> if (Settings.isAutoRecord(this)) handleStart() else stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart() {
        DebugLog.i(TAG, "handleStart: arming (autoRecord=${Settings.isAutoRecord(this)})")
        startForegroundWithNotification(ActivityType.STILL.label, "Waiting for movement…")
        TrackingStatus.update { it.copy(tracking = true) }

        // Start armed but paused — recording begins when a moving activity transition arrives.
        // (Don't optimistically open a track: while stationary it would just be created and
        // immediately discarded, flashing the UI.)
        scope.launch {
            mutex.withLock {
                // Close any track left open by a previous crash/kill, but never the one we're
                // actively recording (a snapshot may have already opened it).
                repository.finalizeDangling(exceptTrackId = activeTrackId)
                currentActivity = ActivityType.STILL
                clearPendingStart()
                publishStatus()
            }
            // Arm activity recognition only after the paused state is established. Doing it before
            // lets the one-shot snapshot's applyActivity() race this block on the mutex; if the
            // snapshot won, it would open a track that finalizeDangling then deleted and
            // currentActivity = STILL then reset — wedging the recorder while GPS kept running.
            withContext(Dispatchers.Main) {
                if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    activityManager.start()
                    // One-shot: if we're already moving right now, start recording without waiting
                    // for the next transition.
                    activityManager.requestSnapshot()
                }
            }
        }
        startActivityPoll()
    }

    /**
     * Re-reads the current activity on a timer while armed. Transitions are lazy: they're filtered,
     * can lag minutes, suspend during long stillness, and replay stale events on registration. The
     * snapshot (current-state read) is the reliable signal — polling it catches starts/stops the
     * transition stream misses (and re-tries after a cold-engine UNKNOWN). The receiver applies the
     * result in both directions.
     */
    private fun startActivityPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Re-read interval and toggle each cycle so changes take effect without re-arming.
                delay(Settings.activityPollIntervalSec(this@LocationRecordingService) * 1000L)
                if (Settings.activityPollEnabled(this@LocationRecordingService) &&
                    isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
                ) {
                    withContext(Dispatchers.Main) { activityManager.requestSnapshot() }
                }
            }
        }
    }

    private fun handleStop() {
        DebugLog.i(TAG, "handleStop: disarming")
        pollJob?.cancel()
        activityManager.stop()
        scope.launch {
            mutex.withLock {
                closeCurrentTrack()
                currentActivity = ActivityType.STILL
                clearPendingStart()
            }
            withContext(Dispatchers.Main) {
                TrackingStatus.reset()
                ServiceCompat.stopForeground(this@LocationRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Called by [ActivityTransitionReceiver] when Play Services reports a new activity. */
    fun onActivityChanged(activity: ActivityType) {
        scope.launch { mutex.withLock { applyActivity(activity) } }
    }

    private suspend fun applyActivity(activity: ActivityType) {
        if (!activity.recording) {
            // STILL (or any non-recording state). A stop also cancels an as-yet-unconfirmed start.
            val hadPending = pendingStartActivity != null
            clearPendingStart()
            if (activity == currentActivity) {
                if (hadPending) DebugLog.i(TAG, "pending start cancelled by $activity")
                return
            }
            val previous = currentActivity
            currentActivity = activity
            DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$currentTrackId paused=${pausedSince != null})")
            // Pause the open track rather than closing it, so a brief stop can be stitched back into
            // the same track when movement resumes. With the window disabled (0), close immediately.
            if (currentTrackId != null && pausedSince == null) {
                if (Settings.resumeWindowSec(this) > 0) {
                    DebugLog.i(TAG, "  -> pausing track $currentTrackId")
                    pauseTrack(previous)
                } else {
                    DebugLog.i(TAG, "  -> closing track $currentTrackId (resume window off)")
                    closeCurrentTrack()
                }
            } else {
                DebugLog.i(TAG, "  -> no open track to pause")
            }
            publishStatus()
            return
        }

        // A moving activity. Resume the paused track if it's the same activity within the window —
        // not gated, the paused track already represents recent real movement.
        if (canResume(activity)) {
            clearPendingStart()
            val previous = currentActivity
            currentActivity = activity
            DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$currentTrackId paused=${pausedSince != null})")
            DebugLog.i(TAG, "  -> resuming paused track $currentTrackId")
            resumeTrack()
            publishStatus()
            return
        }

        // Already actively recording this same activity — nothing to commit.
        if (activity == currentActivity && currentTrackId != null && pausedSince == null) {
            clearPendingStart()
            return
        }

        // Anything else opens a new track, which turns GPS on — so don't act on a single reading.
        // A lone high-confidence blip (the user shifts the phone or takes a few steps) reverts to
        // STILL on the next ~30 s poll; require the same moving activity to persist across
        // [startConfirmations] readings first. Until confirmed, [currentActivity] is left unchanged
        // so the next reading re-enters here (and a STILL above cancels the pending start).
        //
        // The confirming reading can only come from the poll: transitions fire a single ENTER per
        // movement onset, so with the poll off a streak could never complete and recording would
        // never start. Fall back to an instant start when polling is disabled.
        val needed = if (Settings.activityPollEnabled(this)) Settings.startConfirmations(this) else 1
        if (needed > 1) {
            pendingStartCount = if (pendingStartActivity == activity) pendingStartCount + 1 else 1
            pendingStartActivity = activity
            if (pendingStartCount < needed) {
                DebugLog.i(TAG, "pending start $activity ($pendingStartCount/$needed) — awaiting confirmation")
                return
            }
        }
        clearPendingStart()
        val previous = currentActivity
        currentActivity = activity
        DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$currentTrackId paused=${pausedSince != null})")
        DebugLog.i(TAG, "  -> starting new $activity track")
        closeCurrentTrack()
        openTrack(activity)
        publishStatus()
    }

    private fun clearPendingStart() {
        pendingStartActivity = null
        pendingStartCount = 0
    }

    private fun canResume(activity: ActivityType): Boolean {
        val since = pausedSince ?: return false
        if (currentTrackId == null || pausedActivity != activity) return false
        val windowMs = Settings.resumeWindowSec(this) * 1000L
        return windowMs > 0 && now() - since <= windowMs
    }

    /** Stop GPS but keep the track open; finalize it later if movement doesn't resume in time. */
    private suspend fun pauseTrack(trackActivity: ActivityType) {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        pausedSince = now()
        pausedActivity = trackActivity
        pauseAnchor = lastGoodPoint
        val token = ++pauseToken
        val windowMs = Settings.resumeWindowSec(this) * 1000L
        scope.launch {
            delay(windowMs)
            mutex.withLock {
                // Still the same pause, still un-resumed → the stop outlasted the window; finalize.
                if (pausedSince != null && pauseToken == token) {
                    closeCurrentTrack()
                    publishStatus()
                }
            }
        }
    }

    /** Continue the paused track: GPS back on, accumulators kept; the first fix verifies distance. */
    private suspend fun resumeTrack() {
        pausedSince = null
        pausedActivity = null
        verifyResumeDistance = true
        pendingSegmentStart = true
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun openTrack(activity: ActivityType) {
        distanceMeters = 0.0
        pointCount = 0
        lastGoodPoint = null
        verifyResumeDistance = false
        pendingSegmentStart = false
        val id = repository.startTrack(activity, now())
        currentTrackId = id
        activeTrackId = id
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun closeCurrentTrack() {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        val id = currentTrackId ?: return
        // A paused track ended when its last fix arrived, not now — don't count the idle gap.
        val endedAt = if (pausedSince != null) lastGoodPoint?.timestamp ?: now() else now()
        currentTrackId = null
        activeTrackId = null
        pausedSince = null
        pausedActivity = null
        pauseAnchor = null
        verifyResumeDistance = false
        pendingSegmentStart = false
        repository.finishTrack(id, endedAt)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val intervalMs = Settings.minIntervalSec(this) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(Settings.minDistanceM(this).toFloat())
            // Don't let fixes arrive faster than the user's chosen minimum interval.
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = handleLocations(result.locations)
        }
        locationCallback = callback
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fused.removeLocationUpdates(it) }
        locationCallback = null
    }

    // Fixes are ingested under [mutex] so they serialize with activity changes (which retarget the
    // current track) instead of racing them.
    private fun handleLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        scope.launch { mutex.withLock { ingestLocations(locations) } }
    }

    private suspend fun ingestLocations(locations: List<Location>) {
        val maxAccuracyM = Settings.accuracyGateM(this).toFloat()
        for (loc in locations) {
            // First fix after a stitched resume: if it's far from where the track paused, the "pause"
            // actually covered real travel (a mislabelled-STILL drive); split into a fresh track.
            if (verifyResumeDistance) {
                verifyResumeDistance = false
                val anchor = pauseAnchor
                pauseAnchor = null
                if (anchor != null) {
                    val gap = FloatArray(1)
                    Location.distanceBetween(anchor.latitude, anchor.longitude, loc.latitude, loc.longitude, gap)
                    if (gap[0] > Settings.resumeDistanceM(this)) {
                        closeCurrentTrack()
                        openTrack(currentActivity)
                    }
                }
            }
            val trackId = currentTrackId ?: return
            val candidate = TrackPoint(
                trackId = trackId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                timestamp = if (loc.time > 0) loc.time else now(),
            )
            // The first good fix after a resume begins a new segment: disconnect it from the previous
            // segment so the paused gap isn't jump-checked or counted in distance.
            val segStart = pendingSegmentStart
            val baseline = if (segStart) null else lastGoodPoint
            // Bad fixes are still stored, just excluded from distance and the good-point baseline.
            val bad = TrackQuality.isBadFix(baseline, candidate, currentActivity, maxAccuracyM)
            val point = candidate.copy(ignored = bad, segmentStart = segStart && !bad)
            if (!bad) {
                if (baseline != null) distanceMeters += TrackQuality.distanceMeters(baseline, point)
                lastGoodPoint = point
                pointCount++
                if (segStart) pendingSegmentStart = false
            }
            repository.addPoint(point)
            repository.updateDistance(trackId, distanceMeters)
        }
        publishStatus()
    }

    private fun publishStatus() {
        val activity = currentActivity
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activityLabel = activity.label,
                recording = activity.recording,
                distanceMeters = if (activity.recording) distanceMeters else 0.0,
                points = if (activity.recording) pointCount else 0,
            )
        }
        val detail = if (activity.recording) {
            "${formatKm(distanceMeters)} · $pointCount pts"
        } else {
            "Paused — waiting for movement"
        }
        updateNotification(activity.label, detail)
    }

    // --- Notifications -------------------------------------------------------

    private fun startForegroundWithNotification(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title, text), type)
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    // The notification's PendingIntents never change, so build them once and reuse across the
    // per-fix notification rebuilds (each getActivity/getService is a round-trip to the system).
    private val openIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, LocationRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("Tracking: $title")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }


    private fun now() = System.currentTimeMillis()

    override fun onDestroy() {
        stopLocationUpdates()
        instance = null
        activeTrackId = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var instance: LocationRecordingService? = null
            private set

        /** True while the service is alive in this process. */
        val isRunning: Boolean get() = instance != null

        /** Id of the track currently being recorded, or null. Used to skip it during cleanup. */
        @Volatile
        var activeTrackId: Long? = null
            private set

        const val ACTION_START = "io.github.valeronm.breadcrumb.START"
        const val ACTION_STOP = "io.github.valeronm.breadcrumb.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "Breadcrumb"

        fun start(context: Context) {
            Settings.setAutoRecord(context, true)
            val intent = Intent(context, LocationRecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            Settings.setAutoRecord(context, false)
            val intent = Intent(context, LocationRecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
