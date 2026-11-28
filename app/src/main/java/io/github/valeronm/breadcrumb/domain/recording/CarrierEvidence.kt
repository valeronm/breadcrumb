package io.github.valeronm.breadcrumb.domain.recording

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.TrackGroup
import io.github.valeronm.breadcrumb.domain.tracks.Speed

/**
 * The witness's case that a track's label is wrong — accumulated time the position stream
 * contradicted the label's claim about the journey. A carried foot-labelled track *records*
 * completely (the widened jump ceiling keeps its fixes, the parked STILL keeps it open) yet still
 * carries its foot label; evidence accrues from the first contradicting fix but deliberately
 * commits only at finish, since one moment's evidence is one window's worth — the amount the
 * confirmer's feed contract documents as forgeable by a lone teleport — and verdicts flap by
 * design. Each channel is a way to be aboard, and any one alone makes the case:
 *  - **Body-still** ([bodyStillMs]): time a STILL sat parked under ground moving faster than any
 *    pace the label or the trusted activity can explain on foot — the defining carried signature:
 *    detection actively asserted the body stopped while the ground provably kept moving. A STILL
 *    misreported mid-walk or mid-run parks under walking or running pace just the same, and a run
 *    can sit on a walking-labelled track; above the higher ceiling nothing else produces the
 *    signature (a teleport-forged verdict parks a STILL only briefly, the false [Motion.Moving]
 *    decaying within a window before the STILL promotes). Its threshold is short — the signal is
 *    near-conclusive.
 *  - **Body-moving** ([bodyMovingMs]): time observed ground speed exceeded the **most permissive
 *    ceiling in the foot group** ([footCeiling]) — walking about a deck, or a crossing boarded on
 *    foot with no STILL ever sent. The group's ceiling, not the label's, deliberately: a run inside
 *    a walking-labelled track (same group, label kept) sustains speeds above WALKING's own ceiling
 *    and judging against that would rename an honest interval run; no human sustains the group
 *    ceiling, any carrier clears it.
 *  - **Stepless** ([bodySteplessMs]): time the ground moved faster than the label can explain on
 *    foot while the step counter counted at most [STEPLESS_MAX_STEPS] over the last
 *    [STEP_WINDOW_MS]. A seated rider counts no steps at a city carrier's pace, below the group
 *    ceiling; road bumps count a few. The floor is the label's own, since a phone in a pushed
 *    stroller counts no steps on a walk or a run either.
 * Raw transition counts are deliberately not a channel: flip-flop frequency is detection noise,
 * saying nothing the parked-STILL overlap doesn't with more specificity. Time is credited only
 * between *consecutive* samples both holding the condition, capped at [MAX_CREDIT_MS] per pair —
 * evidence accrues at the rate fixes actually arrive, so a stale window cannot credit a delivery
 * gap, and the abstaining samples after every GPS restart (an empty window reads [Motion.Unknown])
 * fence the credit off naturally. [Motion.Unknown] holds no channel's condition, so it accrues
 * nothing. In-memory, one per track: built when the track opens, read when it closes; process
 * death loses it and the dangling track finishes without a rename — with the evidence gone,
 * abstaining is honest.
 */
class CarrierEvidence(private val footCeiling: Speed) {

    private class Channel {
        var ms = 0L
        private var last = false

        fun sample(holds: Boolean, credit: Long?) {
            if (credit != null && holds && last) ms += credit
            last = holds
        }
    }

    private val stillChannel = Channel()
    private val movingChannel = Channel()
    private val steplessChannel = Channel()

    /** Cumulative ms the body-still channel credited. */
    val bodyStillMs: Long get() = stillChannel.ms

    /** Cumulative ms the observed ground speed exceeded [footCeiling]. */
    val bodyMovingMs: Long get() = movingChannel.ms

    /** Cumulative ms the ground outran the label's pace on foot with the step counter near still. */
    val bodySteplessMs: Long get() = steplessChannel.ms

    private var lastAtMs: Long? = null

    /** Whether any channel has made the sustained case. */
    val proven: Boolean
        get() = bodyStillMs >= BODY_STILL_PROVEN_MS ||
            bodyMovingMs >= BODY_MOVING_PROVEN_MS ||
            bodySteplessMs >= BODY_STEPLESS_PROVEN_MS

    /**
     * Whether the case is [proven] against [label]. Only **foot-group** labels can be contradicted:
     * every channel weighs a claim made on foot.
     */
    fun contradicts(label: ActivityType): Boolean = proven && label.trackGroup == TrackGroup.FOOT

    /**
     * What a track labelled [label] is renamed to at finish: null unless the case [contradicts] the
     * label. The target, [ActivityType.UNKNOWN], names the movement and not the carrier: a drive
     * already carries the ceiling and group this would grant, and renaming it would discard real
     * information.
     */
    fun renameFor(label: ActivityType): ActivityType? = if (contradicts(label)) ActivityType.UNKNOWN else null

    /**
     * Fold in one consulted verdict: [motion] as produced for the fix at [atMs], [labelFloor] the
     * fastest pace the track's label can explain on foot, and [parkedUnder] the same for the trusted
     * activity while a STILL was parked, or null while none was. [stepsInWindow] is the steps
     * counted over the [STEP_WINDOW_MS] up to the fix, null where the counter cannot say.
     * Out-of-order samples are refused, like the confirmer refuses out-of-order fixes.
     */
    fun onSample(atMs: Long, motion: Motion, labelFloor: Speed, parkedUnder: Speed?, stepsInWindow: Long?) {
        val lastAtMs = lastAtMs
        if (lastAtMs != null && atMs < lastAtMs) return
        val moving = motion as? Motion.Moving
        val still = moving != null && parkedUnder != null && moving.speed > labelFloor.coerceAtLeast(parkedUnder)
        val fast = moving != null && moving.speed > footCeiling
        val stepless = moving != null &&
            moving.speed > labelFloor &&
            stepsInWindow != null &&
            stepsInWindow <= STEPLESS_MAX_STEPS
        val credit = lastAtMs?.let { minOf(atMs - it, MAX_CREDIT_MS) }
        stillChannel.sample(still, credit)
        movingChannel.sample(fast, credit)
        steplessChannel.sample(stepless, credit)
        this.lastAtMs = atMs
    }

    companion object {
        /**
         * The sustained-case thresholds — field-test tunables, like the confirmer's window and the
         * ceiling's margin. The floor under each: a forged verdict decays within one confirmer
         * window (~20 s at a quick cadence), so none may be reachable inside one.
         */
        const val BODY_STILL_PROVEN_MS = 60_000L
        const val BODY_MOVING_PROVEN_MS = 120_000L
        const val BODY_STEPLESS_PROVEN_MS = 120_000L

        /**
         * A phone in a car has counted bursts of up to 4 steps off road bumps; a walk counts 38 or
         * more in [STEP_WINDOW_MS].
         */
        const val STEPLESS_MAX_STEPS = 5L
        const val STEP_WINDOW_MS = 30_000L

        /**
         * The most a single pair of samples may credit — just over the slowest sampling interval,
         * so sparse cadences accrue in full while a stale window spanning a delivery gap cannot
         * credit the gap as evidence.
         */
        private const val MAX_CREDIT_MS = 35_000L
    }
}
