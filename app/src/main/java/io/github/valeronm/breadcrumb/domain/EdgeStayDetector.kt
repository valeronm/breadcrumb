package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.abs

/**
 * Finds a stay at the *edge* of a track — recording that ran on after the user had already arrived
 * (or before they truly departed) because Activity Recognition lagged the real stop. A stop longer
 * than the stitch window would have left the track behind, so anything at an edge is
 * bounded by observer lag and the venue-scale 10-minute bar of mid-track dwells doesn't apply;
 * what ships is [BRIEF_STOP], a half-minute floor small enough to cover the stops that never split
 * a track at all. Two stages with distinct roles, both required:
 *  1. **Position says whether**: [DwellDetector]'s corral sweep, run with the lowered duration
 *     floor, must find a dwell touching the track's first/last good fix — what distinguishes
 *     "arrived somewhere specific" from plain GPS starvation.
 *  2. **Speed says where**: the cut is placed by speed collapse, not corral geometry. Good fixes
 *     moving at least [Params.movingSpeed] — by displacement, with Doppler as a seconding vote
 *     rather than the source of truth (see [movingBins]) — are binned; a bin holding at least
 *     [Params.movingBinFraction] of *its own* fixes moving is itself "moving", and the boundary is
 *     the end of the last moving bin (the start of the first, at a start edge), pulled back to the
 *     dwell's own bound if the span it would cut ranges beyond a standstill. Field data: the
 *     corral cuts 2–4 min early, swallowing the approach's tail, while speed collapse matched the
 *     recalled arrival in all three ground-truth stays; multipath at a standstill *can* fake
 *     Doppler, which is why displacement holds the veto. The corral alone must never decide — it
 *     is speed-blind (its exit hysteresis and net-drift gate both passed a car circling a parking
 *     lot at 35 km/h) — so when no speed evidence exists at all, nothing is trimmed.
 * Pure and Android-free; nothing is persisted. Detection re-runs from stored points on demand.
 */
object EdgeStayDetector {

