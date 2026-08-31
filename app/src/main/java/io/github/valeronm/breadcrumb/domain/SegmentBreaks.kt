package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A [TrackPoint.segmentStart] means *the recorder was not watching between the previous fix and this
 * one* — a stop's track continued into, or a merge joining two journeys. It governs drawing and export (the
 * leg across it is not a path anyone observed, so it opens a fresh GPX `<trkseg>` and lifts the pen
 * on the metric graph) but not distance, which counts the leg like any other (see
 * [io.github.valeronm.breadcrumb.data.TrackStats] — nothing teleports). The flag is written onto the
 * fix that resumed the recording, which is not guaranteed to survive: the first fix after a stop is
 * exactly the cold-start stray the jump rule rejects, and a merge marks the later track's first
 * point whatever its state. A break on an ignored fix is invisible to every reader that walks the
 * path (they all skip ignored fixes), so two halves the recorder never connected are drawn and
 * exported as one continuous stretch. The flag is therefore honored by *position, not by row*: it
 * belongs to the boundary it marks, and the first good fix after it inherits it — which also repairs
 * the tracks that already carry one on a rejected fix. Pure and Android-free.
 */
object SegmentBreaks {

    /**
     * [points] — all of a track's fixes in order — reduced to the ones on the path, a break that
     * landed on an ignored fix carried onto the first good fix after it. Copies rewrite only where
     * a break moved; a track whose breaks all sit on good fixes (the usual case) is just filtered.
     */
    fun goodWithCarriedBreaks(points: List<TrackPoint>): List<TrackPoint> {
        val good = ArrayList<TrackPoint>(points.size)
        var pending = false
        for (p in points) {
            if (p.ignored) {
                pending = pending || p.segmentStart
                continue
            }
            good.add(if (pending && !p.segmentStart) p.copy(segmentStart = true) else p)
            pending = false
        }
        return good
    }

    /**
     * [points] — a path's fixes, breaks already carried ([goodWithCarriedBreaks]) — cut into the
     * stretches the recorder watched: each break starts a new stretch, and the leg across it
     * belongs to neither. A break on the first fix marks nothing to cut.
     */
    fun split(points: List<TrackPoint>): List<List<TrackPoint>> {
        if (points.isEmpty()) return emptyList()
        val stretches = ArrayList<List<TrackPoint>>()
        var from = 0
        for (i in 1 until points.size) {
            if (points[i].segmentStart) {
                stretches += points.subList(from, i)
                from = i
            }
        }
        stretches += points.subList(from, points.size)
        return stretches
    }
}
