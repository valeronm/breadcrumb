package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.DistanceFn

/**
 * Finds a stay at the *edge* of a track — recording that ran on after the user had already
 * arrived (or before they truly departed) because Activity Recognition lagged the real stop.
 * A stop longer than the auto-pause resume window would have split the track, so anything
 * left at an edge is bounded by observer lag; the venue-scale 10-minute bar of mid-track
 * dwells doesn't apply. What ships is [BRIEF_STOP] — a half-minute floor, small enough to
 * cover the stops that never split a track at all.
 *
 * Two stages with distinct roles, both required:
 *  1. **Position says whether**: [DwellDetector]'s corral sweep, run with the lowered
 *     duration floor, must find a dwell touching the track's first/last good fix. This is
 *     what distinguishes "arrived somewhere specific" from plain GPS starvation.
 *  2. **Speed says where**: the cut is placed by speed collapse, not corral geometry.
 *     Good fixes moving at least [Params.movingSpeedMps] — by displacement, with Doppler as a
 *     seconding vote rather than the source of truth (see [movingBins]) — are binned; a bin holding at least
 *     [Params.movingBinFraction] of *its own* fixes moving is itself "moving", and the boundary
 *     is the end of the last moving bin (the start of the first, at a start edge) — pulled back
 *     to the dwell's own bound if the span it would cut ranges beyond a standstill.
 *     Field data showed the corral cuts 2–4 min early — it swallows the tail of the approach —
 *     while speed collapse matched the recalled arrival in all three ground-truth stays. Multipath at a standstill *can* fake Doppler, which is why
 *     displacement holds the veto. The corral alone must never decide — it is speed-blind (its
 *     exit hysteresis and net-drift gate both passed a car circling a parking lot at 35 km/h),
 *     so when no speed evidence exists at all, nothing is trimmed.
 *
 * Pure and Android-free; nothing is persisted. Detection re-runs from stored points on demand.
 */
object EdgeStayDetector {

    data class Params(
        /** Stage-1 sweep, with the venue bar lowered to edge scale. Nothing ships these defaults —
         *  every production path runs [BRIEF_STOP] or [VEHICLE], and so does the detector's own
         *  test suite; they remain only for callers that don't turn on the rule's numbers. */
        val dwell: DwellDetector.Params = DwellDetector.Params(minDwellMs = 3 * 60_000L),
        /** A fix at or above this speed votes its bin "moving". */
        val movingSpeedMps: Double = 0.7,
        /** What a *lone* fix must clear to carry its bin by itself. Corroboration is the usual
         *  evidence — several fixes in a bin agreeing — and where there is none, the one fix has
         *  to be moving too fast for a standstill to explain: settling GPS drifts 19–31 m over
         *  half a minute, which clears [movingSpeedMps] but not this. */
        val soloMovingSpeedMps: Double = 2.5,
        /** Speed below which *this activity* is not happening at all, so a bin whose fastest fix
         *  falls under it is a standstill however many fixes agree. Off by default because on
         *  foot there is no such speed — settling GPS drifts at 0.9–1.2 m/s and people walk at
         *  1.2–1.5, so any floor that excludes the drift excludes the walking too. In a vehicle
         *  the two are decades apart, which is what [VEHICLE] uses. */
        val activityFloorMps: Double = 0.0,
        /** Speed over the ground is measured as displacement over this lookback — long enough
         *  that standstill jitter averages to ~zero, short enough that real movement registers. */
        val speedLookbackMs: Long = 30_000L,
        val binMs: Long = 30_000L,
        /** Fraction of a bin's own fixes that must read as moving for the bin to count as
         *  moving — relative to what the bin holds, so any sampling rate scales (see [movingBins]). */
        val movingBinFraction: Double = 1.0 / 3.0,
        /** A dwell whose exit (entry) is within this of the track's last (first) good fix
         *  counts as touching that edge. */
        val edgeToleranceMs: Long = 30_000L,
    )

