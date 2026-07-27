package io.github.valeronm.breadcrumb.domain

/**
 * Turns the jittery Activity-Recognition stream into a *trusted* activity signal: the raw readings
 * arrive as transitions and snapshots, out of order and repeated, and this reports only when the
 * trusted activity actually changes.
 *
 * It also holds the one place a second witness may overrule that stream. A reading the position
 * stream contradicts is **parked**, not dropped — see [onReading] — and [onMotion] releases it when
 * the contradiction clears.
 *
 * A pure signal filter — no timing, no windows, no track vocabulary. Whether a change resumes a
 * paused track or starts a new one is a track-lifecycle question, and [TrackController] owns it
 * (along with the resume window and the clock it needs); *when* a parked reading is reconsidered is
 * the recorder's, and it stamps the promotion with its own clock.
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
     * which case the reading is parked for [onMotion] to release.
     *
     * **A contradicted reading is parked rather than refused, because the Activity-Recognition
     * stream is edge-triggered.** Play Services announces that the user *became* still; having
     * announced it, it considers itself in that state and will not say so again. Discard the edge
     * and the recorder never learns of the stop at all — the track would run until something else
     * happened to close it. Holding the reading keeps the edge and costs only the delay.
     *
     * [motion] defaults to [Motion.Unknown], which contradicts nothing: a caller that does not
     * consult a second witness gets exactly the filter this was before there was one.
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
     * Reconsider the parked reading against a fresh [motion], returning it once it is credible —
     * or null when nothing is parked and when the contradiction still stands.
     *
     * [Motion.Unknown] releases the reading. That is the point of abstention being a verdict of its
     * own: overruling the activity stream takes positive evidence, so the moment the evidence stops
     * arriving the reading the recorder was given stands. The recorder leans on this wherever it is
     * about to turn GPS off — with no fixes there is nothing left that could ever release the slot,
     * so it is released on the way down.
     */
    fun onMotion(motion: Motion): ActivityType? {
        val held = parked ?: return null
        if (contradicts(held, motion)) return null
        parked = null
        if (held == confirmed) return null
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
     * something that carries the phone, the body really is still while the journey is not, and
     * acting on the label would pause the recorder mid-journey and turn GPS off for the rest of it.
     *
     * Only STILL is weighed. A *foot* reading arriving at vehicle speed is equally incredible, but
     * the window that measures the ground is a trailing one, so for a while after leaving a car it
     * still reads vehicle-scale — parking the genuine walking reading that follows every drive,
     * and landing the split late with a tail of walking fixes on the drive. That trades a
     * fragmented-but-complete track, which merging and retyping repair, for a silent labelling
     * error on an everyday transition.
     */
    private fun contradicts(raw: ActivityType, motion: Motion): Boolean =
        raw == ActivityType.STILL && motion is Motion.Moving
}
