package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * The stops inside a recorded track: where the phone stayed for [Params.minStopMs] or more. A stop
 * is the union of these signals, merged where they touch: a slow stretch (within [Params.slowNetM]
 * of net movement over [Params.slowWindowMs]), a gap between fixes whose ends lie within
 * [Params.gapEndsM], and a run the settle pass flagged [IgnoreReason.STATIONARY]. A stop reaching
 * within [Params.edgeToleranceMs] of the track's first or last fix is the edge trim's
 * ([EdgeStayDetector]) and is not reported. A stop's bounds are approximate, since the edge trim
 * places a cut stop's exact times. Pure and Android-free.
 */
object StopFinder {

    data class Params(
        val slowWindowMs: Long,
        val slowMinCoverMs: Long,
        val slowNetM: Double,
        val gapMinMs: Long,
        val gapEndsM: Double,
        val mergeGapMs: Long,
        val mergeDistM: Double,
        val minStopMs: Long,
        val edgeToleranceMs: Long,
    )

    val SHIPPED = Params(
        slowWindowMs = 60_000L,
        slowMinCoverMs = 30_000L,
        slowNetM = 30.0,
        gapMinMs = 45_000L,
        gapEndsM = 50.0,
        mergeGapMs = 30_000L,
        mergeDistM = 50.0,
        minStopMs = 60_000L,
        edgeToleranceMs = 30_000L,
    )

    enum class Signal { SLOW, GAP, STATIONARY }

    data class Stop(
        val startTs: Long,
        val endTs: Long,
        val center: Coordinate,
        val spreadM: Double,
        val signals: Set<Signal>,
    )

    fun runsFor(source: String?): Boolean = TrackOrigin.fromCode(source)?.measuresFixQuality != false

    /** [points] is a track's fixes, good and ignored alike; rejected fixes are left out. */
    fun find(points: List<TrackPoint>, params: Params, distance: DistanceFn): List<Stop> {
        val fixes = points
            .filter { !it.ignored || it.isStationary() }
            .sortedBy { it.timestamp }
        return if (fixes.size < 2) emptyList() else Scan(fixes, params, distance).stops()
    }

    private data class Run(val from: Int, val to: Int, val signals: Set<Signal>)

    private fun TrackPoint.isStationary() = ignoreReason == IgnoreReason.STATIONARY.code

    /** One track's fixes, in time order, with prefix sums so any index range's center is O(1). */
    private class Scan(val fixes: List<TrackPoint>, val params: Params, val distance: DistanceFn) {
        private val lat = DoubleArray(fixes.size + 1)
        private val lon = DoubleArray(fixes.size + 1)

        init {
            for (i in fixes.indices) {
                lat[i + 1] = lat[i] + fixes[i].latitude
                lon[i + 1] = lon[i] + fixes[i].longitude
            }
        }

        fun stops(): List<Stop> = merge((slow() + gaps() + stationary()).sortedBy { it.from })
            .filter(::kept)
            .map(::stopOf)

        private fun ts(i: Int) = fixes[i].timestamp

        private fun centerLat(from: Int, to: Int) = (lat[to + 1] - lat[from]) / (to - from + 1)

        private fun centerLon(from: Int, to: Int) = (lon[to + 1] - lon[from]) / (to - from + 1)

        private fun meters(i: Int, j: Int) =
            distance.meters(fixes[i].latitude, fixes[i].longitude, fixes[j].latitude, fixes[j].longitude)

        private fun slow(): List<Run> {
            val out = ArrayList<Run>()
            var j = 0
            for (k in fixes.indices) {
                while (ts(j) < ts(k) - params.slowWindowMs) j++
                if (ts(k) - ts(j) >= params.slowMinCoverMs && meters(j, k) <= params.slowNetM) {
                    out += Run(j, k, setOf(Signal.SLOW))
                }
            }
            return out
        }

        private fun gaps(): List<Run> {
            val minGap = maxOf(params.gapMinMs, GraphBreaks.SPACING_FACTOR * GraphBreaks.medianSpacingMs(fixes))
            val out = ArrayList<Run>()
            for (i in 0 until fixes.lastIndex) {
                if (ts(i + 1) - ts(i) > minGap && meters(i, i + 1) <= params.gapEndsM) {
                    out += Run(i, i + 1, setOf(Signal.GAP))
                }
            }
            return out
        }

        private fun stationary(): List<Run> {
            val out = ArrayList<Run>()
            var k = 0
            while (k < fixes.size) {
                if (!fixes[k].isStationary()) {
                    k++
                    continue
                }
                val first = k
                while (k < fixes.size && fixes[k].isStationary()) k++
                out += Run((first - 1).coerceAtLeast(0), k.coerceAtMost(fixes.lastIndex), setOf(Signal.STATIONARY))
            }
            return out
        }

        private fun merge(pieces: List<Run>): List<Run> {
            val out = ArrayList<Run>()
            for (p in pieces) {
                val run = out.lastOrNull()
                if (run != null && joins(run, p)) {
                    out[out.lastIndex] = run.copy(to = maxOf(run.to, p.to), signals = run.signals + p.signals)
                } else {
                    out += p
                }
            }
            return out
        }

        private fun joins(run: Run, p: Run): Boolean =
            ts(p.from) - ts(run.to) <= params.mergeGapMs &&
                distance.meters(
                    centerLat(run.from, run.to),
                    centerLon(run.from, run.to),
                    centerLat(p.from, p.to),
                    centerLon(p.from, p.to),
                ) <= params.mergeDistM

        private fun kept(run: Run): Boolean =
            ts(run.to) - ts(run.from) >= params.minStopMs &&
                ts(run.from) - ts(0) > params.edgeToleranceMs &&
                ts(fixes.lastIndex) - ts(run.to) > params.edgeToleranceMs

        private fun stopOf(run: Run): Stop {
            val c = Coordinate(centerLat(run.from, run.to), centerLon(run.from, run.to))
            val spread = (run.from..run.to).maxOf { distance.meters(c.lat, c.lon, fixes[it].latitude, fixes[it].longitude) }
            return Stop(ts(run.from), ts(run.to), c, spread, run.signals)
        }
    }
}
