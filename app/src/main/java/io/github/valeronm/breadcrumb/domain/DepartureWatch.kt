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
 * **Nothing here reaches a track.** These positions come from Wi-Fi and cell, land tens to hundreds
 * of meters out, and are a wake and nothing else — the same contract the departure fence has.
 */
class DepartureWatch(private val distance: DistanceFn) {

    /**
     * Where the phone was, and how well that was known. [accuracyM] is carried rather than assumed
     * because the two anchors differ by an order of magnitude: a pause anchors on the recorder's own
     * last good GPS fix, while a watch that begins with no fix at all adopts its first probe
     * position, which is the coarse kind being judged.
     */
    data class Anchor(val latitude: Double, val longitude: Double, val accuracyM: Double)

    /**
     * What a probe position turned out to be worth. Named cases rather than a boolean because the
     * first position is not a departure *or* a non-event: it is where "here" is, and the freshest
     * thing anything else watching for a departure can be centred on.
     */
    sealed interface Verdict {
        /** The first position of a watch that began with nowhere to measure from. */
        data class Anchored(val at: Anchor) : Verdict

        /**
         * Judged, and not far enough. [gapM] against [barM] is **the measurement the whole rule
         * turns on**, and the one nothing outside this class could previously see: a burst that
         * ends with no departure otherwise says nothing about whether the phone stayed put or
         * merely fell short of the bar.
         */
        data class Near(val gapM: Double, val barM: Double) : Verdict

        data class Departed(val gapM: Double, val barM: Double) : Verdict

        /** Nothing is being watched for; a delivery that outlived its request lands here. */
        data object Dormant : Verdict
    }

    private var anchor: Anchor? = null

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
     * What the last position was judged to be. Held here rather than copied out by a caller so it
     * cannot outlive the watch it describes: starting and stopping both reset it to [Verdict.Dormant],
     * so a reader that asks at the wrong moment is told there is nothing to say instead of handed a
     * plausible-looking measurement from a torn-down watch.
     */
    var lastVerdict: Verdict = Verdict.Dormant
        private set

    /**
     * Begin watching at [atMs] from [from], or from wherever the first probe position lands when it
     * is null. Adopting the first position is what keeps an anchorless watch from being a blind one:
     * arming happens with no track behind it and often no fix, and "wait for a good fix" resolves to
     * never while GPS is off.
     */
    fun watch(from: Anchor?, atMs: Long) {
        watching = true
        startedAtMs = atMs
        anchor = from
        lastVerdict = Verdict.Dormant
    }

    fun stop() {
        watching = false
        anchor = null
        lastVerdict = Verdict.Dormant
    }

    /**
     * [Verdict.Departed] once [latitude]/[longitude] is further from the anchor than either
     * position's own error can account for. Both accuracies are subtracted because a departure has
     * to out-run the *sum* of the two uncertainties to mean anything — a coarse position beside a
     * coarse anchor is not evidence of movement however far apart the two coordinates read.
     *
     * Adopts the position as the anchor when there is none and reports [Verdict.Anchored]: the first
     * one establishes where "here" is and cannot also be a departure from it. A position arriving
     * while nothing is watched for must decide nothing and must not become an anchor either.
     */
    fun judge(latitude: Double, longitude: Double, accuracyM: Double): Verdict {
        val verdict = decide(latitude, longitude, accuracyM)
        lastVerdict = verdict
        return verdict
    }

    private fun decide(latitude: Double, longitude: Double, accuracyM: Double): Verdict {
        if (!watching) return Verdict.Dormant
        val from = anchor
        if (from == null) {
            val fresh = Anchor(latitude, longitude, accuracyM)
            anchor = fresh
            return Verdict.Anchored(fresh)
        }
        val gap = distance.meters(from.latitude, from.longitude, latitude, longitude)
        val bar = MARGIN_M + from.accuracyM + accuracyM
        return if (gap > bar) Verdict.Departed(gap, bar) else Verdict.Near(gap, bar)
    }

    companion object {
        /**
         * How far past the combined error a position must sit — **provisional, and not yet set by
         * anything measured.** It was picked to clear the false positive a coarse position stream is
         * assumed to produce, a stationary phone re-deriving its position from a changed Wi-Fi
         * environment; that assumption has not been tested against this app's own data, and until it
         * is, the number rests on nothing firmer than plausibility.
         *
         * **What it costs is not this number alone.** The distance actually required is
         * `MARGIN_M + anchorAccuracy + fixAccuracy`, so the two accuracies dominate it: against the
         * ~100 m positions the fused engine has been observed to return, that is ~255 m from a pause
         * anchored on a good GPS fix and ~350 m from an arming anchored on a coarse one. Tuning this
         * constant alone therefore moves the bar much less than it appears to — and the departure
         * fence answers the same question at a 100 m radius with its own error handling inside it.
         *
         * The direction of error is at least the safe one: a departure missed is a journey never
         * recorded, while one imagined opens a track that `EdgeStayDetector` trims and `KeepRule`
         * discards. [Verdict.Near] carries both numbers so a log can eventually say what the real
         * distribution is, and replace this with something measured.
         */
        const val MARGIN_M = 150.0
    }
}
