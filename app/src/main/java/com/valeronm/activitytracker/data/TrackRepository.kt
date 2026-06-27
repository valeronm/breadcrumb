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

    /** Closes a track, deleting it instead if it captured fewer than two points. */
    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        if (dao.pointCount(trackId) < 2) {
            dao.deleteTrack(trackId)
        } else {
            dao.closeTrack(trackId, endedAt)
        }
    }

    suspend fun deleteTrack(trackId: Long) = dao.deleteTrack(trackId)

    suspend fun track(trackId: Long): Track? = dao.track(trackId)

    suspend fun pointsFor(trackId: Long): List<TrackPoint> = dao.pointsFor(trackId)
}
