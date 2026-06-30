package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType

/**
 * Debounces raw Activity-Recognition readings into a *trusted* activity signal. The debounce is
 * asymmetric:
 *  - **Starting** a new activity needs [Config.startConfirmations] readings in a row, so a lone
 *    high-confidence blip that reverts to STILL on the next poll never gets trusted.
 *  - **Returning** to the activity that *just* ended is trusted instantly within [Config.graceWindowMs]
 *    — a brief stop resumes without having to re-earn confidence.
 *  - **Stopping** is always trusted immediately.
 *
 * Pure and Android-free, and it knows nothing about tracks: the grace window is signal hysteresis, not
 * a track-resume rule. The emitted [Confirmed] tells the track layer whether the user *started*,
 * *continued* (resumed), or *stopped* an activity — all timing decisions are made here, on the wall
 * clock, so they stay correct even when the recorder's timers are frozen in Doze.
 */
class ActivityGate {

    data class Config(
        val startConfirmations: Int,
        val graceWindowMs: Long,
        /** With the poll off there's no stream to corroborate a start, so confirm on the first read. */
        val pollEnabled: Boolean,
    )

    /** The trusted activity — STILL until a moving activity is confirmed. */
    var confirmed: ActivityType = ActivityType.STILL
        private set

    // A new moving activity building toward confirmation (the rising-edge streak).
    private var pendingActivity: ActivityType? = null
    private var pendingCount = 0

    // The moving activity we just left, still trusted to return instantly until [recentUntilMs].
    private var recentActivity: ActivityType? = null
    private var recentUntilMs = 0L

    fun onReading(raw: ActivityType, nowMs: Long, config: Config): Confirmed {
        // A stop is trusted immediately; remember what we left so a prompt return can resume.
        if (!raw.recording) {
            val hadPending = pendingActivity != null
            clearPending()
            if (raw == confirmed) return if (hadPending) Confirmed.Cancelled else Confirmed.NoChange
            if (confirmed.recording && config.graceWindowMs > 0) {
                recentActivity = confirmed
                recentUntilMs = nowMs + config.graceWindowMs
            }
            confirmed = raw
            return Confirmed.Stopped
        }

        // Already on this activity — nothing changes.
        if (raw == confirmed) {
            clearPending()
            return Confirmed.NoChange
        }

        // A prompt return to the activity we just left → trusted instantly (a resume).
        if (raw == recentActivity && nowMs <= recentUntilMs) {
            clearTentative()
            confirmed = raw
            return Confirmed.Continuing(raw)
        }

        // Otherwise a genuinely new activity must corroborate across the streak.
        val needed = if (config.pollEnabled) config.startConfirmations else 1
        if (needed > 1) {
            pendingCount = if (pendingActivity == raw) pendingCount + 1 else 1
            pendingActivity = raw
            if (pendingCount < needed) return Confirmed.Awaiting(raw, pendingCount, needed)
        }
        clearTentative()
        confirmed = raw
        return Confirmed.Started(raw)
    }

    /** On (re)arm: forget any in-flight confirmation/grace and reset the confirmed activity to STILL. */
    fun onArmed() {
        confirmed = ActivityType.STILL
        clearTentative()
    }

    private fun clearPending() {
        pendingActivity = null
        pendingCount = 0
    }

    private fun clearTentative() {
        clearPending()
        recentActivity = null
        recentUntilMs = 0L
    }
}

/** The trusted-activity signal the [ActivityGate] emits for a raw reading. */
sealed interface Confirmed {
    /** The trusted activity is unchanged. */
    data object NoChange : Confirmed

    /** A stop arrived that only abandoned an unconfirmed start; the trusted activity stays STILL. */
    data object Cancelled : Confirmed

    /** The user stopped — the trusted activity is now STILL. */
    data object Stopped : Confirmed

    /** A fresh moving activity was confirmed (passed the streak). */
    data class Started(val activity: ActivityType) : Confirmed

    /** A prompt return to the just-ended activity, trusted within the grace window (a resume). */
    data class Continuing(val activity: ActivityType) : Confirmed

    /** A moving activity still earning confirmation ([count] of [needed]); not yet trusted. */
    data class Awaiting(val activity: ActivityType, val count: Int, val needed: Int) : Confirmed
}
