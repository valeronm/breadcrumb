package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.domain.ActivityGate
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DeafnessWarning
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ReadingClock
import io.github.valeronm.breadcrumb.domain.RecordingAction
import io.github.valeronm.breadcrumb.domain.StaleReadingOracle
import io.github.valeronm.breadcrumb.domain.TrackController

/**
 * Something the recorder wants done that it cannot do itself: every touch of Android on the activity
 * path, named as a value so the path that decides can run off the device. [LocationRecordingService]
 * is the one dispatcher, and performs these in the order given.
 *
 * **The decisions are committed when the list is built, not when it is walked.** [ActivityIngest]
 * moves its own state machines as it decides, so a dispatcher that drops an effect, reorders two, or
 * fails partway leaves the core believing something that never happened. That is the standing price
 * of describing effects rather than performing them, and it is why [EnsureGps] asks for a state
 * rather than commanding a transition.
 *
 * **[NoFixGuard] is the one exception, deliberately.** Its probe clock starts where GPS actually
 * starts, which is inside the dispatch of [EnsureGps] — so a pass reads a guard that still describes
 * the world before the pass. That is the right way round (a probe that never started must not be
 * timed as though it had), but it means the guard is the one piece of state a pass cannot assume it
 * has already moved.
 */
sealed interface Effect {

    /**
     * GPS should be live. Deliberately not "start GPS": the dispatcher checks whether it already is,
     * so a pass that both resumes a track and re-probes after a no-fix give-up asks twice and starts
     * once. Starting twice tears the request down and rebuilds it, which empties the movement
     * witness's window mid-track (see [LocationRecordingService.startLocationUpdates]). Asking for
     * the state also keeps the answer where the truth is: a start is refused outright without the
     * fine-location grant, and a core mirroring "GPS is on" would not know.
     */
    data object EnsureGps : Effect

    data object StopGps : Effect

    /**
     * GPS is off for want of a fix rather than for want of a journey — arm the cheap signals that
     * say conditions may have changed. Deliberately not folded into [StopGps]: a pause stops GPS
     * too, and arms its own resume-deadline wake, so arming these on top would leave one track with
     * two mechanisms waiting to revive it. [retryGatedMs] is how long a motion-triggered retry is
     * held off, which is what the backoff bought and the only thing that explains a signal being
     * heard and ignored.
     */
    data class ArmResumeSignals(val retryGatedMs: Long) : Effect

    /**
     * Re-arm the one-shot motion trigger alone. It has just fired and disarmed itself while the
     * passive listener is still armed, so the pair would be the wrong request.
     */
    data object ArmSignificantMotion : Effect

    /**
     * Watch for the phone leaving where it last stopped. [from] is the recorder's own last good fix
     * — the *pause* is the only moment one is known to be the right anchor, taken while the
     * position stream was still healthy, whereas by the time a resume window lapses anything that
     * carries the phone has already taken it elsewhere, and a fence centred where the phone no
     * longer is is never entered and so never reports leaving.
     *
     * **Null is a decision, not an omission**: the recorder has no fix of its own to anchor on —
     * on arming, before any track, or after a pause whose track never got one — and the dispatcher
     * should fall back to whatever position the platform last saw. Carried as a value so the
     * absent anchor arrives as something to act on rather than something to infer from which
     * effects the pass happened to emit.
     */
    data class ArmDepartureFence(val from: Anchor?) : Effect {
        data class Anchor(val latitude: Double, val longitude: Double)
    }

    /** Stop watching: a track is running, so a departure is no longer news. */
    data object DisarmDepartureFence : Effect

    /**
     * Insert the track row. The id it returns is the dispatcher's to hold — nothing here needs it,
     * which is what lets this core stay synchronous while the insert it asks for is awaited.
     */
    data class OpenTrack(val activity: ActivityType, val startedAt: Long) : Effect

    /**
     * Finish whatever track is open. [endedAt] and [renameTo] are decided when this is built rather
     * than read back at dispatch: a paused track ended at its last good fix rather than at the close,
     * and the carrier case that renames a label is reset by the very state move that emits this.
     */
    data class CloseTrack(val endedAt: Long, val renameTo: ActivityType?) : Effect

    /** Wake at [deadlineMs] and tick. An early or stale wake is a no-op in [ActivityIngest.onTick]. */
    data class SchedulePauseWake(val deadlineMs: Long) : Effect

