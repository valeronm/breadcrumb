package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.MovementConfirmer
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackController
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderText
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.hasLocationPermission
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * Foreground service that records GPS while the app is in the background, opening, continuing or
 * pausing tracks as the detected activity (via [ActivityTransitionReceiver]) moves between motion
 * families. GPS runs at one cadence — the user's, read from [Settings] when each track's request starts.
 */
class LocationRecordingService : Service() {

    // The handler is what keeps a failed child from killing the process — SupervisorJob only
    // shields siblings, an uncaught exception still reaches the default handler. Recording must
    // degrade, not die: a transient DB/disk error on the ingest path costs one batch of fixes,
    // not the whole armed session (crashing would loop — START_STICKY restarts into the same
    // condition).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> DebugLog.e(TAG, "recording coroutine failed", e) },
    )
    private val mutex = Mutex()

    private lateinit var repository: TrackRepository
    private lateinit var livenessRepository: LivenessRepository
    private lateinit var activityManager: ActivityRecognitionManager
    private var locationManager: LocationManager? = null

    // The platform surfaces this service owns but decides nothing about: what it puts in the shade,
    // and the alarm that wakes it while Doze holds every coroutine timer frozen.
    private val notifications = RecorderNotifications(this)
    private val watchdogAlarm = WatchdogAlarm(this)

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

    // The fix path: platform fixes in, point rows and a motion verdict out, with no Android in it so
    // it can be exercised off the device ([FixIngest]). It owns what accumulates across a track —
    // the movement witness, the carrier case against the track's label, and the running aggregates.
    // Touched only under [mutex]: every path that feeds it, reads it or restarts it runs there,
    // [startLocationUpdates] included (always reached through a withContext from inside the lock).
    private val ingest = FixIngest(AndroidDistance)

    // The no-fix give-up guard's decisions (when to give up, backoff gating, what a resume signal
    // means) live in the pure [NoFixGuard]; this service owns only the side effects — GPS on/off,
    // the significant-motion sensor, and the passive listener, all further down. Guard state is
    // touched under [mutex] except the benign racy pre-check in [maybeGiveUpOnNoFix]. Declared here
    // because both paths move it, and [core] below reads it at construction.
    private val noFixGuard = NoFixGuard()

    // The activity path, likewise Android-free: readings in, [Effect]s out. It owns the gate that
    // debounces raw readings into a trusted activity, the controller that turns those into track
    // lifecycle decisions, and the deafness bookkeeping. This service performs what it decides and
    // nothing more — see [dispatch], which is the only place those effects are carried out.
    private val core = ActivityIngest(ingest, noFixGuard)

    // Set while the service is armed; duplicate ACTION_STARTs while armed are no-ops.
    @Volatile private var armed = false

    // When the current armed session began — the deafness oracle reads it to tell a replay of a
    // transition GMS never delivered from one that simply predates this session.
    @Volatile private var armedAtMs = 0L

    // True once any transition reading has been applied since the last arm. Read by
    // [ActivityTransitionReceiver] to drop the arm-time snapshot once the transition stream has
    // spoken — set synchronously on delivery (not in the apply coroutine) so a snapshot arriving
    // after a transition can never slip past the check while the apply is still queued.
    @Volatile var transitionSinceArm = false
        private set

    @Volatile private var trackStartedAt = 0L

    // The live GPS request's listener; non-null == GPS is on.
    @Volatile private var gpsListener: LocationListenerCompat? = null

    // The cheap signals the no-fix guard falls back on, and the satellite watch whose ~1/s tick is
    // what drives that guard in the first place. Both are live only while GPS is, and
    // [stopLocationUpdates] is the single place all three are torn down together.
    private val resumeSignals = ResumeSignals(this) { reason, respectBackoff ->
        onNoFixResumeSignal(reason, respectBackoff)
    }
    private val gnss = GnssWatch(this) {
        maybeGiveUpOnNoFix()
        checkParkedReading()
    }

    // --- Auto-pause / stitch resources (all touched only under [mutex]) ---
    // While paused, [activeTrackId] stays open (GPS off) so a brief stop can be stitched back into
    // the same track when the same activity resumes within the configured window.

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = TrackRepository(this)
        livenessRepository = LivenessRepository(this)
        activityManager = ActivityRecognitionManager(this)
        locationManager = getSystemService(LocationManager::class.java)
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
            else -> if (Settings.isAutoRecord(this)) handleStart() else stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart() {
        // A location-type foreground service can't start without location permission (SecurityException
        // on Android 14+); reachable when the OS restarts the sticky service after location was revoked
        // — or unused-app auto-revoked — with the armed flag still set. Bail cleanly instead of crash-looping;
        // the UI's permission prompt takes over. (The startForegroundService caller path is guarded in
        // [start], so this fires only for system-initiated restarts, which carry no startForeground deadline.)
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
        notifications.startForeground("Idle", "Nothing to record")
        watchdogAlarm.schedule()
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
                core.onArmed()
                publishStatus()
            }
            clearDeafnessWarning()
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
        watchdogAlarm.cancel()
        clearDeafnessWarning()
        activityManager.stop()
        scope.launch {
            mutex.withLock {
                dispatch(core.closeOpenTrack(now()))
                core.onArmed()
                heartbeatJob?.cancel()
                heartbeatJob = null
                Settings.setLastHeartbeatMs(this@LocationRecordingService, now())
                livenessRepository.recordDisarmed(now())
            }
            withContext(Dispatchers.Main) {
                TrackingStatus.reset()
                notifications.stopForeground()
                stopSelf()
            }
        }
    }

    /**
     * Called by [ActivityTransitionReceiver] when Play Services reports a transition. [eventTimeMs]
     * is the event's own (wall-clock) timestamp; [onApplied] runs once the reading has been applied
     * — the receiver holds its broadcast wakelock open until then, so Doze can't freeze the apply.
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
        // already canceled and the body never ran — otherwise a dying service would leak the
        // receiver's goAsync and pin the broadcast until the system times it out.
        scope.launch { mutex.withLock { applyActivity(activity, eventTimeMs) } }
            .invokeOnCompletion { onApplied?.invoke() }
    }

    private suspend fun applyActivity(raw: ActivityType, eventTimeMs: Long?) {
        val nowMs = now()
        val was = core.confirmed
        val wasPaused = core.isPaused
        val effects = core.onReading(
            raw = raw,
            eventTimeMs = eventTimeMs,
            nowMs = nowMs,
            registration = Registration(armedAtMs, ActivityRecognitionManager.lastRegisteredAtMs),
            settings = activitySettings(),
        )
        if (core.confirmed != was) {
            logTransition(was, core.confirmed, wasPaused, readingLagMs(effects, nowMs))
        } else {
            core.parked?.let { DebugLog.i(TAG, "motion cross-check: holding $it — the ground is still moving") }
        }
        dispatch(effects)
    }

    /** The settings a pass is decided under, read here because this is where they live. */
    private fun activitySettings() = ActivitySettings(
        resumeWindowMs = Settings.resumeWindowSec(this) * 1000L,
        crossCheckMotion = Settings.motionCrossCheck(this),
    )

    /**
     * Perform what [ActivityIngest] decided, in the order it decided it. It commits its own state
     * as it builds the list, so every effect must run and run in order — see [Effect]. This is the
     * only place the activity path touches Android.
     */
    private suspend fun dispatch(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                // A request, not a restart: several branches of one pass can ask, and starting twice
                // would tear the GPS request down and rebuild it mid-track.
                Effect.EnsureGps ->
                    if (gpsListener == null) withContext(Dispatchers.Main) { startLocationUpdates() }

                Effect.StopGps -> withContext(Dispatchers.Main) { stopLocationUpdates() }

                is Effect.ArmResumeSignals -> {
                    DebugLog.i(
                        TAG,
                        "no-fix guard: probe gave up — GPS off " +
                            "(motion retry gated ${effect.retryGatedMs / 1000}s)",
                    )
                    withContext(Dispatchers.Main) { resumeSignals.armAll() }
                }

                Effect.ArmSignificantMotion -> withContext(Dispatchers.Main) { resumeSignals.armMotionOnly() }

                is Effect.OpenTrack -> {
                    trackStartedAt = effect.startedAt
                    activeTrackId = repository.startTrack(effect.activity, effect.startedAt)
                    DebugLog.i(TAG, "  -> opened ${effect.activity} track $activeTrackId")
                }

                is Effect.CloseTrack -> {
                    DebugLog.i(TAG, "  -> closing track $activeTrackId")
                    activeTrackId?.let { repository.finishTrack(it, effect.endedAt, effect.renameTo) }
                    activeTrackId = null
                }

                is Effect.SchedulePauseWake -> scope.launch {
                    delay(effect.deadlineMs - now())
                    // Logic-free wake: a stale deadline (after a resume, fresh start, or newer
                    // pause) returns no effects from [ActivityIngest.onTick].
                    finalizeExpiredPause()
                }

                is Effect.RestartRegistration -> {
                    DebugLog.w(
                        TAG,
                        "reading ${effect.readingLateMs / 1000}s late " +
                            "(advanced ${effect.advancedMs}ms) — registration deaf, re-registering",
                    )
                    activityManager.restart()
                }

                is Effect.DeafWarning ->
                    if (effect.show) {
                        notifications.warnDeaf()
                    } else {
                        DebugLog.i(TAG, "live delivery resumed — withdrawing the detection warning")
                        clearDeafnessWarning()
                    }

                is Effect.StampReading ->
                    TrackingStatus.update { it.copy(lastReadingAtMillis = effect.readingMs) }

                Effect.StampHeartbeat -> Settings.setLastHeartbeatMs(this, now())

                Effect.Publish -> publishStatus()
            }
        }
    }

    /** How late the reading behind this pass was, for the log line; zero when none was involved. */
    private fun readingLagMs(effects: List<Effect>, nowMs: Long): Long =
        effects.firstNotNullOfOrNull { (it as? Effect.StampReading)?.readingMs }
            ?.let { nowMs - it } ?: 0L

    /**
     * The one place the cross-check's setting is read, so the one place it branches: every
     * consultation defines a [Motion.Unknown] case identical to the pre-witness behaviour, and
     * nothing downstream knows a switch exists. Caller holds [mutex].
     */
    private fun motionVerdict(atMs: Long): Motion =
        core.motionVerdict(atMs, Settings.motionCrossCheck(this))

    /**
     * Reconsider a reading the ground contradicted, and apply it if the contradiction has cleared.
     * Caller holds [mutex].
     */
    private suspend fun promoteParkedReading(motion: Motion) {
        val was = core.confirmed
        val wasPaused = core.isPaused
        val effects = core.onMotion(motion, now(), activitySettings())
        if (effects.isEmpty()) return
        DebugLog.i(TAG, "motion cross-check: releasing the held ${core.confirmed}")
        logTransition(was, core.confirmed, wasPaused, readingLagMs = 0L)
        dispatch(effects)
    }

    /**
     * A tick's worth of "is that held reading credible yet?". Cheap when nothing is held — always so
     * with the cross-check off, since parking takes a verdict and it is then [Motion.Unknown]. The
     * off-mutex pre-check (as in [maybeGiveUpOnNoFix]) costs at most one tick's delay (~1 s) on a stale read.
     */
    private fun checkParkedReading() {
        if (core.parked == null) return
        scope.launch { mutex.withLock { promoteParkedReading(motionVerdict(now())) } }
    }

    /**
     * [wasPaused] and the activity either side are sampled before the pass: [ActivityIngest] commits
     * as it decides, so by the time this runs its state already describes the outcome. The line
     * reports the change, not the result of it.
     */
    private fun logTransition(
        previous: ActivityType,
        activity: ActivityType,
        wasPaused: Boolean,
        readingLagMs: Long,
    ) {
        // Surface a materially late reading (Doze drain, replay recovery) — it explains why a
        // track decision doesn't line up with the log line's own timestamp.
        val lag = if (readingLagMs > 5_000) " reading=-${readingLagMs / 1000}s" else ""
        DebugLog.i(TAG, "applyActivity: $previous -> $activity (track=$activeTrackId paused=$wasPaused$lag)")
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
        watchdogAlarm.schedule()
        // The alarm fires in Doze, where the pause wake's coroutine delay does not — so this is
        // also where a pause whose window quietly expired gets closed.
        finalizeExpiredPause()
        // …and where a held reading is guaranteed a revisit. The GNSS tick that normally releases
        // one exists only while GPS is on, so this alarm is what makes "a held reading never
        // depends on GPS staying on" true of every path rather than of the ones thought of: with
        // GPS off the window is stale, the verdict abstains, and the reading goes through.
        checkParkedReading()
        if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Request-only, deliberately not restart(): a plain request refreshes a healthy
            // registration in place and replays the latest transition, which feeds the stale-reading
            // oracle. A restart tears the registration down and rebuilds it on the other request
            // code — too disruptive to run every tick against a registration that is probably fine.
            // Restarts happen only at arm and when the oracle proves it deaf.
            activityManager.start().addOnCompleteListener { onDone?.invoke() }
        } else {
            onDone?.invoke()
        }
    }

    /**
     * Close a paused track whose resume window has passed — the single entry point for expiring a
     * pause: the scheduled pause wake, the watchdog alarm (Doze defers the wake's timer) and UI
     * foregrounding all funnel here, so the close never lands without the status publish that
     * keeps the UI and notification in sync.
     */
    fun finalizeExpiredPause() {
        if (!core.isPaused) return
        scope.launch {
            mutex.withLock {
                val effects = core.onTick(now())
                if (effects.isEmpty()) return@withLock
                DebugLog.i(TAG, "pause expired — finalizing track $activeTrackId")
                // The close carries its own publish, so it cannot land while the UI and the
                // notification keep showing a pause the controller has already left.
                dispatch(effects)
            }
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

    // The source is the platform GPS provider, not Play Services' fused provider: fused
    // HIGH_ACCURACY also drives network location (periodic Wi-Fi scans + GmsCore wakelocks billed
    // to us), batches delivery minutes late in screen-off power saving, and its Wi-Fi/cell/
    // dead-reckoning fixes are exactly what [isGnssBacked] rejects. There is deliberately no fused
    // fallback for GNSS-opaque venues: venue time should surface as a stay, not a noisy track.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val intervalSec = Settings.minIntervalSec(this)
        val intervalMs = intervalSec * 1000L
        val minDistanceM = Settings.minDistanceM(this).toFloat()
        noFixGuard.onProbeStarted(SystemClock.elapsedRealtime())
        // The confirmer's window is shaped by the cadence, so it is re-derived where the cadence is
        // read — and emptied here, since this path also runs on a resume, on a new track and on
        // every no-fix probe retry, and fixes from before such a gap describe a different stretch
        // of the journey. An empty window abstains, i.e. behaves as if there were no cross-check.
        ingest.restartConfirmer(MovementConfirmer.forSampling(intervalSec))
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
        gpsListener = listener
        LocationManagerCompat.requestLocationUpdates(
            lm,
            LocationManager.GPS_PROVIDER,
            request,
            ContextCompat.getMainExecutor(this),
            listener,
        )
        DebugLog.i(TAG, "location updates started")
        gnss.register()
    }

    // MissingPermission suppressed for removeUpdates: it performs no permission check (the compat
    // annotation is over-broad), and gating teardown on isGranted would leak the listener exactly
    // when the permission was just revoked.
    @SuppressLint("MissingPermission")
    private fun stopLocationUpdates() {
        val lm = locationManager
        val listener = gpsListener
        if (lm != null && listener != null) LocationManagerCompat.removeUpdates(lm, listener)
        gpsListener = null
        gnss.unregister()
        resumeSignals.disarm()
    }

    // --- No-fix give-up guard -------------------------------------------------

    /**
     * Called from the GnssStatus callback (which ticks ~1/s whenever the GNSS engine is searching,
     * so the check needs no timer and can't be Doze-deferred while GPS is off). If the configured
     * window has passed with zero accepted fixes, hand GPS off to the cheap resume signals.
     */
    private fun maybeGiveUpOnNoFix() {
        val giveUpMs = Settings.gpsGiveUpSec(this) * 1000L
        // The un-vetoed check is the cheap racy pre-filter; the veto needs the verdict, and the
        // verdict needs the lock.
        if (gpsListener == null || !noFixGuard.shouldGiveUp(SystemClock.elapsedRealtime(), giveUpMs)) return
        scope.launch {
            mutex.withLock {
                // This service's own resources, so its own guard — whether a *pause* rules the
                // give-up out is the recorder's, and [ActivityIngest.onGnssTick] keeps it.
                if (gpsListener == null || activeTrackId == null) return@withLock
                dispatch(
                    core.onGnssTick(now(), SystemClock.elapsedRealtime(), giveUpMs, activitySettings()),
                )
            }
        }
    }

    /** A cheap signal says conditions may have changed — turn GPS back on and try again. */
    private fun onNoFixResumeSignal(reason: String, respectBackoff: Boolean) {
        scope.launch {
            mutex.withLock {
                val effects = core.onResumeSignal(SystemClock.elapsedRealtime(), respectBackoff)
                if (Effect.EnsureGps in effects) DebugLog.i(TAG, "no-fix guard: probing again ($reason)")
                dispatch(effects)
            }
        }
    }

    // Fixes are ingested under [mutex] so they serialize with activity changes (which retarget the
    // current track) instead of racing them.
    private fun handleLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        scope.launch { mutex.withLock { ingestLocations(locations) } }
    }

    /** The platform's own absence conventions (`hasX()`), answered once, at the Android boundary. */
    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracy = if (hasAccuracy()) accuracy else null,
        speed = if (hasSpeed()) speed else null,
        bearing = if (hasBearing()) bearing else null,
        // The platform occasionally reports no time at all; arrival time is the closest thing to it.
        timeMs = if (time > 0) time else now(),
        verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000L,
    )

    private suspend fun ingestLocations(locations: List<Location>) {
        val trackId = activeTrackId ?: return
        val ingested = ingest.onFixes(
            trackId = trackId,
            fixes = locations.map { it.toFix() },
            gate = GateState(core.confirmed, stillParked = core.parked != null),
            settings = IngestSettings(
                maxAccuracyM = Settings.accuracyGateM(this).toFloat(),
                requireGnss = Settings.requireGnssFix(this),
                crossCheckMotion = Settings.motionCrossCheck(this),
            ),
            gnss = gnss.state,
        )
        for (point in ingested.points) {
            if (point.ignoreReason == IgnoreReason.NO_GNSS.code) {
                DebugLog.i(TAG, "fix dropped — no recent GNSS backing (acc=${point.accuracy})")
            }
        }
        // Once per batch rather than per accepted fix: the guard only records *when* a fix last
        // arrived, and a batch's fixes are delivered together.
        if (ingested.accepted > 0) noFixGuard.onFixAccepted(SystemClock.elapsedRealtime())
        // The only database write of the hot path: the points themselves. The track row is not
        // touched — a write to `tracks` per fix would wake every timeline query once a second (see
        // [TrackDao]), for a row nothing reads while the track is open. Its aggregates are computed
        // from these points when the track is finished; the live figures the UI shows come from the
        // ingest's accumulator, via [TrackingStatus] below.
        if (ingested.points.isNotEmpty()) repository.addPoints(ingested.points)
        // The batch's last verdict, handed to the publish so the display doesn't walk the
        // confirmer's window a second time per fix — the verdict is O(window), and this path runs
        // per second.
        publishStatus(ingested.motion)
    }

    /**
     * [motion] lets a caller that already produced this moment's verdict (the per-fix ingest path)
     * hand it over instead of paying a second window walk; everyone else leaves it null and the
     * verdict is produced here.
     */
    private fun publishStatus(motion: Motion? = null) {
        val activity = ingest.displayActivity(core.confirmed, motion ?: motionVerdict(now()))
        val rec = activity.recording
        // The controller's phase is the one record of a pause, deadline included — read both off it
        // rather than mirroring the deadline in a field the pause/resume paths must keep in step.
        val paused = core.phase as? TrackController.Phase.Paused
        val suspended = rec && noFixGuard.suspended
        // Held in locals because the notification below is classified from the very same values the
        // UI receives — two surfaces reading one set of inputs, not each sampling its own.
        val points = if (rec) ingest.pointCount else 0
        val deaf = core.deaf
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activity = activity,
                recording = rec,
                activeTrackId = activeTrackId,
                distanceMeters = if (rec) ingest.distanceMeters else 0.0,
                points = points,
                startedAtMillis = if (rec && trackStartedAt > 0) trackStartedAt else null,
                speedMps = if (rec) ingest.lastGood?.speed else null,
                altitudeM = if (rec) ingest.lastGood?.altitude else null,
                deaf = deaf,
                gpsSuspended = suspended,
                gpsSuspendedSinceMillis = when {
                    !suspended -> null
                    it.gpsSuspendedSinceMillis != null -> it.gpsSuspendedSinceMillis
                    else -> now()
                },
                pausedActivity = paused?.activity,
                pausedUntilMillis = paused?.resumeDeadlineMs,
                lastFixAccuracyM = if (rec) ingest.lastFixAccuracyM else null,
                lastFixRejectedByAccuracy = rec && ingest.lastFixRejectedByAccuracy,
            )
        }
        // One classification, shared with the Record tab's card: the pure [recordCardState] decides
        // and [notificationText] words it, so a new or renamed state cannot mean one thing on the
        // card and another in the notification. State only — no live distance — so it re-posts only
        // when the pair below changes; a per-fix post would cost a wakelock + IPC every second.
        // `tracking` is true by construction: this is the live service, which the flag reports.
        val text = recorderText(
            state = recordCardState(
                armed = Settings.isAutoRecord(this),
                tracking = true,
                recording = rec,
                paused = paused != null,
                gpsSuspended = suspended,
                points = points,
                hasOpenTrack = activeTrackId != null,
            ),
            activity = activity,
            pausedActivity = paused?.activity,
            deaf = deaf,
            // No live figures: see [LiveFigures] — a moving detail would re-post this notification
            // every second, since [lastNotified] dedupes on the text itself.
            live = null,
        )
        notifications.update(text.title, text.detailLine())
    }

    /** Withdraw the warning and forget the episode — the notification's half and the recorder's. */
    private fun clearDeafnessWarning() {
        notifications.clearDeafWarning()
        core.forgetDeafness()
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

        /** Id of the track currently being recorded, or null — the one open track that dangling-track cleanup must not close. */
        @Volatile
        var activeTrackId: Long? = null
            private set

        const val ACTION_START = "io.github.valeronm.breadcrumb.START"
        const val ACTION_STOP = "io.github.valeronm.breadcrumb.STOP"

        private const val TAG = "Breadcrumb"
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

        // How many satellites make a status report count as a real fix at all — four is the minimum
        // for a genuine 3D one; below that the position isn't independently satellite-determined.
        // How *stale* such a fix may be and still back a location is the rule's own, and lives with
        // it in [FixIngest.GNSS_FIX_MAX_AGE_MS].

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

        /**
         * [start] with the crash guard the receiver re-arm paths need: a throwing receiver takes
         * the process down, and neither the boot/update re-arm nor the watchdog's self-heal has
         * anything better to do with a failed launch than log it — [reason] names the caller there.
         */
        fun startSafely(context: Context, reason: String) {
            runCatching { start(context) }
                .onFailure { DebugLog.e(TAG, "$reason FAILED: ${it.message}") }
        }

        fun stop(context: Context) {
            Settings.setAutoRecord(context, false)
            val intent = Intent(context, LocationRecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