    /**
     * The same two-stage rule resolved down to brief-stop scale — it covers what the recorder ran
     * on through, including the short stops a resume-window-sized floor leaves alone. Every
     * sub-parameter that is sized for a 10-minute venue has to come down with the floor: 15 s
     * decimation leaves a half-minute dwell three samples, a 2-minute exit grace outlasts the dwell
     * itself, 30 s bins can't place a boundary inside a 30 s stay, and the 4 m/min drift gate —
     * calibrated on venue stops that creep 1.7–1.9 m/min — rejects a genuine standstill whose own
     * jitter nets a few meters over half a minute. Measured over the recorded history: an end stay
     * on ~30% of tracks, median ~72 s, start stays near-absent (departures barely lag).
     */
    val BRIEF_STOP = Params(
        dwell = DwellDetector.Params(
            minDwellMs = 30_000L,
            decimateMs = 5_000L,
            exitConfirmMs = 30_000L,
            mergeGapMs = 2 * 60_000L,
            maxDriftMPerMin = 10.0,
        ),
        binMs = 10_000L,
        edgeToleranceMs = 15_000L,
    )

    /**
     * [BRIEF_STOP] with the floor a vehicle's own speed allows. At ~1 Hz a parked car produces
     * plenty of drift fixes over the 0.7 m/s bar, and three in a bin is easy — which pinned the
     * last moving bin to the end of the track and hid the arrival tail on 156 drives outright.
     * Under 5 km/h a car is not driving, so those bins are standstill regardless of agreement.
     */
    val VEHICLE = BRIEF_STOP.copy(activityFloorMps = 1.4)

    /**
     * The one place a track's tuning is chosen: two callers deriving the same track's overrun
     * through different parameters is the failure this exists to prevent.
     *
     * Takes the stored activity *name* — the value a track row carries — so a row naming a type
     * this build no longer has falls to [BRIEF_STOP] rather than needing a caller to handle it.
     */
    fun paramsFor(activityTypeName: String): Params =
        if (ActivityType.ofName(activityTypeName)?.trackGroup == TrackGroup.VEHICLE) VEHICLE else BRIEF_STOP

    /**
     * Bumped whenever detection changes what it would find — a new stage, a moved threshold, a
     * different boundary. The [IgnoreReason.EDGE_STAY][io.github.valeronm.breadcrumb.data.IgnoreReason.EDGE_STAY]
     * flags on stored points are this code's verdicts, so a rule that has moved leaves them stale
     * in both directions; the app re-sweeps its history when the version it last swept is behind
     * this one. Bumping is therefore part of changing the rule, not a follow-up chore.
     *
     * 1 — the original half-minute edge-stay sweep.
     * 2 — displacement vetoes Doppler, boundary resolved to a real fix, span retracted to the
     *     dwell when it ranges too far, per-bin voting within the bin, vehicle standstill floor.
     * 3 — no rule change (a bump taken to watch the sweep run).
     * 4 — the per-bin vote floor scales with the track's own cadence, so a track sampled at bin
     *     scale is detectable at all.
     * 5 — no rule change: the verdict moved from a review mark to the points themselves
     *     ([io.github.valeronm.breadcrumb.domain.EdgeStayIgnore]), so history has to be swept once
     *     more to acquire it.
     */
    const val RULE_VERSION = 5

    enum class Side { START, END }

    /**
     * A stay at [side]. [boundaryTs] is the **cut point**: the timestamp of the last good fix the
     * track keeps (the first, at a start edge), with the stay running from there to the track's
     * edge. One value, used by everything — the fixes strictly beyond it are the ones flagged
     * [io.github.valeronm.breadcrumb.data.IgnoreReason.EDGE_STAY], the track's clock is pulled in
     * to it, and the track screen grays from it.
     *
     * It is a real fix, not the speed-bin edge the boundary is derived from: a bin edge falls
     * between fixes (measured: 288 of 387 in gaps up to 94 s), and a polyline needs both its
     * endpoints, so a display marking the first *removed* fix would leave the trimmed track
     * ending a leg short of the line the user was shown.
     */
    data class EdgeStay(
        val side: Side,
        val boundaryTs: Long,
        val stayMs: Long,
    ) {
        /** Whether a fix at [ts] is part of the overrun — strictly beyond the boundary fix, which
         *  itself stays on the path as the track's bound. */
        fun movesOut(ts: Long): Boolean =
            if (side == Side.END) ts > boundaryTs else ts < boundaryTs
    }

