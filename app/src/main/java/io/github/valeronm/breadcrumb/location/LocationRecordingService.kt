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
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.github.valeronm.breadcrumb.util.DebugLog
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityGate
import io.github.valeronm.breadcrumb.domain.Confirmed
import io.github.valeronm.breadcrumb.domain.RecordingAction
import io.github.valeronm.breadcrumb.domain.TrackController
import io.github.valeronm.breadcrumb.ui.MainActivity
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
    private lateinit var activityManager: ActivityRecognitionManager
    private var locationManager: LocationManager? = null

    // Two small state machines own the logic; this service owns only the resources below, and wires
    // them together. The gate debounces raw readings into a trusted activity; the controller turns
    // that into track lifecycle actions. All access is under [mutex].
    private val gate = ActivityGate()
    private val controller = TrackController()

    // Set while the service is armed; duplicate ACTION_STARTs while armed are no-ops.
    @Volatile private var armed = false

    @Volatile private var currentTrackId: Long? = null
    @Volatile private var distanceMeters = 0.0
    @Volatile private var pointCount = 0
    @Volatile private var trackStartedAt = 0L
    // Last point accepted as a good fix — the baseline for distance and the bad-fix jump check.
    private var lastGoodPoint: TrackPoint? = null
    private var locationListener: LocationListenerCompat? = null

    // GNSS cross-check: elapsedRealtime (ms) of the last real satellite fix seen while GPS is on.
    // 0 until the receiver first locks this session; used to reject fixes that have no recent
    // satellite backing (see [isGnssBacked]).
    @Volatile private var lastGnssFixElapsedMs = 0L
    private var gnssCallback: GnssStatusCompat.Callback? = null
    // Latest GnssStatus-derived quality, snapshotted for the next fix's metadata (null until seen).
    @Volatile private var lastGnssSatsInFix: Int? = null
    @Volatile private var lastGnssCn0Top4: Float? = null

    // --- Auto-pause / stitch resources (all touched only under [mutex]) ---
    // While paused, [currentTrackId] stays open (GPS off) so a brief stop can be stitched back into
    // the same track when the same activity resumes within the configured window.
    private var pauseToken = 0                        // generation guard for the delayed finalize
    private var pendingSegmentStart = false           // mark the first good fix after a resume as a new segment
    private var pollJob: Job? = null                  // periodic activity re-read while armed

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = TrackRepository(this)
        activityManager = ActivityRecognitionManager(this)
        locationManager = getSystemService(LocationManager::class.java)
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
        // Arming is requested from several places that can race (package-replaced receiver, the
        // activity's reconciliation, sticky restart) — collapse duplicates instead of re-arming.
        if (armed) {
            DebugLog.i(TAG, "handleStart: already armed — ignoring duplicate start")
            return
        }
        armed = true
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
                gate.onArmed()
                publishStatus()
            }
            // Arm activity recognition only after the paused state is established. Doing it before
            // lets the one-shot snapshot's applyActivity() race this block on the mutex; if the
            // snapshot won, it would open a track that finalizeDangling then deleted and onArmed()
            // then reset to STILL — wedging the recorder while GPS kept running.
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
        armed = false
        DebugLog.i(TAG, "handleStop: disarming")
        pollJob?.cancel()
        activityManager.stop()
        scope.launch {
            mutex.withLock {
                closeCurrentTrack()
                gate.onArmed()
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

    private suspend fun applyActivity(raw: ActivityType) {
        // 1) Debounce the raw reading into a trusted activity signal.
        val previous = gate.confirmed
        val confirmed = gate.onReading(
            raw,
            now(),
            ActivityGate.Config(
                startConfirmations = Settings.startConfirmations(this),
                graceWindowMs = Settings.resumeWindowSec(this) * 1000L,
                pollEnabled = Settings.activityPollEnabled(this),
            ),
        )
        when (confirmed) {
            Confirmed.NoChange -> return
            Confirmed.Cancelled -> { DebugLog.i(TAG, "pending start cancelled by $raw"); return }
            is Confirmed.Awaiting -> {
                DebugLog.i(TAG, "pending start ${confirmed.activity} (${confirmed.count}/${confirmed.needed}) — awaiting confirmation")
                return
            }
            else -> Unit // Stopped / Started / Continuing — a real change
        }

        // 2) Turn the trusted change into a track action and apply it.
        logTransition(previous, gate.confirmed)
        when (val action = controller.onConfirmed(confirmed)) {
            RecordingAction.Noop -> Unit
            is RecordingAction.Pause -> {
                DebugLog.i(TAG, "  -> pausing track $currentTrackId")
                pauseTrack(action.pausedActivity)
            }
            RecordingAction.Resume -> {
                DebugLog.i(TAG, "  -> resuming paused track $currentTrackId")
                resumeTrack(gate.confirmed)
            }
            is RecordingAction.StartNew -> {
                DebugLog.i(TAG, "  -> starting new ${action.activity} track")
                closeCurrentTrack()
                openTrack(action.activity)
            }
        }
        publishStatus()
    }

    private fun logTransition(previous: ActivityType, activity: ActivityType) {
        DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$currentTrackId paused=${controller.isPaused})")
    }

    /** Stop GPS but keep the track open; finalize it later if movement doesn't resume in time. */
    private suspend fun pauseTrack(trackActivity: ActivityType) {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        controller.onPaused(trackActivity)
        val token = ++pauseToken
        val windowMs = Settings.resumeWindowSec(this) * 1000L
        scope.launch {
            delay(windowMs)
            mutex.withLock {
                // Still the same pause, still un-resumed → finalize. (The resume decision itself is the
                // gate's job now; this is just resource cleanup for a stop nothing came back from.)
                if (controller.isPaused && pauseToken == token) {
                    DebugLog.i(TAG, "pause expired — finalizing track $currentTrackId")
                    closeCurrentTrack()
                    publishStatus()
                }
            }
        }
    }

    /** Continue the paused track: GPS back on, accumulators kept; the first fix begins a new segment. */
    private suspend fun resumeTrack(activity: ActivityType) {
        controller.onResumed(activity)
        pendingSegmentStart = true
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun openTrack(activity: ActivityType) {
        distanceMeters = 0.0
        pointCount = 0
        lastGoodPoint = null
        pendingSegmentStart = false
        val startedAt = now()
        trackStartedAt = startedAt
        val id = repository.startTrack(activity, startedAt)
        currentTrackId = id
        activeTrackId = id
        controller.onRecordingStarted(activity)
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun closeCurrentTrack() {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        val id = currentTrackId ?: return
        // A paused track ended when its last fix arrived, not now — don't count the idle gap.
        val endedAt = if (controller.isPaused) lastGoodPoint?.timestamp ?: now() else now()
        currentTrackId = null
        activeTrackId = null
        controller.onClosed()
        pendingSegmentStart = false
        repository.finishTrack(id, endedAt)
    }

    // Requests the platform GPS provider directly rather than Play Services' fused provider: fused
    // HIGH_ACCURACY also drives network location (periodic Wi-Fi scans + GmsCore wakelocks billed to
    // us), and its Wi-Fi/cell/dead-reckoning fixes are exactly what [isGnssBacked] rejects anyway.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val lm = locationManager ?: return
        val intervalMs = Settings.minIntervalSec(this) * 1000L
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(Settings.minDistanceM(this).toFloat())
            // Don't let fixes arrive faster than the user's chosen minimum interval.
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = handleLocations(listOf(location))
            override fun onLocationChanged(locations: List<Location>) = handleLocations(locations)
        }
        locationListener = listener
        LocationManagerCompat.requestLocationUpdates(
            lm,
            LocationManager.GPS_PROVIDER,
            request,
            ContextCompat.getMainExecutor(this),
            listener,
        )
        registerGnssStatus()
    }

    private fun stopLocationUpdates() {
        locationListener?.let { listener ->
            locationManager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        }
        locationListener = null
        unregisterGnssStatus()
    }

    /**
     * Track real satellite fixes in parallel with the location stream, for two uses: the
     * [isGnssBacked] cross-check (a provider may report a position without satellite backing —
     * e.g. dead-reckoned in a tunnel — with optimistic accuracy that slips through the accuracy
     * gate), and per-point quality metadata (satellites-in-fix, C/N0). Registered whenever GPS is on,
     * independent of the cross-check toggle, which only controls whether fixes are *rejected*.
     */
    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        if (gnssCallback != null) return
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = locationManager ?: return
        val callback = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                var used = 0
                val cn0s = ArrayList<Float>()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        used++
                        val cn0 = status.getCn0DbHz(i)
                        if (cn0 > 0f) cn0s.add(cn0)
                    }
                }
                lastGnssSatsInFix = used
                lastGnssCn0Top4 = if (cn0s.isEmpty()) null
                    else cn0s.sortedDescending().take(4).average().toFloat()
                if (used >= GNSS_MIN_SATELLITES_IN_FIX) lastGnssFixElapsedMs = SystemClock.elapsedRealtime()
            }
        }
        gnssCallback = callback
        LocationManagerCompat.registerGnssStatusCallback(lm, ContextCompat.getMainExecutor(this), callback)
    }

    private fun unregisterGnssStatus() {
        val cb = gnssCallback ?: return
        locationManager?.let { LocationManagerCompat.unregisterGnssStatusCallback(it, cb) }
        gnssCallback = null
        // Don't carry stale satellite metadata into the next track's first fixes.
        lastGnssSatsInFix = null
        lastGnssCn0Top4 = null
    }

    /**
     * Whether [loc] is backed by a recent real satellite fix. Fails open until the receiver first
     * locks this session (so a device/spot where GNSS never contributes still records rather than
     * emptying the track); once it has locked, a fix whose elapsed timestamp is more than
     * [GNSS_FIX_MAX_AGE_MS] past the last satellite fix is treated as a network/dead-reckoning
     * fabrication.
     */
    private fun isGnssBacked(loc: Location): Boolean {
        val lastGnss = lastGnssFixElapsedMs
        if (lastGnss == 0L) return true
        val fixElapsedMs = loc.elapsedRealtimeNanos / 1_000_000L
        return fixElapsedMs - lastGnss <= GNSS_FIX_MAX_AGE_MS
    }

    // Fixes are ingested under [mutex] so they serialize with activity changes (which retarget the
    // current track) instead of racing them.
    private fun handleLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        scope.launch { mutex.withLock { ingestLocations(locations) } }
    }

    private suspend fun ingestLocations(locations: List<Location>) {
        val maxAccuracyM = Settings.accuracyGateM(this).toFloat()
        val requireGnss = Settings.requireGnssFix(this)
        for (loc in locations) {
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
                verticalAccuracy = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
                speedAccuracy = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
                bearingAccuracy = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else null,
                satellitesInFix = lastGnssSatsInFix,
                cn0 = lastGnssCn0Top4,
                provider = loc.provider,
            )
            // The first good fix after a resume begins a new segment: disconnect it from the previous
            // segment so the paused gap isn't jump-checked or counted in distance.
            val segStart = pendingSegmentStart
            val baseline = if (segStart) null else lastGoodPoint
            // Bad fixes are still stored (with the reason), just excluded from distance and the
            // good-point baseline. A fix with no recent satellite backing is treated the same way.
            val reason = if (requireGnss && !isGnssBacked(loc)) {
                IgnoreReason.NO_GNSS
            } else {
                TrackQuality.badFixReason(baseline, candidate, gate.confirmed, maxAccuracyM)
            }
            if (reason == IgnoreReason.NO_GNSS) {
                DebugLog.i(TAG, "fix dropped — no recent GNSS backing (acc=${candidate.accuracy})")
            }
            val bad = reason != null
            val point = candidate.copy(
                ignored = bad,
                ignoreReason = reason?.code,
                segmentStart = segStart && !bad,
            )
            if (!bad) {
                if (baseline != null) distanceMeters += TrackQuality.distanceMeters(baseline, point)
                lastGoodPoint = point
                pointCount++
                if (segStart) pendingSegmentStart = false
            }
            repository.addPoint(point)
        }
        // One distance write per batch — only the final value persists, and a LocationResult can
        // deliver several buffered fixes at once.
        currentTrackId?.let { repository.updateDistance(it, distanceMeters) }
        publishStatus()
    }

    private fun publishStatus() {
        val activity = gate.confirmed
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activityLabel = activity.label,
                recording = activity.recording,
                distanceMeters = if (activity.recording) distanceMeters else 0.0,
                points = if (activity.recording) pointCount else 0,
                startedAtMillis = if (activity.recording && trackStartedAt > 0) trackStartedAt else null,
                speedMps = if (activity.recording) lastGoodPoint?.speed else null,
                altitudeM = if (activity.recording) lastGoodPoint?.altitude else null,
            )
        }
        // State only — no live distance. The notification re-posts only on activity/pause
        // transitions (a per-fix post costs a wakelock + IPC every second while recording).
        val detail = if (activity.recording) "Recording" else "Paused — waiting for movement"
        updateNotification(activity.label, detail)
    }

    // --- Notifications -------------------------------------------------------

    // Last content posted, so repeat publishStatus() calls with an unchanged state don't re-post.
    private var lastNotified: Pair<String, String>? = null

    private fun startForegroundWithNotification(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        lastNotified = title to text
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title, text), type)
    }

    private fun updateNotification(title: String, text: String) {
        if (lastNotified == title to text) return
        lastNotified = title to text
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    // The notification's PendingIntents never change, so build them once and reuse across
    // notification rebuilds (each getActivity/getService is a round-trip to the system).
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

        // A fix counts as GNSS-backed when a satellite fix using at least this many satellites
        // occurred within [GNSS_FIX_MAX_AGE_MS] of it. Four is the minimum for a genuine 3D fix;
        // below that the position isn't independently satellite-determined. Tunables for field-testing
        // the cross-check against the tunnel/underpass fabrication case.
        private const val GNSS_MIN_SATELLITES_IN_FIX = 4
        private const val GNSS_FIX_MAX_AGE_MS = 5_000L

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
