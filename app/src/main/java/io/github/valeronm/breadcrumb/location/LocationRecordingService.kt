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
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DepartureWatch
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.MovementConfirmer
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.RecordCardState
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackController
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderText
import io.github.valeronm.breadcrumb.ui.recorderWords
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
import java.util.Locale

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
    private val departureFence = DepartureFence(this)
    private val departureProbe = DepartureProbe(this, ::onProbePosition)

    // Held rather than rebuilt per post: the shade is re-worded once per fix batch for the length of
    // a drive. It caches no text — every accessor reads the resource table again — so a language
    // change still reaches the notification already showing.
    private val words by lazy { recorderWords(this) }

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
    // [stopLocationUpdates] is the single place all three are torn down together — deliberately not
    // split across the three objects, where the invariant would become a call sequence someone can
    // half-perform.
    private val resumeSignals = ResumeSignals(this, ::onResumeSignal)
    private val gnss = GnssWatch(this) {
        maybeGiveUpOnNoFix()
        checkParkedReading()
    }

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
        // Armed with nothing recording is a state [recordCardState] already names, so the first
        // notification is worded by the same call the updates use rather than paired again here —
        // two spellings of one state is exactly what that seam exists to prevent.
        val idle = words.recorderText(
            state = RecordCardState.WAITING_FOR_MOVEMENT,
            activity = null,
            pausedActivity = null,
            deaf = false,
            live = null,
        )
        notifications.startForeground(idle.title, idle.detailLine())
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
                // Arming is the one moment a departure must be watched for with no track behind it:
                // Play Services drops every geofence across a reboot and an app update, and both
                // arrive here. Dispatched rather than called directly so the one policy has one
                // home — and so the core's own suite can see it.
                dispatch(core.onArmed(now(), activitySettings()))
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
                // A fence outlives the process that registered it, so leaving one behind would keep
                // waking a recorder the user has switched off. The core emits the teardown for every
                // trigger, so which ones were armed stays one question with one answer.
                dispatch(core.onDisarmed())
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

    /**
     * Called by [GeofenceReceiver] when the phone has left where it last stopped. [onApplied] runs
     * once the departure has been applied — the receiver holds its broadcast wakelock until then,
     * for the reason [onActivityChanged] gives.
     */
    fun onDeparture(onApplied: (() -> Unit)? = null) {
        // A fence registered before the user switched recording off can still be delivered; opening
        // a track off it would record someone who has asked not to be.
        if (!armed) {
            DebugLog.i(TAG, "departure ignored — not armed")
            onApplied?.invoke()
            return
        }
        applyAsync(onApplied) {
            val nowMs = now()
            // How long the fence took to notice is the number this whole trigger is judged on, and
            // it is measured from when *watching* began rather than from the registration: a fence
            // re-armed on a fresh probe position carries a stamp minutes younger than the stop it
            // is still centred on, and reporting against that would flatter it by the whole gap.
            val latency = watchedForMs(nowMs)
            val effects = core.onDeparture(nowMs, activitySettings())
            if (effects.isEmpty()) {
                DebugLog.i(TAG, "departure ignored — already recording ($latency)")
            } else {
                DebugLog.i(TAG, "departure: opening a Moving track ($latency)")
            }
            dispatch(effects)
        }
    }

    private fun applyActivityAsync(activity: ActivityType, eventTimeMs: Long?, onApplied: (() -> Unit)?) =
        applyAsync(onApplied) { applyActivity(activity, eventTimeMs) }

    /**
     * Run [block] under the recorder's lock and report back when it is done, whatever "done" turns
     * out to mean. `invokeOnCompletion` rather than a `try/finally` in the body: it also fires when
     * the scope was already canceled and the body never ran — otherwise a dying service would leak
     * the receiver's `goAsync` and pin the broadcast until the system times it out.
     */
    private fun applyAsync(onApplied: (() -> Unit)?, block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
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
            logTransition(was, core.confirmed, wasPaused, nowMs - core.lastReadingMs, ground = null)
        } else {
            // Which hold, not a sentence about one of them: a reading can now be waiting because the
            // ground disagrees *or* because it said nothing at all, and those want telling apart.
            core.held?.let { DebugLog.i(TAG, "motion cross-check: holding ${it.activity} — ${it.kind}") }
        }
        dispatch(effects)
    }

    /** The settings a pass is decided under, read here because this is where they live. */
    private fun activitySettings() = ActivitySettings(
        resumeWindowMs = Settings.resumeWindowSec(this) * 1000L,
        // Long enough that the witness has had a fair chance to answer: its own window span, plus
        // room for GPS to reacquire after the resume that so often precedes the question. Derived
        // rather than configured — a cap shorter than the window it waits on would hold nothing.
        uncorroboratedHoldMs = witnessSpanMs + GPS_REACQUIRE_MS,
        triggers = DepartureTriggers(
            fence = Settings.departureFence(this),
            continuous = Settings.departureContinuous(this),
            motion = Settings.departureMotion(this),
        ),
    )

    // The witness's window span, stamped where the GPS request is built from the same setting. Read
    // on every pass — including the satellite tick for the length of a hold — and it can only change
    // when the request is rebuilt, so re-reading the preference per pass buys nothing. Sharing the
    // one derivation also keeps the cap and the window it waits on from drifting apart.
    @Volatile
    private var witnessSpanMs =
        MovementConfirmer.forSampling(Settings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC).minSpanMs

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

                // GeofencingClient is thread-safe and posts its own callbacks to the main looper,
                // so unlike the listener registrations above these need no hop — and a hop here
                // would be taken with the recorder's mutex held.
                is Effect.ArmDepartureFence ->
                    effect.from
                        ?.let(departureFence::arm)
                        ?: departureFence.armFromLastKnown()

                Effect.DisarmDepartureFence -> departureFence.disarm()

                is Effect.StartDepartureProbe ->
                    withContext(Dispatchers.Main) {
                        departureProbe.start(effect.intervalMs, effect.durationMs)
                    }

                // Guarded like EnsureGps above, and for the same reason DepartureFence.disarm guards
                // itself: this rides along with every track that opens and every stitch that resumes,
                // and a main-thread hop taken with the recorder's mutex held is not free.
                Effect.StopDepartureProbe ->
                    if (departureProbe.running) withContext(Dispatchers.Main) { departureProbe.stop() }

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

    /**
     * A tick's worth of "is that held reading credible yet?", and the release when it is. Cheap when
     * nothing is held. The off-mutex pre-check (as in [maybeGiveUpOnNoFix]) costs at most one
     * tick's delay (~1 s) on a stale read.
     */
    private fun checkParkedReading() {
        if (core.parked == null) return
        scope.launch {
            mutex.withLock {
                // A *contradicted* hold has no cap by design — a fresh verdict tells a stale hold
                // from a crossing still under way and a deadline cannot — so this runs every GNSS
                // tick for as long as one lasts. Read once and shared with the log below, rather
                // than walking the witness's window a second time for the same moment.
                val settings = activitySettings()
                val nowMs = now()
                val was = core.confirmed
                val wasPaused = core.isPaused
                val ground = core.motionVerdict(nowMs)
                val effects = core.onMotion(ground, nowMs, settings)
                if (effects.isEmpty()) return@withLock
                DebugLog.i(TAG, "motion cross-check: releasing the held ${core.confirmed}")
                logTransition(was, core.confirmed, wasPaused, readingLagMs = 0L, ground = ground)
                dispatch(effects)
            }
        }
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
        /** The verdict the change was decided under, where the caller already has it — the window
         *  walk is not worth repeating, and a fresh one would report a different moment anyway. */
        ground: Motion?,
    ) {
        // Surface a materially late reading (Doze drain, replay recovery) — it explains why a
        // track decision doesn't line up with the log line's own timestamp.
        val lag = if (readingLagMs > 5_000) " reading=-${readingLagMs / 1000}s" else ""
        // What the ground said as the change was decided — the distribution of these is what says
        // whether corroboration ever changes an outcome, and how often a stop lands on silence.
        val verdict = ground?.let { " ground=${groundOf(it)}" }.orEmpty()
        DebugLog.i(
            TAG,
            "applyActivity: $previous -> $activity (track=$activeTrackId paused=$wasPaused$lag$verdict)",
        )
    }

    /** The witness's verdict as one log word. Logs are never localized — see the conventions. */
    private fun groundOf(motion: Motion): String = when (motion) {
        is Motion.Moving -> "moving@${"%.1f".format(Locale.US, motion.speed.kmh)}kmh"
        Motion.Stopped -> "stopped"
        Motion.Unknown -> "unknown"
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
                val effects = core.onTick(now(), activitySettings())
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
    //
    // This stays in the service rather than moving into a wrapper beside the four platform classes:
    // it is a junction of settings, the guard's probe clock, the confirmer's window, the satellite
    // watch and the fix listener, so wrapping it would take all five in and relocate the coupling
    // rather than remove it.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val intervalSec = Settings.minIntervalSec(this)
        val intervalMs = intervalSec * 1000L
        val minDistanceM = Settings.minDistanceM(this).toFloat()
        noFixGuard.onProbeStarted(SystemClock.elapsedRealtime())
        // The confirmer's window is shaped by the cadence, so it is re-derived where the cadence is
        // read. It is not emptied here — see [MovementConfirmer.reshape]: this path runs on every
        // resume, and clearing seconds before a carrier pulls away is what blinded the witness at
        // the one moment it was needed. Age expiry drains a window that genuinely describes an
        // older stretch of the journey.
        val window = MovementConfirmer.forSampling(intervalSec)
        witnessSpanMs = window.minSpanMs
        ingest.reshapeConfirmer(window)
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
                // GPS is this service's own resource, so its own guard; whether the recording rules
                // the give-up out is the recorder's, and [ActivityIngest.onGnssTick] keeps it.
                if (gpsListener == null) return@withLock
                dispatch(
                    core.onGnssTick(now(), SystemClock.elapsedRealtime(), giveUpMs, activitySettings()),
                )
            }
        }
    }

    /**
     * A cheap signal says conditions may have changed. What that means depends on why GPS is off —
     * a stalled search resumes it, an idle recorder buys a burst of coarse positions instead — and
     * [ActivityIngest.onResumeSignal] is where the two are told apart.
     */
    private fun onResumeSignal(signal: ResumeSignals.Signal) {
        scope.launch {
            mutex.withLock {
                val effects =
                    core.onResumeSignal(signal, SystemClock.elapsedRealtime(), activitySettings())
                // The departure branch needs no line of its own: ResumeSignals already logs the
                // firing, and the probe logs the burst it starts with a cadence that names it.
                if (Effect.EnsureGps in effects) DebugLog.i(TAG, "no-fix guard: probing again ($signal)")
                dispatch(effects)
            }
        }
    }

    /**
     * A coarse position from the departure probe. Deliberately does **not** go through
     * [ingestLocations]: these come from Wi-Fi and cell, land tens to hundreds of meters out, and
     * exist only to answer whether the phone has left where it stopped.
     */
    private fun onProbePosition(
        latitude: Double,
        longitude: Double,
        accuracyM: Double,
        ageMs: Long,
    ) {
        if (!armed) return
        scope.launch {
            mutex.withLock {
                val nowMs = now()
                // Read before the pass, which stops the watch and takes the stamp with it.
                val latency = watchedForMs(nowMs)
                val effects =
                    core.onProbeFix(Coordinate(latitude, longitude), accuracyM, nowMs, activitySettings())
                // The age is on every line because it means a different thing on each: as an anchor
                // a remembered position is the stale one the burst exists to replace, and as a
                // verdict it dates the leaving to whenever the cache was filled.
                val position = "acc=${accuracyM.toInt()}m, ${ageMs / 1000}s old"
                // Every position, unfiltered. The two numbers this trigger is tuned by — how far
                // jitter carries a stationary phone, and what accuracy the coarse stream returns —
                // are distributions, and any selection here would sample them biased.
                when (val verdict = core.lastProbeVerdict) {
                    // The same latency the fence reports itself by, measured the same way, so a log
                    // holding both says which of them is worth its cost.
                    is DepartureWatch.Verdict.Departed ->
                        DebugLog.i(
                            TAG,
                            "departure: probe saw the phone leave " +
                                "($latency, ${measured(verdict.gapM, verdict.barM)}, $position)",
                        )

                    is DepartureWatch.Verdict.Near ->
                        DebugLog.i(TAG, "departure watch: ${measured(verdict.gapM, verdict.barM)} ($position)")

                    is DepartureWatch.Verdict.Anchored ->
                        DebugLog.i(TAG, "departure watch anchored ($position)")

                    DepartureWatch.Verdict.Dormant -> Unit
                }
                dispatch(effects)
            }
        }
    }

    /**
     * The distance a probe position reached against the distance it needed, phrased for a log line.
     * One spelling for both verdicts that carry the pair, so the two cannot drift apart on how the
     * same measurement is written.
     */
    private fun measured(gapM: Double, barM: Double) = "${gapM.toInt()}m of ${barM.toInt()}m"

    /**
     * How long a departure has been watched for, phrased for a log line. **One stamp for every
     * trigger**, so the fence's latency and the probe's are the same measurement and a log holding
     * both says which is worth its cost.
     */
    private fun watchedForMs(nowMs: Long): String {
        val startedAt = core.watchStartedAtMs
        return if (startedAt == 0L) "unwatched" else "${(nowMs - startedAt) / 1000}s after arming"
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
            // The *stop* specifically: carrier evidence counts time a STILL sat parked under moving
            // ground, and a walking reading can now be parked too — one that would credit body-still
            // time for a reading saying the opposite.
            gate = GateState(core.confirmed, stillParked = core.parked == ActivityType.STILL),
            settings = IngestSettings(
                maxAccuracyM = Settings.accuracyGateM(this).toFloat(),
                requireGnss = Settings.requireGnssFix(this),
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
        val activity = ingest.displayActivity(core.confirmed, motion ?: core.motionVerdict(now()))
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
        val text = words.recorderText(
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
        // Unlike the fence, which the system holds and which is meant to outlive this process, the
        // probe is a live request owned by it — one left behind delivers to a callback whose service
        // is gone.
        departureProbe.stop()
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

        /**
         * Headroom over the witness's window for a cold-ish GPS start. A resume rebuilds the
         * request from scratch, and the first fix does not arrive with it — without this the cap
         * could expire having given the window almost nothing to work with, which is the failure
         * the hold exists to prevent.
         */
        private const val GPS_REACQUIRE_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

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