    /** 0–2 stays: at most one per track edge. */
    fun detect(
        points: List<TrackPoint>,
        params: Params = Params(),
        distance: DistanceFn,
    ): List<EdgeStay> {
        val good = points.filter { !it.ignored }
        if (good.size < 2) return emptyList()
        val dwells = DwellDetector.detect(good, params.dwell, distance)
        if (dwells.isEmpty()) return emptyList()

        val firstTs = good.first().timestamp
        val lastTs = good.last().timestamp
        val movingBins = movingBins(good, params, distance)

        // Speed is THE arrival discriminator, not a refinement: without moving bins there is no
        // evidence of where travel ended, and the corral boundary alone is known-unreliable (it
        // passed a car circling at 35 km/h on imported speed-less data). No signal — no trim.
        if (movingBins.isEmpty()) return emptyList()

        // A bin edge is an instant between fixes; the boundary is the fix the surviving track
        // would keep — everything strictly beyond it goes.
        fun boundaryAt(side: Side, instant: Long): TrackPoint? =
            if (side == Side.END) {
                good.lastOrNull { it.timestamp < instant }
            } else {
                good.firstOrNull { it.timestamp >= instant }
            }

        // How far the span being cut ranges from where the journey ends (starts): a stop barely
        // moves, so this is the check that the two stages agree about the same stretch.
        fun spreadOf(side: Side, boundary: TrackPoint): Double {
            val span = good.filter {
                if (side == Side.END) {
                    it.timestamp >= boundary.timestamp
                } else {
                    it.timestamp <= boundary.timestamp
                }
            }
            val anchor = span.first()
            return span.maxOf {
                distance.meters(anchor.latitude, anchor.longitude, it.latitude, it.longitude)
            }
        }

        /**
         * Stage 1 says a stop touches this edge; stage 2 says where travel stopped. When they
         * disagree — the bin boundary lands outside the stop, so the span covers ground a stop
         * never could — the span is pulled back to the dwell's own bound, which is stationary by
         * construction. Two imported drives proposed cutting 347 m and 246 m of ordinary
         * driving off their starts because the bins, counted against the
         * *recorder's* sampling rate rather than the file's, only reached the moving threshold
         * a minute late. Retracting is the fix rather than abstaining: those tracks do open with
         * a real stop, just a shorter one than the bins claimed. If even the dwell's bound ranges
         * too far, nothing is offered — stage 1 alone is speed-blind and must not place a cut.
         */
        fun MutableList<EdgeStay>.addStay(side: Side, binEdge: Long, dwellBound: Long, edgeTs: Long) {
            var boundary = boundaryAt(side, binEdge) ?: return
            if (spreadOf(side, boundary) > params.dwell.exitHardRadiusM) {
                boundary = boundaryAt(side, dwellBound) ?: return
                if (spreadOf(side, boundary) > params.dwell.exitHardRadiusM) return
            }
            val stayMs = Math.abs(edgeTs - boundary.timestamp)
            if (stayMs >= params.dwell.minDwellMs) add(EdgeStay(side, boundary.timestamp, stayMs))
        }

        return buildList {
            if (dwells.first().entryTs - firstTs <= params.edgeToleranceMs) {
                addStay(
                    Side.START,
                    binEdge = movingBins.first() * params.binMs,
                    dwellBound = dwells.first().exitTs,
                    edgeTs = firstTs,
                )
            }
            if (lastTs - dwells.last().exitTs <= params.edgeToleranceMs) {
                addStay(
                    Side.END,
                    binEdge = (movingBins.last() + 1) * params.binMs,
                    dwellBound = dwells.last().entryTs,
                    edgeTs = lastTs,
                )
            }
        }
    }

