package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Finds a stay at the *edge* of a track — recording that ran on after the user had already
 * arrived (or before they truly departed) because Activity Recognition lagged the real stop.
 * A stop longer than the auto-pause resume window would have split the track, so anything
 * left at an edge is bounded by observer lag; the venue-scale 10-minute bar of mid-track
 * dwells doesn't apply, and the floor here is the resume window itself (~3 min).
 *
 * Two stages with distinct roles, both required:
 *  1. **Position says whether**: [DwellDetector]'s corral sweep, run with the lowered
 *     duration floor, must find a dwell touching the track's first/last good fix. This is
 *     what distinguishes "arrived somewhere specific" from plain GPS starvation.
 *  2. **Speed says where**: the cut is placed by speed collapse, not corral geometry.
 *     Good fixes moving at least [Params.movingSpeedMps] — by displacement, with Doppler as a
 *     seconding vote rather than the source of truth (see [movingBins]) — are binned; a bin holding at least
 *     [Params.movingBinFraction] of the sampling cadence's nominal count is "moving", and the
 *     boundary is the end of the last moving bin (start of the first, for a start-edge stay).
 *     Field data (Jun 29 + Jul 18 walks) showed the corral cuts 2–4 min early — it swallows
 *     the tail of the approach — while speed collapse matched the user-recalled arrival in
 *     all three ground-truth stays. Multipath at a standstill *can* fake Doppler, which is why
 *     displacement holds the veto. The corral alone must never decide — it is speed-blind (its
 *     exit hysteresis and net-drift gate both passed a car circling a parking lot at 35 km/h),
 *     so when no speed evidence exists at all, nothing is trimmed.
 *
 * Pure and Android-free; nothing is persisted. Detection re-runs from stored points on demand.
 */
object EdgeStayDetector {

    data class Params(
        /** Stage-1 sweep, with the venue bar lowered to the resume-window scale. */
        val dwell: DwellDetector.Params = DwellDetector.Params(minDwellMs = 3 * 60_000L),
        /** A fix at or above this speed votes its bin "moving". */
        val movingSpeedMps: Double = 0.7,
        /** Speed over the ground is measured as displacement over this lookback — long enough
         *  that standstill jitter averages to ~zero, short enough that real movement registers. */
        val derivedSpeedLookbackMs: Long = 30_000L,
        val binMs: Long = 30_000L,
        /** Fraction of the nominal per-bin fix count (binMs / [expectedFixIntervalMs]) a bin
         *  needs in moving fixes to count as moving — relative, so sampling settings scale it. */
        val movingBinFraction: Double = 1.0 / 3.0,
        /** The sampling min-time between recorded points, from Settings. */
        val expectedFixIntervalMs: Long = 1_000L,
        /** A dwell whose exit (entry) is within this of the track's last (first) good fix
         *  counts as touching that edge. */
        val edgeToleranceMs: Long = 30_000L,
    )

    /** [OVERLAY] with the recorder's actual sampling cadence, which sets the per-bin moving-fix
     *  bar. The track screen and the trim it offers must run the *same* params — what the user
     *  sees greyed out is exactly what the trim then cuts. */
    fun overlayParams(fixIntervalMs: Long): Params =
        OVERLAY.copy(expectedFixIntervalMs = fixIntervalMs.coerceAtLeast(1L))

    /**
     * The same two-stage rule resolved down to brief-stop scale, for the track screen's overlay
     * and the manual trim it offers — it shows what the recorder ran on through, including the
     * short stops the old automatic trim's resume-window floor left alone. Every sub-parameter that is sized for a
     * 10-minute venue has to come down with the floor: 15 s decimation leaves a half-minute dwell
     * three samples, a 2-minute exit grace outlasts the dwell itself, 30 s bins can't place a boundary
     * inside a 30 s stay, and the 4 m/min drift gate — calibrated on venue stops that creep
     * 1.7–1.9 m/min — rejects a genuine standstill whose own jitter nets a few metres over half a
     * minute. Measured over the recorded history: an end stay on ~30% of tracks, median ~72 s,
     * start stays near-absent (departures barely lag).
     */
    val OVERLAY = Params(
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

    enum class Side { START, END }

    /** A stay at [side]: the track's real content ends (starts) at [boundaryTs]; the stay runs
     *  from there to the track edge. */
    data class EdgeStay(
        val side: Side,
        val boundaryTs: Long,
        val stayMs: Long,
    )

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

        return buildList {
            if (dwells.first().entryTs - firstTs <= params.edgeToleranceMs) {
                val boundary = movingBins.first() * params.binMs
                val stayMs = boundary - firstTs
                if (stayMs >= params.dwell.minDwellMs) {
                    add(EdgeStay(Side.START, boundary, stayMs))
                }
            }
            if (lastTs - dwells.last().exitTs <= params.edgeToleranceMs) {
                val boundary = (movingBins.last() + 1) * params.binMs
                val stayMs = lastTs - boundary
                if (stayMs >= params.dwell.minDwellMs) {
                    add(EdgeStay(Side.END, boundary, stayMs))
                }
            }
        }
    }

    /**
     * Ascending bin indices (timestamp / binMs) holding enough moving good fixes. A fix counts as
     * moving only when its **displacement** over [Params.derivedSpeedLookbackMs] says so, and —
     * where the platform reported one — its Doppler speed agrees. Displacement is the veto, not a
     * fallback for Doppler-less imports: a parked phone reports phantom Doppler (field case, a
     * 2026-07-04 arrival: three consecutive fixes at up to 3.5 m/s while sitting 7 m from the
     * track's final position), and a handful of those at the very end of a track is enough to put
     * the last moving bin past the real arrival and collapse the detected stay to nothing. You
     * cannot travel at 3.5 m/s and stay 7 m from where you stop; over the lookback window jitter
     * averages to ~zero while genuine travel — a queue creep, a parking-lot loop — still shows.
     */
    private fun movingBins(good: List<TrackPoint>, params: Params, distance: DistanceFn): List<Long> {
        val threshold = maxOf(
            1,
            (params.binMs / params.expectedFixIntervalMs * params.movingBinFraction).toInt(),
        )
        // Displacement is only evidence over a long enough baseline: across a second or two,
        // standstill jitter alone reads as metres per second. Where the recorder went quiet — as
        // it does once parked, on min-distance sampling — the nearest earlier fix can be minutes
        // old, and the window shrinks to an adjacent-fix delta; those fixes abstain rather than
        // vote on noise.
        val minBaselineMs = params.derivedSpeedLookbackMs / 3
        val counts = HashMap<Long, Int>()
        var back = 0
        for ((i, p) in good.withIndex()) {
            // The first fix has no window behind it to measure against, so it never votes.
            if (i == 0) continue
            while (p.timestamp - good[back].timestamp > params.derivedSpeedLookbackMs) back++
            val anchor = good[if (back == i) i - 1 else back]
            val dtMs = p.timestamp - anchor.timestamp
            if (dtMs < minBaselineMs) continue
            val overGround = distance.metres(anchor.latitude, anchor.longitude, p.latitude, p.longitude) /
                (dtMs / 1000.0)
            if (overGround < params.movingSpeedMps) continue
            val doppler = p.speed?.toDouble()
            if (doppler != null && doppler < params.movingSpeedMps) continue
            counts.merge(p.timestamp / params.binMs, 1, Int::plus)
        }
        return counts.filterValues { it >= threshold }.keys.sorted()
    }
}
