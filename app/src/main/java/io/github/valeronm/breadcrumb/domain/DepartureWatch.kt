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
     * What a probe position turned out to be worth. Three outcomes rather than a boolean because
     * the first position is not a departure *or* a non-event: it is where "here" is, and the
     * freshest thing anything else watching for a departure can be centred on.
     */
    sealed interface Verdict {
        /** The first position of a watch that began with nowhere to measure from. */
        data class Anchored(val at: Anchor) : Verdict

        /** Inside the margin — or nothing was being watched for at all, which reads the same here. */
        data object Waiting : Verdict

        data object Departed : Verdict
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
     * Begin watching at [atMs] from [from], or from wherever the first probe position lands when it
     * is null. Adopting the first position is what keeps an anchorless watch from being a blind one:
     * arming happens with no track behind it and often no fix, and "wait for a good fix" resolves to
     * never while GPS is off.
     */
    fun watch(from: Anchor?, atMs: Long) {
        watching = true
        startedAtMs = atMs
        anchor = from
    }

    fun stop() {
        watching = false
        anchor = null
    }

    /**
     * [Verdict.Departed] once [latitude]/[longitude] is further from the anchor than either
     * position's own error can account for. Both accuracies are subtracted because a departure has
     * to out-run the *sum* of the two uncertainties to mean anything — a coarse position beside a
     * coarse anchor is not evidence of movement however far apart the two coordinates read.
     *
     * Adopts the position as the anchor when there is none and reports [Verdict.Anchored]: the first
     * one establishes where "here" is and cannot also be a departure from it.
     *
     * [Verdict.Waiting] while nothing is being watched for, which is where a delivery outliving the
     * request that asked for it lands — it must decide nothing, and must not become an anchor either.
     */
    fun judge(latitude: Double, longitude: Double, accuracyM: Double): Verdict {
        if (!watching) return Verdict.Waiting
        val from = anchor
        if (from == null) {
            val fresh = Anchor(latitude, longitude, accuracyM)
            anchor = fresh
            return Verdict.Anchored(fresh)
        }
        val gap = distance.meters(from.latitude, from.longitude, latitude, longitude)
        return if (gap - from.accuracyM - accuracyM > MARGIN_M) Verdict.Departed else Verdict.Waiting
    }

    companion object {
        /**
         * How far past the combined error a position must sit. It is set for the false positive that
         * actually happens: a *stationary* phone whose Wi-Fi environment shifts reports positions a
         * couple of hundred meters apart, and a margin that only cleared the stated accuracy would
         * read that as leaving. Set against the other side too — at road speed this is a few seconds
         * of travel, so buying the robustness costs almost no latency, and on foot the trigger is
         * not needed at all because walking is the one activity the platform reports reliably.
         *
         * Erring high would be the wrong way round regardless: a departure missed is a journey that
         * never existed, while one imagined opens a track that `EdgeStayDetector` trims and
         * `KeepRule` discards.
         */
        const val MARGIN_M = 150.0
    }
}
