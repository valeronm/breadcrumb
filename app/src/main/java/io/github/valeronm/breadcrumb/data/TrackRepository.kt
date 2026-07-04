package io.github.valeronm.breadcrumb.data

import android.content.Context
import android.location.Location
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.KeepRule
import kotlinx.coroutines.flow.Flow
import kotlin.math.sin
import kotlin.random.Random

/** Thin wrapper around the DAO so callers don't touch Room directly. */
class TrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).trackDao()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    suspend fun startTrack(activityType: ActivityType, startedAt: Long): Long =
        dao.insertTrack(Track(activityType = activityType.name, startedAt = startedAt))

    suspend fun addPoint(point: TrackPoint): Long = dao.insertPoint(point)

    suspend fun updateDistance(trackId: Long, distanceMeters: Double) =
        dao.updateDistance(trackId, distanceMeters)

    /**
     * Whether a track meets the user's configured keep thresholds (duration / length / extent).
     * The rule itself lives in [KeepRule]; this loads the points/settings it needs. The extent is
     * passed lazily so its bounding-box pass runs only when the extent gate is enabled.
     */
    private suspend fun meetsKeepThresholds(track: Track, endedAt: Long): Boolean {
        val points = dao.pointsFor(track.id)
        val durationSec = (endedAt - track.startedAt) / 1000
        return KeepRule.shouldKeep(
            pointCount = points.size,
            durationSec = durationSec,
            distanceMeters = track.distanceMeters,
            thresholds = KeepRule.Thresholds(
                minDurationSec = Settings.minTrackDurationSec(appContext),
                minLengthM = Settings.minTrackLengthM(appContext),
                minExtentM = Settings.minTrackExtentM(appContext),
            ),
            extent = { TrackQuality.boundingExtentMeters(points) },
        )
    }

    /** Closes a track, deleting it instead if it's too short to be meaningful. */
    private suspend fun closeOrDelete(track: Track, endedAt: Long) {
        if (meetsKeepThresholds(track, endedAt)) {
            dao.closeTrack(track.id, endedAt)
        } else {
            dao.deleteTrack(track.id)
        }
    }

    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        val track = dao.track(trackId) ?: return
        closeOrDelete(track, endedAt)
    }

    suspend fun deleteTrack(trackId: Long) = dao.deleteTrack(trackId)

    /**
     * Closes tracks left open by a crash/kill (endedAt == null), using their last recorded point
     * as the end time, or deleting them if too short. [exceptTrackId] is the track currently being
     * recorded, which must be left untouched.
     */
    suspend fun finalizeDangling(exceptTrackId: Long?) {
        for (track in dao.openTracks()) {
            if (track.id == exceptTrackId) continue
            val endedAt = dao.lastPointTime(track.id) ?: track.startedAt
            closeOrDelete(track, endedAt)
        }
    }

    suspend fun track(trackId: Long): Track? = dao.track(trackId)

    suspend fun allTrackIds(): List<Long> = dao.allTrackIds()

    suspend fun pointsFor(trackId: Long): List<TrackPoint> = dao.pointsFor(trackId)

    /** The ignored "bad fix" points, for marking them on the map. */
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint> = dao.ignoredPointsFor(trackId)

    /**
     * Inserts a synthetic, already-finished track so the list / map / swipe-to-delete / share flows
     * can be exercised without real movement. Intended for debug builds only; bypasses the keep
     * thresholds. The path is a short wander at a randomised location so repeated seeds don't overlap.
     */
    suspend fun seedSampleTrack(): Long {
        val activity = ActivityType.entries.filter { it.recording }.random()
        val pointCount = 40
        val stepSec = 15L
        val now = System.currentTimeMillis()
        val startedAt = now - (pointCount - 1) * stepSec * 1000

        val baseLat = 37.7749 + Random.nextDouble(-0.05, 0.05)
        val baseLon = -122.4194 + Random.nextDouble(-0.05, 0.05)

        val trackId = dao.insertTrack(Track(activityType = activity.name, startedAt = startedAt))
        var prevLat = baseLat
        var prevLon = baseLon
        var distance = 0.0
        val seg = FloatArray(1)
        for (i in 0 until pointCount) {
            val lat = baseLat + i * 0.00012 + sin(i / 4.0) * 0.00008
            val lon = baseLon + i * 0.00018
            if (i > 0) {
                Location.distanceBetween(prevLat, prevLon, lat, lon, seg)
                distance += seg[0]
            }
            dao.insertPoint(
                TrackPoint(
                    trackId = trackId,
                    latitude = lat,
                    longitude = lon,
                    altitude = 30.0,
                    accuracy = 5f,
                    speed = 1.4f,
                    bearing = 45f,
                    timestamp = startedAt + i * stepSec * 1000,
                ),
            )
            prevLat = lat
            prevLon = lon
        }
        dao.updateDistance(trackId, distance)
        dao.closeTrack(trackId, startedAt + (pointCount - 1) * stepSec * 1000)
        return trackId
    }
}
