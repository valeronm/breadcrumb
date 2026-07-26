package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackDao
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackStatsUpdate
import io.github.valeronm.breadcrumb.domain.DistanceFn

/**
 * The one implementation of a track's point walk: distance, counts, endpoints and extent.
 *
 * Two callers need it in two shapes, and they must agree. The recorder accumulates as each fix
 * arrives ([Accumulator]) and shows the running total on the Record card; the repository folds the
 * *stored* points when a track is finished ([of]) and writes the result onto the track row — which
 * is the only distance that survives, since the recorder writes none while it records (see
 * [TrackDao]'s observed queries for why). If the two walks disagreed, a finished track would
 * contradict the card the user just watched, and a crash-recovered one would be judged on a number
 * the recorder never produced. So [of] is a fold over [Accumulator] rather than a second walk.
 *
 * The rule itself: ignored fixes contribute nothing but their count, and every leg between
 * consecutive good fixes is travel — including the one spanning a [TrackPoint.segmentStart], where
 * the recorder had auto-paused or a merge joined two journeys.
 *
 * That last part is a deliberate reversal. The gap used to be dropped on the reasoning that a
 * paused stretch wasn't traveled, which holds for the standstill that fires the pause and fails
 * whenever the pause outlasted the stop: Activity Recognition is late to call movement, and a
 * reacquiring GPS lands its first fix down the road. Measured over the recorded history, the gaps
 * span everything from a parked phone's drift to vehicle pace over a couple of minutes — so a rule
 * that drops them all discards real travel to avoid counting a little jitter. Nothing teleports:
 * the straight line across a gap already *understates* whatever path connected the two fixes, and
 * dropping it understates by the whole leg.
 *
 * The break still separates the path everywhere it is drawn or exported (see
 * [io.github.valeronm.breadcrumb.domain.SegmentBreaks]) — a line the recorder never observed should
 * not be drawn, which is a different question from whether the ground was covered.
 */
object TrackStats {

    /**
     * Bumped whenever this walk would produce different numbers for the same points. The aggregates
     * on a track row are this code's output, so a rule that has moved leaves every stored track
     * behind it — and nothing re-walks a track whose points haven't changed (the edge-stay sweep
     * skips it, and a track is otherwise re-walked only when it is finished, merged, imported or
     * retyped). [TrackRepository.sweepStats] re-walks the history when the version it last swept is
     * behind this one, so bumping is part of changing the rule, not a follow-up chore.
     *
     * 1 — the leg spanning a [TrackPoint.segmentStart] counts as travel; it used to be dropped.
     */
    const val RULE_VERSION = 1

    /** Aggregates of one track's points. Endpoints are null for a track with no good points. */
    data class Stats(
        val distanceMeters: Double,
        /** Usable (non-ignored) points. */
        val pointCount: Int,
        /** Ignored points: bad fixes — a signal that the track is questionable — plus the
         *  recorder's overrun at the edges, which is not. The reason on each row separates them. */
        val ignoredCount: Int,
        val startLat: Double?,
        val startLon: Double?,
        val endLat: Double?,
        val endLon: Double?,
        /**
         * Diagonal of the good points' bounding box (meters): a real trip's spread, which GPS jitter
         * can't inflate the way it inflates accumulated distance. The keep rule's extent gate reads
         * it; unlike the rest, it isn't stored on the track row — it's only needed at the moment the
         * track finishes. 0 for fewer than two points.
         */
        val extentMeters: Double,
    ) {
        /** Whether [track]'s stored columns already read as these stats — what lets a sweep that
         *  agrees with the rows cost no writes. */
        fun matches(track: Track): Boolean =
            track.distanceMeters == distanceMeters &&
                track.pointCount == pointCount &&
                track.ignoredCount == ignoredCount &&
                track.startLat == startLat &&
                track.startLon == startLon &&
                track.endLat == endLat &&
                track.endLon == endLon

        /** The row-shaped projection [TrackDao.updateStats] writes: these stats onto [trackId]. */
        fun toUpdate(trackId: Long): TrackStatsUpdate = TrackStatsUpdate(
            id = trackId,
            distanceMeters = distanceMeters,
            pointCount = pointCount,
            ignoredCount = ignoredCount,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
        )
    }

    /** Feeds points in track order, one at a time — the shape the recorder ingests them in. */
    class Accumulator(private val distance: DistanceFn = AndroidDistance) {

        /** The last accepted fix: the recorder's baseline for the jump check and the live readout. */
        var lastGood: TrackPoint? = null
            private set
        var distanceMeters = 0.0
            private set
        var pointCount = 0
            private set

        private var ignoredCount = 0
        private var first: TrackPoint? = null

        // Seeded so the first good point clamps them; stats() zeroes the extent below two points.
        private var minLat = Double.POSITIVE_INFINITY
        private var maxLat = Double.NEGATIVE_INFINITY
        private var minLon = Double.POSITIVE_INFINITY
        private var maxLon = Double.NEGATIVE_INFINITY

        fun add(point: TrackPoint) {
            if (point.ignored) {
                ignoredCount++
                return
            }
            // Every leg between consecutive good fixes counts, the one spanning a segment break
            // included: nothing teleports, so a phone recorded at one fix and then at the next
            // covered the ground between them whether or not the recorder was watching.
            lastGood?.let { distanceMeters += TrackQuality.distanceMeters(it, point, distance) }
            if (first == null) first = point
            minLat = minOf(minLat, point.latitude)
            maxLat = maxOf(maxLat, point.latitude)
            minLon = minOf(minLon, point.longitude)
            maxLon = maxOf(maxLon, point.longitude)
            lastGood = point
            pointCount++
        }

        fun stats(): Stats = Stats(
            distanceMeters = distanceMeters,
            pointCount = pointCount,
            ignoredCount = ignoredCount,
            startLat = first?.latitude,
            startLon = first?.longitude,
            endLat = lastGood?.latitude,
            endLon = lastGood?.longitude,
            extentMeters = if (pointCount < 2) 0.0 else distance.meters(minLat, minLon, maxLat, maxLon),
        )
    }

    /** [points] is *all* of a track's points — good and ignored — in track order (timestamp, id). */
    fun of(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): Stats =
        Accumulator(distance).apply { points.forEach(::add) }.stats()
}
