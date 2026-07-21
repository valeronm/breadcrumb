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
 *  2. **Speed says where**: the cut is placed by Doppler-speed collapse, not corral geometry.
 *     Good fixes moving at least [Params.movingSpeedMps] are binned; a bin holding at least
 *     [Params.movingBinFraction] of the sampling cadence's nominal count is "moving", and the
 *     boundary is the end of the last moving bin (start of the first, for a start-edge stay).
 *     Field data (Jun 29 + Jul 18 walks) showed the corral cuts 2–4 min early — it swallows
 *     the tail of the approach — while speed collapse matched the user-recalled arrival in
 *     all three ground-truth stays. Indoor multipath can't fake a moving bin: phantom-speed
 *     fixes are mostly quality-flagged ignored, and the survivors don't sustain the count.
 *     Fixes without Doppler speed (imported GPX) get a position-derived stand-in: displacement
 *     over [Params.derivedSpeedLookbackMs]. The corral alone must never decide — it is
 *     speed-blind (its exit hysteresis and net-drift gate both passed a car circling a parking
 *     lot at 35 km/h), so when no speed evidence exists at all, nothing is trimmed.
 *
 * Pure and Android-free; nothing is persisted. Detection re-runs from stored points on demand.
 */
object EdgeStayDetector {

    data class Params(
        /** Stage-1 sweep, with the venue bar lowered to the resume-window scale. */
        val dwell: DwellDetector.Params = DwellDetector.Params(minDwellMs = 3 * 60_000L),
        /** A fix at or above this speed votes its bin "moving". */
        val movingSpeedMps: Double = 0.7,
        /** For fixes without Doppler speed (imported GPX), speed is derived as displacement over
         *  this lookback — long enough that standstill jitter averages to ~zero, short enough
         *  that real movement registers. */
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

    /** Ascending bin indices (timestamp / binMs) holding enough moving-speed good fixes. A fix
     *  without Doppler speed uses displacement over [Params.derivedSpeedLookbackMs] instead —
     *  adjacent-fix deltas are jitter-dominated at a standstill, but over the lookback window
     *  jitter averages to ~zero while genuine travel (a queue creep, a parking-lot loop) shows. */
    private fun movingBins(good: List<TrackPoint>, params: Params, distance: DistanceFn): List<Long> {
        val threshold = maxOf(
            1,
            (params.binMs / params.expectedFixIntervalMs * params.movingBinFraction).toInt(),
        )
        val counts = HashMap<Long, Int>()
        var back = 0
        for ((i, p) in good.withIndex()) {
            val speed = p.speed?.toDouble() ?: run {
                if (i == 0) return@run null
                while (p.timestamp - good[back].timestamp > params.derivedSpeedLookbackMs) back++
                val anchor = good[if (back == i) i - 1 else back]
                val dtMs = p.timestamp - anchor.timestamp
                if (dtMs <= 0) return@run null
                distance.metres(anchor.latitude, anchor.longitude, p.latitude, p.longitude) /
                    (dtMs / 1000.0)
            } ?: continue
            if (speed >= params.movingSpeedMps) {
                counts.merge(p.timestamp / params.binMs, 1, Int::plus)
            }
        }
        return counts.filterValues { it >= threshold }.keys.sorted()
    }
}
