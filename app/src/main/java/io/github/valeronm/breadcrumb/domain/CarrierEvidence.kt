package io.github.valeronm.breadcrumb.domain

/**
 * The witness's case that a track's label is wrong — accumulated time the position stream
 * contradicted the label's claim about the journey. A carried foot-labelled track *records*
 * completely (the widened jump ceiling keeps its fixes, the parked STILL keeps it open) yet still
 * carries its foot label; evidence accrues from the first contradicting fix but deliberately
 * commits only at finish, since one moment's evidence is one window's worth — the amount the
 * confirmer's feed contract documents as forgeable by a lone teleport — and verdicts flap by
 * design. Two channels, two ways to be aboard, either alone making the case:
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
 * Raw transition counts are deliberately not a channel: flip-flop frequency is detection noise,
 * saying nothing the parked-STILL overlap doesn't with more specificity. Time is credited only
 * between *consecutive* samples both holding the condition, capped at [MAX_CREDIT_MS] per pair —
 * evidence accrues at the rate fixes actually arrive, so a stale window cannot credit a delivery
 * gap, and the abstaining samples after every GPS restart (an empty window reads [Motion.Unknown])
 * fence the credit off naturally. [Motion.Unknown] holds neither condition, so it accrues nothing.
 * In-memory: [restart]ed when a track opens, read when it closes; process death loses it and the
 * dangling track finishes without a rename — with the evidence gone, abstaining is honest.
 */
class CarrierEvidence(private val footCeiling: Speed) {

    /** Cumulative ms the body-still channel credited. */
    var bodyStillMs: Long = 0
        private set

    /** Cumulative ms the observed ground speed exceeded [footCeiling]. */
    var bodyMovingMs: Long = 0
        private set

    private var hasSample = false
    private var lastAtMs = 0L
    private var lastStill = false
    private var lastFast = false

    /** Whether either channel has made the sustained case. */
    val proven: Boolean
        get() = bodyStillMs >= BODY_STILL_PROVEN_MS || bodyMovingMs >= BODY_MOVING_PROVEN_MS

    /**
     * Whether the case is [proven] against [label]. Only **foot-group** labels can be contradicted:
     * both channels weigh foot ceilings only.
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
     * activity while a STILL was parked, or null while none was.
     * Out-of-order samples are refused, like the confirmer refuses out-of-order fixes.
     */
    fun onSample(atMs: Long, motion: Motion, labelFloor: Speed, parkedUnder: Speed?) {
        if (hasSample && atMs < lastAtMs) return
        val moving = motion as? Motion.Moving
        val still = moving != null && parkedUnder != null && moving.speed > labelFloor.coerceAtLeast(parkedUnder)
        val fast = moving != null && moving.speed > footCeiling
        if (hasSample) {
            val credit = minOf(atMs - lastAtMs, MAX_CREDIT_MS)
            if (still && lastStill) bodyStillMs += credit
            if (fast && lastFast) bodyMovingMs += credit
        }
        hasSample = true
        lastAtMs = atMs
        lastStill = still
        lastFast = fast
    }

    /** A new track: no case against its label. */
    fun restart() {
        bodyStillMs = 0
        bodyMovingMs = 0
        hasSample = false
        lastAtMs = 0
        lastStill = false
        lastFast = false
    }

    companion object {
        /**
         * The sustained-case thresholds — field-test tunables, like the confirmer's window and the
         * ceiling's margin. The floor under both: a forged verdict decays within one confirmer
         * window (~20 s at a quick cadence), so neither may be reachable inside one.
         */
        const val BODY_STILL_PROVEN_MS = 60_000L
        const val BODY_MOVING_PROVEN_MS = 120_000L

        /**
         * The most a single pair of samples may credit — just over the slowest sampling interval,
         * so sparse cadences accrue in full while a stale window spanning a delivery gap cannot
         * credit the gap as evidence.
         */
        private const val MAX_CREDIT_MS = 35_000L
    }
}
