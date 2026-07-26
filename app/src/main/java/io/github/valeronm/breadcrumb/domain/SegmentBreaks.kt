package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A [TrackPoint.segmentStart] means *the recorder was not watching between the previous fix and this
 * one* — an auto-pause resumed, or a merge joined two journeys. It governs how a track is drawn and
 * exported: the leg across it is not a path anyone observed, so it opens a fresh GPX `<trkseg>` and
 * lifts the pen on the metric graph. It does not govern distance, which counts the leg like any
 * other (see [io.github.valeronm.breadcrumb.data.TrackStats] — nothing teleports).
 *
 * The flag is written onto the fix that resumed the recording, and that fix is not guaranteed to
 * survive: the first fix after a pause is exactly the cold-start stray the jump rule rejects, and a
 * merge marks the later track's first point whatever its state. A break on an ignored fix is then
 * invisible to every reader that walks the path, because they all skip ignored fixes — so two
 * halves the recorder never connected are drawn and exported as one continuous stretch.
 *
 * The flag is therefore honored by *position, not by row*: it belongs to the boundary it marks, and
 * the first good fix after it inherits it. Fixing it here rather than where the flag is written also
 * repairs the tracks that already carry one on a rejected fix.
 *
 * Pure and Android-free.
 */
object SegmentBreaks {

    /**
     * [points] — all of a track's fixes in order — reduced to the ones on the path, with a break
     * that landed on an ignored fix carried onto the first good fix after it.
     *
     * Returns rewritten copies only where a break actually moved; a track whose breaks all sit on
     * good fixes (the usual case) is filtered and nothing more.
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
}
