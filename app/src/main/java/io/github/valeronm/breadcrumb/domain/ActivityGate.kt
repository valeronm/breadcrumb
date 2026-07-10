package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * Debounces raw Activity-Recognition readings into a *trusted* activity signal:
 *  - **Starting** a new activity is trusted on the first reading.
 *  - **Returning** to the activity that *just* ended is distinguished within [graceWindowMs]
 *    — a brief stop resumes instead of reading as a fresh start.
 *  - **Stopping** is always trusted immediately.
 *
 * Pure and Android-free, and it knows nothing about tracks: the grace window is signal hysteresis, not
 * a track-resume rule. The emitted [Confirmed] tells the track layer whether the user *started*,
 * *continued* (resumed), or *stopped* an activity — all timing decisions are made here, on the wall
 * clock, so they stay correct even when the recorder's timers are frozen in Doze.
 */
class ActivityGate {

    /** The trusted activity — STILL until a moving activity is confirmed. */
    var confirmed: ActivityType = ActivityType.STILL
        private set

    // The moving activity we just left, still trusted to return instantly until [recentUntilMs].
    private var recentActivity: ActivityType? = null
    private var recentUntilMs = 0L

    fun onReading(raw: ActivityType, nowMs: Long, graceWindowMs: Long): Confirmed {
        // A stop is trusted immediately; remember what we left so a prompt return can resume.
        if (!raw.recording) {
            if (raw == confirmed) return Confirmed.NoChange
            // Only a *moving* activity is worth remembering for a grace-window resume. With STILL
            // as the only non-recording type, confirmed.recording is always true here — the check
            // guards the invariant if a second non-recording type is ever added.
            if (confirmed.recording) {
                recentActivity = confirmed
                recentUntilMs = nowMs + graceWindowMs
            }
            confirmed = raw
            return Confirmed.Stopped(nowMs + graceWindowMs)
        }

        // Already on this activity — nothing changes.
        if (raw == confirmed) return Confirmed.NoChange

        // A prompt return to the activity we just left → a resume rather than a fresh start.
        // Strictly before the deadline: at the deadline the window has expired (a zero window
        // never resumes).
        if (raw == recentActivity && nowMs < recentUntilMs) {
            clearRecent()
            confirmed = raw
            return Confirmed.Continuing(raw)
        }

        clearRecent()
        confirmed = raw
        return Confirmed.Started(raw)
    }

    /**
     * Clock tick at (or after) a [Confirmed.Stopped] deadline: reports whether the grace window
     * has now expired. The caller's timer is logic-free — a stale wake after a resume, a fresh
     * start, or a newer pause (with its own later deadline) is just [Confirmed.NoChange].
     */
    fun onTick(nowMs: Long): Confirmed {
        if (recentActivity == null || nowMs < recentUntilMs) return Confirmed.NoChange
        clearRecent()
        return Confirmed.Expired
    }

    /** On (re)arm: forget any grace state and reset the confirmed activity to STILL. */
    fun onArmed() {
        confirmed = ActivityType.STILL
        clearRecent()
    }

    private fun clearRecent() {
        recentActivity = null
        recentUntilMs = 0L
    }
}

/** The trusted-activity signal the [ActivityGate] emits for a raw reading. */
sealed interface Confirmed {
    /** The trusted activity is unchanged. */
    data object NoChange : Confirmed

    /**
     * The user stopped — the trusted activity is now STILL. A return before [resumeDeadlineMs]
     * (wall clock) reads as [Continuing]; the service should [ActivityGate.onTick] at the deadline.
     */
    data class Stopped(val resumeDeadlineMs: Long) : Confirmed

    /** The grace window after a stop passed with no return — a paused track should finalize. */
    data object Expired : Confirmed

    /** A fresh moving activity started. */
    data class Started(val activity: ActivityType) : Confirmed

    /** A prompt return to the just-ended activity, trusted within the grace window (a resume). */
    data class Continuing(val activity: ActivityType) : Confirmed
}
