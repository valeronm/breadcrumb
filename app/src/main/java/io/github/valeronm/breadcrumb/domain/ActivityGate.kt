package io.github.valeronm.breadcrumb.domain

/**
 * Turns the jittery Activity-Recognition stream into a *trusted* activity signal: raw readings
 * arrive as transitions and snapshots, out of order and repeated, and this reports only when the
 * trusted activity actually changes. Also the one place a second witness may overrule that stream:
 * a reading the position stream contradicts is **parked**, not dropped — see [onReading] — and
 * [onMotion] releases it when the contradiction clears. A pure signal filter — no timing, windows,
 * or track vocabulary: whether a change resumes a paused track or starts a new one is a
 * track-lifecycle question [TrackController] owns (with the resume window and the clock it needs);
 * *when* a parked reading is reconsidered is the recorder's, stamped with its own clock.
 */
class ActivityGate {

    /** The trusted activity — STILL until a moving activity is confirmed. */
    var confirmed: ActivityType = ActivityType.STILL
        private set

    /**
     * The reading being held back because the ground contradicts it, or null. One slot: a newer
     * reading always supersedes an older one, since the older describes a moment that has passed.
     */
    var parked: ActivityType? = null
        private set

    /**
     * Null when [raw] leaves the trusted activity unchanged — or when [motion] contradicts it, in
     * which case the reading is parked for [onMotion] to release. A contradicted reading is parked
     * rather than refused because the Activity-Recognition stream is edge-triggered: Play Services
     * announces that the user *became* still and, having announced it, will not say so again —
     * discard the edge and the recorder never learns of the stop at all, the track running until
     * something else happens to close it; holding the reading keeps the edge and costs only the
     * delay. [motion] defaults to [Motion.Unknown], which contradicts nothing: a caller that does
     * not consult a second witness gets exactly the filter this was before there was one.
     */
    fun onReading(raw: ActivityType, motion: Motion = Motion.Unknown): ActivityType? {
        // Nothing to park when the gate already believes the reading: there is no edge to lose.
        if (raw == confirmed) {
            parked = null
            return null
        }
        if (contradicts(raw, motion)) {
            parked = raw
            return null
        }
        parked = null
        confirmed = raw
        return raw
    }

    /**
     * Reconsider the parked reading against a fresh [motion], returning it once credible — null
     * when nothing is parked or the contradiction still stands. [Motion.Unknown] releases it: that
     * is the point of abstention being a verdict of its own — overruling the activity stream takes
     * positive evidence, so the moment the evidence stops arriving the reading the recorder was
     * given stands. The recorder leans on this wherever it is about to turn GPS off: with no fixes
     * nothing could ever release the slot, so it is released on the way down.
     */
    fun onMotion(motion: Motion): ActivityType? {
        val held = parked ?: return null
        if (contradicts(held, motion)) return null
        // A held reading is never the trusted one: parking requires the reading to differ from
        // [confirmed], and every path that moves [confirmed] rewrites the slot.
        parked = null
        confirmed = held
        return held
    }

    /** On (re)arm: the trusted activity resets to STILL and any held reading is dropped. */
    fun onArmed() {
        confirmed = ActivityType.STILL
        parked = null
    }

    /**
     * Ground that is provably moving contradicts a report that the user has stopped: aboard
     * something that carries the phone the body really is still while the journey is not, and
     * acting on the label would pause the recorder mid-journey and turn GPS off for the rest of it.
     * Only STILL is weighed. A *foot* reading at vehicle speed is equally incredible, but the
     * window that measures the ground is a trailing one and reads vehicle-scale for a while after
     * leaving a car — parking the genuine walking reading that follows every drive and landing the
     * split late, with a tail of walking fixes on the drive. That trades a fragmented-but-complete
     * track, which merging and retyping repair, for a silent labelling error on an everyday
     * transition.
     */
    private fun contradicts(raw: ActivityType, motion: Motion): Boolean =
        raw == ActivityType.STILL && motion is Motion.Moving
}
