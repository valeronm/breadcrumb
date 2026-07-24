package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.ActivityType

/**
 * Owns the track lifecycle: it turns trusted activity changes (from [ActivityGate]) into
 * track-lifecycle actions, holding the current [Phase].
 *
 * Both rules that decide whether an activity continues the open track or starts a new one live
 * here, next to the phase they act on:
 *  - **the resume window** — a stop pauses the track rather than closing it, and a return within
 *    [Phase.Paused.resumeDeadlineMs] resumes it. Timing is compared against the *reading's own*
 *    timestamp, so a decision is correct even when the recorder's timers were frozen in Doze and
 *    the reading arrives late (or the expiry tick never fired at all).
 *  - **the motion family** — walking ⇄ running stays one track; walking → driving splits.
 *
 * Pure and Android-free. The service applies the returned [RecordingAction] and reports the
 * resulting phase back via [onRecording] / [onPaused] / [onClosed].
 */
class TrackController {

    sealed interface Phase {
        data object Idle : Phase
        data class Recording(val activity: ActivityType) : Phase

        /** [activity] resumes this track if a moving reading arrives before [resumeDeadlineMs]. */
        data class Paused(val activity: ActivityType, val resumeDeadlineMs: Long) : Phase
    }

    var phase: Phase = Phase.Idle
        private set

    val isPaused: Boolean get() = phase is Phase.Paused

    /**
     * A trusted activity change at [atMs] (the reading's own time, not the apply time), with the
     * user's [resumeWindowMs].
     */
    fun onActivity(activity: ActivityType, atMs: Long, resumeWindowMs: Long): RecordingAction =
        if (!activity.recording) onStop(atMs, resumeWindowMs) else onMoving(activity, atMs)

    private fun onStop(atMs: Long, resumeWindowMs: Long): RecordingAction = when (val p = phase) {
        // A stop pauses the open track: it stays open until the window lapses, so a brief stop
        // stitches back into the same track instead of splitting it.
        is Phase.Recording -> RecordingAction.Pause(p.activity, atMs + resumeWindowMs)
        else -> RecordingAction.Noop // nothing open to pause
    }

    private fun onMoving(activity: ActivityType, atMs: Long): RecordingAction = when (val p = phase) {
        // A switch within the same motion family keeps the live track, with a segment break at
        // the boundary; a cross-family change splits.
        is Phase.Recording ->
            if (p.activity.sharesTrackWith(activity)) {
                RecordingAction.ContinueSameTrack(activity)
            } else {
                RecordingAction.StartNew(activity)
            }

        // Back before the deadline, in the same family: the same outing continues. Otherwise the
        // window has lapsed (or this is a different kind of movement) and it's a new track —
        // strictly before, so a zero window never resumes.
        is Phase.Paused ->
            if (atMs < p.resumeDeadlineMs && p.activity.sharesTrackWith(activity)) {
                RecordingAction.Resume
            } else {
                RecordingAction.StartNew(activity)
            }

        Phase.Idle -> RecordingAction.StartNew(activity)
    }

    /**
     * Clock tick at (or after) a pause deadline: [RecordingAction.Finalize] once the window has
     * lapsed with the track still paused. Callers' timers are logic-free — an early or stale tick
     * (after a resume, a fresh start, or a newer pause with its own later deadline) is a
     * [RecordingAction.Noop], so a tick can be fired from anywhere, as often as convenient.
     */
    fun onTick(nowMs: Long): RecordingAction = when (val p = phase) {
        is Phase.Paused -> if (nowMs >= p.resumeDeadlineMs) RecordingAction.Finalize else RecordingAction.Noop
        else -> RecordingAction.Noop
    }

    /** Recording is live for [activity] — a fresh start, a resume, or a same-family switch. */
    fun onRecording(activity: ActivityType) {
        phase = Phase.Recording(activity)
    }

    fun onPaused(activity: ActivityType, resumeDeadlineMs: Long) {
        phase = Phase.Paused(activity, resumeDeadlineMs)
    }

    fun onClosed() {
        phase = Phase.Idle
    }
}

/** What the service should do for a trusted activity change. Names mirror its side-effect methods. */
sealed interface RecordingAction {
    data object Noop : RecordingAction

    /**
     * Pause the open track, recording [pausedActivity] as the activity to resume into. The service
     * schedules a logic-free wake at [resumeDeadlineMs] that just ticks the controller.
     */
    data class Pause(val pausedActivity: ActivityType, val resumeDeadlineMs: Long) : RecordingAction

    /** Resume the paused track. */
    data object Resume : RecordingAction

    /** The resume window lapsed with the track still paused — close it. */
    data object Finalize : RecordingAction

    /** Finalize whatever is open and start a new track for [activity]. */
    data class StartNew(val activity: ActivityType) : RecordingAction

    /**
     * Keep the live track open across a same-family activity switch (e.g. walking → running),
     * starting a new segment at the boundary. The track keeps its original label.
     */
    data class ContinueSameTrack(val activity: ActivityType) : RecordingAction
}
