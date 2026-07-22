package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint

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
 * The rule itself: ignored fixes contribute nothing but their count, and a [TrackPoint.segmentStart]
 * detaches from the previous point so an auto-paused gap isn't counted as travel.
 */
object TrackStats {

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
         * Diagonal of the good points' bounding box (metres): a real trip's spread, which GPS jitter
         * can't inflate the way it inflates accumulated distance. The keep rule's extent gate reads
         * it; unlike the rest, it isn't stored on the track row — it's only needed at the moment the
         * track finishes. 0 for fewer than two points.
         */
        val extentMeters: Double,
    )

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
            // A segment start detaches from the previous point: the paused gap wasn't travelled.
            if (!point.segmentStart) {
                lastGood?.let { distanceMeters += TrackQuality.distanceMeters(it, point, distance) }
            }
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
            extentMeters = if (pointCount < 2) 0.0 else distance.metres(minLat, minLon, maxLat, maxLon),
        )
    }

    /** [points] is *all* of a track's points — good and ignored — in track order (timestamp, id). */
    fun of(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): Stats =
        Accumulator(distance).apply { points.forEach(::add) }.stats()
}
