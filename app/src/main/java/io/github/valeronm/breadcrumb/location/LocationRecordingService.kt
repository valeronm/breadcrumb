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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityGate
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CarrierEvidence
import io.github.valeronm.breadcrumb.domain.DeafnessWarning
import io.github.valeronm.breadcrumb.domain.GnssSnapshot
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.MovementConfirmer
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ReadingClock
import io.github.valeronm.breadcrumb.domain.RecordingAction
import io.github.valeronm.breadcrumb.domain.StaleReadingOracle
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackController
import io.github.valeronm.breadcrumb.domain.TrackGroup
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderText
import io.github.valeronm.breadcrumb.ui.MainActivity
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
 * Foreground service that records GPS while the app is in the background. It listens for activity
 * changes (delivered via [ActivityTransitionReceiver]) and opens, continues or pauses tracks as the
 * detected activity moves between motion families — GPS runs at one cadence throughout, the
 * user's, read from [Settings] when each track's request starts.
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

    // The second witness: what the position stream says the ground is doing, so a "you have
    // stopped" reading can be weighed against it rather than believed on sight. Touched only under
    // [mutex] — every path that feeds it, reads it or restarts it runs there, [startLocationUpdates]
    // included (it is always reached through a withContext from inside the lock).
    private val confirmer = MovementConfirmer(AndroidDistance)

    // The witness's case that the open track's label is wrong ([CarrierEvidence]): fed per fix
    // alongside the verdict, judged once when the track closes. [openTrackActivity] is the
    // *track's* label as opened — a same-group activity switch keeps the track's original label,
    // so neither the evidence bar nor the rename verdict may follow the confirmed activity. Both
    // touched only under [mutex].
    private val carrierEvidence = CarrierEvidence()
    private var openTrackActivity: ActivityType? = null

    // Set while the service is armed; duplicate ACTION_STARTs while armed are no-ops.
    @Volatile private var armed = false

    // When the current armed session began, and when the stale-reading oracle last forced a
    // re-registration — see the deafness check in [applyActivity].
    @Volatile private var armedAtMs = 0L
    private var lastStaleRestartMs = 0L

    // Warns the user when activity detection stops responding; see [DeafnessWarning] for why it
    // takes two detections. Its replay window is timed against the manager's registration stamp,
    // since a re-registration's replay is indistinguishable from a live delivery.
    private val deafnessWarning = DeafnessWarning(
        liveMaxAgeMs = STALE_READING_RESTART_MS,
        replayWindowMs = REGISTRATION_REPLAY_WINDOW_MS,
    )

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

    // The live GPS request's listener; non-null == GPS is on.
    @Volatile private var gpsListener: LocationListenerCompat? = null

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
        startForegroundWithNotification("Idle", "Nothing to record")
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
        cancelWatchdog()
        clearDeafnessWarning()
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
        // already canceled and the body never ran — otherwise a dying service would leak the
        // receiver's goAsync and pin the broadcast until the system times it out.
        scope.launch { mutex.withLock { applyActivity(activity, eventTimeMs) } }
            .invokeOnCompletion { onApplied?.invoke() }
    }

    private suspend fun applyActivity(raw: ActivityType, eventTimeMs: Long?) {
        val nowMs = now()
        val readingMs = onReadingArrived(eventTimeMs, nowMs)
        // Debounce the raw reading into a trusted activity signal; nothing to apply if the trusted
        // activity didn't move — or if the ground contradicts it, in which case the gate holds it
        // until [promoteParkedReading] finds it credible.
        val previous = gate.confirmed
        val changed = gate.onReading(raw, motionVerdict(nowMs))
        if (changed == null) {
            gate.parked?.let { DebugLog.i(TAG, "motion cross-check: holding $it — the ground is still moving") }
            return
        }
        applyConfirmed(previous, changed, readingMs, nowMs - readingMs)
    }

    /**
     * **The one place the cross-check's verdict is produced, and so the one place its setting is
     * read.** Every consultation defines a [Motion.Unknown] case identical to the recorder's
     * behaviour before there was a second witness, so switching the feature off is this one branch
     * and nothing downstream knows a switch exists.
     *
     * The confirmer is fed whether or not the setting is on — a ring push of arithmetic — so
     * turning it on takes effect at once instead of after a warm-up window. Caller holds [mutex].
     */
    private fun motionVerdict(atMs: Long): Motion =
        if (Settings.motionCrossCheck(this)) confirmer.verdict(atMs) else Motion.Unknown

    /**
     * Reconsider a reading the ground contradicted, and apply it if the contradiction has cleared.
     * Caller holds [mutex].
     */
    private suspend fun promoteParkedReading(motion: Motion) {
        val previous = gate.confirmed
        val promoted = gate.onMotion(motion) ?: return
        DebugLog.i(TAG, "motion cross-check: releasing the held $promoted")
        // **The promotion's own time, not the reading's.** The controller measures the resume
        // window from the time it is given, and a reading held longer than that window would
        // promote into a pause that had already lapsed — the holding silently replacing the resume
        // window instead of preceding it. The hold is evidence the stop had not begun yet, so the
        // window that follows it is the first one measuring an actual stop.
        applyConfirmed(previous, promoted, now(), readingLagMs = 0L)
    }

    /**
     * A tick's worth of "is that held reading credible yet?". Cheap when nothing is held, which is
     * always the case with the cross-check off — parking takes a verdict, and the verdict is then
     * [Motion.Unknown].
     *
     * The pre-check reads gate state off the mutex, like the one in [maybeGiveUpOnNoFix]: a stale
     * read costs at most one tick's delay, since the caller ticks about once a second.
     */
    private fun checkParkedReading() {
        if (gate.parked == null) return
        scope.launch { mutex.withLock { promoteParkedReading(motionVerdict(now())) } }
    }

    /**
     * The preamble every Play-Services *reading* runs: sanitize the event's own time, let the
     * deafness oracle judge it, and stamp the liveness the Record card shows. Returns the reading
     * time the gate and controller are to work in.
     *
     * Separate from [applyConfirmed] because it is specific to a delivered reading: a caller that
     * applies an activity with no reading behind it must not touch the oracle, whose whole job is
     * to notice when readings stop arriving.
     */
    private suspend fun onReadingArrived(eventTimeMs: Long?, nowMs: Long): Long {
        // The gate gets the event's own (sanitized) time, not the apply time: readings drained
        // late from a frozen queue must keep their real spacing, or a stop and a return ten
        // minutes apart would land inside the resume window and stitch through a genuine stop.
        val lastReadingMs = readingClock.lastReadingMs
        val readingMs = readingClock.sanitize(eventTimeMs, nowMs, READING_MAX_AGE_MS)
        // Deafness oracle: a stale-yet-clock-advancing reading applied while armed can only have
        // arrived via replay of a transition GMS never delivered live — proof the registration is
        // deaf (a package update or a GMS restart kills it silently). The advance must clear
        // STALE_READING_ADVANCE_MS so a repeat of an already-applied event can't fire a spurious
        // restart (see [StaleReadingOracle]).
        if (StaleReadingOracle.provesDeaf(
                eventTimeMs, readingMs, lastReadingMs, armedAtMs, nowMs,
                STALE_READING_RESTART_MS, STALE_READING_ADVANCE_MS,
            )
        ) {
            // The restart is rate-limited; the detection is not. A detection that arrives while a
            // restart is still within its cooldown is the interesting one — it means the last
            // restart didn't take, which is what the user needs telling about.
            if (nowMs - lastStaleRestartMs > STALE_RESTART_MIN_GAP_MS) {
                lastStaleRestartMs = nowMs
                DebugLog.w(
                    TAG,
                    "reading ${(nowMs - readingMs) / 1000}s late " +
                        "(advanced ${readingMs - lastReadingMs}ms) — registration deaf, re-registering",
                )
                activityManager.restart()
            }
            if (deafnessWarning.onDeafDetected()) {
                showDeafnessWarning()
                publishStatus()
            }
        } else if (
            deafnessWarning.onReading(
                nowMs - readingMs, nowMs - ActivityRecognitionManager.lastRegisteredAtMs,
            )
        ) {
            DebugLog.i(TAG, "live delivery resumed — withdrawing the detection warning")
            clearDeafnessWarning()
            publishStatus()
        }
        // Every delivery — even a NoChange — proves activity detection is alive; the Record
        // tab's standing-by card surfaces this.
        TrackingStatus.update { it.copy(lastReadingAtMillis = readingMs) }
        return readingMs
    }

    /**
     * The apply tail: a confirmed activity change becomes a track action, and the action's side
     * effects are performed. [atMs] is the time the change is taken to have happened — the
     * controller measures the pause deadline from it — and [readingLagMs] only colours the log
     * line.
     */
    private suspend fun applyConfirmed(
        previous: ActivityType,
        changed: ActivityType,
        atMs: Long,
        readingLagMs: Long,
    ) {
        // The controller compares the change's own time against the pause deadline, so a
        // late-drained reading can't stitch through a genuine stop even if the pause wake never
        // fired.
        logTransition(previous, changed, readingLagMs)
        val action = controller.onActivity(
            changed, atMs, Settings.resumeWindowSec(this) * 1000L,
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
        if (noFixGuard.suspended && gate.confirmed.recording && gpsListener == null) {
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
        controller.onRecording(activity)
        pendingSegmentStart = true
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun openTrack(activity: ActivityType) {
        accumulator = TrackStats.Accumulator()
        pendingSegmentStart = false
        lastFixAccuracyM = null
        lastFixRejectedByAccuracy = false
        noFixGuard.onTrackOpened()
        openTrackActivity = activity
        carrierEvidence.restart(TrackQuality.groupCeilingKmh(activity))
        val startedAt = now()
        trackStartedAt = startedAt
        activeTrackId = repository.startTrack(activity, startedAt)
        controller.onRecording(activity)
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    // --- Registration watchdog ---
    // The GMS transition registration can die with no error surfacing — replays keep answering
    // while live delivery stops. While armed, an alarm re-registers every interval:
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
        // …and where a held reading is guaranteed a revisit. The GNSS tick that normally releases
        // one exists only while GPS is on, so this alarm is what makes "a held reading never
        // depends on GPS staying on" true of every path rather than of the ones thought of: with
        // GPS off the window is stale, the verdict abstains, and the reading goes through.
        checkParkedReading()
        if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Request-only, deliberately not restart(): a plain request refreshes a healthy
            // registration without touching it, and replays the latest transition, which is what
            // feeds the stale-reading oracle. A restart tears the registration down and rebuilds it
            // on the other request code — too disruptive to run every tick against a registration
            // that is probably fine. Restarts happen only at arm and when the oracle proves the
            // registration deaf.
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
        // The evidence verdict travels into the finish transaction: which labels rename, and to
        // what, is the domain's decision (CarrierEvidence.renameFor) — a proven carried journey on
        // a foot label finishes as "Moving" with its warm-up jump flags restored. The evidence is
        // restarted when a track opens, so nothing carries over.
        val renameTo = openTrackActivity?.let { carrierEvidence.renameFor(it) }
        openTrackActivity = null
        repository.finishTrack(id, endedAt, renameTo)
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
        confirmer.restart(MovementConfirmer.forSampling(intervalSec))
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
        registerGnssStatus()
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
        // The un-vetoed check is the cheap racy pre-filter; the veto needs the verdict, and the
        // verdict needs the lock.
        if (gpsListener == null || !noFixGuard.shouldGiveUp(SystemClock.elapsedRealtime(), giveUpMs)) return
        scope.launch {
            mutex.withLock {
                if (gpsListener == null || activeTrackId == null || controller.isPaused) return@withLock
                val motion = motionVerdict(now())
                if (!noFixGuard.shouldGiveUp(SystemClock.elapsedRealtime(), giveUpMs, motion)) return@withLock
                // GPS is about to go, and with it the tick that would ever revisit a held reading —
                // so it is reconsidered here, on the way down. The veto above is what makes that
                // honest: reaching this line means fixes have genuinely ceased, so there is no
                // longer any moving ground to contradict a stop.
                promoteParkedReading(motion)
                // A promotion may have paused the track, and [pauseTrack] both stops GPS and arms
                // its own resume-deadline wake. Carrying on would arm the no-fix resume signals on
                // top of that, leaving one track with two mechanisms waiting to revive it.
                if (controller.isPaused) return@withLock
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

    // MissingPermission suppressed for the same reason as [stopLocationUpdates]: removeUpdates
    // checks nothing, and teardown must run whatever the grant state.
    @SuppressLint("MissingPermission")
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
            // Reused across callbacks — the reduction must stay allocation-free at ~1/s. Safe to
            // share: the main executor delivers status updates one at a time.
            private val snapshot = GnssSnapshot()

            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                snapshot.reset()
                for (i in 0 until status.satelliteCount) {
                    snapshot.add(status.usedInFix(i), status.getCn0DbHz(i))
                }
                lastGnssSatsInFix = snapshot.usedInFix
                lastGnssCn0Top4 = snapshot.topCn0Mean()
                if (snapshot.usedInFix >= GNSS_MIN_SATELLITES_IN_FIX) {
                    lastGnssFixElapsedMs = SystemClock.elapsedRealtime()
                }
                maybeGiveUpOnNoFix()
                checkParkedReading()
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

    /** Whether [loc] is backed by a recent real satellite fix — see [GnssSnapshot.backed]. */
    private fun isGnssBacked(loc: Location): Boolean =
        GnssSnapshot.backed(lastGnssFixElapsedMs, loc.elapsedRealtimeNanos / 1_000_000L, GNSS_FIX_MAX_AGE_MS)

    // Fixes are ingested under [mutex] so they serialize with activity changes (which retarget the
    // current track) instead of racing them.
    private fun handleLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        scope.launch { mutex.withLock { ingestLocations(locations) } }
    }

    private suspend fun ingestLocations(locations: List<Location>) {
        val maxAccuracyM = Settings.accuracyGateM(this).toFloat()
        val requireGnss = Settings.requireGnssFix(this)
        // The last fix's verdict, handed to the publish below so the display doesn't walk the
        // confirmer's window a second time per fix — the verdict is O(window), and this path runs
        // per second.
        var lastMotion: Motion? = null
        // One insert per batch — the platform listener's List overload can deliver several
        // buffered fixes at once.
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
            )
            // The first good fix after a resume begins a new segment: disconnect it from the previous
            // segment so the paused gap isn't jump-checked or counted in distance.
            val segStart = pendingSegmentStart
            val baseline = if (segStart) null else accumulator.lastGood
            // Bad fixes are still stored (with the reason), just excluded from distance and the
            // good-point baseline. The rule weighs all three reasons and their order; this reports
            // the platform evidence for one of them (null = the cross-check is off).
            // The motion verdict is taken against the ground as it stood *before* this fix joined
            // the window, so a fix can never be part of the evidence that clears it.
            val gates = TrackQuality.Gates(
                maxAccuracyM,
                if (requireGnss) isGnssBacked(loc) else null,
                motionVerdict(candidate.timestamp),
            )
            val reason = TrackQuality.badFixReason(baseline, candidate, gate.confirmed, gates)
            // The feed contract ([MovementConfirmer]): every fix that cleared the *label-independent*
            // gates, and only those. A jump-flagged fix is included deliberately — its rejection came
            // from the activity ceiling, which is the very thing the witness exists to second-guess,
            // and withholding it would make the witness inherit that error.
            if (reason != IgnoreReason.ACCURACY && reason != IgnoreReason.NO_GNSS) {
                confirmer.onFix(candidate.timestamp, candidate.latitude, candidate.longitude)
            }
            // The same verdict, folded into the carrier evidence the track is judged by at finish.
            carrierEvidence.onSample(candidate.timestamp, gates.motion, gate.parked != null)
            lastMotion = gates.motion
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
        publishStatus(lastMotion)
    }

    /**
     * While the verdict overrules a foot label — measured ground speed the label's own ceiling
     * cannot explain — the Record card and notification say "Moving" ([ActivityType.UNKNOWN])
     * instead of repeating the label. Display only, and structurally so: computed here, downstream
     * of every decision, feeding nothing — not the gate, not the controller, not the ceiling — and
     * it reverts by itself when the verdict drops out, being derived state recomputed at every
     * publish rather than a mode to exit. With the cross-check off the verdict is [Motion.Unknown]
     * and the substitution never triggers.
     *
     * The parked-STILL stretch is covered by the same test: the *confirmed* activity stays the
     * foot label while a STILL is parked, which is exactly when the card should say "Moving".
     */
    private fun displayActivity(confirmed: ActivityType, motion: Motion): ActivityType {
        if (confirmed.trackGroup != TrackGroup.FOOT) return confirmed
        return if (TrackQuality.motionOverrules(confirmed, motion)) ActivityType.UNKNOWN else confirmed
    }

    /**
     * [motion] lets a caller that already produced this moment's verdict (the per-fix ingest path)
     * hand it over instead of paying a second window walk; everyone else leaves it null and the
     * verdict is produced here.
     */
    private fun publishStatus(motion: Motion? = null) {
        val activity = displayActivity(gate.confirmed, motion ?: motionVerdict(now()))
        val rec = activity.recording
        // The controller's phase is the one record of a pause, deadline included — read both off it
        // rather than mirroring the deadline in a field the pause/resume paths must keep in step.
        val paused = controller.phase as? TrackController.Phase.Paused
        val suspended = rec && noFixGuard.suspended
        // Held in locals because the notification below is classified from the very same values the
        // UI receives — two surfaces reading one set of inputs, not each sampling its own.
        val points = if (rec) accumulator.pointCount else 0
        val deaf = deafnessWarning.warned
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activity = activity,
                recording = rec,
                activeTrackId = activeTrackId,
                distanceMeters = if (rec) accumulator.distanceMeters else 0.0,
                points = points,
                startedAtMillis = if (rec && trackStartedAt > 0) trackStartedAt else null,
                speedMps = if (rec) accumulator.lastGood?.speed else null,
                altitudeM = if (rec) accumulator.lastGood?.altitude else null,
                deaf = deaf,
                gpsSuspended = suspended,
                gpsSuspendedSinceMillis = when {
                    !suspended -> null
                    it.gpsSuspendedSinceMillis != null -> it.gpsSuspendedSinceMillis
                    else -> now()
                },
                pausedActivity = paused?.activity,
                pausedUntilMillis = paused?.resumeDeadlineMs,
                lastFixAccuracyM = if (rec) lastFixAccuracyM else null,
                lastFixRejectedByAccuracy = rec && lastFixRejectedByAccuracy,
            )
        }
        // One classification, shared with the Record tab's card: the state is decided by the pure
        // [recordCardState] and worded by [notificationText], so a new or renamed state cannot mean
        // one thing on the card and another in the notification. State only — no live distance, so
        // the notification re-posts only when the pair below changes (a per-fix post would cost a
        // wakelock + IPC every second while recording). `tracking` is true by construction: this
        // runs in the live service, which is what the flag reports to the UI.
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
        updateNotification(text.title, text.detailLine())
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
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private val notificationManager by lazy {
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
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

    /** Escalate the deafness the Record card already shows, for when the app isn't open. */
    private fun showDeafnessWarning() {
        DebugLog.w(TAG, "activity detection not responding — notifying the user")
        val text = "Trips may be missed or start late. Restarting the phone usually fixes it."
        notificationManager?.notify(
            ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, App.ALERT_CHANNEL_ID)
                .setContentTitle("Activity detection stalled")
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

    /** Withdraw the warning and forget the episode. Cheap no-op when nothing was ever posted. */
    private fun clearDeafnessWarning() {
        if (deafnessWarning.warned) notificationManager?.cancel(ALERT_NOTIFICATION_ID)
        deafnessWarning.reset()
    }

    private fun buildNotification(title: String, text: String): Notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openIntent)
        .addAction(0, "Stop", stopIntent)
        .build()

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
        private const val ALERT_NOTIFICATION_ID = 1002

        // A reading this soon after a registration is its replay. Comfortably over the settle
        // ActivityRecognitionManager waits before re-requesting.
        private const val REGISTRATION_REPLAY_WINDOW_MS = 5_000L
        private const val TAG = "Breadcrumb"
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L

        // Age cap for trusting an AR event's own timestamp (see [ReadingClock]): far above any
        // real Doze drain delay, below the garbage stamps GMS can emit (22.5 h).
        private const val READING_MAX_AGE_MS = 6 * 60 * 60_000L

        // Stale-reading deafness oracle: live transition deliveries arrive 0–5 s after their
        // event; one this late while armed could only have come from a registration replay.
        private const val STALE_READING_RESTART_MS = 60_000L

        // The reading must advance the clock by at least this much to count — a bare > lets a repeat
        // of an already-applied event, whose wall-clock stamp drifts a few ms across a long still
        // stretch, fire a spurious restart; a real missed transition advances by seconds. See
        // [StaleReadingOracle].
        private const val STALE_READING_ADVANCE_MS = 1_000L
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

        /**
         * [start] with the crash guard the receiver re-arm paths need: a broadcast receiver that
         * throws takes the process down, and neither the boot/update re-arm nor the watchdog's
         * self-heal has anything better to do with a failed launch than log it. [reason] names the
         * caller in that log line.
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
