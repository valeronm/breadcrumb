package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * The recorder's logical state machine: the single source of truth for the current activity, the
 * start-confirmation streak, and whether a track is open and/or paused. [onActivity] decides what to
 * do on each new reading; the rest of the recorder is side effects.
 *
 * [LocationRecordingService] owns the *resources* — the Room track id, GPS client, accumulators and
 * timers — and drives this machine: it calls [onActivity] for a reading, applies the returned
 * [RecordingAction], then reports the resulting track lifecycle back via [onRecordingStarted] /
 * [onPaused] / [onResumed] / [onClosed]. All calls happen under the service's mutex, so the machine
 * needs no internal synchronization.
 */
class RecordingStateMachine {

    /** Tunables, read fresh on each reading so the user can change them while armed. */
    data class Config(
        val resumeWindowMs: Long,
        val pollEnabled: Boolean,
        val startConfirmations: Int,
    )

    /** The last applied activity — STILL while idle or paused. Drives the notification/UI label. */
    var currentActivity: ActivityType = ActivityType.STILL
        private set

    /** Whether the open track is paused (GPS off, kept open for stitching). */
    var isPaused: Boolean = false
        private set

    private var hasOpenTrack = false
    private var pausedActivity: ActivityType? = null
    private var pausedSinceMs: Long? = null
    private var pendingActivity: ActivityType? = null
    private var pendingCount = 0

    /** Decide on a new reading, updating the decision state; the service applies the returned action. */
    fun onActivity(incoming: ActivityType, nowMs: Long, config: Config): RecordingAction {
        // Non-recording (STILL etc.): a stop, which also cancels any unconfirmed start.
        if (!incoming.recording) {
            val hadPending = pendingActivity != null
            clearPending()
            if (incoming == currentActivity) {
                // Already stopped/paused; only a lingering pending start is worth noting.
                return if (hadPending) RecordingAction.CancelledPending else RecordingAction.Noop
            }
            // Pause keeps the track open for stitching; close immediately when the window is off.
            // (StopNoTrack is defensive: reaching a stop with no open, non-paused track can't happen,
            // since currentActivity is only a moving value while actively recording.)
            val action = when {
                !hasOpenTrack || isPaused -> RecordingAction.StopNoTrack
                config.resumeWindowMs > 0 -> RecordingAction.Pause(currentActivity)
                else -> RecordingAction.Close
            }
            currentActivity = incoming
            return action
        }

        // Moving: resume a paused track of the same activity within the window — never gated, it
        // already represents recent real movement.
        if (canResume(incoming, nowMs, config)) {
            clearPending()
            currentActivity = incoming
            return RecordingAction.Resume
        }

        // Already actively recording this activity — nothing to commit.
        if (incoming == currentActivity && hasOpenTrack && !isPaused) {
            clearPending()
            return RecordingAction.Noop
        }

        // Anything else opens a new track (GPS on), so don't act on a single reading: require the same
        // moving activity across [Config.startConfirmations] readings first. The confirming reading can
        // only come from the poll (transitions fire a single ENTER per onset), so with the poll off a
        // streak could never complete — fall back to an instant start.
        val needed = if (config.pollEnabled) config.startConfirmations else 1
        if (needed > 1) {
            pendingCount = if (pendingActivity == incoming) pendingCount + 1 else 1
            pendingActivity = incoming
            if (pendingCount < needed) {
                // Leave currentActivity unchanged so the next reading re-enters here.
                return RecordingAction.AwaitingConfirmation(incoming, pendingCount, needed)
            }
        }
        clearPending()
        currentActivity = incoming
        return RecordingAction.StartNew(incoming)
    }

    private fun canResume(incoming: ActivityType, nowMs: Long, config: Config): Boolean {
        if (!isPaused || !hasOpenTrack) return false
        if (pausedActivity != incoming) return false
        val since = pausedSinceMs ?: return false
        return config.resumeWindowMs > 0 && nowMs - since <= config.resumeWindowMs
    }

    private fun clearPending() {
        pendingActivity = null
        pendingCount = 0
    }

    // --- Track lifecycle, reported by the service after it performs a side effect ---

    fun onRecordingStarted() {
        hasOpenTrack = true
        isPaused = false
        pausedActivity = null
        pausedSinceMs = null
    }

    fun onPaused(activity: ActivityType, nowMs: Long) {
        isPaused = true
        pausedActivity = activity
        pausedSinceMs = nowMs
    }

    fun onResumed() {
        isPaused = false
        pausedActivity = null
        pausedSinceMs = null
    }

    fun onClosed() {
        hasOpenTrack = false
        isPaused = false
        pausedActivity = null
        pausedSinceMs = null
    }

    /** On (re)arm: reset the activity label and the pending streak, leaving any open track untouched. */
    fun onArmed() {
        currentActivity = ActivityType.STILL
        pendingActivity = null
        pendingCount = 0
    }
}

/** What the service should do for a reading. Names mirror the recorder's side-effect methods. */
sealed interface RecordingAction {
    /** State already matches the reading — do nothing. */
    data object Noop : RecordingAction

    /** An unconfirmed start was abandoned because the user is (still) stationary. */
    data object CancelledPending : RecordingAction

    /** Pause the open track, recording [pausedActivity] as the activity to resume into. */
    data class Pause(val pausedActivity: ActivityType) : RecordingAction

    /** Close the open track immediately (resume window disabled). */
    data object Close : RecordingAction

    /** A stop arrived but there was no open track to pause (defensive — see [RecordingStateMachine]). */
    data object StopNoTrack : RecordingAction

    /** Resume the paused track. */
    data object Resume : RecordingAction

    /** A moving reading that hasn't met the confirmation streak yet ([count] of [needed]). */
    data class AwaitingConfirmation(val activity: ActivityType, val count: Int, val needed: Int) : RecordingAction

    /** Finalize whatever is open and start a new track for [activity]. */
    data class StartNew(val activity: ActivityType) : RecordingAction
}
