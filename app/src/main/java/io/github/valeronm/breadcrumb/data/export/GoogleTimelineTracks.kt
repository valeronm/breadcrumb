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
     * One track per activity, in start order. The activity's own endpoints are its first and last
     * fixes because a track's bounds are derived from its fixes, and some activities carry no path
     * samples at all.
     *
     * A track's end is stated to the place of the top-level visit starting at the instant it ends,
     * and its start to the one ending at the instant it starts: Google joined the two exactly when
     * their times are equal. [rowOf] is the place row a Google place id was inserted as.
     */
    fun build(
        activities: List<GoogleTimelineActivity>,
        path: SortedPath,
        visits: List<GoogleTimelineVisit>,
        rowOf: (String) -> Long?,
        distance: DistanceFn = AndroidDistance,
    ): Sequence<Pair<Track, List<TrackPoint>>> {
        val arrivals = HashMap<Long, String>()
        val departures = HashMap<Long, String>()
        for (visit in visits) {
            if (!visit.topLevel) continue
            arrivals[visit.startMs] = visit.placeId
            departures[visit.endMs] = visit.placeId
        }
        return activities.sortedBy { it.startMs }.asSequence().map { activity ->
            val type = activityFor(activity.type)
            val points = ArrayList<TrackPoint>()
            points += fix(activity.startMs, activity.start.lat, activity.start.lon)
            var i = path.firstAfter(activity.startMs)
            while (i < path.times.size && path.times[i] < activity.endMs) {
                points += fix(path.times[i], path.lats[i], path.lons[i])
                i++
            }
            points += fix(activity.endMs, activity.end.lat, activity.end.lon)
            Track(
                activityType = type.name,
                source = TrackOrigin.GOOGLE_TIMELINE.code,
                startedAt = activity.startMs,
                endedAt = activity.endMs,
                startPlaceId = departures[activity.startMs]?.let(rowOf),
                endPlaceId = arrivals[activity.endMs]?.let(rowOf),
            ) to flagJumps(points, type, distance)
        }
    }

    /** The export carries no accuracy or satellite evidence, so of the bad-fix rule only the jump
     *  gate can judge these fixes. */
    private val NO_EVIDENCE = TrackQuality.Gates(maxAccuracyM = Float.POSITIVE_INFINITY)

    private fun flagJumps(points: List<TrackPoint>, type: ActivityType, distance: DistanceFn): List<TrackPoint> {
        var lastGood: TrackPoint? = null
        return points.map { point ->
            val reason = TrackQuality.badFixReason(lastGood, point, type, NO_EVIDENCE, distance)
            if (reason == null) {
                lastGood = point
                point
            } else {
                point.copy(ignored = true, ignoreReason = reason.code)
            }
        }
    }

    private fun fix(timeMs: Long, lat: Double, lon: Double) = TrackPoint(
        trackId = NO_TRACK,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = timeMs,
    )
}
