package io.github.valeronm.breadcrumb.domain

/**
 * Owns the track lifecycle: turns trusted activity changes (from [ActivityGate]) into
 * track-lifecycle actions, holding the current [Phase]. A stop **closes** the track — the row
 * reaches the timeline with its stats and its stays derived, and the recorder reads as idle — so
 * nothing here is held open waiting to be continued. Whether the next stretch of movement belongs to
 * that track is asked of the stored history when it arrives ([StitchRule]), which is why this has no
 * window, no deadline and no clock in it at all.
 *
 * The one rule left is the **motion family**: walking ⇄ running stays one track, walking → driving
 * splits. Pure and Android-free; the ingest performs the returned [RecordingAction] and moves the
 * phase as it opens and closes the track, through [onRecording] / [onClosed] — the same calls the
 * paths that never ask [onActivity] (a departure, an arrival, a disarm) move it by.
 */
class TrackController {

    sealed interface Phase {
        data object Idle : Phase
        data class Recording(val activity: ActivityType) : Phase
    }

    var phase: Phase = Phase.Idle
        private set

    /** A trusted activity change. */
    fun onActivity(activity: ActivityType): RecordingAction =
        if (!activity.recording) onStop() else onMoving(activity)

    private fun onStop(): RecordingAction = when (phase) {
        is Phase.Recording -> RecordingAction.Close
        else -> RecordingAction.Noop // nothing open to close
    }

    private fun onMoving(activity: ActivityType): RecordingAction = when (val p = phase) {
        // A switch within the same motion family keeps the live track, with a segment break at
        // the boundary; a cross-family change splits.
        is Phase.Recording ->
            if (p.activity.sharesTrackWith(activity)) {
                RecordingAction.ContinueSameTrack(activity)
            } else {
                RecordingAction.StartNew(activity)
            }

        Phase.Idle -> RecordingAction.StartNew(activity)
    }

    /** Recording is live for [activity] — a fresh start or a same-family switch. */
    fun onRecording(activity: ActivityType) {
        phase = Phase.Recording(activity)
    }

    fun onClosed() {
        phase = Phase.Idle
    }
}

/** What the service should do for a trusted activity change. Names mirror its side-effect methods. */
sealed interface RecordingAction {
    data object Noop : RecordingAction

    /** Close the open track. */
    data object Close : RecordingAction

    /** Close whatever is open and ask for a track to record [activity] into. */
    data class StartNew(val activity: ActivityType) : RecordingAction

    /**
     * Keep the live track open across a same-family activity switch (e.g. walking → running),
     * starting a new segment at the boundary. The track keeps its original label.
     */
    data class ContinueSameTrack(val activity: ActivityType) : RecordingAction
}
