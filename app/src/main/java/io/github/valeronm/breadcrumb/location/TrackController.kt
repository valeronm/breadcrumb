package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * Translates the trusted activity signal ([Confirmed], from [ActivityGate]) into track-lifecycle
 * actions, holding the current [Phase]. Pure mechanics with **no timing**: the gate has already
 * decided whether a moving reading is a fresh start or a resume, so this only maps that onto
 * Idle/Recording/Paused. The sealed [Phase] makes illegal states (e.g. "moving but no track")
 * unrepresentable, so there are no defensive guards.
 *
 * [LocationRecordingService] applies the returned [RecordingAction] and reports the resulting phase
 * back via [onRecordingStarted] / [onPaused] / [onResumed] / [onClosed] (including for closes driven
 * by the finalize timer or the resume-distance split, not just by [onConfirmed]).
 */
class TrackController {

    sealed interface Phase {
        data object Idle : Phase
        data class Recording(val activity: ActivityType) : Phase
        data class Paused(val activity: ActivityType) : Phase
    }

    var phase: Phase = Phase.Idle
        private set

    val isPaused: Boolean get() = phase is Phase.Paused

    fun onConfirmed(confirmed: Confirmed): RecordingAction = when (confirmed) {
        // Non-changes are handled by the service before it ever reaches here; map to Noop for totality.
        Confirmed.NoChange, Confirmed.Cancelled, is Confirmed.Awaiting -> RecordingAction.Noop

        Confirmed.Stopped -> when (val p = phase) {
            is Phase.Recording -> RecordingAction.Pause(p.activity)
            else -> RecordingAction.Noop // nothing open to pause
        }

        is Confirmed.Continuing -> when (phase) {
            is Phase.Paused -> RecordingAction.Resume
            // The paused track was already finalized (timer) — treat the return as a fresh start.
            else -> RecordingAction.StartNew(confirmed.activity)
        }

        is Confirmed.Started -> RecordingAction.StartNew(confirmed.activity)
    }

    fun onRecordingStarted(activity: ActivityType) {
        phase = Phase.Recording(activity)
    }

    fun onPaused(activity: ActivityType) {
        phase = Phase.Paused(activity)
    }

    fun onResumed(activity: ActivityType) {
        phase = Phase.Recording(activity)
    }

    fun onClosed() {
        phase = Phase.Idle
    }
}

/** What the service should do for a trusted activity change. Names mirror its side-effect methods. */
sealed interface RecordingAction {
    data object Noop : RecordingAction

    /** Pause the open track, recording [pausedActivity] as the activity to resume into. */
    data class Pause(val pausedActivity: ActivityType) : RecordingAction

    /** Resume the paused track. */
    data object Resume : RecordingAction

    /** Finalize whatever is open and start a new track for [activity]. */
    data class StartNew(val activity: ActivityType) : RecordingAction
}
