package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Finds *embedded stays* inside a recorded track — stretches where the user lingered within a
 * small area while Activity Recognition kept reporting movement (an open-air museum, a
 * GNSS-transparent building), so neither the STILL auto-pause nor the no-fix guard ever fired.
 *
 * The sweep is a running-centroid stay-point detector: a dwell is a maximal run of fixes that
 * stay within [Params.corralRadiusM] of their own running mean for at least [Params.minDwellMs].
 * Two asymmetric exits keep venue-edge wandering from splitting one visit: a fix beyond
 * [Params.exitHardRadiusM] ends the dwell immediately, while a fix merely outside the corral
 * ends it only after [Params.exitConfirmMs] without re-entry. Auto-pause gaps need no special
 * handling — dwell time is wall-clock between samples, so a pause whose resume fix lands back
 * in the corral credits the whole gap, and one that resumes elsewhere ends the dwell at the last
 * in-corral fix (the user left during the pause).
 *
 * Adjacent dwells closer than [Params.mergeGapMs] and [Params.mergeDistM] coalesce into one —
 * a large venue often reads as two or three neighbouring corrals with short strolls between.
 *
 * Pure and Android-free; nothing is persisted. Detection re-runs from stored points on demand.
 */
object DwellDetector {

    data class Params(
        /** Corral radius: a fix within this of the running centroid is part of the dwell. */
        val corralRadiusM: Double = 55.0,
        /** Minimum in-corral span for a dwell to count — venue scale, not café stops. */
        val minDwellMs: Long = 10 * 60_000L,
        /** A fix outside the corral (but inside the hard radius) ends the dwell only after this
         *  long without re-entry — poking past the corral edge and returning doesn't split. */
        val exitConfirmMs: Long = 120_000L,
        /** A fix beyond this ends the dwell immediately: the user has clearly walked away. */
        val exitHardRadiusM: Double = 110.0,
        /** Adjacent dwells at most this far apart in time and space merge into one visit. */
        val mergeGapMs: Long = 15 * 60_000L,
        val mergeDistM: Double = 100.0,
        /** Net entry→exit progress above this rate means a slow *transit* — an arced or dawdling
         *  walk passing through the corral — not a stay. Field data: false detections drift
         *  7–10 m/min, real venue stops 1.7–1.9 m/min. */
        val maxDriftMPerMin: Double = 4.0,
        /** Input decimation: at most one sample per this interval. Bounds the sweep's cost and
         *  its boundary resolution; ±15 s on a ≥10 min dwell is noise. */
        val decimateMs: Long = 15_000L,
    )

    /** One embedded stay: [entryTs]..[exitTs] are real fix timestamps bounding the in-corral run. */
    data class Dwell(
        val entryTs: Long,
        val exitTs: Long,
        val centroid: StayDeriver.Endpoint,
        /** Decimated samples inside the corral — the merge pass weights centroids by it. */
        val sampleCount: Int,
    )

    fun detect(
        points: List<TrackPoint>,
        params: Params = Params(),
        distance: DistanceFn,
    ): List<Dwell> {
        val samples = decimate(points.filter { !it.ignored }, params.decimateMs)
        if (samples.size < 2) return emptyList()
        if (samples.last().timestamp - samples.first().timestamp < params.minDwellMs) return emptyList()
        return merge(sweep(samples, params, distance), params, distance)
    }

    /** At most one sample per [intervalMs]; the last point always survives so a dwell that runs
     *  to the end of the track exits at the real final fix. */
    private fun decimate(points: List<TrackPoint>, intervalMs: Long): List<TrackPoint> {
        if (points.size < 2) return points
        val out = ArrayList<TrackPoint>()
        var lastKept: Long? = null
        for (p in points) {
            if (lastKept == null || p.timestamp - lastKept >= intervalMs) {
                out.add(p)
                lastKept = p.timestamp
            }
        }
        if (out.last() !== points.last()) out.add(points.last())
        return out
    }

    private fun sweep(pts: List<TrackPoint>, params: Params, distance: DistanceFn): List<Dwell> {
        val out = ArrayList<Dwell>()
        var i = 0
        while (i < pts.size - 1) {
            // Grow a candidate window anchored at i; centroid is the mean of in-corral members.
            var cLat = pts[i].latitude
            var cLon = pts[i].longitude
            var members = 1
            var lastInside = i
            var j = i + 1
            while (j < pts.size) {
                val p = pts[j]
                val d = distance.metres(p.latitude, p.longitude, cLat, cLon)
                when {
                    d <= params.corralRadiusM -> {
                        cLat += (p.latitude - cLat) / (members + 1)
                        cLon += (p.longitude - cLon) / (members + 1)
                        members++
                        lastInside = j
                    }
                    d > params.exitHardRadiusM -> break
                    p.timestamp - pts[lastInside].timestamp >= params.exitConfirmMs -> break
                }
                j++
            }
            val span = pts[lastInside].timestamp - pts[i].timestamp
            // A slow curved walk can hold the corral past minDwellMs while steadily passing
            // through it; steady net progress separates that transit from a genuine stay.
            val netM = distance.metres(
                pts[i].latitude, pts[i].longitude,
                pts[lastInside].latitude, pts[lastInside].longitude,
            )
            val transit = span > 0 && netM / (span / 60_000.0) > params.maxDriftMPerMin
            if (span >= params.minDwellMs && !transit) {
                out.add(Dwell(pts[i].timestamp, pts[lastInside].timestamp, StayDeriver.Endpoint(cLat, cLon), members))
                i = lastInside + 1
            } else {
                // Not a dwell from this anchor. Advancing one sample (not to the break point) is
                // deliberate: approach points seeded into the centroid can drag it off the true
                // dwell and mask it; decimation keeps the O(n²) worst case cheap.
                i++
            }
        }
        return out
    }

    private fun merge(dwells: List<Dwell>, params: Params, distance: DistanceFn): List<Dwell> {
        if (dwells.size < 2) return dwells
        val out = ArrayList<Dwell>()
        var cur = dwells.first()
        for (next in dwells.drop(1)) {
            val closeInTime = next.entryTs - cur.exitTs <= params.mergeGapMs
            val closeInSpace = distance.metres(
                cur.centroid.lat, cur.centroid.lon, next.centroid.lat, next.centroid.lon,
            ) <= params.mergeDistM
            cur = if (closeInTime && closeInSpace) {
                val total = cur.sampleCount + next.sampleCount
                Dwell(
                    entryTs = cur.entryTs,
                    exitTs = next.exitTs,
                    centroid = StayDeriver.Endpoint(
                        (cur.centroid.lat * cur.sampleCount + next.centroid.lat * next.sampleCount) / total,
                        (cur.centroid.lon * cur.sampleCount + next.centroid.lon * next.sampleCount) / total,
                    ),
                    sampleCount = total,
                )
            } else {
                out.add(cur)
                next
            }
        }
        out.add(cur)
        return out
    }
}
