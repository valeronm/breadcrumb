package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.ActivityType

/**
 * Turns the jittery Activity-Recognition stream into a *trusted* activity signal: the raw readings
 * arrive as transitions and snapshots, out of order and repeated, and this reports only when the
 * trusted activity actually changes.
 *
 * A pure signal filter — no timing, no windows, no track vocabulary. Whether a change resumes a
 * paused track or starts a new one is a track-lifecycle question, and [TrackController] owns it
 * (along with the resume window and the clock it needs).
 */
class ActivityGate {

    /** The trusted activity — STILL until a moving activity is confirmed. */
    var confirmed: ActivityType = ActivityType.STILL
        private set

    /** Null when [raw] leaves the trusted activity unchanged. */
    fun onReading(raw: ActivityType): ActivityType? {
        if (raw == confirmed) return null
        confirmed = raw
        return raw
    }

    /** On (re)arm: the trusted activity resets to STILL. */
    fun onArmed() {
        confirmed = ActivityType.STILL
    }
}
