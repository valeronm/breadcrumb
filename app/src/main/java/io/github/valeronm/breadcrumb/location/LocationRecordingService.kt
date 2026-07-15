package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.valeronm.breadcrumb.util.DebugLog
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackGroup
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityGate
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ReadingClock
import io.github.valeronm.breadcrumb.domain.RecordingAction
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackController
import io.github.valeronm.breadcrumb.ui.MainActivity
import io.github.valeronm.breadcrumb.util.hasLocationPermission
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private lateinit var livenessRepository: LivenessRepository
    private lateinit var activityManager: ActivityRecognitionManager
    private var locationManager: LocationManager? = null
    private lateinit var fused: FusedLocationProviderClient

    // --- Liveness heartbeat (evidence for stay derivation) ---
    // A periodic "still alive" timestamp in Settings; a restart finding it stale materializes an
    // OUTAGE row so the silent interval isn't derived as a stay. Doze defers the loop's delay —
    // that's fine: a dozed phone is alive, and a late heartbeat only widens a real outage's start.
    private var heartbeatJob: Job? = null
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Best-effort exact outage start on a clean power-off; synchronous — the process dies.
            Settings.setLastHeartbeatMs(context, System.currentTimeMillis(), sync = true)
        }
    }

    // Two small state machines own the logic; this service owns only the resources below, and wires
    // them together. The gate debounces raw readings into a trusted activity; the controller turns
    // that into track lifecycle actions. All access is under [mutex].
    private val gate = ActivityGate()
    private val controller = TrackController()

    // Set while the service is armed; duplicate ACTION_STARTs while armed are no-ops.
    @Volatile private var armed = false

    // When the current armed session began, and when the stale-reading oracle last forced a
    // re-registration — see the deafness check in [applyActivity].
    @Volatile private var armedAtMs = 0L
    private var lastStaleRestartMs = 0L

    // True once any transition reading has been applied since the last arm. Read by
    // [ActivityTransitionReceiver] to drop the arm-time snapshot once the transition stream has
    // spoken — set synchronously on delivery (not in the apply coroutine) so a snapshot arriving
    // after a transition can never slip past the check while the apply is still queued.
    @Volatile var transitionSinceArm = false
        private set

    @Volatile private var trackStartedAt = 0L

    // The open track's running aggregates. The same accumulator the repository folds the stored
    // points through when the track is finished ([TrackStats]) — so the total the user watches on
    // the Record card and the one written to the track row can't drift apart. Touched only under
    // [mutex]; its [TrackStats.Accumulator.lastGood] is also the bad-fix jump check's baseline.
    private var accumulator = TrackStats.Accumulator()

    // The live location request, whichever source made it; non-null == GPS is on.
    private sealed interface ActiveRequest {
        class Gps(val listener: LocationListenerCompat) : ActiveRequest
        class Fused(val callback: LocationCallback) : ActiveRequest
    }

    @Volatile private var activeRequest: ActiveRequest? = null

    // --- No-fix give-up guard ---
    // The decisions (when to give up, backoff gating, what a resume signal means) live in the pure
    // [NoFixGuard]; this service owns only the side effects — GPS on/off, the significant-motion
    // sensor, and the passive listener. Guard state is touched under [mutex] except the benign
    // racy pre-check in [maybeGiveUpOnNoFix].
    private val noFixGuard = NoFixGuard()
    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private var motionListener: TriggerEventListener? = null
    private var passiveListener: LocationListenerCompat? = null

    // GNSS cross-check: elapsedRealtime (ms) of the last real satellite fix seen while GPS is on.
    // 0 until the receiver first locks this session; used to reject fixes that have no recent
    // satellite backing (see [isGnssBacked]).
    @Volatile private var lastGnssFixElapsedMs = 0L
    private var gnssCallback: GnssStatusCompat.Callback? = null
    // Latest GnssStatus-derived quality, snapshotted for the next fix's metadata (null until seen).
    @Volatile private var lastGnssSatsInFix: Int? = null
    @Volatile private var lastGnssCn0Top4: Float? = null

    // --- Auto-pause / stitch resources (all touched only under [mutex]) ---
    // While paused, [activeTrackId] stays open (GPS off) so a brief stop can be stitched back into
    // the same track when the same activity resumes within the configured window.
    private var pendingSegmentStart = false           // mark the first good fix after a resume as a new segment
    private var pauseDeadlineMs: Long? = null         // resume-window end, for the Record tab's countdown

    // Last fix's accuracy and whether the gate rejected it — the "waiting for GPS" card's feedback.
    private var lastFixAccuracyM: Float? = null
    private var lastFixRejectedByAccuracy = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = TrackRepository(this)
        livenessRepository = LivenessRepository(this)
        activityManager = ActivityRecognitionManager(this)
        locationManager = getSystemService(LocationManager::class.java)
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        // Must be dynamic: ACTION_SHUTDOWN is not on the API-26+ implicit-broadcast exemption
        // list, so a manifest receiver would never fire.
        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN).apply { addAction("android.intent.action.QUICKBOOT_POWEROFF") },
            ContextCompat.RECEIVER_EXPORTED,
        )
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
        // A location-type foreground service can't be started without location permission (the
        // platform throws SecurityException on Android 14+). This is reachable when the OS restarts
        // the sticky service after the user revoked location — or after unused-app auto-revoke —
        // while the armed flag is still set. Bail out cleanly instead of crash-looping; the UI's
        // permission prompt takes over. (The startForegroundService caller path is guarded in
        // [start] so this only fires for system-initiated restarts, which carry no
        // startForeground deadline.)
        if (!hasLocationPermission()) {
            DebugLog.i(TAG, "handleStart: location permission missing — staying disarmed")
            armed = false
            TrackingStatus.reset()
            stopSelf()
            return
        }
        // Arming is requested from several places that can race (package-replaced receiver, the
        // activity's reconciliation, sticky restart) — collapse duplicates instead of re-arming.
        if (armed) {
            DebugLog.i(TAG, "handleStart: already armed — ignoring duplicate start")
            return
        }
        armed = true
        armedAtMs = now()
        transitionSinceArm = false
        DebugLog.i(TAG, "handleStart: arming (autoRecord=${Settings.isAutoRecord(this)})")
        startForegroundWithNotification("Standing by", "Waiting for movement")
        scheduleWatchdog()
        TrackingStatus.update { it.copy(tracking = true) }

        // Start armed but paused — recording begins when a moving activity transition arrives.
        // (Don't optimistically open a track: while stationary it would just be created and
        // immediately discarded, flashing the UI.)
        scope.launch {
            mutex.withLock {
                // Close any track left open by a previous crash/kill, but never the one we're
                // actively recording (a snapshot may have already opened it).
                repository.finalizeDangling(exceptTrackId = activeTrackId)
                // Liveness bookkeeping: if the heartbeat went stale while armed, the app was dead
                // for that span — record the outage before the new ARMED row.
                livenessRepository.materializeOutageIfDead(
                    lastHeartbeat = Settings.lastHeartbeatMs(this@LocationRecordingService),
                    now = now(),
                    toleranceMs = StayDeriver.Params().heartbeatToleranceMs,
                )
                livenessRepository.recordArmed(now())
                startHeartbeat()
                gate.onArmed()
                publishStatus()
            }
            // Arm activity recognition only after the paused state is established. Doing it before
            // lets the one-shot snapshot's applyActivity() race this block on the mutex; if the
            // snapshot won, it would open a track that finalizeDangling then deleted and onArmed()
            // then reset to STILL — wedging the recorder while GPS kept running.
            withContext(Dispatchers.Main) {
                if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    // restart, not start: arming after a package update finds a registration whose
                    // PendingIntent token is dead, and only a fresh token delivers again.
                    activityManager.restart()
                    // One-shot: if we're already moving right now, start recording without waiting
                    // for the next transition.
                    activityManager.requestSnapshot()
                }
            }
        }
    }

    private fun handleStop() {
        armed = false
        DebugLog.i(TAG, "handleStop: disarming")
        cancelWatchdog()
        activityManager.stop()
        scope.launch {
            mutex.withLock {
                closeCurrentTrack()
                gate.onArmed()
                heartbeatJob?.cancel()
                heartbeatJob = null
                Settings.setLastHeartbeatMs(this@LocationRecordingService, now())
                livenessRepository.recordDisarmed(now())
            }
            withContext(Dispatchers.Main) {
                TrackingStatus.reset()
                ServiceCompat.stopForeground(this@LocationRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // Sanitizes AR event timestamps into gate reading times; see [ReadingClock].
    private val readingClock = ReadingClock()

    /**
     * Called by [ActivityTransitionReceiver] when Play Services reports a transition.
     * [eventTimeMs] is the event's own (wall-clock) timestamp; [onApplied] runs once the reading
     * has been applied — the receiver holds its broadcast wakelock open until then, so Doze can't
     * freeze the apply between delivery and processing.
     */
    fun onActivityChanged(activity: ActivityType, eventTimeMs: Long? = null, onApplied: (() -> Unit)? = null) {
        transitionSinceArm = true
        applyActivityAsync(activity, eventTimeMs, onApplied)
    }

    /** The arm-time snapshot reading — applied like a transition but never claims to be one. */
    fun onSnapshot(activity: ActivityType, eventTimeMs: Long? = null, onApplied: (() -> Unit)? = null) {
        applyActivityAsync(activity, eventTimeMs, onApplied)
    }

    private fun applyActivityAsync(activity: ActivityType, eventTimeMs: Long?, onApplied: (() -> Unit)?) {
        // invokeOnCompletion (not try/finally in the body): it also fires when the scope was
        // already cancelled and the body never ran — otherwise a dying service would leak the
        // receiver's goAsync and pin the broadcast until the system times it out.
        scope.launch { mutex.withLock { applyActivity(activity, eventTimeMs) } }
            .invokeOnCompletion { onApplied?.invoke() }
    }

    private suspend fun applyActivity(raw: ActivityType, eventTimeMs: Long?) {
        // 1) Debounce the raw reading into a trusted activity signal. The gate gets the event's
        // own (sanitized) time, not the apply time: readings drained late from a frozen queue
        // must keep their real spacing, or a stop and a return ten minutes apart would land
        // inside the resume window and stitch through a genuine stop.
        val nowMs = now()
        val lastReadingMs = readingClock.lastReadingMs
        val readingMs = readingClock.sanitize(eventTimeMs, nowMs, READING_MAX_AGE_MS)
        // Deafness oracle: live deliveries run seconds behind their event, so a clock-advancing
        // reading whose event fired well in the past *while we were armed* is one GMS never
        // delivered — it only reached us as a registration replay. That proves the registration
        // is deaf (a package update or a GMS restart kills them silently); re-register on a fresh
        // PendingIntent token, the only revive that works. Requiring the clock to advance skips
        // replays that repeat an event already applied; the arm-time replay is exempt because its
        // event legitimately predates the arm. Rate-limited: the re-mint itself can lose a GMS
        // server-side race, and the next detection retries it.
        if (eventTimeMs != null && readingMs > lastReadingMs && readingMs > armedAtMs &&
            nowMs - readingMs > STALE_READING_RESTART_MS &&
            nowMs - lastStaleRestartMs > STALE_RESTART_MIN_GAP_MS
        ) {
            lastStaleRestartMs = nowMs
            DebugLog.w(TAG, "reading ${(nowMs - readingMs) / 1000}s late — registration deaf, re-registering")
            activityManager.restart()
        }
        // Every delivery — even a NoChange — proves activity detection is alive; the Record
        // tab's standing-by card surfaces this.
        TrackingStatus.update { it.copy(lastReadingAtMillis = readingMs) }
        val previous = gate.confirmed
        val changed = gate.onReading(raw) ?: return

        // 2) Turn the trusted change into a track action and apply it. The controller compares
        // the reading's own time against the pause deadline, so a late-drained reading can't
        // stitch through a genuine stop even if the pause wake never fired.
        logTransition(previous, changed, nowMs - readingMs)
        val action = controller.onActivity(
            changed, readingMs, Settings.resumeWindowSec(this) * 1000L,
        )
        when (action) {
            RecordingAction.Noop -> Unit
            is RecordingAction.Pause -> {
                DebugLog.i(TAG, "  -> pausing track $activeTrackId")
                pauseTrack(action.pausedActivity, action.resumeDeadlineMs)
            }
            RecordingAction.Finalize -> {
                // Unreachable from a reading (expiry only comes from a tick); for totality.
                DebugLog.i(TAG, "  -> finalizing track $activeTrackId")
                closeCurrentTrack()
            }
            RecordingAction.Resume -> {
                DebugLog.i(TAG, "  -> resuming paused track $activeTrackId")
                resumeTrack(changed)
            }
            is RecordingAction.StartNew -> {
                DebugLog.i(TAG, "  -> starting new ${action.activity} track")
                closeCurrentTrack()
                openTrack(action.activity)
            }
            is RecordingAction.ContinueSameTrack -> {
                // Same motion family (e.g. walking ⇄ running): keep the track and its label, just
                // break a new segment at the boundary. GPS is already running.
                DebugLog.i(TAG, "  -> ${action.activity} continues track $activeTrackId (same family); new segment")
                pendingSegmentStart = true
                controller.onRecording(action.activity)
            }
        }
        // A confirmed moving reading while the no-fix guard has GPS off is a resume signal too
        // (Resume/StartNew restart GPS themselves; this covers confirmations that map to Noop).
        if (noFixGuard.suspended && gate.confirmed.recording && activeRequest == null) {
            DebugLog.i(TAG, "no-fix guard: probing again (activity ${gate.confirmed})")
            withContext(Dispatchers.Main) { startLocationUpdates() }
        }
        publishStatus()
    }

    private fun logTransition(previous: ActivityType, activity: ActivityType, readingLagMs: Long) {
        // Surface a materially late reading (Doze drain, replay recovery) — it explains why a
        // track decision doesn't line up with the log line's own timestamp.
        val lag = if (readingLagMs > 5_000) " reading=-${readingLagMs / 1000}s" else ""
        DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$activeTrackId paused=${controller.isPaused}$lag)")
    }

    /** Stop GPS but keep the track open; a wake at [resumeDeadlineMs] finalizes it if unresumed. */
    private suspend fun pauseTrack(trackActivity: ActivityType, resumeDeadlineMs: Long) {
        pauseDeadlineMs = resumeDeadlineMs
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        noFixGuard.onStopped()
        controller.onPaused(trackActivity, resumeDeadlineMs)
        scope.launch {
            delay(resumeDeadlineMs - now())
            // Logic-free wake: a stale deadline (after a resume, fresh start, or newer pause)
            // is a no-op inside finalizeExpiredPause.
            finalizeExpiredPause()
        }
    }

    /**
     * Close a paused track whose resume window has passed. Only [finalizeExpiredPause] may call
     * this: the close must be paired with the [publishStatus] that pushes the post-pause state to
     * the UI and notification — a finalize without a publish leaves both showing a stale pause,
     * and every later publish trigger early-outs because the controller is no longer paused.
     * Caller holds [mutex].
     */
    private suspend fun finalizeIfPauseExpired(): Boolean {
        if (controller.onTick(now()) != RecordingAction.Finalize) return false
        DebugLog.i(TAG, "pause expired — finalizing track $activeTrackId")
        closeCurrentTrack()
        return true
    }

    /** Continue the paused track: GPS back on, accumulators kept; the first fix begins a new segment. */
    private suspend fun resumeTrack(activity: ActivityType) {
        pauseDeadlineMs = null
        controller.onRecording(activity)
        pendingSegmentStart = true
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun openTrack(activity: ActivityType) {
        accumulator = TrackStats.Accumulator()
        pendingSegmentStart = false
        pauseDeadlineMs = null
        lastFixAccuracyM = null
        lastFixRejectedByAccuracy = false
        noFixGuard.onTrackOpened()
        val startedAt = now()
        trackStartedAt = startedAt
        activeTrackId = repository.startTrack(activity, startedAt)
        controller.onRecording(activity)
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    // --- Registration watchdog ---
    // The GMS transition registration can die silently (observed: the arm-time replay arrives,
    // then no live transitions ever again). While armed, an alarm re-registers every interval:
    // registration replays the current activity, so a missed transition is recovered within one
    // tick. Alarm-based (not a coroutine delay) because Doze freezes coroutine timers — exactly
    // when transitions go missing.
    private val watchdogIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWatchdog() {
        // setAndAllowWhileIdle needs no exact-alarm grant; in deep Doze while-idle alarms are
        // throttled to roughly one per 15 min per app, which matches the interval anyway.
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
            watchdogIntent,
        )
    }

    private fun cancelWatchdog() {
        getSystemService(AlarmManager::class.java).cancel(watchdogIntent)
    }

    /**
     * Called by [WatchdogReceiver] on the armed-session alarm. [onDone] fires once the
     * re-registration has been handed to GMS — the receiver holds its wakelock open until then.
     */
    fun onWatchdog(onDone: (() -> Unit)? = null) {
        if (!armed) {
            onDone?.invoke()
            return
        }
        DebugLog.i(TAG, "watchdog: re-registering transition updates")
        // A free heartbeat: the alarm fires even in Doze, where the heartbeat coroutine is frozen.
        Settings.setLastHeartbeatMs(this, now())
        scheduleWatchdog()
        // The alarm fires in Doze, where the pause wake's coroutine delay does not — so this is
        // also where a pause whose window quietly expired gets closed.
        finalizeExpiredPause()
        if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Request-only, deliberately not restart(): a plain request refreshes a healthy
            // registration without touching it (and replays the latest transition, feeding the
            // stale-reading oracle below), while a restart re-mints the token — observed to lose
            // a server-side race ~1 time in 4 and come up dead. Restarts happen only at arm and
            // when the oracle proves the registration deaf.
            activityManager.start().addOnCompleteListener { onDone?.invoke() }
        } else {
            onDone?.invoke()
        }
    }

    /**
     * Close a paused track whose resume window has passed. The single entry point for expiring a
     * pause — the scheduled pause wake, the watchdog alarm (Doze defers the wake's timer), and
     * the UI coming to the foreground all funnel through here, so the close can never be applied
     * without the status publish that keeps the UI and notification in sync.
     */
    fun finalizeExpiredPause() {
        if (!controller.isPaused) return
        scope.launch {
            mutex.withLock { if (finalizeIfPauseExpired()) publishStatus() }
        }
    }

    /** Writes the heartbeat every 15 min while armed; a track close is a free extra attestation. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        Settings.setLastHeartbeatMs(this, now())
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                Settings.setLastHeartbeatMs(this@LocationRecordingService, now())
            }
        }
    }

    private suspend fun closeCurrentTrack() {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        Settings.setLastHeartbeatMs(this, now())
        val id = activeTrackId ?: return
        // A paused track ended when its last fix arrived, not now — don't count the idle gap.
        val endedAt = if (controller.isPaused) accumulator.lastGood?.timestamp ?: now() else now()
        activeTrackId = null
        controller.onClosed()
        pendingSegmentStart = false
        noFixGuard.onStopped()
        repository.finishTrack(id, endedAt)
    }

    // Default source is the platform GPS provider, not Play Services' fused provider: fused
    // HIGH_ACCURACY also drives network location (periodic Wi-Fi scans + GmsCore wakelocks billed
    // to us), and its Wi-Fi/cell/dead-reckoning fixes are exactly what [isGnssBacked] rejects.
    // The fused path stays selectable (Settings > Location source) because network positioning is
    // the only thing that yields fixes inside GNSS-opaque buildings — for field comparison there.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val intervalMs = Settings.minIntervalSec(this) * 1000L
        val minDistanceM = Settings.minDistanceM(this).toFloat()
        val useFused = Settings.useFusedProvider(this)
        noFixGuard.onProbeStarted(SystemClock.elapsedRealtime())
        if (useFused) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateDistanceMeters(minDistanceM)
                // Don't let fixes arrive faster than the user's chosen minimum interval.
                .setMinUpdateIntervalMillis(intervalMs)
                .setWaitForAccurateLocation(false)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) = handleLocations(result.locations)
            }
            activeRequest = ActiveRequest.Fused(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } else {
            val lm = locationManager ?: return
            val request = LocationRequestCompat.Builder(intervalMs)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(minDistanceM)
                // Don't let fixes arrive faster than the user's chosen minimum interval.
                .setMinUpdateIntervalMillis(intervalMs)
                .build()
            val listener = object : LocationListenerCompat {
                override fun onLocationChanged(location: Location) = handleLocations(listOf(location))
                override fun onLocationChanged(locations: List<Location>) = handleLocations(locations)
            }
            activeRequest = ActiveRequest.Gps(listener)
            LocationManagerCompat.requestLocationUpdates(
                lm,
                LocationManager.GPS_PROVIDER,
                request,
                ContextCompat.getMainExecutor(this),
                listener,
            )
        }
        DebugLog.i(TAG, "location updates started (${if (useFused) "fused" else "gps"})")
        registerGnssStatus()
    }

    private fun stopLocationUpdates() {
        when (val request = activeRequest) {
            is ActiveRequest.Gps ->
                locationManager?.let { LocationManagerCompat.removeUpdates(it, request.listener) }
            is ActiveRequest.Fused -> fused.removeLocationUpdates(request.callback)
            null -> {}
        }
        activeRequest = null
        unregisterGnssStatus()
        disarmResumeSignals()
    }

    // --- No-fix give-up guard -------------------------------------------------

    /**
     * Called from the GnssStatus callback (which ticks ~1/s whenever the GNSS engine is searching,
     * so the check needs no timer and can't be Doze-deferred while GPS is off). If the configured
     * window has passed with zero accepted fixes, hand GPS off to the cheap resume signals.
     */
    private fun maybeGiveUpOnNoFix() {
        val giveUpMs = Settings.gpsGiveUpSec(this) * 1000L
        if (activeRequest == null || !noFixGuard.shouldGiveUp(SystemClock.elapsedRealtime(), giveUpMs)) return
        scope.launch {
            mutex.withLock {
                if (activeRequest == null || activeTrackId == null || controller.isPaused) return@withLock
                if (!noFixGuard.shouldGiveUp(SystemClock.elapsedRealtime(), giveUpMs)) return@withLock
                val backoffMs = noFixGuard.onGaveUp(SystemClock.elapsedRealtime())
                DebugLog.i(
                    TAG,
                    "no-fix guard: no accepted fix in ${giveUpMs / 1000}s — GPS off" +
                        " (motion retry gated ${backoffMs / 1000}s)",
                )
                withContext(Dispatchers.Main) {
                    stopLocationUpdates()
                    armResumeSignals()
                }
                publishStatus()
            }
        }
    }

    /** A cheap signal says conditions may have changed — turn GPS back on and try again. */
    private fun onNoFixResumeSignal(reason: String, respectBackoff: Boolean) {
        scope.launch {
            mutex.withLock {
                if (!noFixGuard.suspended) return@withLock
                if (!noFixGuard.shouldProbe(SystemClock.elapsedRealtime(), respectBackoff)) {
                    // Too soon after the last failed probe; keep listening for motion instead.
                    withContext(Dispatchers.Main) { armSignificantMotion() }
                    return@withLock
                }
                DebugLog.i(TAG, "no-fix guard: probing again ($reason)")
                withContext(Dispatchers.Main) { startLocationUpdates() }
                publishStatus()
            }
        }
    }

    private fun armResumeSignals() {
        armSignificantMotion()
        armPassiveListener()
    }

    private fun disarmResumeSignals() {
        motionListener?.let { listener ->
            motionListener = null
            motionSensor?.let { sensor -> sensorManager?.cancelTriggerSensor(listener, sensor) }
        }
        passiveListener?.let { listener ->
            locationManager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        }
        passiveListener = null
    }

    /** One-shot hardware trigger that fires on walking/driving-scale motion, then disarms itself. */
    private fun armSignificantMotion() {
        if (motionListener != null) return
        val sm = sensorManager ?: return
        val sensor = motionSensor ?: return
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                motionListener = null // one-shot: already disarmed by the sensor framework
                onNoFixResumeSignal("significant motion", respectBackoff = true)
            }
        }
        if (sm.requestTriggerSensor(listener, sensor)) motionListener = listener
    }

    /** Free ride on other apps' fixes: a GPS fix delivered to anyone proves the sky is visible. */
    @SuppressLint("MissingPermission")
    private fun armPassiveListener() {
        if (passiveListener != null) return
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = locationManager ?: return
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    onNoFixResumeSignal("passive GPS fix", respectBackoff = false)
                }
            }
        }
        passiveListener = listener
        LocationManagerCompat.requestLocationUpdates(
            lm,
            LocationManager.PASSIVE_PROVIDER,
            LocationRequestCompat.Builder(PASSIVE_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_LOW_POWER)
                .build(),
            ContextCompat.getMainExecutor(this),
            listener,
        )
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
                // Single pass, no allocations — this ticks ~1/s for the whole recording.
                var used = 0
                val top = FloatArray(4) // strongest C/N0s seen, descending
                var topCount = 0
                for (i in 0 until status.satelliteCount) {
                    if (!status.usedInFix(i)) continue
                    used++
                    var cn0 = status.getCn0DbHz(i)
                    if (cn0 <= 0f) continue
                    for (j in 0 until topCount) {
                        if (cn0 > top[j]) {
                            val t = top[j]; top[j] = cn0; cn0 = t
                        }
                    }
                    if (topCount < top.size) top[topCount++] = cn0
                }
                lastGnssSatsInFix = used
                lastGnssCn0Top4 = if (topCount == 0) null else {
                    var sum = 0f
                    for (j in 0 until topCount) sum += top[j]
                    sum / topCount
                }
                if (used >= GNSS_MIN_SATELLITES_IN_FIX) lastGnssFixElapsedMs = SystemClock.elapsedRealtime()
                maybeGiveUpOnNoFix()
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
        // Fused on-foot fixes are exempt from the satellite cross-check: indoors, network fixes are
        // the only positions a walk track can get, and admitting them is the fused mode's purpose.
        // The whole foot family qualifies — a walking↔running flip mid-track must not start
        // rejecting fixes. Raw GPS keeps the check even on foot — the GNSS engine dead-reckons
        // through signal loss (observed: ~80 s of zero-satellite fixes entering a parking garage),
        // and those fabrications are exactly what this rejects.
        val fusedOnFoot = activeRequest is ActiveRequest.Fused && gate.confirmed.trackGroup == TrackGroup.FOOT
        val requireGnss = Settings.requireGnssFix(this) && !fusedOnFoot
        // One insert per batch — a LocationResult can deliver several buffered fixes at once.
        val batch = ArrayList<TrackPoint>(locations.size)
        for (loc in locations) {
            val trackId = activeTrackId ?: return
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
            val baseline = if (segStart) null else accumulator.lastGood
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
            lastFixAccuracyM = candidate.accuracy
            lastFixRejectedByAccuracy = reason == IgnoreReason.ACCURACY
            val point = candidate.copy(
                ignored = bad,
                ignoreReason = reason?.code,
                segmentStart = segStart && !bad,
            )
            // Every fix goes through the accumulator, ignored ones included — it applies the same
            // rule (skip ignored, detach at a segment start) the finished track is recomputed with.
            accumulator.add(point)
            if (!bad) {
                if (segStart) pendingSegmentStart = false
                noFixGuard.onFixAccepted(SystemClock.elapsedRealtime())
            }
            batch.add(point)
        }
        // The only database write of the hot path: the points themselves. The track row is not
        // touched — a write to `tracks` per fix would wake every timeline query once a second (see
        // [TrackDao]), for a row nothing reads while the track is open. Its aggregates are computed
        // from these points when the track is finished; the live figures the UI shows come from the
        // accumulator, via [TrackingStatus] below.
        if (batch.isNotEmpty()) repository.addPoints(batch)
        publishStatus()
    }

    private fun publishStatus() {
        val activity = gate.confirmed
        val rec = activity.recording
        val pausedActivity = (controller.phase as? TrackController.Phase.Paused)?.activity
        val suspended = rec && noFixGuard.suspended
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activity = activity,
                recording = rec,
                activeTrackId = activeTrackId,
                distanceMeters = if (rec) accumulator.distanceMeters else 0.0,
                points = if (rec) accumulator.pointCount else 0,
                startedAtMillis = if (rec && trackStartedAt > 0) trackStartedAt else null,
                speedMps = if (rec) accumulator.lastGood?.speed else null,
                altitudeM = if (rec) accumulator.lastGood?.altitude else null,
                gpsSuspended = suspended,
                gpsSuspendedSinceMillis = when {
                    !suspended -> null
                    it.gpsSuspendedSinceMillis != null -> it.gpsSuspendedSinceMillis
                    else -> now()
                },
                pausedActivity = pausedActivity,
                pausedUntilMillis = if (pausedActivity != null) pauseDeadlineMs else null,
                lastFixAccuracyM = if (rec) lastFixAccuracyM else null,
                lastFixRejectedByAccuracy = rec && lastFixRejectedByAccuracy,
            )
        }
        // State only — no live distance. The notification re-posts only on activity/pause
        // transitions (a per-fix post costs a wakelock + IPC every second while recording).
        // Same "recording"/"standing by" vocabulary as the Record tab's state card.
        val (title, detail) = when {
            activity.recording && noFixGuard.suspended ->
                "Recording · ${activity.label}" to "No GPS signal — waiting for one"
            activity.recording -> "Recording · ${activity.label}" to "Track in progress"
            pausedActivity != null ->
                "Paused · ${pausedActivity.label}" to "Continues if you move soon"
            else -> "Standing by" to "Waiting for movement"
        }
        updateNotification(title, detail)
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
            .setContentTitle(title)
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
        unregisterReceiver(shutdownReceiver)
        instance = null
        activeTrackId = null
        TrackingStatus.update { it.copy(activeTrackId = null) }
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
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L

        // Age cap for trusting an AR event's own timestamp (see [ReadingClock]): far above any
        // real Doze drain delay, below the garbage stamps observed in the field (22.5 h).
        private const val READING_MAX_AGE_MS = 6 * 60 * 60_000L

        // Stale-reading deafness oracle: live transition deliveries arrive 0–5 s after their
        // event; one this late while armed could only have come from a registration replay.
        private const val STALE_READING_RESTART_MS = 60_000L
        private const val STALE_RESTART_MIN_GAP_MS = 5 * 60_000L

        // A fix counts as GNSS-backed when a satellite fix using at least this many satellites
        // occurred within [GNSS_FIX_MAX_AGE_MS] of it. Four is the minimum for a genuine 3D fix;
        // below that the position isn't independently satellite-determined. Tunables for field-testing
        // the cross-check against the tunnel/underpass fabrication case.
        private const val GNSS_MIN_SATELLITES_IN_FIX = 4
        private const val GNSS_FIX_MAX_AGE_MS = 5_000L
        private const val PASSIVE_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            // Never start the location foreground service without location permission — the platform
            // throws SecurityException on Android 14+, and startForegroundService obligates a
            // startForeground call we couldn't satisfy. Leave disarmed so the UI prompts for the
            // grant; the user re-arms once it's granted.
            if (!context.hasLocationPermission()) {
                Settings.setAutoRecord(context, false)
                return
            }
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
