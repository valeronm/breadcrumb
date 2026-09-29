package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.TrackOrigin

internal object GoogleTimelineTracks {

    /** A type this table does not name reads as [ActivityType.UNKNOWN]: Google's set is open, and
     *  a wrong specific label would be worse than an honest vague one. */
    fun activityFor(googleType: String?): ActivityType = when (googleType) {
        "WALKING" -> ActivityType.WALKING
        "RUNNING" -> ActivityType.RUNNING
        "CYCLING" -> ActivityType.CYCLING
        "IN_PASSENGER_VEHICLE", "MOTORCYCLING" -> ActivityType.DRIVING
        "IN_TAXI" -> ActivityType.TAXI
        "IN_BUS", "IN_TRAM", "IN_TRAIN", "IN_SUBWAY", "IN_GONDOLA_LIFT", "IN_FUNICULAR" -> ActivityType.TRANSIT
        "IN_FERRY", "BOATING" -> ActivityType.FERRY
        "FLYING" -> ActivityType.FLIGHT
        else -> ActivityType.UNKNOWN
    }

    /**
     * One track per activity of [export], in start order. The activity's own endpoints are its first
     * and last fixes because a track's bounds are derived from its fixes, and some activities carry
     * no path samples at all.
     *
     * Between them sit the phone's own [GoogleTimelineExport.fixes] where they span the whole
     * activity, and otherwise the [GoogleTimelineExport.path] samples, never both: each path sample
     * is one of those fixes restamped to the minute, so the two interleaved would put one position
     * at two times. A fix at or beyond [maxAccuracyM] is ignored as the recorder would ignore it.
     *
     * A track's ends are stated to the places [Ends] joins them to, [rowOf] being the place row a
     * Google place id stands as.
     */
    fun build(
        export: GoogleTimelineExport,
        rowOf: (String) -> Long?,
        maxAccuracyM: Float,
        distance: DistanceFn = AndroidDistance,
        activities: List<GoogleTimelineActivity> = export.activities,
    ): Sequence<Pair<Track, List<TrackPoint>>> {
        val ends = Ends(export.visits)
        val gates = TrackQuality.Gates(maxAccuracyM = maxAccuracyM)
        return activities.sortedBy { it.startMs }.asSequence().map { activity ->
            val type = activityFor(activity.type)
            val inside = if (spans(export.fixes, activity)) export.fixes else export.path
            val points = ArrayList<TrackPoint>()
            points += fix(activity.startMs, activity.start.lat, activity.start.lon)
            var i = inside.firstAfter(activity.startMs)
            while (i < inside.times.size && inside.times[i] < activity.endMs) {
                val accuracy = inside.accuracies?.get(i)?.takeUnless { it.isNaN() }
                points += fix(inside.times[i], inside.lats[i], inside.lons[i], accuracy)
                i++
            }
            points += fix(activity.endMs, activity.end.lat, activity.end.lon)
            Track(
                activityType = type.name,
                source = TrackOrigin.GOOGLE_TIMELINE.code,
                startedAt = activity.startMs,
                endedAt = activity.endMs,
                startPlaceId = ends.startOf(activity)?.let(rowOf),
                endPlaceId = ends.endOf(activity)?.let(rowOf),
            ) to flagBadFixes(points, type, gates, distance)
        }
    }

    /**
     * The Google place an activity's ends are joined to: its end to the top-level visit starting at
     * the instant it ends, and its start to the one ending at the instant it starts. Google joined the
     * two exactly when their times are equal.
     */
    class Ends(visits: List<GoogleTimelineVisit>) {
        private val arrivals = HashMap<Long, String>()
        private val departures = HashMap<Long, String>()

        init {
            for (visit in visits) {
                if (!visit.topLevel) continue
                arrivals[visit.startMs] = visit.placeId
                departures[visit.endMs] = visit.placeId
            }
        }

        fun startOf(activity: GoogleTimelineActivity): String? = departures[activity.startMs]

        fun endOf(activity: GoogleTimelineActivity): String? = arrivals[activity.endMs]
    }

    /** The export keeps [fixes] for its last month only, which can begin or end mid-trip. */
    private fun spans(fixes: SortedPath, activity: GoogleTimelineActivity): Boolean {
        val n = fixes.times.size
        if (n == 0 || fixes.times[0] > activity.startMs || fixes.times[n - 1] < activity.endMs) return false
        val first = fixes.firstAfter(activity.startMs)
        return first < n && fixes.times[first] < activity.endMs
    }

    private fun flagBadFixes(
        points: List<TrackPoint>,
        type: ActivityType,
        gates: TrackQuality.Gates,
        distance: DistanceFn,
    ): List<TrackPoint> {
        var lastGood: TrackPoint? = null
        return points.map { point ->
            val reason = TrackQuality.badFixReason(lastGood, point, type, gates, distance)
            if (reason == null) {
                lastGood = point
                point
            } else {
                point.copy(ignored = true, ignoreReason = reason.code)
            }
        }
    }

    /** The export carries an altitude on only some of the phone's fixes, too few to draw a profile from. */
    private fun fix(timeMs: Long, lat: Double, lon: Double, accuracyM: Float? = null) = TrackPoint(
        trackId = NO_TRACK,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = accuracyM,
        speed = null,
        bearing = null,
        timestamp = timeMs,
    )
}