    /**
     * The registration is proven deaf — rebuild it on a fresh token. [readingLateMs] and [advancedMs]
     * are the oracle's evidence, carried because they are what makes the verdict readable in a log: a
     * live delivery arrives seconds after its event, so a reading this late that still advanced the
     * clock can only be the replay of a transition that was never delivered live.
     */
    data class RestartRegistration(val readingLateMs: Long, val advancedMs: Long) : Effect

    /** Raise or withdraw the user-facing detection-stalled alert. */
    data class DeafWarning(val show: Boolean) : Effect

    /** A reading arrived, at its own sanitized time — the liveness the Record card shows. */
    data class StampReading(val readingMs: Long) : Effect

    data object StampHeartbeat : Effect

    data object Publish : Effect
}

/** The settings a pass is decided under, read once per pass by the caller that owns them. */
data class ActivitySettings(
    val resumeWindowMs: Long,
    /**
     * How long a stop the ground could not vouch for is held before it lands anyway. Derived from
     * the witness's own window rather than chosen: the hold exists to give that window time to
     * fill, so the cap is the span it needs plus room for GPS to come back, and a caller that
     * sampled more slowly waits proportionally longer.
     */
    val uncorroboratedHoldMs: Long,
)

/**
 * The state of the transition registration a reading arrived under — the deafness oracle's other
 * two inputs. [armedAtMs] bounds the session, so a reading predating it is merely old rather than
 * proof of anything; [lastRegisteredAtMs] dates the last request, whose replay is indistinguishable
 * from a live delivery and must not be counted as one.
 */
data class Registration(
    val armedAtMs: Long,
    val lastRegisteredAtMs: Long,
)

/**
 * The recorder's activity path, with no Android in it: readings in, [Effect]s out. Everything here
 * used to sit in [LocationRecordingService] between `applyActivity` and `closeCurrentTrack`, where
 * it could only be exercised by walking around with the phone — the rules each had a suite, but the
 * loop that sequences them had none, and it is the loop that decides where tracks begin and end.
 *
 * It owns the two state machines ([ActivityGate], [TrackController]), the reading clock and the
 * deafness bookkeeping — everything that persists across readings. [FixIngest] and [NoFixGuard] are
 * shared with the fix path rather than owned, because both paths move them: a fix feeds the guard
 * and the witness, while an activity change opens, pauses and closes the track they accumulate over.
 *
 * Callers hold whatever lock they serialize the recorder with; nothing here is thread-safe on its own.
 */
