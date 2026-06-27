package com.valeronm.activitytracker.data

import android.content.Context
import com.valeronm.activitytracker.data.db.AppDatabase
import com.valeronm.activitytracker.data.db.Track
import com.valeronm.activitytracker.data.db.TrackPoint
import com.valeronm.activitytracker.data.db.TrackSummary
import kotlinx.coroutines.flow.Flow

/** Thin wrapper around the DAO so callers don't touch Room directly. */
class TrackRepository(context: Context) {

    private val dao = AppDatabase.get(context).trackDao()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    suspend fun startTrack(activityType: ActivityType, startedAt: Long): Long =
        dao.insertTrack(Track(activityType = activityType.name, startedAt = startedAt))

    suspend fun addPoint(point: TrackPoint): Long = dao.insertPoint(point)

    suspend fun updateDistance(trackId: Long, distanceMeters: Double) =
        dao.updateDistance(trackId, distanceMeters)

    /** Closes a track, deleting it instead if it's too short to be meaningful. */
    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        if (dao.pointCount(trackId) < MIN_TRACK_POINTS) {
            dao.deleteTrack(trackId)
        } else {
            dao.closeTrack(trackId, endedAt)
        }
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
            if (dao.pointCount(track.id) < MIN_TRACK_POINTS) {
                dao.deleteTrack(track.id)
            } else {
                dao.closeTrack(track.id, dao.lastPointTime(track.id) ?: track.startedAt)
            }
        }
    }

    suspend fun track(trackId: Long): Track? = dao.track(trackId)

    suspend fun allTrackIds(): List<Long> = dao.allTrackIds()

    suspend fun pointsFor(trackId: Long): List<TrackPoint> = dao.pointsFor(trackId)

    private companion object {
        /** Tracks with fewer than this many points are discarded as noise. */
        const val MIN_TRACK_POINTS = 4
    }
}
