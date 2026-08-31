package io.github.valeronm.breadcrumb.domain

/**
 * What the position stream says the ground is doing, independent of any activity label. **Every
 * consultation of a [Motion] must define an [Unknown] case identical to its pre-consultation
 * behaviour** — the invariant that makes the cross-check a toggle: off substitutes [Unknown] at
 * the single place the verdict is produced, and nothing downstream needs to know a switch exists.
 */
sealed interface Motion {

    /** The ground is provably moving, at [speed] averaged over the window that proved it. */
    data class Moving(val speed: Speed) : Motion

    /**
     * The ground is provably still — every fix in the window sits within a few metres of the rest.
     * No consultation distinguishes this from [Unknown] today (the cross-check acts only on positive
     * evidence of *movement*); it stays separate because the confirmer can tell the two apart, and a
     * rule ending a track on a standstill — not merely declining to end one — needs this, not [Unknown].
     */
    data object Stopped : Motion

    /** Not enough evidence — treat as "behave as if the cross-check did not exist". */
    data object Unknown : Motion
}

/**
 * The recorder's second witness: a trailing window of accepted fixes answering one question — *is
 * the ground moving?* Activity Recognition describes the **body**, not the journey: aboard a
 * carrier the body is genuinely still (or walking about) while the ground moves at vehicle speed,
 * yet the recorder's stop, split and fix-quality rules all read the label as the trip; this
 * reports what the positions say, so those rules can decline to act on a label the ground
 * contradicts. **Net displacement over a window, never a single fix's speed**: a parked phone can
 * report phantom Doppler and consecutive-fix deltas are noise-dominated; the displacement between
 * the window's *averaged* halves is not — means damp the noise raw endpoints carry, and for steady
 * motion the halves' mean times are separated by exactly the interval their mean positions are, so
 * the derived speed is honest, not diluted. **Abstention is a real verdict**: too few fixes, too
 * short a span, or neither clear progress nor clear standstill give [Motion.Unknown] —
 * contradicting the label demands positive evidence, never its absence. **[Motion.Stopped]
 * measures the window's whole spread, not just the endpoints**: net displacement alone reads a
 * departure-and-return as a standstill, and a wrong standstill ends a journey still under way.
 *
 * **The feed contract.** [onFix] must get every fix that cleared the *label-independent* quality
 * gates, and only those: [IgnoreReason.ACCURACY] and [IgnoreReason.NO_GNSS] reject a fix on its own
 * merits and are withheld, but an [IgnoreReason.JUMP] fix was rejected by a ceiling chosen from the
 * very label this witness exists to check, and withholding it would make the witness inherit the
 * error it is here to catch. `TrackQuality.badFixReason` reports one prioritised reason, so the
 * seam is `reason != ACCURACY && reason != NO_GNSS`. The price: a lone teleport that cleared
 * accuracy and GNSS is evidence like any other and can carry a window to [Motion.Moving] alone;
 * averaging damps rather than removes it, so the consultations bound what a single verdict may
 * cost them — see the ceiling's own clamp in `TrackQuality.badFixReason`. Pure and Android-free
 * like [ActivityGate]: clocks and positions are handed in, [DistanceFn] keeps geometry host-testable.
 */