class ActivityIngest(
    private val ingest: FixIngest,
    private val noFixGuard: NoFixGuard,
) {

    // The ceiling is a constant of the vocabulary, resolved once: [TrackQuality.groupCeiling]
    // walks the whole activity table, and a reading arriving is no reason to walk it again.
    private val gate = ActivityGate(TrackQuality.groupCeiling(ActivityType.WALKING))
    private val controller = TrackController()

    // Sanitizes AR event timestamps into gate reading times; see [ReadingClock].
    private val readingClock = ReadingClock()

    // Warns the user when activity detection stops responding; see [DeafnessWarning] for why it
    // takes two detections. Its replay window is timed against the manager's registration stamp,
    // since a re-registration's replay is indistinguishable from a live delivery.
    private val deafnessWarning = DeafnessWarning(
        liveMaxAgeMs = STALE_READING_RESTART_MS,
        replayWindowMs = REGISTRATION_REPLAY_WINDOW_MS,
    )

    // When the stale-reading oracle last forced a re-registration.
    private var lastStaleRestartMs = 0L

    // The *track's* label as opened — a same-group activity switch keeps the track's original label,
    // so neither the carrier-evidence bar nor its rename verdict may follow the confirmed activity.
    private var openTrackActivity: ActivityType? = null

    val confirmed: ActivityType get() = gate.confirmed
    val parked: ActivityType? get() = gate.parked

    /** The held reading with the doubt behind it — a log that says only *what* is waiting, and not
     *  whether the ground disagreed or merely said nothing, reports the two identically. */
    val held: ActivityGate.Held? get() = gate.held

    /** The last reading's own sanitized time — how late it was is what a log line wants to say. */
    val lastReadingMs: Long get() = readingClock.lastReadingMs
    val phase: TrackController.Phase get() = controller.phase
    val isPaused: Boolean get() = controller.isPaused
    val deaf: Boolean get() = deafnessWarning.warned

    /** The witness's verdict at [atMs] — see [FixIngest.verdict]. */
    fun motionVerdict(atMs: Long): Motion = ingest.verdict(atMs)

    /**
     * When a held reading stops waiting, and the time it should be taken to have happened at. The
     * gate is deliberately clock-free — *when* a held reading is reconsidered is the recorder's
     * business — so the stamps live here, beside the passes that carry a clock.
     */
    private data class Waiting(val expiresAtMs: Long, val readingMs: Long)

    private var waiting: Waiting? = null

    /**
     * Land a reading the ground never vouched for, once it has waited long enough that a fair
     * chance to vouch has passed. The deadline is computed when the hold begins rather than
     * compared per call: this runs on the ~1 Hz satellite tick for the whole length of every hold.
     * A [ActivityGate.Hold.CONTRADICTED] hold is refused by the gate, which is where that rule lives.
     */
    private fun releaseExpiredHold(nowMs: Long): ActivityType? {
        val deadline = waiting?.expiresAtMs ?: return null
        if (nowMs < deadline) return null
        return gate.releaseHeld()
    }

    /**
     * A Play-Services reading — a transition or the arm-time snapshot. Runs the deafness preamble,
     * debounces [raw] into a trusted activity, and turns a confirmed change into track lifecycle
     * effects. [nowMs] is when it is being applied; the reading's own time is derived from
     * [eventTimeMs] and is what the gate and controller work in.
     */
    fun onReading(
        raw: ActivityType,
        eventTimeMs: Long?,
        nowMs: Long,
        registration: Registration,
        settings: ActivitySettings,
    ): List<Effect> {
        val out = ArrayList<Effect>()
        val readingMs = intake(eventTimeMs, nowMs, registration, out)
        // Nothing to apply if the trusted activity didn't move — or if the ground cannot vouch for
        // it, in which case the gate holds it until [onMotion] or the cap in [releaseExpiredHold]
        // lands it.
        val changed = gate.onReading(raw, motionVerdict(nowMs), requireCorroboration = true)
        waiting = if (gate.held == null) {
            null
        } else {
            Waiting(nowMs + settings.uncorroboratedHoldMs, readingMs)
        }
        if (changed == null) return out
        applyConfirmed(changed, readingMs, nowMs, settings, out)
        return out
    }

    /**
     * Reconsider a held reading against a fresh verdict, and apply it once the ground vouches for it
     * — or once an uncorroborated hold has run its cap. Empty when nothing is held or it still stands.
     */
    fun onMotion(motion: Motion, nowMs: Long, settings: ActivitySettings): List<Effect> {
        // Read before the release, which clears the slot: what kind of hold this was decides when
        // the stop is taken to have happened.
        val wasUncorroborated = gate.held?.kind == ActivityGate.Hold.UNCORROBORATED
        val readingMs = waiting?.readingMs ?: nowMs
        val promoted = gate.onMotion(motion) ?: releaseExpiredHold(nowMs) ?: return emptyList()
        waiting = null
        val out = ArrayList<Effect>()
        // **Which time a released stop happened at depends on why it was held.**
        //
        // A *contradicted* hold is released at the promotion's own time: the ground was positively
        // moving throughout, so the hold is evidence the stop had not begun yet, and the window
        // that follows it is the first one measuring an actual stop. Timing it from the reading
        // would let a hold longer than the resume window promote into a pause that had already
        // lapsed — the holding silently replacing the window instead of preceding it.
        //
        // An *uncorroborated* hold carries no such evidence. Nothing was heard; the recorder simply
        // waited to see whether anything would be. The stop happened when the reading said it did,
        // so timing it from the release would move every unwitnessed track boundary later by the
        // cap — a change to the recorded history, not merely to what GPS costs.
        val atMs = if (wasUncorroborated) readingMs else nowMs
        applyConfirmed(promoted, atMs, nowMs, settings, out)
        return out
    }

    /**
     * The phone has left where it last stopped, on evidence that is not Play Services' — today the
     * departure fence. **Opens a track on the trigger alone**: there is no walking-valid speed to
     * gate on (settling GPS drifts at a walking pace), and the shipped witness is a *carrier*
     * detector by construction, so gating here would quietly make this vehicle-only. Over-recording
     * is what `EdgeStayDetector` trims and `KeepRule` discards; under-recording is not repairable at
     * all, which is the whole trade.
     *
     * Empty while a track is already running — something got there first, and a second opinion about
     * a journey under way is not news. The activity is [ActivityType.UNKNOWN]: the trigger knows the
     * ground moved and nothing about what carried it.
     */
    fun onDeparture(nowMs: Long, settings: ActivitySettings): List<Effect> {
        if (controller.phase is TrackController.Phase.Recording) return emptyList()
        val out = ArrayList<Effect>()
        // Adopted, not read: see [ActivityGate.adopt]. Without this the STILL that ends the journey
        // is no change at all and nothing can ever close what this opens.
        gate.adopt(ActivityType.UNKNOWN)
        waiting = null
        applyConfirmed(ActivityType.UNKNOWN, nowMs, nowMs, settings, out)
        return out
    }

    /**
     * A wake at (or after) a pause deadline: the effects that close a track whose resume window
     * lapsed, empty otherwise. Callers' timers stay logic-free — an early or stale tick (after a
     * resume, a fresh start, or a newer pause) returns nothing; fire anywhere, however often. The
     * close always carries its own [Effect.Publish], so it cannot land without the UI and the
     * notification being brought along.
     */
    fun onTick(nowMs: Long): List<Effect> {
        if (controller.onTick(nowMs) != RecordingAction.Finalize) return emptyList()
        val out = ArrayList<Effect>()
        close(nowMs, out)
        out += Effect.Publish
        return out
    }

    /**
     * A `GnssStatus` tick's worth of "has this probe run [giveUpMs] with nothing to show?" — the
     * engine reports about once a second while searching, which is why the guard needs no timer of
     * its own and cannot be Doze-deferred while GPS is off. [elapsedMs] is monotonic (the guard's
     * only clock: a probe outlives a wall-clock step), [nowMs] is wall time for everything else.
     * Empty unless the probe has genuinely failed. Whether GPS is even on is the caller's own
     * resource and its guard to keep; everything about the recording belongs here.
     */
    fun onGnssTick(
        nowMs: Long,
        elapsedMs: Long,
        giveUpMs: Long,
        settings: ActivitySettings,
    ): List<Effect> {
        // The phase is what says a track exists, as it is in [close] — not the id the dispatcher
        // holds, which after a failed insert says the opposite and would hold the give-up off for
        // the rest of the outing. A paused track, meanwhile, turned GPS off for its own reasons and
        // is waiting on its own deadline.
        if (controller.phase == TrackController.Phase.Idle || controller.isPaused) return emptyList()
        val motion = motionVerdict(nowMs)
        if (!noFixGuard.shouldGiveUp(elapsedMs, giveUpMs, motion)) return emptyList()
        // GPS is about to go, and with it the tick that would ever revisit a held reading — so it is
        // reconsidered here, on the way down. The veto inside [NoFixGuard.shouldGiveUp] is what makes
        // that honest: reaching this line means fixes have genuinely ceased, so there is no longer
        // any moving ground to contradict a stop.
        val out = ArrayList(onMotion(motion, nowMs, settings))
        // The promotion may have paused the track, which stops GPS and arms the pause's own wake.
        if (controller.isPaused) return out
        out += Effect.StopGps
        out += Effect.ArmResumeSignals(noFixGuard.onGaveUp(elapsedMs))
        out += Effect.Publish
        return out
    }

    /**
     * A cheap signal says conditions may have changed. What each is worth is decided here rather
     * than where it is registered: a passive fix is the platform having produced one somewhere, so
     * it stands on its own evidence and ignores the backoff, while motion merely suggests the phone
     * has gone somewhere and must wait its turn. Empty when GPS is not suspended at all.
     */
    fun onResumeSignal(signal: ResumeSignals.Signal, elapsedMs: Long): List<Effect> {
        if (!noFixGuard.suspended) return emptyList()
        val respectBackoff = signal == ResumeSignals.Signal.MOTION
        // Too soon after the last failed probe; keep listening for motion instead.
        if (!noFixGuard.shouldProbe(elapsedMs, respectBackoff)) return listOf(Effect.ArmSignificantMotion)
        return listOf(Effect.EnsureGps, Effect.Publish)
    }

    /** Disarming: close whatever is open, without a publish — the caller resets the status wholesale. */
    fun closeOpenTrack(nowMs: Long): List<Effect> = ArrayList<Effect>().also { close(nowMs, it) }

    /**
     * On (re)arm: the trusted activity resets to STILL, any held reading is dropped, and a
     * departure is watched for from wherever the phone already is. That last part is why this
     * returns effects at all — arming is the one moment a departure must be watched for with no
     * track behind it, and the position is the platform's to supply, not a fix of ours.
     */
    fun onArmed(): List<Effect> {
        reset()
        return listOf(Effect.ArmDepartureFence(from = null))
    }

    /** On disarm: the same reset, watching for nothing — the user has asked not to be recorded. */
    fun onDisarmed() {
        reset()
    }

    private fun reset() {
        gate.onArmed()
        waiting = null
    }

    /** Forget the deafness episode. The alert itself is the caller's to withdraw. */
    fun forgetDeafness() {
        deafnessWarning.reset()
    }

    /**
     * The preamble every *reading* runs: sanitize the event's own time, let the deafness oracle judge
     * it, and report that one arrived; returns the reading time the gate and controller work in.
     * Separate from [applyConfirmed]: an activity applied with no reading behind it must not touch
     * the oracle, whose job is noticing when readings stop arriving.
     */
    private fun intake(
        eventTimeMs: Long?,
        nowMs: Long,
        registration: Registration,
        out: MutableList<Effect>,
    ): Long {
        // The gate gets the event's own (sanitized) time, not the apply time: readings drained late
        // from a frozen queue must keep their real spacing, or a stop and a return ten minutes apart
        // would land inside the resume window and stitch through a genuine stop.
        val lastReadingMs = readingClock.lastReadingMs
        val readingMs = readingClock.sanitize(eventTimeMs, nowMs, READING_MAX_AGE_MS)
        // Deafness oracle: a stale-yet-clock-advancing reading applied while armed can only have
        // arrived via replay of a transition GMS never delivered live — proof the registration is
        // deaf (a package update or a GMS restart kills it silently). The advance must clear
        // STALE_READING_ADVANCE_MS so a repeat of an already-applied event can't fire a spurious
        // restart (see [StaleReadingOracle]).
        if (StaleReadingOracle.provesDeaf(
                eventTimeMs, readingMs, lastReadingMs, registration.armedAtMs, nowMs,
                STALE_READING_RESTART_MS, STALE_READING_ADVANCE_MS,
            )
        ) {
            // The restart is rate-limited; the detection is not. A detection that arrives while a
            // restart is still within its cooldown is the interesting one — it means the last
            // restart didn't take, which is what the user needs telling about.
            if (nowMs - lastStaleRestartMs > STALE_RESTART_MIN_GAP_MS) {
                lastStaleRestartMs = nowMs
                out += Effect.RestartRegistration(nowMs - readingMs, readingMs - lastReadingMs)
            }
            if (deafnessWarning.onDeafDetected()) {
                out += Effect.DeafWarning(show = true)
                out += Effect.Publish
            }
        } else if (deafnessWarning.onReading(nowMs - readingMs, nowMs - registration.lastRegisteredAtMs)) {
            out += Effect.DeafWarning(show = false)
            out += Effect.Publish
        }
        // Every delivery — even one that changes nothing — proves activity detection is alive; the
        // Record tab's standing-by card surfaces this.
        out += Effect.StampReading(readingMs)
        return readingMs
    }

    /**
     * The apply tail: a confirmed activity change becomes a track action and its effects. [atMs] is
     * when the change is taken to have happened, which the controller measures the pause deadline
     * from; [nowMs] is when it is being applied, and stamps the track rows — a reading drained late
     * out of Doze decides against its own time but opens and closes tracks at the wall clock, as it
     * did when both were read separately.
     */
    private fun applyConfirmed(
        changed: ActivityType,
        atMs: Long,
        nowMs: Long,
        settings: ActivitySettings,
        out: MutableList<Effect>,
    ) {
        // The controller compares the change's own time against the pause deadline, so a
        // late-drained reading can't stitch through a genuine stop even if the pause wake never
        // fired.
        when (val action = controller.onActivity(changed, atMs, settings.resumeWindowMs)) {
            RecordingAction.Noop -> Unit
            is RecordingAction.Pause -> pause(action.pausedActivity, action.resumeDeadlineMs, out)
            // Unreachable from a reading (expiry only comes from a tick); for totality.
            RecordingAction.Finalize -> close(nowMs, out)
            RecordingAction.Resume -> resume(changed, out)
            is RecordingAction.StartNew -> {
                close(nowMs, out)
                open(action.activity, nowMs, out)
            }
            is RecordingAction.ContinueSameTrack -> {
                // Same motion family (e.g. walking ⇄ running): keep the track and its label, just
                // break a new segment at the boundary. GPS is already running.
                ingest.markSegmentStart()
                controller.onRecording(action.activity)
            }
        }
        // A confirmed moving reading while the no-fix guard has GPS off is a resume signal too. The
        // two checks guard different things and neither subsumes the other: the dispatcher's asks
        // whether GPS is on *now*, across passes and after a permission refusal, while this one asks
        // whether this pass has already said so — [resume] asks for GPS without clearing the
        // suspension, and the suspension only lifts when the probe it describes actually starts.
        if (noFixGuard.suspended && gate.confirmed.recording && Effect.EnsureGps !in out) {
            out += Effect.EnsureGps
        }
        out += Effect.Publish
    }

    /** Stop GPS but keep the track open; a wake at [resumeDeadlineMs] finalizes it if unresumed. */
    private fun pause(trackActivity: ActivityType, resumeDeadlineMs: Long, out: MutableList<Effect>) {
        noFixGuard.onStopped()
        controller.onPaused(trackActivity, resumeDeadlineMs)
        out += Effect.StopGps
        out += Effect.SchedulePauseWake(resumeDeadlineMs)
        // The stop is where the next departure will be from, and the last good fix is where the
        // phone is standing as it is declared. A track that never got one still wants watching —
        // that is the GNSS-starved case, where a fence is the only thing that can notice at all.
        out += Effect.ArmDepartureFence(
            ingest.lastGood?.let { Effect.ArmDepartureFence.Anchor(it.latitude, it.longitude) },
        )
    }

    /** Continue the paused track: GPS back on, accumulators kept; the first fix begins a new segment. */
    private fun resume(activity: ActivityType, out: MutableList<Effect>) {
        controller.onRecording(activity)
        ingest.markSegmentStart()
        out += Effect.EnsureGps
    }

    private fun open(activity: ActivityType, startedAt: Long, out: MutableList<Effect>) {
        ingest.onTrackOpened(activity)
        noFixGuard.onTrackOpened()
        openTrackActivity = activity
        controller.onRecording(activity)
        out += Effect.OpenTrack(activity, startedAt)
        out += Effect.EnsureGps
        // Whatever the fence was watching for has happened, or something else got there first.
        out += Effect.DisarmDepartureFence
    }

    /**
     * Close whatever is open. Nothing open is not nothing to do — GPS goes off and the heartbeat is
     * stamped either way, which is what makes this safe to run ahead of every [open]. The phase is
     * what says whether a track exists, rather than the id the dispatcher holds: they agree except
     * after a failed insert, and there the phase is the one that recovers, since closing on it
     * resets a core that would otherwise spend the rest of the outing believing it was recording.
     */
    private fun close(nowMs: Long, out: MutableList<Effect>) {
        out += Effect.StopGps
        out += Effect.StampHeartbeat
        if (controller.phase == TrackController.Phase.Idle) return
        // A paused track ended when its last fix arrived, not now — don't count the idle gap. Read
        // before [TrackController.onClosed] clears the phase the question is asked of.
        val endedAt = if (controller.isPaused) ingest.lastGood?.timestamp ?: nowMs else nowMs
        // The evidence verdict travels into the finish transaction: which labels rename, and to what,
        // is the domain's decision (CarrierEvidence.renameFor) — a proven carried journey on a foot
        // label finishes as "Moving" with its warm-up jump flags restored. The evidence is restarted
        // when a track opens, so nothing carries over.
        val renameTo = openTrackActivity?.let { ingest.renameFor(it) }
        openTrackActivity = null
        controller.onClosed()
        ingest.onTrackClosed()
        noFixGuard.onStopped()
        out += Effect.CloseTrack(endedAt, renameTo)
    }

    companion object {
        // A reading this soon after a registration is its replay. Comfortably over the settle
        // ActivityRecognitionManager waits before re-requesting.
        private const val REGISTRATION_REPLAY_WINDOW_MS = 5_000L

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

        /** How long a forced re-registration holds off the next one. Read by the suite that pins it. */
        const val STALE_RESTART_MIN_GAP_MS = 5 * 60_000L
    }
}
