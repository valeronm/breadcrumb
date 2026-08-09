package io.github.valeronm.breadcrumb.domain

/**
 * Whether the phone has left the spot the recorder last stopped at, judged from positions far too
 * coarse to record with. It exists because Activity Recognition describes the *body*, and a body
 * seated in a train or a taxi is genuinely still — the ground moving under it is the only evidence
 * there is, and asking for it cheaply is the only way to ask for it all day.
 *
 * **The verdict is one question asked of every probe position**, so the two triggers that supply
 * them — a standing slow request, and a short fast burst after the hardware motion sensor fires —
 * are suppliers rather than rules, and neither can drift from the other about what leaving means.
 *
 * **Corroboration is the usual evidence**: two consecutive positions past [MARGIN_M] are a
 * departure, while one alone must clear [SOLO_MARGIN_M] — the pricing is on the two constants.
 * [Verdict.Near] carries the distance against the bar so the log can say what the real
 * distributions are.
 *
 * **Nothing here reaches a track.** These positions come from Wi-Fi and cell, land tens to hundreds
 * of meters out, and are a wake and nothing else — the same contract the departure fence has.
 */
class DepartureWatch(private val distance: DistanceFn) {

    /**
     * What a probe position turned out to be worth. Named cases rather than a boolean because the
     * first position is not a departure *or* a non-event: it is where "here" is, and the freshest
     * thing anything else watching for a departure can be centred on.
     */
    sealed interface Verdict {
        /** The first position of a watch that began with nowhere to measure from. */
        data class Anchored(val at: MeasuredPosition) : Verdict

        /**
         * Judged, and not far enough. [gapM] against [barM] is **the measurement the whole rule
         * turns on**, and the one nothing outside this class could previously see: a burst that
         * ends with no departure otherwise says nothing about whether the phone stayed put or
         * merely fell short of the bar. [barM] is the bar in force for this position's evidence,
         * and [marginM] names which margin built it — the corroborated one or the solo one — since
         * the two bars overlap once the accuracies are added, and a logged distribution that can't
         * be split by regime can tune neither constant.
         */
        data class Near(val gapM: Double, val barM: Double, val marginM: Double) : Verdict

        data class Departed(val gapM: Double, val barM: Double, val marginM: Double) : Verdict

        /** Nothing is being watched for; a delivery that outlived its request lands here. */
        data object Dormant : Verdict
    }

    private var anchor: MeasuredPosition? = null

    // Whether the previous position already sat past the corroborated margin — the first half of
    // the two-position evidence, holding across exactly one delivery. A delivery is a count, not a
    // duration: under Doze the next one can be an hour away, and the vouch deliberately keeps — a
    // stale pairing fires at worst the bounded false departure the margins' pricing accepts.
    private var corroborating = false

    /**
     * Whether a departure is being watched for at all — **not** whether an anchor is held, which is
     * the distinction the whole anchorless case turns on. A watch that begins with nowhere to
     * measure from is running and waiting for its first position; conflating the two would make it
     * report itself dormant and swallow every position it was started to judge.
     */
    var watching: Boolean = false
        private set

    /**
     * When watching began — **the latency a departure is reported against**, and the only number
     * that says whether a trigger is worth its cost. Held here rather than at the call site because
     * the two suppliers start at different moments (one at the stop, one at the sensor firing) while
     * the question they answer began being asked at the stop, and a latency measured from the burst
     * would flatter it by exactly the delay being measured.
     */
    var startedAtMs: Long = 0L
        private set

    /**
     * Begin watching at [atMs] from [from], or from wherever the first probe position lands when it
     * is null. Adopting the first position is what keeps an anchorless watch from being a blind one:
     * arming happens with no track behind it and often no fix, and "wait for a good fix" resolves to
     * never while GPS is off.
     */
    fun watch(from: MeasuredPosition?, atMs: Long) {
        watching = true
        startedAtMs = atMs
        anchor = from
        corroborating = false
    }

    fun stop() {
        watching = false
        anchor = null
        corroborating = false
    }

    /**
     * [Verdict.Departed] once [position] is further from the anchor than either position's own
     * error can account for, plus the margin its evidence earns ([MARGIN_M] / [SOLO_MARGIN_M]).
     * Both accuracies are subtracted because a departure has to out-run the *sum* of the two
     * uncertainties to mean anything — a coarse position beside a coarse anchor is not evidence of
     * movement however far apart the two coordinates read.
     *
     * Adopts the position as the anchor when there is none and reports [Verdict.Anchored]: the first
     * one establishes where "here" is and cannot also be a departure from it. A position arriving
     * while nothing is watched for must decide nothing and must not become an anchor either.
     */
    fun judge(position: MeasuredPosition): Verdict {
        if (!watching) return Verdict.Dormant
        val from = anchor
        if (from == null) {
            anchor = position
            return Verdict.Anchored(position)
        }
        val gap = distance.meters(
            from.coordinate.lat, from.coordinate.lon,
            position.coordinate.lat, position.coordinate.lon,
        )
        val errorM = from.accuracyM + position.accuracyM
        val corroboratedBar = MARGIN_M + errorM
        val marginM = if (corroborating) MARGIN_M else SOLO_MARGIN_M
        val bar = marginM + errorM
        corroborating = gap > corroboratedBar
        return if (gap > bar) Verdict.Departed(gap, bar, marginM) else Verdict.Near(gap, bar, marginM)
    }

    companion object {
        /**
         * The margin the usual evidence clears: two consecutive positions this far past the
         * combined error. Corroboration is what makes it affordable — the coarse stream's one
         * confident lie, a stationary phone re-deriving its position from a changed Wi-Fi
         * environment, retreats on the next delivery and never corroborates itself, while measured
         * standstill wander stays under ~20 m against accuracies in the tens. A lie that *repeats*
         * still fires, and what that now costs is bounded: a false departure opens a track
         * [ArrivalWatch] pauses minutes later and `KeepRule` discards.
         */
        const val MARGIN_M = 50.0

        /**
         * What a *lone* position must clear to fire by itself — this rule's solo bar, as
         * `EdgeStayDetector`'s `soloMovingSpeed` is to its `movingSpeed`. Kept at the margin every
         * position used to need, whose width was priced for exactly the single repositioning jump
         * that corroboration now absorbs.
         */
        const val SOLO_MARGIN_M = 150.0
    }
}