    /**
     * Ascending bin indices (timestamp / binMs) holding enough moving good fixes. A fix counts as
     * moving only when its **displacement** over [Params.speedLookbackMs] says so, and —
     * where the platform reported one — its Doppler speed agrees. Displacement is the veto, not a
     * fallback for Doppler-less imports: a parked phone reports phantom Doppler (field case:
     * three consecutive fixes at up to 3.5 m/s while the phone sat 7 m from the track's final
     * position), and a handful of those at the very end of a track is enough to put
     * the last moving bin past the real arrival and collapse the detected stay to nothing. You
     * cannot travel at 3.5 m/s and stay 7 m from where you stop; over the lookback window jitter
     * averages to ~zero while genuine travel — a queue creep, a parking-lot loop — still shows.
     *
     * "Enough" is a fraction of the fixes the bin *actually holds*, floored at one. A bar drawn
     * from a nominal count instead — what the recorder's sampling rate says a bin should hold —
     * measures sparseness rather than evidence, and sparseness is exactly what a stop produces:
     * an arrival crossed 85 m between two fixes 27 s apart, and that lone unambiguous
     * fix was outvoted by the emptiness around it, so two stops 85 m apart read as one. Counting
     * within the bin costs nothing in confidence now that displacement holds the veto — a fix
     * that qualifies has already been checked against its own 10-second baseline.
     */
    /**
     * How fast this track samples when the recorder is actually sampling — the lower quartile of
     * its own inter-fix gaps, not the mean, which the minutes of silence a stop produces would
     * drag out. Only used to ask whether a bin holding a single fix is *unusual* for this track.
     */
    private fun cadenceMs(good: List<TrackPoint>): Long {
        val gaps = good.zipWithNext { a, b -> b.timestamp - a.timestamp }.sorted()
        return gaps.getOrNull(gaps.size / 4)?.coerceAtLeast(1L) ?: 1L
    }

    private fun movingBins(good: List<TrackPoint>, params: Params, distance: DistanceFn): List<Long> {
        // Displacement is only evidence over a long enough baseline: across a second or two,
        // standstill jitter alone reads as meters per second. Where the recorder went quiet — as
        // it does once parked, on min-distance sampling — the nearest earlier fix can be minutes
        // old, and the window shrinks to an adjacent-fix delta; those fixes abstain rather than
        // vote on noise.
        val minBaselineMs = params.speedLookbackMs / 3
        // A lone voting fix is only suspicious where a bin normally holds several: at 1 Hz it
        // means the rest of the bin disagreed, which is what standstill drift looks like. Where
        // the track samples at bin scale or slower — a 15 s sampling setting, min-distance
        // sampling on foot, an imported file — one fix per bin is simply all there is, and
        // demanding two would mean no bin could ever be moving and nothing would be detected.
        val minVotes = if (params.binMs / cadenceMs(good) >= 2) 2 else 1
        val moving = HashMap<Long, Int>()
        val total = HashMap<Long, Int>()
        val fastest = HashMap<Long, Double>()
        var back = 0
        for ((i, p) in good.withIndex()) {
            val bin = p.timestamp / params.binMs
            total.merge(bin, 1, Int::plus)
            // The first fix has no window behind it to measure against, so it never votes.
            if (i == 0) continue
            while (p.timestamp - good[back].timestamp > params.speedLookbackMs) back++
            val anchor = good[if (back == i) i - 1 else back]
            val dtMs = p.timestamp - anchor.timestamp
            if (dtMs < minBaselineMs) continue
            val overGround = distance.meters(anchor.latitude, anchor.longitude, p.latitude, p.longitude) /
                (dtMs / 1000.0)
            if (overGround < params.movingSpeedMps) continue
            val doppler = p.speed?.toDouble()
            if (doppler != null && doppler < params.movingSpeedMps) continue
            moving.merge(bin, 1, Int::plus)
            fastest.merge(bin, overGround, ::maxOf)
        }
        return moving.filter { (bin, votes) ->
            val needed = maxOf(minVotes, (total.getValue(bin) * params.movingBinFraction).toInt())
            // Corroborated (and clear of what this activity calls standing still), or fast
            // enough that one fix settles it alone.
            val peak = fastest.getValue(bin)
            (votes >= needed && peak >= params.activityFloorMps) ||
                peak >= params.soloMovingSpeedMps
        }.keys.sorted()
    }
}