class MovementConfirmer(
    private val distance: DistanceFn,
    private var params: Params = Params(),
) {

    /**
     * The window's shape. [forSampling] derives these from the user's configured cadence rather
     * than fixing the window in seconds, because at the slow end of the sampling ladder two fixes
     * can be a minute apart and a window stated in seconds alone would never fill.
     */
    data class Params(
        /** Fewest fixes that can carry a verdict. */
        val minFixes: Int = MIN_FIXES,
        /** Shortest span between the oldest and newest fix that can carry one. */
        val minSpanMs: Long = BASE_SPAN_MS,
        /** Displacement between the window's halves that means the ground moved. */
        val movingMinDisplacementM: Double = MOVING_MIN_DISPLACEMENT_M,
        /** …and the spread within which it means the ground stood still. */
        val stoppedMaxDisplacementM: Double = STOPPED_MAX_DISPLACEMENT_M,
        /** Fixes older than this leave the window — evidence has a shelf life. */
        val maxFixAgeMs: Long = BASE_MAX_AGE_MS,
    )

    private class Fix(val timeMs: Long, val lat: Double, val lon: Double)

    // Oldest first. Bounded by [Params.maxFixAgeMs] rather than by a fixed capacity: at 30 s
    // sampling the window holds a handful of fixes and at 1 s a couple of hundred, and both are the
    // same question asked over the same stretch of the journey.
    private val window = ArrayDeque<Fix>()

    /** Record an accepted fix. See the feed contract in the class KDoc for *which* fixes. */
    fun onFix(timeMs: Long, lat: Double, lon: Double) {
        // Out-of-order arrival would put the halves in the wrong order, and the halves are the
        // whole measurement. The platform listener delivers in order; a shuffled batch would not.
        if (timeMs < (window.lastOrNull()?.timeMs ?: Long.MIN_VALUE)) return
        window.addLast(Fix(timeMs, lat, lon))
        expire(timeMs)
    }

    /** What the window says the ground is doing as of [nowMs]. */
    fun verdict(nowMs: Long): Motion {
        expire(nowMs)
        if (window.size < params.minFixes) return Motion.Unknown
        if (window.last().timeMs - window.first().timeMs < params.minSpanMs) return Motion.Unknown

        // Split in time order; the middle fix of an odd-sized window joins the later half, which
        // costs nothing since both halves' mean times are measured rather than assumed.
        val split = window.size / 2
        val early = mean(0, split)
        val late = mean(split, window.size)
        val movedM = distance.meters(early.lat, early.lon, late.lat, late.lon)
        if (movedM >= params.movingMinDisplacementM) {
            val elapsedSec = (late.timeMs - early.timeMs) / 1000.0
            // Guard the divide alone: halves that share a mean time can still have moved, and
            // calling that infinitely fast would widen the jump ceiling without bound.
            return if (elapsedSec > 0) Motion.Moving(Speed.mps(movedM / elapsedSec)) else Motion.Unknown
        }
        return if (spreadWithin(params.stoppedMaxDisplacementM)) Motion.Stopped else Motion.Unknown
    }

    /**
     * Adopt a new window shape, keeping the evidence already in it. Called wherever the GPS request
     * is built, which is where the sampling these [Params] describe is read.
     *
     * **Deliberately does not clear.** It used to, on the reasoning that a new stretch or a
     * no-fix retry each open a gap, and evidence from before a gap describes a different stretch of
     * the journey. But the recorder closes at every stop and opens at every start, so the clear
     * landed a few seconds before exactly the questions this exists to answer — a carrier pulling
     * away is preceded by an open, and the emptied window then abstains through the departure it
     * was built to catch. Nothing is lost by keeping it: [Params.maxFixAgeMs] expires evidence by
     * age on every [onFix] and [verdict], so a window that really does describe an older stretch of
     * the journey has already drained itself, and one that survives a short pause describes ground
     * the phone was on moments ago. The ground does not observe track boundaries.
     */
    fun reshape(params: Params) {
        this.params = params
    }

    private fun expire(nowMs: Long) {
        while (window.isNotEmpty() && nowMs - window.first().timeMs > params.maxFixAgeMs) {
            window.removeFirst()
        }
    }

    /** A mean of fixes is itself a synthetic fix — same shape, same fields. */
    private fun mean(from: Int, until: Int): Fix {
        var time = 0L
        var lat = 0.0
        var lon = 0.0
        for (i in from until until) {
            val fix = window[i]
            time += fix.timeMs
            lat += fix.lat
            lon += fix.lon
        }
        val n = until - from
        return Fix(time / n, lat / n, lon / n)
    }

    /**
     * Whether every fix stays within [maxM] of the window's oldest — the standstill test. Returns
     * at the first fix that ranges out: the common non-standstill window fails within a few fixes,
     * and only the comparison is ever used, never the exact spread.
     */
    private fun spreadWithin(maxM: Double): Boolean {
        val first = window.first()
        for (fix in window) {
            if (distance.meters(first.lat, first.lon, fix.lat, fix.lon) > maxM) return false
        }
        return true
    }

    companion object {

        /**
         * [Params] for a recorder sampling at [minIntervalSec] — the same setting the GPS request
         * is built from, so the window is re-derived wherever that request is. Only the time bounds
         * scale: a slower cadence doesn't need a *longer* look to see a carrier move, it needs one
         * its few fixes can fill, and [MIN_FIXES] of them span [MIN_FIXES] − 1 intervals by
         * construction — the floor the span is raised to. The displacement thresholds are
         * cadence-free: they measure metres against a fix's error, neither changed by cadence. A
         * verdict thus arrives about [BASE_SPAN_MS] into a journey at a quick cadence, later as the
         * cadence slows — past some point never, which is the recorder with no cross-check at all.
         */
        fun forSampling(minIntervalSec: Int): Params {
            val intervalMs = minIntervalSec.coerceAtLeast(1) * 1000L
            val minSpanMs = maxOf(BASE_SPAN_MS, (MIN_FIXES - 1) * intervalMs)
            return Params(
                minSpanMs = minSpanMs,
                // Headroom over the span the verdict needs, so a window that has just filled isn't
                // trimmed back under it by fixes the min-update-distance held back.
                maxFixAgeMs = maxOf(BASE_MAX_AGE_MS, 2 * minSpanMs),
            )
        }

        /**
         * Enough fixes that each half's mean averages its noise, few enough that the slow end of
         * the sampling ladder still fills a window inside a short crossing.
         */
        private const val MIN_FIXES = 4

        /**
         * The window a quick cadence uses. Long enough that anything on wheels or afloat covers far
         * more ground than an accepted fix's error budget, short enough that a genuine stop is
         * reported well inside the recorder's own resume window.
         */
        private const val BASE_SPAN_MS = 20_000L

        /**
         * How long a fix stays evidence. Its real job is the *end* of a journey: fixes cease when
         * the carrier stops, and this is what drains the window back to abstention instead of
         * leaving a [Motion.Moving] standing over ground that has been still for minutes.
         */
        private const val BASE_MAX_AGE_MS = 60_000L

        /**
         * Displacement between the halves that counts as the ground moving. Well above what an
         * accepted fix's error can fabricate — over [BASE_SPAN_MS] roughly a brisk cyclist's pace,
         * so an ordinary walk doesn't trip it and anything carrying a phone clears it several times over.
         */
        private const val MOVING_MIN_DISPLACEMENT_M = 40.0

        /**
         * Spread within which the ground counts as still. Around the accuracy of a good fix: a
         * parked phone wanders this much and no further.
         */
        private const val STOPPED_MAX_DISPLACEMENT_M = 15.0
    }
}