    data class Params(
        /** Stage-1 sweep, with the venue bar lowered to edge scale. */
        val dwell: DwellDetector.Params,
        /** A fix at or above this speed votes its bin "moving". */
        val movingSpeed: Speed = Speed.mps(0.7),
        /** What a *lone* fix must clear to carry its bin by itself. Corroboration is the usual
         *  evidence — several fixes in a bin agreeing — and where there is none, the one fix has
         *  to be moving too fast for a standstill to explain: settling GPS drifts 19–31 m over
         *  half a minute, which clears [movingSpeed] but not this. */
        val soloMovingSpeed: Speed = Speed.mps(2.5),
        /** Speed below which *this activity* is not happening at all, so a bin whose fastest fix
         *  falls under it is a standstill however many fixes agree. Off by default because on
         *  foot there is no such speed — settling GPS drifts at 0.9–1.2 m/s and people walk at
         *  1.2–1.5, so any floor that excludes the drift excludes the walking too. In a vehicle
         *  the two are decades apart, which is what [VEHICLE] uses. */
        val activityFloor: Speed = Speed.ZERO,
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
     * The two-stage rule resolved down to brief-stop scale — covering what the recorder ran on
     * through, including the short stops a resume-window-sized floor leaves alone. Every
     * venue-sized sub-parameter comes down with the floor: 15 s decimation leaves a half-minute
     * dwell three samples, a 2-minute exit grace outlasts the dwell itself, 30 s bins can't place
     * a boundary inside a 30 s stay, and the 4 m/min drift gate (venue stops creep 1.7–1.9 m/min)
     * rejects a genuine standstill whose own jitter nets a few meters over half a minute. Measured
     * history-wide: an end stay on ~30% of tracks, median ~72 s; start stays near-absent (departures barely lag).
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
     * [BRIEF_STOP] with the floor a vehicle's own speed allows: at ~1 Hz a parked car easily puts
     * three drift fixes over the [Params.movingSpeed] bar in a bin — pinning the last moving bin to
     * the track's end and hiding the arrival tail on 156 drives outright. Below this a car is not
     * driving, so those bins are standstill regardless of agreement.
     */
    val VEHICLE = BRIEF_STOP.copy(activityFloor = Speed.kmh(5.0))

    /**
     * The one place a track's tuning is chosen — two callers deriving the same track's overrun
     * through different parameters is the failure this exists to prevent. Takes the stored activity
     * *name* (the value a track row carries), so a row naming a type this build no longer has falls
     * to [BRIEF_STOP] rather than needing a caller to handle it.
     */
    fun paramsFor(activityTypeName: String): Params = when (ActivityType.ofName(activityTypeName)?.trackGroup) {
        // AIR rides the vehicle tuning: a recorded flight's edge overrun is gate and taxi time,
        // drift at a vehicle's standstill pace rather than a pedestrian dwell.
        TrackGroup.VEHICLE, TrackGroup.AIR -> VEHICLE
        else -> BRIEF_STOP
    }

    /**
     * Bumped whenever the sweep this drives would settle a track differently — a new stage, a moved
     * threshold, a different boundary, or a change to the clock [TrackBounds] reads off the flags.
     * The [IgnoreReason.EDGE_STAY][io.github.valeronm.breadcrumb.data.IgnoreReason.EDGE_STAY] flags
     * on stored points and the bounds over them are this pass's verdicts, so a moved rule leaves them
     * stale in both directions; the app re-sweeps its history when the version it last swept is
     * behind this one, making the bump part of changing the rule, not a follow-up chore. One version
     * for the one pass: a second keyed to the same sweep would only be a second thing to forget.
     * 1 — the original half-minute edge-stay sweep.
     * 2 — displacement vetoes Doppler, real-fix boundary, dwell retraction, per-bin voting, vehicle standstill floor.
     * 3 — no rule change (a bump taken to watch the sweep run).
     * 4 — the per-bin vote floor scales with the track's own cadence, so bin-scale sampling is detectable at all.
     * 5 — no rule change: the verdict moved from a review mark onto the points ([EdgeStayIgnore]); history re-swept to acquire it.
     * 6 — no rule change: every flag is now reconsidered wherever it sits, so a merge-buried overrun is handed back.
     * 7 — no detection change: the clock follows the good fixes ([TrackBounds]), covering the edge
     *     this rule declines — an arrival GPS went blind for leaves nothing to place a boundary in.
     */
    const val RULE_VERSION = 7

    enum class Side { START, END }

    /**
     * A stay at [side]. [boundaryTs] is the **cut point**: the timestamp of the last good fix the
     * track keeps (the first, at a start edge), the stay running from there to the track's edge.
     * One value, used by everything — the fixes strictly beyond it are the ones flagged
     * [io.github.valeronm.breadcrumb.data.IgnoreReason.EDGE_STAY], and the track screen grays from
     * it. The track's clock arrives here too without being told to — see [TrackBounds]. It is a real fix, not the speed-bin edge it is
     * derived from: a bin edge falls between fixes (measured: 288 of 387 in gaps up to 94 s), and
     * a polyline needs both its endpoints, so marking the first *removed* fix would leave the
     * trimmed track ending a leg short of the line the user was shown.
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
        params: Params,
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
         * disagree — the bin boundary lands outside the stop, covering ground a stop never could —
         * the span retracts to the dwell's own stationary-by-construction bound. Retracting beats
         * abstaining: field data saw late-triggering bins propose cutting hundreds of meters of
         * ordinary driving off a track's start, yet such tracks do open with a real, shorter stop.
         * If even the dwell's bound ranges too far, nothing is offered — stage 1 alone is
         * speed-blind and must not place a cut.
         */
        fun MutableList<EdgeStay>.addStay(side: Side, binEdge: Long, dwellBound: Long, edgeTs: Long) {
            var boundary = boundaryAt(side, binEdge) ?: return
            if (spreadOf(side, boundary) > params.dwell.exitHardRadiusM) {
                boundary = boundaryAt(side, dwellBound) ?: return
                if (spreadOf(side, boundary) > params.dwell.exitHardRadiusM) return
            }
            val stayMs = abs(edgeTs - boundary.timestamp)
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
     * How fast this track samples when the recorder is actually sampling — the lower quartile of
     * its own inter-fix gaps, not the mean, which the minutes of silence a stop produces would
     * drag out. Only used to ask whether a bin holding a single fix is *unusual* for this track.
     */
    private fun cadenceMs(good: List<TrackPoint>): Long {
        val gaps = good.zipWithNext { a, b -> b.timestamp - a.timestamp }.sorted()
        return gaps.getOrNull(gaps.size / 4)?.coerceAtLeast(1L) ?: 1L
    }

    /**
     * Ascending bin indices (timestamp / binMs) holding enough moving good fixes. A fix counts as
     * moving only when its **displacement** over [Params.speedLookbackMs] says so and — where the
     * platform reported one — its Doppler speed agrees. Displacement is the veto, not a fallback
     * for Doppler-less imports: a parked phone can report phantom Doppler up to 3.5 m/s across
     * several consecutive fixes, and a handful at a track's very end can put the last moving bin
     * past the real arrival and collapse the detected stay to nothing — Doppler that fast while
     * going nowhere is physically empty, since over the lookback window standstill jitter averages
     * to ~zero while genuine travel (a queue creep, a parking-lot loop) still shows. "Enough" is a
     * fraction of the fixes the bin *actually holds*, floored at one: a bar drawn from a nominal
     * per-bin count instead measures sparseness rather than evidence, and sparseness is exactly
     * what a stop produces — on min-distance sampling an arrival can cross its last stretch in one
     * long leg between two sparse fixes, that lone unambiguous fix outvoted by the emptiness around
     * it, reading two nearby stops as one. Counting within the bin costs nothing in confidence,
     * since displacement holds the veto — a qualifying fix was already checked against its own
     * 10-second baseline.
     */
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
        val fastest = HashMap<Long, Speed>()
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
            val overGround = Speed.mps(
                distance.meters(anchor.latitude, anchor.longitude, p.latitude, p.longitude) /
                    (dtMs / 1000.0),
            )
            if (overGround < params.movingSpeed) continue
            // Compared without a [Speed] of its own: a nullable value class boxes, and this runs
            // over every good point of every track, history-wide on a rule-version sweep.
            val doppler = p.speed
            if (doppler != null && Speed.mps(doppler.toDouble()) < params.movingSpeed) continue
            moving.merge(bin, 1, Int::plus)
            fastest.merge(bin, overGround) { a, b -> a.coerceAtLeast(b) }
        }
        return moving.filter { (bin, votes) ->
            val needed = maxOf(minVotes, (total.getValue(bin) * params.movingBinFraction).toInt())
            // Corroborated (and clear of what this activity calls standing still), or fast
            // enough that one fix settles it alone.
            val peak = fastest.getValue(bin)
            (votes >= needed && peak >= params.activityFloor) ||
                peak >= params.soloMovingSpeed
        }.keys.sorted()
    }
}
