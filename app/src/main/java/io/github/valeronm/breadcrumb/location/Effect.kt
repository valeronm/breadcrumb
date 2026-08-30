package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MeasuredPosition
import io.github.valeronm.breadcrumb.domain.NoFixGuard

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
     *
     * **A null anchor is provisional.** That fallback can be hours stale, and a fence registered
     * where the phone no longer is is *already outside* it, so the exit it exists to report can
     * never happen. The same pass therefore buys a short probe burst, and this is emitted again with
     * a real anchor as soon as one position lands — one fence id, so the second registration
     * replaces the first.
     *
     * A coordinate and no accuracy, unlike [MeasuredPosition]: the fence has a fixed radius of its
     * own, so the anchor's error is the watch's business rather than the registration's.
     */
    data class ArmDepartureFence(val from: Coordinate?) : Effect

    /** Stop watching: a track is running, so a departure is no longer news. */
    data object DisarmDepartureFence : Effect

    /**
     * Ask for coarse positions to judge a departure from, every [intervalMs] for [durationMs] (0 =
     * until stopped). The two numbers are what make one effect serve both position-based triggers:
     * the continuous one runs slowly and forever, the motion-triggered one runs fast and briefly.
     *
     * **Re-asking at the same [intervalMs] only extends the window** — like [EnsureGps], this is a
     * request for a state rather than a command to rebuild, and the core relies on it: the motion
     * sensor re-arms immediately and can fire repeatedly while one burst is still running, so a
     * dispatcher that tore the request down and rebuilt it would restart the engine's acquisition
     * once per firing. A *different* interval is a different request and does rebuild.
     *
     * **Not folded into [ArmDepartureFence]**, though a pause emits both: they are independently
     * switchable, they fail in different ways, and the fence is a registration the system holds
     * across process death while this is a live request that dies with the service.
     */
    data class StartDepartureProbe(val intervalMs: Long, val durationMs: Long) : Effect

    /** Stop asking. A track is running, or the recorder has been disarmed. */
    data object StopDepartureProbe : Effect

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

    /** A reading arrived, at its own sanitized time — the delivery proof the Record card shows. */
    data class StampReading(val readingMs: Long) : Effect

    data object Publish : Effect
}

/**
 * Which ways of hearing a departure are switched on, and how the ones that sample do it. They are
 * parallel rather than ranked — each fails where another works, which is why the user can run any
 * combination and why none of them is a fallback for another.
 *
 * The fence is free and slow, the motion window is free until it fires, the continuous request costs
 * battery for the whole of the state the recorder spends most of its life in. That is the whole
 * reason the last one is off unless asked for.
 */
data class DepartureTriggers(
    /** The geofence: a system-held registration that survives this process dying. */
    val fence: Boolean,
    /** A standing coarse request for the whole time nothing records. */
    val continuous: Boolean,
    /** A short burst of coarse positions after the hardware motion sensor fires. */
    val motion: Boolean,
) {
    companion object {
        /**
         * How often the standing request asks. Chosen against the measurement that started all of
         * this: the geofence took five minutes to report a departure it had every chance to see
         * within seconds, so anything in this range is a large improvement, and the slower end of it
         * is what keeps an all-day request defensible.
         */
        const val CONTINUOUS_INTERVAL_MS = 60_000L

        /**
         * How fast the motion-triggered burst asks. Not a setting: it runs inside a bounded window,
         * so it trades against nothing a user would recognise — it is as fast as is worth asking a
         * Wi-Fi-derived position, which settles in a few seconds and does not improve by being asked
         * again sooner.
         */
        const val MOTION_INTERVAL_MS = 15_000L

        /**
         * How long that burst runs. Long enough to cover the acquisition and a couple of positions
         * at road speed, short enough that a phone merely picked up and put down costs a handful of
         * Wi-Fi scans. A departure the window misses is not lost — the sensor re-arms, and a moving
         * phone keeps firing it.
         */
        const val MOTION_WINDOW_MS = 120_000L

        /**
         * How long a watch with no anchor probes to get one. Shorter than the motion window because
         * it wants a single sharp position rather than a verdict, and it gives up rather than
         * hunting: a phone whose coarse stream cannot place it under the fence's radius is one the
         * fence keeps its last-known anchor on, which is what the arming already logged the age of.
         */
        const val ANCHOR_WINDOW_MS = 60_000L
    }
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
    val triggers: DepartureTriggers,
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
